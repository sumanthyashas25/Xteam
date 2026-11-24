package com.example.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
            id = "ambient_glow",
            label = "Ambient Glow",
            baseColors = listOf(Color(0xFFD6FFF0), Color(0xFFECE4FF), Color(0xFFFFE5D9)),
            orbColors = listOf(Color(0xFF10B981), Color(0xFF8B5CF6), Color(0xFFFB923C), Color(0xFF38BDF8))
        ),
        ChatWallpaperPreset(
            id = "sunset_rose",
            label = "Sunset Rose",
            baseColors = listOf(Color(0xFFFFE4DA), Color(0xFFF3E8FF), Color(0xFFE0F4FF)),
            orbColors = listOf(Color(0xFFFB7185), Color(0xFF8B5CF6), Color(0xFF38BDF8), Color(0xFFFDBA74))
        ),
        ChatWallpaperPreset(
            id = "ocean_mint",
            label = "Ocean Mint",
            baseColors = listOf(Color(0xFFD1FAE5), Color(0xFFE0F2FE), Color(0xFFF0FDF4)),
            orbColors = listOf(Color(0xFF06B6D4), Color(0xFF10B981), Color(0xFF3B82F6), Color(0xFF6EE7B7))
        ),
        ChatWallpaperPreset(
            id = "midnight_iris",
            label = "Iris Cloud",
            baseColors = listOf(Color(0xFFE0E7FF), Color(0xFFF3E8FF), Color(0xFFEFF6FF)),
            orbColors = listOf(Color(0xFF6366F1), Color(0xFFA855F7), Color(0xFFEC4899), Color(0xFF38BDF8))
        ),
        ChatWallpaperPreset(
            id = "warm_sand",
            label = "Warm Sand",
            baseColors = listOf(Color(0xFFFEF3C7), Color(0xFFFFEDD5), Color(0xFFFDF2F8)),
            orbColors = listOf(Color(0xFFF59E0B), Color(0xFFFB923C), Color(0xFFF43F5E), Color(0xFFA855F7))
        )
    )

    val bubbleThemes = listOf(
        ChatBubbleThemePreset(
            id = "classic_mint",
            label = "Studio Mint",
            outgoingBg = Color(0xFFE7F8EE),
            outgoingBorder = Color(0xFFBCE7CB),
            incomingBg = PureWhite,
            accent = Color(0xFF10B981)
        ),
        ChatBubbleThemePreset(
            id = "violet_luxe",
            label = "Violet Luxe",
            outgoingBg = Color(0xFFF3E8FF),
            outgoingBorder = Color(0xFFD8B4FE),
            incomingBg = PureWhite,
            accent = VioletPrimary
        ),
        ChatBubbleThemePreset(
            id = "sky_breeze",
            label = "Sky Breeze",
            outgoingBg = Color(0xFFE0F2FE),
            outgoingBorder = Color(0xFFBAE6FD),
            incomingBg = PureWhite,
            accent = Color(0xFF0284C7)
        ),
        ChatBubbleThemePreset(
            id = "obsidian_pro",
            label = "Slate Minimal",
            outgoingBg = Color(0xFFF1F5F9),
            outgoingBorder = Color(0xFFCBD5E1),
            incomingBg = PureWhite,
            accent = ObsidianCharcoal
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
        if (exact != null && wallpaperId != "ambient_glow") return exact
        return if (isThread) wallpapers[1] else wallpapers[0]
    }
}

@Composable
fun Modifier.animatedChatGlowBackdrop(
    isThread: Boolean,
    wallpaperId: String = "ambient_glow"
): Modifier {
    val preset = ChatVisualCatalog.wallpaper(wallpaperId, isThread)
    val transition = rememberInfiniteTransition(label = "chat_ambient_glow_motion")
    val primaryOrbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_primary_orbit"
    )
    val secondaryOrbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 17500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_secondary_orbit"
    )
    val driftOrbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glow_drift_orbit"
    )

    return this.drawBehind {
        val sweepStart = Offset(
            x = size.width * (0.5f + 0.5f * cos(primaryOrbit)),
            y = size.height * (0.5f + 0.5f * sin(primaryOrbit))
        )
        val sweepEnd = Offset(
            x = size.width - sweepStart.x,
            y = size.height - sweepStart.y
        )
        drawRect(
            brush = Brush.linearGradient(
                colors = preset.baseColors.map { it.copy(alpha = 0.94f) },
                start = sweepStart,
                end = sweepEnd
            )
        )

        val c1 = Offset(
            x = size.width * (0.50f + 0.30f * cos(primaryOrbit)),
            y = size.height * (0.42f + 0.24f * sin(primaryOrbit))
        )
        val c2 = Offset(
            x = size.width * (0.24f + 0.30f * sin(secondaryOrbit)),
            y = size.height * (0.28f + 0.24f * cos(secondaryOrbit))
        )
        val c3 = Offset(
            x = size.width * (0.76f - 0.28f * cos(driftOrbit)),
            y = size.height * (0.64f + 0.24f * sin(driftOrbit))
        )
        val c4 = Offset(
            x = size.width * (0.54f - 0.26f * sin(primaryOrbit)),
            y = size.height * (0.78f - 0.20f * cos(secondaryOrbit))
        )

        val primaryColor = preset.orbColors[0].copy(alpha = 0.34f)
        val secondColor = preset.orbColors[1].copy(alpha = 0.33f)
        val thirdColor = preset.orbColors[2].copy(alpha = 0.31f)
        val fourthColor = preset.orbColors[3].copy(alpha = 0.28f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primaryColor, primaryColor.copy(alpha = 0.08f), Color.Transparent),
                center = c1,
                radius = size.width * 0.90f
            ),
            radius = size.width * 0.90f,
            center = c1
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondColor, secondColor.copy(alpha = 0.07f), Color.Transparent),
                center = c2,
                radius = size.width * 0.78f
            ),
            radius = size.width * 0.78f,
            center = c2
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(thirdColor, thirdColor.copy(alpha = 0.07f), Color.Transparent),
                center = c3,
                radius = size.width * 0.74f
            ),
            radius = size.width * 0.74f,
            center = c3
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(fourthColor, Color.Transparent),
                center = c4,
                radius = size.width * 0.68f
            ),
            radius = size.width * 0.68f,
            center = c4
        )
    }
}

private val RICH_TOKEN_REGEX = Regex(
    """(https://[^\s<>"'\)]+|\*[^*\n]+\*|_[^_\n]+_|~[^~\n]+~|`[^`\n]+`|@[A-Za-z0-9_.-]+)"""
)

/**
 * Parses WhatsApp-style inline text formatting (*bold*, _italic_, ~strike~, `code`),
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
