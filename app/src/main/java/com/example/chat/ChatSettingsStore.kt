package com.example.chat

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

data class ChatUserPreferences(
    val hasSecretCode: Boolean = false,
    val lockedChannelIds: Set<String> = emptySet(),
    val unlockedThisSessionIds: Set<String> = emptySet(),
    val customLists: List<String> = listOf("Priority", "Clients", "Creative"),
    val channelListsMap: Map<String, Set<String>> = emptyMap(),
    val globalWallpaperId: String = "ambient_glow",
    val channelWallpapers: Map<String, String> = emptyMap(),
    val bubbleThemeId: String = "classic_mint",
    val autoDownloadPhotos: Boolean = true,
    val autoDownloadAudio: Boolean = true,
    val autoDownloadDocs: Boolean = false,
    val mediaVisibleInGallery: Boolean = true
)

/**
 * Local persistent chat settings (salted SHA-256 secret code for locked chats,
 * custom chat lists, custom wallpapers/themes, auto-download & gallery visibility).
 */
object ChatSettingsStore {
    private const val PREF_NAME = "xmax_chat_settings_v1"
    private const val KEY_PIN_HASH = "lock_pin_hash"
    private const val KEY_PIN_SALT = "lock_pin_salt"
    private const val KEY_LOCKED_IDS = "locked_channel_ids"
    private const val KEY_CUSTOM_LISTS = "custom_lists"
    private const val KEY_CHANNEL_LISTS = "channel_lists_json"
    private const val KEY_GLOBAL_WALLPAPER = "global_wallpaper_id"
    private const val KEY_CHANNEL_WALLPAPERS = "channel_wallpapers_json"
    private const val KEY_BUBBLE_THEME = "bubble_theme_id"
    private const val KEY_AUTO_DL_PHOTOS = "auto_dl_photos"
    private const val KEY_AUTO_DL_AUDIO = "auto_dl_audio"
    private const val KEY_AUTO_DL_DOCS = "auto_dl_docs"
    private const val KEY_MEDIA_GALLERY = "media_visible_gallery"

    private val _prefsFlow = MutableStateFlow(ChatUserPreferences())
    val prefsFlow: StateFlow<ChatUserPreferences> = _prefsFlow.asStateFlow()

