package dev.veilshare.ui.features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class DecoyKind(val label: String) { Calc("Calc"), Calendar("Calendario"), Notes("Notas"), Clock("Reloj"), Converter("Conversor") }
enum class DecoyTheme { System, Light, Dark }
data class DecoyPreferences(val kind: DecoyKind = DecoyKind.Calc, val theme: DecoyTheme = DecoyTheme.System)
interface DecoyStore {
    val preferences: StateFlow<DecoyPreferences>
    fun select(value: DecoyPreferences): Boolean
    fun read(key: String): String?
    fun write(key: String, value: String): Boolean
}
class MemoryDecoyStore : DecoyStore {
    private val state = MutableStateFlow(DecoyPreferences())
    override val preferences: StateFlow<DecoyPreferences> = state
    private val data = mutableMapOf<String,String>()
    override fun select(value: DecoyPreferences): Boolean { state.value = value; return true }
    override fun read(key: String): String? = data[key]
    override fun write(key: String, value: String): Boolean { data[key] = value; return true }
}
