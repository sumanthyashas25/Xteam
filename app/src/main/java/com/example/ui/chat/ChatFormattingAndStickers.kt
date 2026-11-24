package com.example.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.example.ui.theme.ObsidianCharcoal
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SubtleOutline
import com.example.ui.theme.VioletPrimary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class ChatWallpaperPreset(
    val id: String,
    val label: String,
    val baseColors: List<Color>,
    val orbColors: List<Color>
)

data class ChatBubbleThemePreset(
    val id: String,
    val label: String,
    val outgoingBg: Color,
    val outgoingBorder: Color,
    val incomingBg: Color,
    val accent: Color
)

data class ChatStickerItem(
    val id: String,
    val title: String,
    val emoji: String,
    val bgColor: Color,
    val accentColor: Color
)

data class ChatGifItem(
    val id: String,
    val title: String,
    val badgeText: String,
    val emoji: String,
    val gradientStart: Color,
    val gradientEnd: Color
)

object ChatVisualCatalog {
    val wallpapers = listOf(
        ChatWallpaperPreset(
            id = "studio_white",
            label = "Studio White",
            baseColors = listOf(Color(0xFFFFFFFF), Color(0xFFFFFFFF)),
            orbColors = listOf(Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFFFFFFFF))
        ),
        ChatWallpaperPreset(
            id = "soft_light",
            label = "Soft Light",
            baseColors = listOf(Color(0xFFF8FAFC), Color(0xFFF8FAFC)),
            orbColors = listOf(Color(0xFFF8FAFC), Color(0xFFF8FAFC), Color(0xFFF8FAFC), Color(0xFFF8FAFC))
        ),
        ChatWallpaperPreset(
            id = "cool_slate",
            label = "Cool Slate",
            baseColors = listOf(Color(0xFFF1F5F9), Color(0xFFF1F5F9)),
            orbColors = listOf(Color(0xFFF1F5F9), Color(0xFFF1F5F9), Color(0xFFF1F5F9), Color(0xFFF1F5F9))
        ),
        ChatWallpaperPreset(
            id = "subtle_zinc",
            label = "Subtle Zinc",
            baseColors = listOf(Color(0xFFF4F4F5), Color(0xFFF4F4F5)),
            orbColors = listOf(Color(0xFFF4F4F5), Color(0xFFF4F4F5), Color(0xFFF4F4F5), Color(0xFFF4F4F5))
        )
    )

    val bubbleThemes = listOf(
        ChatBubbleThemePreset(
            id = "studio_dark",
            label = "Studio Charcoal (Default)",
            outgoingBg = Color(0xFF111318),
            outgoingBorder = Color(0xFF111318),
            incomingBg = Color(0xFFECEFF2),
            accent = Color(0xFF0A84FF)
        ),
        ChatBubbleThemePreset(
            id = "studio_blue",
            label = "Studio Blue",
            outgoingBg = Color(0xFF0A84FF),
            outgoingBorder = Color(0xFF0A84FF),
            incomingBg = Color(0xFFECEFF2),
            accent = Color(0xFF0A84FF)
        ),
        ChatBubbleThemePreset(
            id = "obsidian_pro",
            label = "Slate Minimal",
            outgoingBg = Color(0xFF1E293B),
            outgoingBorder = Color(0xFF1E293B),
            incomingBg = Color(0xFFF1F5F9),
            accent = Color(0xFF38BDF8)
        ),
        ChatBubbleThemePreset(
            id = "emerald_luxe",
            label = "Studio Emerald",
            outgoingBg = Color(0xFF065F46),
            outgoingBorder = Color(0xFF065F46),
            incomingBg = Color(0xFFECEFF2),
            accent = Color(0xFF10B981)
        )
    )

    val stickers = listOf(
        ChatStickerItem("stk_approved", "Approved!", "✅", Color(0xFFECFDF5), Color(0xFF059669)),
        ChatStickerItem("stk_fire", "On Fire", "🔥", Color(0xFFFFF7ED), Color(0xFFEA580C)),
        ChatStickerItem("stk_ship", "Shipped!", "🚀", Color(0xFFEFF6FF), Color(0xFF2563EB)),
        ChatStickerItem("stk_love", "Love It", "💜", Color(0xFFF5F3FF), Color(0xFF7C3AED)),
        ChatStickerItem("stk_clap", "Bravo!", "👏", Color(0xFFFEFCE8), Color(0xFFCA8A04)),
        ChatStickerItem("stk_coffee", "Coffee Break", "☕", Color(0xFFFDF2F8), Color(0xFFDB2777)),
        ChatStickerItem("stk_deadline", "On Track", "⏱️", Color(0xFFF0FDF4), Color(0xFF16A34A)),
        ChatStickerItem("stk_idea", "Genius Idea", "💡", Color(0xFFFFFBEB), Color(0xFFD97706))
    )

    val gifs = listOf(
        ChatGifItem("gif_celebrate", "Celebration", "PARTY TIME", "🎉", Color(0xFF7C3AED), Color(0xFFEC4899)),
        ChatGifItem("gif_mindblown", "Mind Blown", "WOW!", "🤯", Color(0xFFF97316), Color(0xFFEF4444)),
        ChatGifItem("gif_rocket", "Launch Mode", "3.. 2.. 1.. GO", "🚀", Color(0xFF2563EB), Color(0xFF06B6D4)),
        ChatGifItem("gif_highfive", "Team Win", "HIGH FIVE!", "🙌", Color(0xFF10B981), Color(0xFF059669)),
        ChatGifItem("gif_typing", "On It Now", "WORKING...", "⚡", Color(0xFFEAB308), Color(0xFFF97316)),
        ChatGifItem("gif_thumbsup", "100% Yes", "CONFIRMED", "👍", Color(0xFF6366F1), Color(0xFF8B5CF6))
    )

    val statusGradients = listOf(
        listOf(Color(0xFF6366F1), Color(0xFFEC4899)),
        listOf(Color(0xFF10B981), Color(0xFF0284C7)),
        listOf(Color(0xFFF97316), Color(0xFFE11D48)),
        listOf(Color(0xFF1E293B), Color(0xFF475569)),
        listOf(Color(0xFF8B5CF6), Color(0xFF06B6D4))
    )

    val quickEmojis = listOf(
        "👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "🎉",
        "👏", "🚀", "💯", "✅", "💡", "✨", "🤝", "☕",
        "👀", "🙌", "🥳", "💪", "⚡", "📌", "📎", "💜"
    )

    fun bubbleTheme(themeId: String): ChatBubbleThemePreset =
        bubbleThemes.firstOrNull { it.id == themeId } ?: bubbleThemes.first()

    fun wallpaper(wallpaperId: String, isThread: Boolean): ChatWallpaperPreset {
        val exact = wallpapers.firstOrNull { it.id == wallpaperId }
        if (exact != null) return exact
        return wallpapers.first()
    }
}

