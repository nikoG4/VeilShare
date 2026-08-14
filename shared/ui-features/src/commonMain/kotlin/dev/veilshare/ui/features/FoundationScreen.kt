package dev.veilshare.ui.features
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.veilshare.ui.design.VeilAdaptiveScaffold
import dev.veilshare.ui.design.VeilCard
@Composable fun FoundationScreen(presenter: AppPresenter) = VeilAdaptiveScaffold(presenter.windowClass) { padding -> Column(Modifier.padding(padding)) { VeilCard { Text("VeilShare"); Text("Foundation ready — vault setup remains intentionally unavailable.") } } }
