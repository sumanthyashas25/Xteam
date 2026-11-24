package com.example.chat.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "outgoing_messages")
data class OutgoingMessageEntity(
    @PrimaryKey val clientMsgId: String,
    val companyId: String,
    val chatId: String,
    val senderUid: String,
    val senderName: String,
    val senderRole: String,
    val senderPhotoUrl: String,
    val messageKind: String,
    val body: String,
    val linkUrl: String = "",
    val replyToMessageId: String = "",
    val replyPreview: String = "",
    val mentionsCsv: String = "",
    val localAttachmentUri: String = "",
    val localThumbUri: String = "",
    val attachmentMime: String = "",
    val attachmentName: String = "",
    val attachmentSizeBytes: Long = 0L,
    val attachmentSha256: String = "",
    val durationMs: Long = 0L,
    val businessRefKind: String = "",
    val businessRefId: String = "",
    val businessRefTitle: String = "",
    val businessRefSubtitle: String = "",
    val businessRefStatus: String = "",
    val businessRefAmountPaise: Long = 0L,
    val priority: String = "NORMAL",
    val ackRequired: Boolean = false,
    val locationLabel: String = "",
    val contactSummary: String = "",
    val status: String = "PENDING",
    val retryCount: Int = 0,
    val errorMessage: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_drafts")
data class ChatDraftEntity(
    @PrimaryKey val chatId: String,
    val draftText: String,
    val replyToMessageId: String = "",
    val replyPreview: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "cached_messages")
data class CachedMessageEntity(
    @PrimaryKey val messageId: String,
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val kind: String,
    val body: String,
    val attachmentName: String,
    val attachmentStoragePath: String,
    val businessRefTitle: String,
    val createdAtMillis: Long
)

@Fts4
@Entity(tableName = "cached_messages_fts")
data class CachedMessageFtsEntity(
    val messageId: String,
    val chatId: String,
    val senderName: String,
    val body: String,
    val attachmentName: String,
    val businessRefTitle: String
)

@Entity(tableName = "media_download_cache")
data class MediaCacheEntity(
    @PrimaryKey val storagePath: String,
    val localFilePath: String,
    val mimeType: String,
    val byteSize: Long,
    val lastAccessedMillis: Long
)

@Dao
interface ChatLocalDao {
    // --- Outbox Queue (Idempotent via clientMsgId PK) ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutgoingIfAbsent(entity: OutgoingMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutgoing(entity: OutgoingMessageEntity)

    @Query("SELECT * FROM outgoing_messages WHERE chatId = :chatId ORDER BY createdAtMillis ASC")
    fun observeOutgoingForChat(chatId: String): Flow<List<OutgoingMessageEntity>>

    @Query("SELECT * FROM outgoing_messages WHERE status IN ('PENDING', 'FAILED') ORDER BY createdAtMillis ASC")
    suspend fun getRetryableOutgoing(): List<OutgoingMessageEntity>

    @Query("SELECT * FROM outgoing_messages WHERE clientMsgId = :clientMsgId LIMIT 1")
    suspend fun getOutgoingById(clientMsgId: String): OutgoingMessageEntity?

    @Query("UPDATE outgoing_messages SET status = :status, errorMessage = :error, retryCount = :retryCount, updatedAtMillis = :updatedAt WHERE clientMsgId = :clientMsgId")
    suspend fun updateOutgoingState(
        clientMsgId: String,
        status: String,
        error: String,
        retryCount: Int,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM outgoing_messages WHERE clientMsgId = :clientMsgId")
    suspend fun deleteOutgoing(clientMsgId: String)

    @Query("DELETE FROM outgoing_messages")
    suspend fun clearAllOutgoing()

    // --- Drafts ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(draft: ChatDraftEntity)

    @Query("SELECT * FROM chat_drafts WHERE chatId = :chatId LIMIT 1")
    suspend fun getDraft(chatId: String): ChatDraftEntity?

    @Query("SELECT * FROM chat_drafts")
    fun observeAllDrafts(): Flow<List<ChatDraftEntity>>

    @Query("DELETE FROM chat_drafts WHERE chatId = :chatId")
    suspend fun deleteDraft(chatId: String)

    @Query("DELETE FROM chat_drafts")
    suspend fun clearAllDrafts()

    // --- Local Search & Message Cache ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedMessages(items: List<CachedMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedFts(items: List<CachedMessageFtsEntity>)

    @Query("DELETE FROM cached_messages_fts WHERE messageId IN (:ids)")
    suspend fun deleteCachedFtsByIds(ids: List<String>)

    @Transaction
    suspend fun indexMessages(items: List<CachedMessageEntity>) {
        if (items.isEmpty()) return
        upsertCachedMessages(items)
        deleteCachedFtsByIds(items.map { it.messageId })
        upsertCachedFts(
            items.map {
                CachedMessageFtsEntity(
                    messageId = it.messageId,
                    chatId = it.chatId,
                    senderName = it.senderName,
                    body = it.body,
                    attachmentName = it.attachmentName,
                    businessRefTitle = it.businessRefTitle
                )
            }
        )
    }

    @Query(
        """
        SELECT c.* FROM cached_messages c
        JOIN cached_messages_fts fts ON c.messageId = fts.messageId
        WHERE cached_messages_fts MATCH :ftsQuery
        AND (:chatId = '' OR c.chatId = :chatId)
        ORDER BY c.createdAtMillis DESC
        LIMIT 60
        """
    )
    suspend fun searchCachedFts(ftsQuery: String, chatId: String = ""): List<CachedMessageEntity>

    @Query(
        """
        SELECT * FROM cached_messages
        WHERE (:chatId = '' OR chatId = :chatId)
        AND (body LIKE '%' || :plainQuery || '%' OR attachmentName LIKE '%' || :plainQuery || '%' OR senderName LIKE '%' || :plainQuery || '%' OR businessRefTitle LIKE '%' || :plainQuery || '%')
        ORDER BY createdAtMillis DESC
        LIMIT 60
        """
    )
    suspend fun searchCachedLike(plainQuery: String, chatId: String = ""): List<CachedMessageEntity>

    @Query("DELETE FROM cached_messages WHERE chatId = :chatId")
    suspend fun clearCachedForChat(chatId: String)

    @Query("DELETE FROM cached_messages")
    suspend fun clearAllCachedMessages()

    @Query("DELETE FROM cached_messages_fts")
    suspend fun clearAllCachedFts()

    // --- Media LRU Disk Cache ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaCache(entry: MediaCacheEntity)

    @Query("SELECT * FROM media_download_cache WHERE storagePath = :path LIMIT 1")
    suspend fun getMediaCache(path: String): MediaCacheEntity?

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM media_download_cache")
    suspend fun totalMediaCacheBytes(): Long

    @Query("SELECT * FROM media_download_cache ORDER BY lastAccessedMillis ASC LIMIT :count")
    suspend fun oldestMediaCache(count: Int): List<MediaCacheEntity>

    @Query("DELETE FROM media_download_cache WHERE storagePath = :path")
    suspend fun deleteMediaCache(path: String)

    @Query("DELETE FROM media_download_cache")
    suspend fun clearAllMediaCache()

    @Transaction
    suspend fun wipeAllChatLocalCaches() {
        clearAllOutgoing()
        clearAllDrafts()
        clearAllCachedFts()
        clearAllCachedMessages()
        clearAllMediaCache()
    }
}

@Database(
    entities = [
        OutgoingMessageEntity::class,
        ChatDraftEntity::class,
        CachedMessageEntity::class,
        CachedMessageFtsEntity::class,
        MediaCacheEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class XMaxChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatLocalDao

    companion object {
        @Volatile
        private var instance: XMaxChatDatabase? = null

        fun getInstance(context: Context): XMaxChatDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    XMaxChatDatabase::class.java,
                    "xmax_chat_offline.db"
                ).build().also { instance = it }
            }
        }
    }
}
