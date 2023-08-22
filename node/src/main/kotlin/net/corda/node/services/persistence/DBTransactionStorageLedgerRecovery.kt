package net.corda.node.services.persistence

import net.corda.core.crypto.SecureHash
import net.corda.core.flows.RecoveryTimeWindow
import net.corda.core.flows.TransactionMetadata
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.NamedCacheFactory
import net.corda.core.node.StatesToRecord
import net.corda.core.node.services.vault.Sort
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.OpaqueBytes
import net.corda.node.CordaClock
import net.corda.node.services.EncryptionService
import net.corda.node.services.network.PersistentPartyInfoCache
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.NODE_DATABASE_PREFIX
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.persistence.Column
import javax.persistence.Embeddable
import javax.persistence.EmbeddedId
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.Lob
import javax.persistence.Table
import javax.persistence.criteria.Predicate

class DBTransactionStorageLedgerRecovery(private val database: CordaPersistence,
                                         cacheFactory: NamedCacheFactory,
                                         val clock: CordaClock,
                                         private val encryptionService: EncryptionService,
                                         private val partyInfoCache: PersistentPartyInfoCache) : DBTransactionStorage(database, cacheFactory, clock) {
    @Embeddable
    @Immutable
    data class PersistentKey(
            /** PartyId of flow peer **/
            @Column(name = "peer_party_id", nullable = false)
            var peerPartyId: Long,

            @Column(name = "timestamp", nullable = false)
            var timestamp: Instant,

            @Column(name = "timestamp_discriminator", nullable = false)
            var timestampDiscriminator: Int

    ) : Serializable {
        constructor(key: Key) : this(key.partyId, key.timestamp, key.timestampDiscriminator)
    }

    @CordaSerializable
    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}sender_distribution_records")
    data class DBSenderDistributionRecord(
            @EmbeddedId
            var compositeKey: PersistentKey,

            @Column(name = "transaction_id", length = 144, nullable = false)
            var txId: String,

            /** states to record: NONE, ALL_VISIBLE, ONLY_RELEVANT */
            @Column(name = "states_to_record", nullable = false)
            var statesToRecord: StatesToRecord
    ) {
        fun toSenderDistributionRecord() =
            SenderDistributionRecord(
                    SecureHash.parse(this.txId),
                    this.compositeKey.peerPartyId,
                    this.statesToRecord,
                    this.compositeKey.timestamp
            )
    }

    @CordaSerializable
    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}receiver_distribution_records")
    class DBReceiverDistributionRecord(
            @EmbeddedId
            var compositeKey: PersistentKey,

            @Column(name = "transaction_id", length = 144, nullable = false)
            var txId: String,

            /** Encrypted recovery information for sole use by Sender **/
            @Lob
            @Column(name = "distribution_list", nullable = false)
            val distributionList: ByteArray
    ) {
        constructor(key: Key, txId: SecureHash, encryptedDistributionList: ByteArray) : this(
                PersistentKey(key),
                txId.toString(),
                encryptedDistributionList
        )

        fun toReceiverDistributionRecord(): ReceiverDistributionRecord {
            return ReceiverDistributionRecord(
                    SecureHash.parse(this.txId),
                    this.compositeKey.peerPartyId,
                    OpaqueBytes(this.distributionList),
                    this.compositeKey.timestamp
            )
        }
    }

    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}recovery_party_info")
    data class DBRecoveryPartyInfo(
            @Id
            /** CordaX500Name hashCode() **/
            @Column(name = "party_id", nullable = false)
            var partyId: Long,

            /** CordaX500Name of party **/
            @Column(name = "party_name", nullable = false)
            val partyName: String
    )

    data class TimestampKey(val timestamp: Instant, val timestampDiscriminator: Int)

    class Key(
            val partyId: Long,
            val timestamp: Instant,
            val timestampDiscriminator: Int = nextDiscriminatorNumber.andIncrement
    ) {
        constructor(key: TimestampKey, partyId: Long): this(partyId, key.timestamp, key.timestampDiscriminator)
        companion object {
            val nextDiscriminatorNumber = AtomicInteger()
        }
    }

    override fun addSenderTransactionRecoveryMetadata(txId: SecureHash, metadata: TransactionMetadata): ByteArray {
        val senderRecordingTimestamp = clock.instant()
        return database.transaction {
            // sender distribution records must be unique per txnId and timestamp
            val timeDiscriminator = Key.nextDiscriminatorNumber.andIncrement
            metadata.distributionList.peersToStatesToRecord.forEach { peerCordaX500Name, peerStatesToRecord ->
                val senderDistributionRecord = DBSenderDistributionRecord(
                        PersistentKey(Key(
                                TimestampKey(senderRecordingTimestamp, timeDiscriminator),
                                partyInfoCache.getPartyIdByCordaX500Name(peerCordaX500Name)
                        )),
                        txId.toString(),
                        peerStatesToRecord
                )
                session.save(senderDistributionRecord)
            }

            val hashedPeersToStatesToRecord = metadata.distributionList.peersToStatesToRecord.mapKeys { (peer) ->
                partyInfoCache.getPartyIdByCordaX500Name(peer)
            }
            val hashedDistributionList = HashedDistributionList(
                    metadata.distributionList.senderStatesToRecord,
                    hashedPeersToStatesToRecord,
                    HashedDistributionList.PublicHeader(senderRecordingTimestamp)
            )
            hashedDistributionList.encrypt(encryptionService)
        }
    }

    fun createReceiverTransactionRecoverMetadata(txId: SecureHash,
                                                 senderPartyId: Long,
                                                 senderStatesToRecord: StatesToRecord,
                                                 senderRecords: List<DBSenderDistributionRecord>): List<DBReceiverDistributionRecord> {
        val senderRecordsByTimestampKey = senderRecords.groupBy { TimestampKey(it.compositeKey.timestamp, it.compositeKey.timestampDiscriminator) }
        return senderRecordsByTimestampKey.map { (key) ->
            val hashedDistributionList = HashedDistributionList(
                    senderStatesToRecord,
                    senderRecords.associate { it.compositeKey.peerPartyId to it.statesToRecord },
                    HashedDistributionList.PublicHeader(key.timestamp)
            )
            DBReceiverDistributionRecord(
                    PersistentKey(Key(TimestampKey(key.timestamp, key.timestampDiscriminator), senderPartyId)),
                    txId.toString(),
                    hashedDistributionList.encrypt(encryptionService)
            )
        }
    }

    fun addSenderTransactionRecoveryMetadata(record: DBSenderDistributionRecord) {
        return database.transaction {
            session.save(record)
        }
    }

    override fun addReceiverTransactionRecoveryMetadata(txId: SecureHash,
                                                        sender: CordaX500Name,
                                                        receiver: CordaX500Name,
                                                        receiverStatesToRecord: StatesToRecord,
                                                        encryptedDistributionList: ByteArray) {
        val publicHeader = HashedDistributionList.PublicHeader.unauthenticatedDeserialise(encryptedDistributionList, encryptionService)
        database.transaction {
            val receiverDistributionRecord = DBReceiverDistributionRecord(
                    Key(partyInfoCache.getPartyIdByCordaX500Name(sender), publicHeader.senderRecordedTimestamp),
                    txId,
                    encryptedDistributionList
            )
            session.save(receiverDistributionRecord)
        }
    }

    fun addReceiverTransactionRecoveryMetadata(record: DBReceiverDistributionRecord) {
        return database.transaction {
            session.save(record)
        }
    }

    override fun removeUnnotarisedTransaction(id: SecureHash): Boolean {
        return database.transaction {
            super.removeUnnotarisedTransaction(id)
            val criteriaBuilder = session.criteriaBuilder
            val deleteSenderDistributionRecords = criteriaBuilder.createCriteriaDelete(DBSenderDistributionRecord::class.java)
            val root = deleteSenderDistributionRecords.from(DBSenderDistributionRecord::class.java)
            deleteSenderDistributionRecords.where(criteriaBuilder.equal(root.get<String>(DBSenderDistributionRecord::txId.name), id.toString()))
            val deletedSenderDistributionRecords = session.createQuery(deleteSenderDistributionRecords).executeUpdate() != 0
            val deleteReceiverDistributionRecords = criteriaBuilder.createCriteriaDelete(DBReceiverDistributionRecord::class.java)
            val rootReceiverDistributionRecord = deleteReceiverDistributionRecords.from(DBReceiverDistributionRecord::class.java)
            deleteReceiverDistributionRecords.where(criteriaBuilder.equal(rootReceiverDistributionRecord.get<String>(DBReceiverDistributionRecord::txId.name), id.toString()))
            val deletedReceiverDistributionRecords = session.createQuery(deleteReceiverDistributionRecords).executeUpdate() != 0
            deletedSenderDistributionRecords || deletedReceiverDistributionRecords
        }
    }

    fun queryDistributionRecords(timeWindow: RecoveryTimeWindow,
                               recordType: DistributionRecordType = DistributionRecordType.ALL,
                               excludingTxnIds: Set<SecureHash> = emptySet(),
                               orderByTimestamp: Sort.Direction? = null
    ): DistributionRecords {
        return when(recordType) {
            DistributionRecordType.SENDER ->
                DistributionRecords(senderRecords =
                    querySenderDistributionRecords(timeWindow, excludingTxnIds = excludingTxnIds, orderByTimestamp = orderByTimestamp))
            DistributionRecordType.RECEIVER ->
                DistributionRecords(receiverRecords =
                    queryReceiverDistributionRecords(timeWindow, excludingTxnIds = excludingTxnIds, orderByTimestamp = orderByTimestamp))
            DistributionRecordType.ALL ->
                DistributionRecords(senderRecords =
                    querySenderDistributionRecords(timeWindow, excludingTxnIds = excludingTxnIds, orderByTimestamp = orderByTimestamp),
                                    receiverRecords =
                    queryReceiverDistributionRecords(timeWindow, excludingTxnIds = excludingTxnIds, orderByTimestamp = orderByTimestamp))
        }
    }

    @Suppress("SpreadOperator")
    fun querySenderDistributionRecords(timeWindow: RecoveryTimeWindow,
                                       peers: Set<CordaX500Name> = emptySet(),
                                       excludingTxnIds: Set<SecureHash> = emptySet(),
                                       orderByTimestamp: Sort.Direction? = null
                             ): List<DBSenderDistributionRecord> {
        return database.transaction {
            val criteriaBuilder = session.criteriaBuilder
            val criteriaQuery = criteriaBuilder.createQuery(DBSenderDistributionRecord::class.java)
            val txnMetadata = criteriaQuery.from(DBSenderDistributionRecord::class.java)
            val predicates = mutableListOf<Predicate>()
            val compositeKey = txnMetadata.get<PersistentKey>("compositeKey")
            predicates.add(criteriaBuilder.greaterThanOrEqualTo(compositeKey.get<Instant>(PersistentKey::timestamp.name), timeWindow.fromTime))
            predicates.add(criteriaBuilder.and(criteriaBuilder.lessThanOrEqualTo(compositeKey.get<Instant>(PersistentKey::timestamp.name), timeWindow.untilTime)))
            if (excludingTxnIds.isNotEmpty()) {
                predicates.add(criteriaBuilder.and(criteriaBuilder.not(txnMetadata.get<String>(DBSenderDistributionRecord::txId.name).`in`(
                        excludingTxnIds.map { it.toString() }))))
            }
            if (peers.isNotEmpty()) {
                val peerPartyIds = peers.map { partyInfoCache.getPartyIdByCordaX500Name(it) }
                predicates.add(criteriaBuilder.and(compositeKey.get<Long>(PersistentKey::peerPartyId.name).`in`(peerPartyIds)))
            }
            criteriaQuery.where(*predicates.toTypedArray())
            // optionally order by timestamp
            orderByTimestamp?.let {
                val orderCriteria =
                        when (orderByTimestamp) {
                            // when adding column position of 'group by' shift in case columns were removed
                            Sort.Direction.ASC -> criteriaBuilder.asc(compositeKey.get<Instant>(PersistentKey::timestamp.name))
                            Sort.Direction.DESC -> criteriaBuilder.desc(compositeKey.get<Instant>(PersistentKey::timestamp.name))
                        }
                criteriaQuery.orderBy(orderCriteria)
            }
            session.createQuery(criteriaQuery).resultList
        }
    }

    fun querySenderDistributionRecordsByTxId(txId: SecureHash): List<DBSenderDistributionRecord> {
        return database.transaction {
            val criteriaBuilder = session.criteriaBuilder
            val criteriaQuery = criteriaBuilder.createQuery(DBSenderDistributionRecord::class.java)
            val txnMetadata = criteriaQuery.from(DBSenderDistributionRecord::class.java)
            criteriaQuery.where(criteriaBuilder.equal(txnMetadata.get<String>(DBSenderDistributionRecord::txId.name), txId.toString()))
            session.createQuery(criteriaQuery).resultList
        }
    }

    @Suppress("SpreadOperator")
    fun queryReceiverDistributionRecords(timeWindow: RecoveryTimeWindow,
                                       initiators: Set<CordaX500Name> = emptySet(),
                                       excludingTxnIds: Set<SecureHash> = emptySet(),
                                       orderByTimestamp: Sort.Direction? = null
    ): List<DBReceiverDistributionRecord> {
        return database.transaction {
            val criteriaBuilder = session.criteriaBuilder
            val criteriaQuery = criteriaBuilder.createQuery(DBReceiverDistributionRecord::class.java)
            val txnMetadata = criteriaQuery.from(DBReceiverDistributionRecord::class.java)
            val predicates = mutableListOf<Predicate>()
            val compositeKey = txnMetadata.get<PersistentKey>("compositeKey")
            val timestamp = compositeKey.get<Instant>(PersistentKey::timestamp.name)
            predicates.add(criteriaBuilder.greaterThanOrEqualTo(timestamp, timeWindow.fromTime))
            predicates.add(criteriaBuilder.and(criteriaBuilder.lessThanOrEqualTo(timestamp, timeWindow.untilTime)))
            if (excludingTxnIds.isNotEmpty()) {
                val txId = txnMetadata.get<String>(DBSenderDistributionRecord::txId.name)
                predicates.add(criteriaBuilder.and(criteriaBuilder.not(txId.`in`(excludingTxnIds.map { it.toString() }))))
            }
            if (initiators.isNotEmpty()) {
                val initiatorPartyIds = initiators.map(partyInfoCache::getPartyIdByCordaX500Name)
                predicates.add(criteriaBuilder.and(compositeKey.get<Long>(PersistentKey::peerPartyId.name).`in`(initiatorPartyIds)))
            }
            criteriaQuery.where(*predicates.toTypedArray())
            // optionally order by timestamp
            orderByTimestamp?.let {
                val orderCriteria = when (orderByTimestamp) {
                    // when adding column position of 'group by' shift in case columns were removed
                    Sort.Direction.ASC -> criteriaBuilder.asc(timestamp)
                    Sort.Direction.DESC -> criteriaBuilder.desc(timestamp)
                }
                criteriaQuery.orderBy(orderCriteria)
            }
            session.createQuery(criteriaQuery).resultList
        }
    }

    fun decryptHashedDistributionList(encryptedBytes: ByteArray): HashedDistributionList {
        return HashedDistributionList.decrypt(encryptedBytes, encryptionService)
    }
}


