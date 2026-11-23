package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val StudioLightColors = lightColorScheme(
    primary = ObsidianCharcoal,
    onPrimary = PureWhite,
    primaryContainer = VioletSoftBg,
    onPrimaryContainer = VioletPrimary,
    secondary = VioletPrimary,
    onSecondary = PureWhite,
    secondaryContainer = MutedSurface,
    onSecondaryContainer = ObsidianCharcoal,
    tertiary = EmeraldApproved,
    onTertiary = PureWhite,
    tertiaryContainer = SoftMint,
    onTertiaryContainer = DeepMintInk,
    error = CrimsonBlocked,
    onError = PureWhite,
    errorContainer = SoftBlush,
    onErrorContainer = DeepBlushInk,
    background = CrispCanvas,
    onBackground = ObsidianCharcoal,
    surface = PureWhite,
    onSurface = ObsidianCharcoal,
    surfaceVariant = MutedSurface,
    onSurfaceVariant = StudioTextSecondary,
    outline = SubtleOutline
)

private val StudioDarkColors = darkColorScheme(
    primary = PureWhite,
    onPrimary = ObsidianCharcoal,
    primaryContainer = Color(0xFF23252C),
    onPrimaryContainer = PureWhite,
    secondary = WarmAlarmAmber,
    onSecondary = ObsidianCharcoal,
    secondaryContainer = Color(0xFF423210),
    onSecondaryContainer = ButterYellow,
    tertiary = SoftMint,
    onTertiary = DeepMintInk,
    tertiaryContainer = Color(0xFF143531),
    onTertiaryContainer = SoftMint,
    error = Color(0xFFF97066),
    onError = ObsidianCharcoal,
    errorContainer = Color(0xFF55160C),
    onErrorContainer = SoftBlush,
    background = ObsidianCharcoal,
    onBackground = Color(0xFFF4F5F8),
    surface = ObsidianInset,
    onSurface = Color(0xFFF4F5F8),
    surfaceVariant = Color(0xFF282A34),
    onSurfaceVariant = DockIconInactive,
    outline = Color(0xFF343744)
)

val XMaxShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun XMaxTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) StudioDarkColors else StudioLightColors,
        typography = XMaxTypography,
        shapes = XMaxShapes,
        content = content
    )
}
