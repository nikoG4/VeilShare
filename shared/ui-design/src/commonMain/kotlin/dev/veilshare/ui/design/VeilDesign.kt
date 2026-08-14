package dev.veilshare.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.adaptivekt.components.AdaptiveCard

enum class VeilWindowClass { Compact, Medium, Expanded }

/** The only module permitted to import AdaptiveKt. */
@Composable fun VeilTheme(content: @Composable () -> Unit) = MaterialTheme(content = content)
@Composable fun VeilAdaptiveScaffold(windowClass: VeilWindowClass, content: @Composable (PaddingValues) -> Unit) { Surface(Modifier.fillMaxSize()) { content(PaddingValues(if (windowClass == VeilWindowClass.Expanded) 24.dp else 16.dp)) } }
@Composable fun VeilCard(content: @Composable () -> Unit) { AdaptiveCard { Column { content() } } }
