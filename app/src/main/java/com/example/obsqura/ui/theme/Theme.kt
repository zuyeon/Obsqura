package com.example.obsqura.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// ===== ColorScheme 기존 유지 =====
private val DarkColorScheme = darkColorScheme(
    primary = md_primary_dark, onPrimary = md_onPrimary_dark,
    primaryContainer = md_primaryContainer_dark, onPrimaryContainer = md_onPrimaryContainer_dark,
    secondary = md_secondary_dark, onSecondary = md_onSecondary_dark,
    secondaryContainer = md_secondaryContainer_dark, onSecondaryContainer = md_onSecondaryContainer_dark,
    tertiary = md_tertiary_dark, onTertiary = md_onTertiary_dark,
    tertiaryContainer = md_tertiaryContainer_dark, onTertiaryContainer = md_onTertiaryContainer_dark,
    background = md_background_dark, onBackground = md_onBackground_dark,
    surface = md_surface_dark, onSurface = md_onSurface_dark,
    surfaceVariant = md_surfaceVariant_dark, onSurfaceVariant = md_onSurfaceVariant_dark,
    outline = md_outline_dark, error = md_error_dark, onError = md_onError_dark,
    errorContainer = md_errorContainer_dark, onErrorContainer = md_onErrorContainer_dark,
)

private val LightColorScheme = lightColorScheme(
    primary = md_primary_light, onPrimary = md_onPrimary_light,
    primaryContainer = md_primaryContainer_light, onPrimaryContainer = md_onPrimaryContainer_light,
    secondary = md_secondary_light, onSecondary = md_onSecondary_light,
    secondaryContainer = md_secondaryContainer_light, onSecondaryContainer = md_onSecondaryContainer_light,
    tertiary = md_tertiary_light, onTertiary = md_onTertiary_light,
    tertiaryContainer = md_tertiaryContainer_light, onTertiaryContainer = md_onTertiaryContainer_light,
    background = md_background_light, onBackground = md_onBackground_light,
    surface = md_surface_light, onSurface = md_onSurface_light,
    surfaceVariant = md_surfaceVariant_light, onSurfaceVariant = md_onSurfaceVariant_light,
    outline = md_outline_light, error = md_error_light, onError = md_onError_light,
    errorContainer = md_errorContainer_light, onErrorContainer = md_onErrorContainer_light,
)

// ===== App Shapes (모서리 규격 통일) =====
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

// ===== App 간격/패딩 토큰 =====
object AppDimens {
    val ScreenPadding = 16.dp
    val GapSm = 8.dp
    val GapMd = 12.dp
    val GapLg = 16.dp
}

// ===== 공통 버튼 =====
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.large
    ) { ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() } }
}

@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor   = MaterialTheme.colorScheme.onSecondary
        )
    ) { ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() } }
}

// ===== 공통 스캐폴드(AppBar + 기본 패딩) =====

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    onBack?.let { SecondaryButton(onClick = it) { Text("←") } }
                },
                actions = actions
            )
        }
    ) { inner ->
        Column(Modifier.padding(inner).padding(AppDimens.ScreenPadding)) {
            content()
        }
    }
}

@Composable
fun BLECommunicatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,  // Type.kt
        shapes      = AppShapes,
        content     = content
    )
}
