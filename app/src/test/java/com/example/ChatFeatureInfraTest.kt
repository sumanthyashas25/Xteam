package com.example

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.chat.ChatFileManager
import com.example.chat.ChatQrGenerator
import com.example.chat.ChatSettingsStore
import com.example.chat.realtime.MemberAvailability
import com.example.chat.realtime.MemberPresenceState
import com.example.chat.realtime.RtdbPresenceManager
import com.example.chat.remote.WorkerBridgeClient
import com.example.model.ChatAttachmentType
import com.example.model.ChatChannel
import com.example.model.ChatChannelType
import com.example.model.ChatMessage
import com.example.model.ChatStatus
import com.example.model.Member
import com.example.ui.chat.formatChatRichText
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatFeatureInfraTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ChatSettingsStore.clearSecretCode(context)
    }

    @Test
    fun `qr generator produces valid monochrome matrix bitmap for invite links`() {
        val bmp = ChatQrGenerator.generateQrBitmap("https://xmax.app/join/AB12CD34", targetPixels = 256)
        assertTrue(bmp.width >= 100)
        assertEquals(bmp.width, bmp.height)

        var blackCount = 0
        var whiteCount = 0
        for (y in 0 until bmp.height step 4) {
            for (x in 0 until bmp.width step 4) {
                when (bmp.getPixel(x, y)) {
                    Color.BLACK -> blackCount++
                    Color.WHITE -> whiteCount++
                }
            }
        }
        assertTrue("Expected finder & data black modules", blackCount > 20)
        assertTrue("Expected quiet zone & white modules", whiteCount > 20)
    }

    @Test
    fun `chat settings store manages salted sha256 secret code, locks, lists, and wallpapers`() {
        ChatSettingsStore.ensureLoaded(context)
        assertFalse(ChatSettingsStore.prefsFlow.value.hasSecretCode)
        assertEquals("studio_white", ChatSettingsStore.prefsFlow.value.globalWallpaperId)
        assertEquals("studio_dark", ChatSettingsStore.prefsFlow.value.bubbleThemeId)

        ChatSettingsStore.setSecretCode(context, "4829")
        assertTrue(ChatSettingsStore.prefsFlow.value.hasSecretCode)
        assertTrue(ChatSettingsStore.verifySecretCode(context, "4829"))
        assertFalse(ChatSettingsStore.verifySecretCode(context, "0000"))

        ChatSettingsStore.toggleChannelLocked(context, "ch_secret_1")
        assertTrue("ch_secret_1" in ChatSettingsStore.prefsFlow.value.lockedChannelIds)

        ChatSettingsStore.markUnlockedForSession("ch_secret_1")
        assertTrue("ch_secret_1" in ChatSettingsStore.prefsFlow.value.unlockedThisSessionIds)
        ChatSettingsStore.lockAllSessionChannels()
        assertFalse("ch_secret_1" in ChatSettingsStore.prefsFlow.value.unlockedThisSessionIds)

        ChatSettingsStore.addCustomList(context, "VIP Clients")
        ChatSettingsStore.toggleChannelInCustomList(context, "ch_secret_1", "VIP Clients")
        assertTrue("VIP Clients" in ChatSettingsStore.prefsFlow.value.customLists)
        assertTrue("VIP Clients" in ChatSettingsStore.prefsFlow.value.channelListsMap["ch_secret_1"].orEmpty())

        ChatSettingsStore.setWallpaper(context, "ocean_mist", channelId = "ch_secret_1")
        assertEquals("ocean_mist", ChatSettingsStore.prefsFlow.value.channelWallpapers["ch_secret_1"])

        ChatSettingsStore.updateMediaSettings(
            context = context,
            autoDownloadPhotos = false,
            autoDownloadAudio = true,
            autoDownloadDocs = true,
            mediaVisibleInGallery = false
        )
        val updated = ChatSettingsStore.prefsFlow.value
        assertFalse(updated.autoDownloadPhotos)
        assertTrue(updated.autoDownloadDocs)
        assertFalse(updated.mediaVisibleInGallery)

        ChatSettingsStore.clearSecretCode(context)
        assertFalse(ChatSettingsStore.prefsFlow.value.hasSecretCode)
        assertTrue(ChatSettingsStore.prefsFlow.value.lockedChannelIds.isEmpty())
    }

    @Test
    fun `rich text formatter, url extraction, message receipts, video bucket, and 24h status behave cleanly`() {
        val sample = "Check *bold* _italic_ ~old~ `code` @aarav at https://xmax.app/briefs/42!"
        val urls = ChatFileManager.extractHttpsUrls(sample)
        assertEquals(listOf("https://xmax.app/briefs/42"), urls)

        val formatted = formatChatRichText(sample)
        assertEquals("Check bold italic old code @aarav at https://xmax.app/briefs/42!", formatted.text)
        assertTrue(formatted.spanStyles.isNotEmpty())

        assertEquals("chat-videos", WorkerBridgeClient.bucketForAttachmentType(ChatAttachmentType.VIDEO))

        val msgRead = ChatMessage(
            id = "m1",
            senderUid = "u1",
            readByUids = listOf("u2"),
            deliveredToUids = listOf("u2"),
            deliveryStatus = "SENT",
            viewOnce = true,
            viewedByUids = listOf("u2"),
            expiresAtMillis = 1_000L
        )
        assertEquals("READ", msgRead.effectiveStatusForSender(1))
        assertTrue("u2" in msgRead.viewedByUids)
        assertTrue(msgRead.isExpired(nowMillis = 2_000L))

        val ch = ChatChannel(
            id = "ch_grp_1",
            channelType = ChatChannelType.GROUP.name,
            adminUids = listOf("u_creator"),
            onlyAdminsCanMessage = true
        )
        assertTrue(ch.isChannelAdmin("u_creator", isSuperAdmin = false))
        assertTrue(ch.isChannelAdmin("u_random", isSuperAdmin = true))
        assertFalse(ch.isChannelAdmin("u_staff", isSuperAdmin = false))

        val now = System.currentTimeMillis()
        val activeStatus = ChatStatus(id = "s1", expiresAtMillis = now + 60_000L)
        val expiredStatus = ChatStatus(id = "s2", expiresAtMillis = now - 1_000L)
        assertTrue(activeStatus.isActive(now))
        assertFalse(expiredStatus.isActive(now))

        val memberStub = Member(uid = "u_mem", fullName = "Riya")
        assertEquals(
            MemberAvailability.ACTIVE,
            RtdbPresenceManager.resolveAvailability(memberStub, MemberPresenceState(online = true, availability = MemberAvailability.ACTIVE))
        )
        assertEquals(
            MemberAvailability.BUSY,
            RtdbPresenceManager.resolveAvailability(memberStub, MemberPresenceState(online = true, availability = MemberAvailability.BUSY))
        )
        assertEquals(
            MemberAvailability.AWAY,
            RtdbPresenceManager.resolveAvailability(memberStub, MemberPresenceState(online = false, availability = MemberAvailability.ACTIVE))
        )
    }
}
