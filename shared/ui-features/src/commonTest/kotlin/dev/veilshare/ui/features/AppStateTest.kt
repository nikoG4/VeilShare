package dev.veilshare.ui.features
import kotlin.test.Test
import kotlin.test.assertIs
class AppStateTest {
    @Test fun initialStateIsExplicit() { assertIs<RootState.Initializing>(RootState.Initializing) }
}
