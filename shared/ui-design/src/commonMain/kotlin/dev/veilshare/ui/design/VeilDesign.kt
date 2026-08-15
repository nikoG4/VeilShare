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
@Composable fun VeilTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme=if(isSystemInDarkTheme())darkColors else lightColors,content=content)
@Composable fun VeilAdaptiveScaffold(windowClass: VeilWindowClass, content: @Composable (PaddingValues) -> Unit) { Surface(Modifier.fillMaxSize()) { content(PaddingValues(if (windowClass == VeilWindowClass.Expanded) 24.dp else 16.dp)) } }
@Composable fun VeilCard(content: @Composable () -> Unit) { AdaptiveCard { Column { content() } } }