@CordaSerializable
class DistributionRecords(
        val senderRecords: List<DBTransactionStorageLedgerRecovery.DBSenderDistributionRecord> = emptyList(),
        val receiverRecords: List<DBTransactionStorageLedgerRecovery.DBReceiverDistributionRecord> = emptyList()
) {
    init {
        require(senderRecords.isNotEmpty() || receiverRecords.isNotEmpty()) { "Must set senderRecords or receiverRecords or both." }
    }

    val size = senderRecords.size + receiverRecords.size
}

@CordaSerializable
abstract class DistributionRecord {
    abstract val txId: SecureHash
    abstract val timestamp: Instant
}

@CordaSerializable
data class SenderDistributionRecord(
        override val txId: SecureHash,
        val peerPartyId: Long,     // CordaX500Name hashCode()
        val statesToRecord: StatesToRecord,
        override val timestamp: Instant
) : DistributionRecord()

@CordaSerializable
data class ReceiverDistributionRecord(
        override val txId: SecureHash,
        val initiatorPartyId: Long,     // CordaX500Name hashCode()
        val encryptedDistributionList: OpaqueBytes,
        override val timestamp: Instant
) : DistributionRecord()

@CordaSerializable
enum class DistributionRecordType {
    SENDER, RECEIVER, ALL
}
