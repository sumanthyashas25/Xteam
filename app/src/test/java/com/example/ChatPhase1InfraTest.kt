package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.chat.local.CachedMessageEntity
import com.example.chat.local.ChatDraftEntity
import com.example.chat.local.MediaCacheEntity
import com.example.chat.local.OutgoingMessageEntity
import com.example.chat.local.XMaxChatDatabase
import com.example.chat.remote.WorkerBridgeClient
import com.example.chat.remote.WorkerResult
import com.example.model.AdvancedChatType
import com.example.model.ChatThread
import com.example.model.InvoiceRecord
import com.example.model.InvoiceStatus
import com.example.model.MessageDeliveryStatus
import com.example.model.MessageKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatPhase1InfraTest {

    private lateinit var db: XMaxChatDatabase

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, XMaxChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `room outbox prevents duplicate messages via clientMsgId primary key`() = runBlocking {
        val dao = db.chatDao()
        val msg = OutgoingMessageEntity(
            clientMsgId = "cmsg_uuid_001",
            companyId = "xmax",
            chatId = "dm_1_2",
            senderUid = "u1",
            senderName = "Aarav",
            senderRole = "Designer",
            senderPhotoUrl = "",
            messageKind = MessageKind.TEXT.name,
            body = "Sending deliverable preview"
        )
        val firstInsert = dao.insertOutgoingIfAbsent(msg)
        val duplicateInsert = dao.insertOutgoingIfAbsent(msg.copy(body = "Duplicate attempt"))

        assertTrue(firstInsert > 0L)
        assertEquals(-1L, duplicateInsert)
        val items = dao.observeOutgoingForChat("dm_1_2").first()
        assertEquals(1, items.size)
        assertEquals("Sending deliverable preview", items.first().body)
    }

    @Test
    fun `room drafts, FTS4 offline search, and wipeAllChatLocalCaches work accurately`() = runBlocking {
        val dao = db.chatDao()
        dao.saveDraft(ChatDraftEntity(chatId = "grp_brand", draftText = "Need updated logo grid"))
        assertEquals("Need updated logo grid", dao.getDraft("grp_brand")?.draftText)

        dao.indexMessages(
            listOf(
                CachedMessageEntity(
                    messageId = "m_10",
                    chatId = "grp_brand",
                    senderId = "u1",
                    senderName = "Aarav Designer",
                    kind = MessageKind.DOCUMENT.name,
                    body = "Attached final brand guidelines and invoice PDF",
                    attachmentName = "Brand_Guidelines.pdf",
                    attachmentStoragePath = "grp_brand/m_10/Brand_Guidelines.pdf",
                    businessRefTitle = "INV-2026-0001",
                    createdAtMillis = 1000L
                )
            )
        )
        dao.upsertMediaCache(
            MediaCacheEntity(
                storagePath = "grp_brand/m_10/Brand_Guidelines.pdf",
                localFilePath = "/cache/Brand_Guidelines.pdf",
                mimeType = "application/pdf",
                byteSize = 4096L,
                lastAccessedMillis = 1000L
            )
        )

        val ftsHits = dao.searchCachedFts("guidelines*", "")
        assertEquals(1, ftsHits.size)
        assertEquals("m_10", ftsHits.first().messageId)
        assertEquals(4096L, dao.totalMediaCacheBytes())

        dao.wipeAllChatLocalCaches()
        assertNull(dao.getDraft("grp_brand"))
        assertTrue(dao.searchCachedLike("guidelines", "").isEmpty())
        assertEquals(0L, dao.totalMediaCacheBytes())
    }

    @Test
    fun `worker bridge returns NotConfigured gracefully when CHAT_WORKER_BASE_URL is empty`() = runBlocking {
        if (!WorkerBridgeClient.isConfigured()) {
            val res = WorkerBridgeClient.requestSignedUploadUrl(
                companyId = "xmax",
                bucket = "chat-media",
                storagePath = "c1/m1/img.jpg",
                mimeType = "image/jpeg",
                byteSize = 1024L,
                sha256 = "abc"
            )
            assertTrue(res is WorkerResult.NotConfigured)
        }
    }

    @Test
    fun `chat models cover all 6 chat types, 22 message kinds, lifecycle states, and additive invoices`() {
        assertEquals(6, AdvancedChatType.entries.size)
        assertEquals(22, MessageKind.entries.size)
        assertEquals(8, MessageDeliveryStatus.entries.size)

        val thread = ChatThread(id = "saved_u1", type = AdvancedChatType.SAVED.name, ownerUid = "u1")
        assertEquals(AdvancedChatType.SAVED, thread.typeEnum())

        val invoice = InvoiceRecord(
            id = "inv_1",
            invoiceNumber = "INV-2026-0001",
            totalPaise = 57000_00L,
            advanceCreditedPaise = 28500_00L,
            balanceDuePaise = 28500_00L,
            status = InvoiceStatus.ISSUED.name
        )
        assertEquals(InvoiceStatus.ISSUED, invoice.statusEnum())
    }

    @Test
    fun `cloudflare r2 urls, upload response parsing, and ChatMessage metadata behave cleanly`() {
        assertEquals("chat-media", WorkerBridgeClient.bucketForAttachmentType(com.example.model.ChatAttachmentType.IMAGE))
        assertEquals("chat-voice", WorkerBridgeClient.bucketForAttachmentType(com.example.model.ChatAttachmentType.AUDIO))
        assertEquals("chat-docs", WorkerBridgeClient.bucketForAttachmentType(com.example.model.ChatAttachmentType.FILE))

        val uploadUrl = WorkerBridgeClient.buildUploadUrl(
            base = "https://xmax-chat-backend.example.workers.dev/",
            companyId = "xmax",
            chatId = "ch_general",
            bucket = "chat-docs",
            fileName = "Client Brief v2.pdf",
            sha256 = "abc123"
        )
        assertNotNull(uploadUrl)
        assertTrue(uploadUrl!!.contains("/v1/storage/upload?"))
        assertTrue(uploadUrl.contains("companyId=xmax"))
        assertTrue(uploadUrl.contains("chatId=ch_general"))
        assertTrue(uploadUrl.contains("bucket=chat-docs"))

        val downloadUrl = WorkerBridgeClient.buildDownloadUrl(
            base = "https://xmax-chat-backend.example.workers.dev",
            companyId = "xmax",
            chatId = "ch_general",
            storagePath = "companies/xmax/chats/ch_general/id_brief.pdf"
        )
        assertNotNull(downloadUrl)
        assertTrue(downloadUrl!!.contains("/v1/storage/download?"))
        assertTrue(downloadUrl.contains("storagePath=companies%2Fxmax%2Fchats%2Fch_general%2Fid_brief.pdf"))

        val parsed = WorkerBridgeClient.parseUploadResponse(
            org.json.JSONObject(
                """
                {
                  "ok": true,
                  "provider": "CLOUDFLARE_R2",
                  "bucket": "chat-docs",
                  "storagePath": "companies/xmax/chats/ch_general/u1_brief.pdf",
                  "byteSize": 2048000,
                  "mimeType": "application/pdf",
                  "sha256": "deadbeef"
                }
                """.trimIndent()
            ),
            fallbackBucket = "chat-docs"
        )
        assertEquals("CLOUDFLARE_R2", parsed.provider)
        assertEquals("companies/xmax/chats/ch_general/u1_brief.pdf", parsed.storagePath)
        assertEquals(2048000L, parsed.byteSize)

        val r2Message = com.example.model.ChatMessage(
            id = "msg_r2_1",
            channelId = "ch_general",
            senderUid = "u1",
            attachmentType = com.example.model.ChatAttachmentType.FILE.name,
            attachmentName = "Client_Brief_v2.pdf",
            attachmentMimeType = "application/pdf",
            attachmentSizeBytes = 2_048_000L,
            attachmentDataBase64 = "",
            attachmentBucket = "chat-docs",
            attachmentStoragePath = "companies/xmax/chats/ch_general/u1_brief.pdf",
            attachmentSha256 = "deadbeef",
            storageProvider = "CLOUDFLARE_R2"
        )
        assertTrue(r2Message.isCloudflareStored)
        assertTrue(r2Message.hasAttachment)
    }
}
