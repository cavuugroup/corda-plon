package com.r3corda.node.messaging

import com.r3corda.core.contracts.Attachment
import com.r3corda.core.crypto.SecureHash
import com.r3corda.core.crypto.sha256
import com.r3corda.core.messaging.SingleMessageRecipient
import com.r3corda.core.node.services.ServiceInfo
import com.r3corda.core.write
import com.r3corda.node.services.config.NodeConfiguration
import com.r3corda.node.services.network.NetworkMapService
import com.r3corda.node.services.persistence.NodeAttachmentService
import com.r3corda.node.services.transactions.SimpleNotaryService
import com.r3corda.protocols.FetchAttachmentsProtocol
import com.r3corda.protocols.FetchDataProtocol
import com.r3corda.testing.node.MockNetwork
import com.r3corda.testing.rootCauseExceptions
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AttachmentTests {
    lateinit var network: MockNetwork

    @Before
    fun setUp() {
        network = MockNetwork()
    }

    fun fakeAttachment(): ByteArray {
        val bs = ByteArrayOutputStream()
        val js = JarOutputStream(bs)
        js.putNextEntry(ZipEntry("file1.txt"))
        js.writer().apply { append("Some useful content"); flush() }
        js.closeEntry()
        js.close()
        return bs.toByteArray()
    }

    @Test
    fun `download and store`() {
        val (n0, n1) = network.createTwoNodes()

        // Insert an attachment into node zero's store directly.
        val id = n0.storage.attachments.importAttachment(ByteArrayInputStream(fakeAttachment()))

        // Get node one to run a protocol to fetch it and insert it.
        network.runNetwork()
        val f1 = n1.services.startProtocol(FetchAttachmentsProtocol(setOf(id), n0.info.legalIdentity))
        network.runNetwork()
        assertEquals(0, f1.get().fromDisk.size)

        // Verify it was inserted into node one's store.
        val attachment = n1.storage.attachments.openAttachment(id)!!
        assertEquals(id, attachment.open().readBytes().sha256())

        // Shut down node zero and ensure node one can still resolve the attachment.
        n0.stop()

        val response: FetchDataProtocol.Result<Attachment> = n1.services.startProtocol(FetchAttachmentsProtocol(setOf(id), n0.info.legalIdentity)).get()
        assertEquals(attachment, response.fromDisk[0])
    }

    @Test
    fun `missing`() {
        val (n0, n1) = network.createTwoNodes()

        // Get node one to fetch a non-existent attachment.
        val hash = SecureHash.randomSHA256()
        network.runNetwork()
        val f1 = n1.services.startProtocol(FetchAttachmentsProtocol(setOf(hash), n0.info.legalIdentity))
        network.runNetwork()
        val e = assertFailsWith<FetchDataProtocol.HashNotFound> { rootCauseExceptions { f1.get() } }
        assertEquals(hash, e.requested)
    }

    @Test
    fun `malicious response`() {
        // Make a node that doesn't do sanity checking at load time.
        val n0 = network.createNode(null, -1, object : MockNetwork.Factory {
            override fun create(config: NodeConfiguration, network: MockNetwork, networkMapAddr: SingleMessageRecipient?,
                                advertisedServices: Set<ServiceInfo>, id: Int, keyPair: KeyPair?): MockNetwork.MockNode {
                return object : MockNetwork.MockNode(config, network, networkMapAddr, advertisedServices, id, keyPair) {
                    override fun start(): MockNetwork.MockNode {
                        super.start()
                        (storage.attachments as NodeAttachmentService).checkAttachmentsOnLoad = false
                        return this
                    }
                }
            }
        }, true, null, null, ServiceInfo(NetworkMapService.type), ServiceInfo(SimpleNotaryService.type))
        val n1 = network.createNode(n0.info.address)

        // Insert an attachment into node zero's store directly.
        val id = n0.storage.attachments.importAttachment(ByteArrayInputStream(fakeAttachment()))

        // Corrupt its store.
        network.filesystem.getPath("/nodes/0/attachments/$id").write { it.write(byteArrayOf(99, 99, 99, 99)) }

        // Get n1 to fetch the attachment. Should receive corrupted bytes.
        network.runNetwork()
        val f1 = n1.services.startProtocol(FetchAttachmentsProtocol(setOf(id), n0.info.legalIdentity))
        network.runNetwork()
        assertFailsWith<FetchDataProtocol.DownloadedVsRequestedDataMismatch> {
            rootCauseExceptions { f1.get() }
        }
    }
}
