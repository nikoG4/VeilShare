package dev.veilshare.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.veilshare.ui.features.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class AndroidDecoyStore(context: Context) : DecoyStore {
    private val ctx = context.applicationContext
    private val disk = ctx.getSharedPreferences("public_tools", Context.MODE_PRIVATE)
    private val pm = ctx.packageManager
    private fun component(kind: DecoyKind) = ComponentName(ctx.packageName, "dev.veilshare.android.Launcher" + kind.name)
    private val state = MutableStateFlow(DecoyPreferences(
        runCatching { DecoyKind.valueOf(disk.getString("kind", "Calc")!!) }.getOrDefault(DecoyKind.Calc),
        runCatching { DecoyTheme.valueOf(disk.getString("theme", "System")!!) }.getOrDefault(DecoyTheme.System),
    ))
    override val preferences: StateFlow<DecoyPreferences> = state
    private fun applyAliases(kind: DecoyKind) {
        val flag = PackageManager.DONT_KILL_APP
        if (Build.VERSION.SDK_INT >= 33) {
            pm.setComponentEnabledSettings(DecoyKind.entries.map {
                PackageManager.ComponentEnabledSetting(component(it), if (it == kind) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, flag)
            })
        } else {
            pm.setComponentEnabledSetting(component(kind), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, flag)
            DecoyKind.entries.filter { it != kind }.forEach {
                pm.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, flag)
            }
        }
    }
    override fun select(value: DecoyPreferences): Boolean {
        val old = state.value
        return try {
            if (value.kind != old.kind) applyAliases(value.kind)
            check(disk.edit().putString("kind", value.kind.name).putString("theme", value.theme.name).commit())
            state.value = value
            true
        } catch (_: Exception) {
            runCatching { applyAliases(old.kind) }
            false
        }
    }
    override fun read(key: String): String? = disk.getString("tool_" + key, null)
    override fun write(key: String, value: String): Boolean = disk.edit().putString("tool_" + key, value).commit()
}
