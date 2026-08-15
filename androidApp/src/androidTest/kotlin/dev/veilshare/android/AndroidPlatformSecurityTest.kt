package dev.veilshare.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidPlatformSecurityTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()

    @Test fun ownedPlaintextCacheCleansStartupAndLockWithoutTouchingSiblingCache() {
        val parent=File(context.cacheDir,"temp-policy-test").also { it.deleteRecursively();it.mkdirs() }
        val unrelated=File(parent,"unrelated.txt").also { it.writeText("keep") }
        val root=File(parent,"open-4f16a9")
        File(root,"nested/stale.txt").also { it.parentFile!!.mkdirs();it.writeText("plaintext") }

        val cache=AndroidOwnedPlaintextCache(root)
        assertTrue(unrelated.exists())
        assertTrue(root.listFiles().isNullOrEmpty())
        val opened=cache.create(".txt")
        assertTrue(opened.exists())
        assertFalse(opened.name.contains("secret",ignoreCase=true))
        assertTrue(cache.cleanup())
        assertFalse(opened.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun fileProviderExposesOnlyTheDedicatedOwnedRoot() {
        val owned=File(context.cacheDir,"open-4f16a9/allowed.bin").also { it.parentFile!!.mkdirs();it.writeBytes(byteArrayOf(1)) }
        val outside=File(context.cacheDir,"not-exported.bin").also { it.writeBytes(byteArrayOf(2)) }
        val authority="${context.packageName}.files"
        assertTrue(FileProvider.getUriForFile(context,authority,owned).toString().contains("owned_open"))
        assertFailsWith<IllegalArgumentException> { FileProvider.getUriForFile(context,authority,outside) }
    }

    @Test fun manifestAndWindowKeepTheLocalSurfacePrivate() {
        val packageInfo=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS)
        val permissions=packageInfo.requestedPermissions?.toSet().orEmpty()
        assertFalse("android.permission.MANAGE_EXTERNAL_STORAGE" in permissions)
        assertFalse("android.permission.READ_MEDIA_IMAGES" in permissions)
        assertFalse("android.permission.READ_EXTERNAL_STORAGE" in permissions)
        assertFalse("android.permission.WRITE_EXTERNAL_STORAGE" in permissions)
        assertFalse("android.permission.READ_PHONE_STATE" in permissions)
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        }
    }
}