    @Volatile
    private var initialized = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun ensureLoaded(context: Context): ChatUserPreferences {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    _prefsFlow.value = readFromDisk(context)
                    initialized = true
                }
            }
        }
        return _prefsFlow.value
    }

    private fun readFromDisk(context: Context): ChatUserPreferences {
        val sp = prefs(context)
        val hasPin = !sp.getString(KEY_PIN_HASH, null).isNullOrBlank()
        val locked = sp.getStringSet(KEY_LOCKED_IDS, emptySet())?.toSet().orEmpty()
        val listsRaw = sp.getString(KEY_CUSTOM_LISTS, null)
        val customLists = if (listsRaw.isNullOrBlank()) {
            listOf("Priority", "Clients", "Creative")
        } else {
            runCatching {
                val arr = JSONArray(listsRaw)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.getOrDefault(listOf("Priority", "Clients", "Creative"))
        }
        val channelLists = parseStringSetMap(sp.getString(KEY_CHANNEL_LISTS, null))
        val channelWallpapers = parseStringMap(sp.getString(KEY_CHANNEL_WALLPAPERS, null))
        return ChatUserPreferences(
            hasSecretCode = hasPin,
            lockedChannelIds = locked,
            unlockedThisSessionIds = _prefsFlow.value.unlockedThisSessionIds,
            customLists = customLists,
            channelListsMap = channelLists,
            globalWallpaperId = sp.getString(KEY_GLOBAL_WALLPAPER, "ambient_glow") ?: "ambient_glow",
            channelWallpapers = channelWallpapers,
            bubbleThemeId = sp.getString(KEY_BUBBLE_THEME, "classic_mint") ?: "classic_mint",
            autoDownloadPhotos = sp.getBoolean(KEY_AUTO_DL_PHOTOS, true),
            autoDownloadAudio = sp.getBoolean(KEY_AUTO_DL_AUDIO, true),
            autoDownloadDocs = sp.getBoolean(KEY_AUTO_DL_DOCS, false),
            mediaVisibleInGallery = sp.getBoolean(KEY_MEDIA_GALLERY, true)
        )
    }

    fun setSecretCode(context: Context, pin: String) {
        val clean = pin.trim()
        require(clean.length >= 4) { "Secret code must be at least 4 characters." }
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = saltBytes.joinToString("") { "%02x".format(it) }
        val hash = sha256("$saltHex:$clean")
        prefs(context).edit()
            .putString(KEY_PIN_SALT, saltHex)
            .putString(KEY_PIN_HASH, hash)
            .apply()
        _prefsFlow.value = _prefsFlow.value.copy(hasSecretCode = true)
    }

    fun verifySecretCode(context: Context, pin: String): Boolean {
        val sp = prefs(context)
        val salt = sp.getString(KEY_PIN_SALT, null) ?: return false
        val expected = sp.getString(KEY_PIN_HASH, null) ?: return false
        val actual = sha256("$salt:${pin.trim()}")
        return MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
    }

    fun clearSecretCode(context: Context) {
        prefs(context).edit()
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_HASH)
            .remove(KEY_LOCKED_IDS)
            .apply()
        _prefsFlow.value = _prefsFlow.value.copy(
            hasSecretCode = false,
            lockedChannelIds = emptySet(),
            unlockedThisSessionIds = emptySet()
        )
    }

    fun toggleChannelLocked(context: Context, channelId: String) {
        val cur = ensureLoaded(context)
        val next = cur.lockedChannelIds.toMutableSet()
        if (channelId in next) next.remove(channelId) else next.add(channelId)
        prefs(context).edit().putStringSet(KEY_LOCKED_IDS, next).apply()
        _prefsFlow.value = cur.copy(
            lockedChannelIds = next,
            unlockedThisSessionIds = cur.unlockedThisSessionIds - channelId
        )
    }

    fun markUnlockedForSession(channelId: String) {
        val cur = _prefsFlow.value
        _prefsFlow.value = cur.copy(unlockedThisSessionIds = cur.unlockedThisSessionIds + channelId)
    }

    fun lockAllSessionChannels() {
        val cur = _prefsFlow.value
        _prefsFlow.value = cur.copy(unlockedThisSessionIds = emptySet())
    }

    fun addCustomList(context: Context, name: String) {
        val clean = name.trim().take(28)
        if (clean.isBlank()) return
        val cur = ensureLoaded(context)
        val next = (cur.customLists + clean).distinct()
        prefs(context).edit().putString(KEY_CUSTOM_LISTS, JSONArray(next).toString()).apply()
        _prefsFlow.value = cur.copy(customLists = next)
    }

    fun deleteCustomList(context: Context, name: String) {
        val cur = ensureLoaded(context)
        val next = cur.customLists.filterNot { it.equals(name, ignoreCase = true) }
        val nextMap = cur.channelListsMap.mapValues { (_, set) -> set - name }.filterValues { it.isNotEmpty() }
        prefs(context).edit()
            .putString(KEY_CUSTOM_LISTS, JSONArray(next).toString())
            .putString(KEY_CHANNEL_LISTS, encodeStringSetMap(nextMap))
            .apply()
        _prefsFlow.value = cur.copy(customLists = next, channelListsMap = nextMap)
    }

    fun toggleChannelInCustomList(context: Context, channelId: String, listName: String) {
        val cur = ensureLoaded(context)
        val nextMap = cur.channelListsMap.toMutableMap()
        val existing = (nextMap[channelId] ?: emptySet()).toMutableSet()
        if (listName in existing) existing.remove(listName) else existing.add(listName)
        if (existing.isEmpty()) nextMap.remove(channelId) else nextMap[channelId] = existing
        prefs(context).edit().putString(KEY_CHANNEL_LISTS, encodeStringSetMap(nextMap)).apply()
        _prefsFlow.value = cur.copy(channelListsMap = nextMap)
    }

    fun setWallpaper(context: Context, wallpaperId: String, channelId: String? = null) {
        val cur = ensureLoaded(context)
        if (channelId.isNullOrBlank()) {
            prefs(context).edit().putString(KEY_GLOBAL_WALLPAPER, wallpaperId).apply()
            _prefsFlow.value = cur.copy(globalWallpaperId = wallpaperId)
        } else {
            val next = cur.channelWallpapers.toMutableMap()
            if (wallpaperId == "default") next.remove(channelId) else next[channelId] = wallpaperId
            prefs(context).edit().putString(KEY_CHANNEL_WALLPAPERS, encodeStringMap(next)).apply()
            _prefsFlow.value = cur.copy(channelWallpapers = next)
        }
    }

    fun setBubbleTheme(context: Context, themeId: String) {
        val cur = ensureLoaded(context)
        prefs(context).edit().putString(KEY_BUBBLE_THEME, themeId).apply()
        _prefsFlow.value = cur.copy(bubbleThemeId = themeId)
    }

    fun updateMediaSettings(
        context: Context,
        autoDownloadPhotos: Boolean,
        autoDownloadAudio: Boolean,
        autoDownloadDocs: Boolean,
        mediaVisibleInGallery: Boolean
    ) {
        val cur = ensureLoaded(context)
        prefs(context).edit()
            .putBoolean(KEY_AUTO_DL_PHOTOS, autoDownloadPhotos)
            .putBoolean(KEY_AUTO_DL_AUDIO, autoDownloadAudio)
            .putBoolean(KEY_AUTO_DL_DOCS, autoDownloadDocs)
            .putBoolean(KEY_MEDIA_GALLERY, mediaVisibleInGallery)
            .apply()
        _prefsFlow.value = cur.copy(
            autoDownloadPhotos = autoDownloadPhotos,
            autoDownloadAudio = autoDownloadAudio,
            autoDownloadDocs = autoDownloadDocs,
            mediaVisibleInGallery = mediaVisibleInGallery
        )
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun parseStringSetMap(raw: String?): Map<String, Set<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { k ->
                    val arr = obj.optJSONArray(k) ?: JSONArray()
                    val set = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
                    if (set.isNotEmpty()) put(k, set)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodeStringSetMap(map: Map<String, Set<String>>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, JSONArray(v.toList())) }
        return obj.toString()
    }

    private fun parseStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { k ->
                    obj.optString(k).takeIf(String::isNotBlank)?.let { put(k, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodeStringMap(map: Map<String, String>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }
}
