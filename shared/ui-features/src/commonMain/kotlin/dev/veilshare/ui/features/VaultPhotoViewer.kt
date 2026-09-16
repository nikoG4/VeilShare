package dev.veilshare.ui.features

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Full-screen photo viewer backed by the in-memory preview provider. */
@Composable
fun VaultPhotoViewerOverlay(
    controller: LocalAppController,
    mediaPreview: MediaPreviewProvider,
) {
    val selected by controller.viewerItem.collectAsState()
    val item = selected ?: return

    Dialog(
        onDismissRequest = controller::closeViewer,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var bitmap by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
        var finishedLoading by remember(item.id) { mutableStateOf(false) }
        var scale by remember(item.id) { mutableStateOf(1f) }
        var offset by remember(item.id) { mutableStateOf(Offset.Zero) }

        LaunchedEffect(item.id, mediaPreview) {
            bitmap = mediaPreview.load(item.id, 2560)
            finishedLoading = true
        }

        val transform = rememberTransformableState { zoomChange, panChange, _ ->
            val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
            scale = nextScale
            offset = if (nextScale <= 1.01f) Offset.Zero else offset + panChange
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = controller::closeViewer) {
                    Text("Cerrar")
                }
                Text(
                    item.name,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Button(onClick = { controller.shareVaultItem(item.id) }) {
                    Text("Compartir")
                }
            }

            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val image = bitmap
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                            .transformable(transform),
                    )
                } else if (!finishedLoading) {
                    CircularProgressIndicator()
                } else {
                    Text("No se pudo mostrar esta imagen.", color = Color.White)
                }
            }
        }
    }
}
