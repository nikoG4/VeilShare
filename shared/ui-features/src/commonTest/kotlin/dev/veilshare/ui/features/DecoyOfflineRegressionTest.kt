package dev.veilshare.ui.features

import kotlin.test.*
import kotlinx.datetime.LocalDate

/** Regression coverage for the public, offline tools. No vault credentials are used. */
class DecoyOfflineRegressionTest {
    @Test fun calendarGridCoversLeapDayAndWeekBoundaries() {
        val february = monthDays(LocalDate(2028, 2, 1))
        assertEquals(42, february.size)
        assertEquals(LocalDate(2028, 1, 31), february.first())
        assertTrue(LocalDate(2028, 2, 29) in february)
        assertEquals(LocalDate(2028, 3, 12), february.last())
        assertEquals(7, february.chunked(7).first().size)
    }

    @Test fun publicNotesAndEventsRemainSeparatedAcrossAppearanceChanges() {
        val store = MemoryDecoyStore()
        assertTrue(store.write("note_0", "Shopping"))
        assertTrue(store.write("event_2026-11-17", "Meeting"))
        for (kind in DecoyKind.entries) {
            for (theme in DecoyTheme.entries) {
                assertTrue(store.select(DecoyPreferences(kind, theme)))
                assertEquals("Shopping", store.read("note_0"))
                assertEquals("Meeting", store.read("event_2026-11-17"))
            }
        }
        assertTrue(store.write("note_0", ""))
        assertEquals("", store.read("note_0"))
        assertEquals("Meeting", store.read("event_2026-11-17"))
    }

    @Test fun offlineConversionsHaveInversePairsAndFiniteFactors() {
        assertEquals(6, conversions.size)
        conversions.forEach { (_, factor) ->
            assertTrue(factor.isFinite())
            assertTrue(factor > 0.0)
        }
        assertEquals(1.0, conversions[0].second * conversions[1].second, 0.000001)
        assertEquals(1.0, conversions[2].second * conversions[3].second, 0.000001)
        assertEquals(1.0, conversions[4].second * conversions[5].second, 0.000001)
        assertEquals("00:00", secondsText(0))
        assertEquals("60:00", secondsText(3600))
    }
}