@Composable
fun Modifier.animatedChatGlowBackdrop(
    isThread: Boolean,
    wallpaperId: String = "studio_white"
): Modifier {
    val preset = ChatVisualCatalog.wallpaper(wallpaperId, isThread)
    val bgColor = preset.baseColors.firstOrNull() ?: PureWhite
    return this.background(bgColor)
}

private val RICH_TOKEN_REGEX = Regex(
    """(https://[^\s<>"'\)]+|\*[^*\n]+\*|_[^_\n]+_|~[^~\n]+~|`[^`\n]+`|@[A-Za-z0-9_.-]+)"""
)

/**
 * Parses standard inline text formatting (*bold*, _italic_, ~strike~, `code`),
 * @mentions, and clickable https:// links into an AnnotatedString.
 */
fun formatChatRichText(
    body: String,
    mentions: List<String> = emptyList(),
    mentionColor: Color = VioletPrimary,
    linkColor: Color = Color(0xFF0284C7)
): AnnotatedString {
    if (body.isBlank()) return AnnotatedString("")
    return buildAnnotatedString {
        var cursor = 0
        RICH_TOKEN_REGEX.findAll(body).forEach { match ->
            if (match.range.first > cursor) {
                append(body.substring(cursor, match.range.first))
            }
            val token = match.value
            when {
                token.startsWith("https://", ignoreCase = true) -> {
                    val cleanUrl = token.trimEnd('.', ',', ';', '!', '?')
                    val trailing = token.removePrefix(cleanUrl)
                    withLink(
                        LinkAnnotation.Url(
                            url = cleanUrl,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    fontWeight = FontWeight.Medium,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                        )
                    ) {
                        append(cleanUrl)
                    }
                    if (trailing.isNotEmpty()) append(trailing)
                }
                token.startsWith("*") && token.endsWith("*") && token.length > 2 -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                token.startsWith("_") && token.endsWith("_") && token.length > 2 -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                token.startsWith("~") && token.endsWith("~") && token.length > 2 -> {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                token.startsWith("`") && token.endsWith("`") && token.length > 2 -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = SubtleOutline.copy(alpha = 0.65f)
                        )
                    ) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                token.startsWith("@") -> {
                    withStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.Bold)) {
                        append(token)
                    }
                }
                else -> append(token)
            }
            cursor = match.range.last + 1
        }
        if (cursor < body.length) {
            append(body.substring(cursor))
        }
    }
}
