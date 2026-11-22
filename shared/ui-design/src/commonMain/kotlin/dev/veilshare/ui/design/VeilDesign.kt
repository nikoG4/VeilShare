package dev.veilshare.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.adaptivekt.components.AdaptiveCard

enum class VeilWindowClass { Compact, Medium, Expanded }

/** The only module permitted to import AdaptiveKt. */
private val lightColors = lightColorScheme(primary=Color(0xFF315B72),onPrimary=Color.White,primaryContainer=Color(0xFFD0E8F4),onPrimaryContainer=Color(0xFF102F3D),secondary=Color(0xFF53636D),secondaryContainer=Color(0xFFDCE4E8),background=Color(0xFFF7F9FA),surface=Color(0xFFF7F9FA),surfaceVariant=Color(0xFFE9EEF1))
private val darkColors = darkColorScheme(primary=Color(0xFF9DCCE2),onPrimary=Color(0xFF073547),primaryContainer=Color(0xFF234C61),secondaryContainer=Color(0xFF3B494F),background=Color(0xFF101416),surface=Color(0xFF101416),surfaceVariant=Color(0xFF283034))
private val workspaceColors = darkColorScheme(
    primary = Color(0xFFA9C3FF),
    onPrimary = Color(0xFF14284D),
    primaryContainer = Color(0xFF253552),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFB2BAC9),
    onSecondary = Color(0xFF202735),
    secondaryContainer = Color(0xFF303B4E),
    onSecondaryContainer = Color(0xFFE3E9F5),
    background = Color(0xFF111317),
    onBackground = Color(0xFFF3F5FA),
    surface = Color(0xFF171B24),
    onSurface = Color(0xFFF3F5FA),
    surfaceVariant = Color(0xFF202630),
    onSurfaceVariant = Color(0xFFB2BAC9),
    outline = Color(0xFF768295),
    outlineVariant = Color(0xFF303846),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun VeilTheme(privateWorkspace: Boolean = false, content: @Composable () -> Unit) =
    MaterialTheme(
        colorScheme = when {
            privateWorkspace -> workspaceColors
            isSystemInDarkTheme() -> darkColors
            else -> lightColors
        },
        content = content,
    )
@Composable fun VeilAdaptiveScaffold(windowClass: VeilWindowClass, content: @Composable (PaddingValues) -> Unit) { Surface(Modifier.fillMaxSize()) { content(PaddingValues(if (windowClass == VeilWindowClass.Expanded) 24.dp else 16.dp)) } }
@Composable fun VeilCard(content: @Composable () -> Unit) { AdaptiveCard { Column { content() } } }
