package dev.veilshare.ui.features
import dev.veilshare.core.platform.FakePlatformCapabilities
import dev.veilshare.ui.design.VeilWindowClass
import kotlin.test.Test
import kotlin.test.assertIs
class AppStateTest {
    @Test fun foundationStartsInOnboarding() {
        assertIs<RootState.NeedsOnboarding>(AppPresenter(FakePlatformCapabilities, VeilWindowClass.Compact).state)
    }
}
