package dev.veilshare.android

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.veilshare.ui.features.DecoyKind
import dev.veilshare.ui.features.DecoyPreferences
import dev.veilshare.ui.features.DecoyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoyLauncherPersistenceTest {
    @Test fun eachSelectionPersistsAndExposesExactlyOneLauncher() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidDecoyStore(context)
        val original = store.preferences.value
        try {
            for (kind in DecoyKind.entries) {
                for (theme in DecoyTheme.entries) {
                    val choice = DecoyPreferences(kind, theme)
                    assertTrue(store.select(choice))
                    assertEquals(choice, AndroidDecoyStore(context).preferences.value)
                    val intent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(context.packageName)
                    @Suppress("DEPRECATION")
                    val launchers = context.packageManager.queryIntentActivities(intent, 0)
                    assertEquals(listOf("dev.veilshare.android.Launcher" + kind.name),
                        launchers.map { it.activityInfo.name })
                    assertEquals(kind.label, launchers.single().loadLabel(context.packageManager).toString())
                    assertTrue(launchers.single().activityInfo.icon != 0)
                }
            }
        } finally {
            assertTrue("Restore original appearance", store.select(original))
        }
    }
}
