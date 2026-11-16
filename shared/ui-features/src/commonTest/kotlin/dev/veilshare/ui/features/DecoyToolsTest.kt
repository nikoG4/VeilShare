package dev.veilshare.ui.features
import kotlin.test.*
import kotlinx.datetime.LocalDate
class DecoyToolsTest {
    @Test fun defaultsAndSelection() {
        val store = MemoryDecoyStore()
        assertEquals(DecoyKind.Calc, store.preferences.value.kind)
        assertTrue(store.select(DecoyPreferences(DecoyKind.Calendar, DecoyTheme.Dark)))
        assertEquals(DecoyKind.Calendar, store.preferences.value.kind)
        assertTrue(store.write("event_2026-11-16", "test"))
        assertEquals("test", store.read("event_2026-11-16"))
    }
    @Test fun calendarAndConversions() {
        val days = monthDays(LocalDate(2026,11,1))
        assertEquals(42, days.size)
        assertEquals(LocalDate(2026,10,26), days.first())
        assertEquals("01:05", secondsText(65))
        assertEquals(1.609344, conversions[1].second)
    }
}
