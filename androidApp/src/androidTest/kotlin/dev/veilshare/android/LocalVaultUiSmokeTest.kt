package dev.veilshare.android

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.veilshare.core.vault.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertIs

class LocalVaultUiSmokeTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    init { AndroidVaultStorage.privateRoot(context).deleteRecursively() }
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun firstRunGenericUnlockBrowserFolderDeleteAndPinChange() {
        compose.waitUntil(10_000){compose.onAllNodesWithText("Prepara tus archivos").fetchSemanticsNodes().isNotEmpty()}
        val setup=compose.onAllNodes(hasSetTextAction());setup[0].performTextInput("5101");setup[1].performTextInput("5101");setup[2].performTextInput("5202");setup[3].performTextInput("5202")
        compose.onNodeWithText("Crear espacios").performClick()
        waitText("Continuar",30_000);unlock("5101");waitText("Nueva carpeta",30_000)
        compose.onNodeWithText("Nueva carpeta").performClick();compose.onNode(hasSetTextAction()).performTextInput("Trabajo");compose.onNodeWithText("Crear").performClick();waitText("Trabajo")
        compose.onNodeWithText("Bloquear").performClick();waitText("Continuar")

        runBlocking {
            val service=AndroidLocalVaultService(AndroidVaultStorage.privateRoot(context));val vault=assertIs<LocalUnlockResult.Ready>(service.unlock("5101".toCharArray())).vault
            val folder=assertIs<VaultItem.Directory>(vault.items(null).single{it.displayName=="Trabajo"})
            vault.import(UiBytesSource("visible.txt","contenido".encodeToByteArray()),VaultDirectoryId(folder.id.value));vault.close()
        }
        unlock("5101");waitText("Trabajo");compose.onNodeWithText("Trabajo").performClick();waitText("visible.txt")
        compose.onNodeWithText("Más").performClick();compose.onNodeWithText("Eliminar").performClick();compose.onAllNodesWithText("Eliminar")[1].performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("visible.txt").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText("Ajustes").performClick();val pins=compose.onAllNodes(hasSetTextAction());pins[0].performTextInput("5303");pins[1].performTextInput("5303");compose.onAllNodesWithText("Cambiar código")[1].performClick()
        waitText("Continuar",30_000);unlock("5101");waitText("No se pudo continuar.",30_000);unlock("5303");waitText("Trabajo",30_000)
    }

    private fun unlock(pin:String){compose.onNodeWithTag("unlock_input").performTextInput(pin);compose.onNodeWithTag("unlock_submit").performClick()}
    private fun waitText(text:String,timeout:Long=10_000)=compose.waitUntil(timeout){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
}

private class UiBytesSource(override val displayName:String,private val bytes:ByteArray):ImportSource{
    override val mimeHint="text/plain";override val sizeHint=bytes.size.toLong();override suspend fun openRead()=object:ImportReadHandle{var done=false;override suspend fun read(maxBytes:Int)=if(done)ByteArray(0)else bytes.also{done=true};override suspend fun close()=Unit}
}
