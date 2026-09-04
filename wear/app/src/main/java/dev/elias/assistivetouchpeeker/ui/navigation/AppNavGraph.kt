package dev.elias.assistivetouchpeeker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.elias.assistivetouchpeeker.GestureViewModel
import dev.elias.assistivetouchpeeker.ui.EnrollmentScreen
import dev.elias.assistivetouchpeeker.ui.GestureValidationScreen

private const val ROUTE_DETECT = "detect"
private const val ROUTE_ENROLL = "enroll"

/**
 * The app's two screens, wired with [SwipeDismissableNavHost] for the standard Wear OS
 * swipe-to-dismiss back gesture: the main screen (which lists and manages every gesture,
 * built-in and custom) and the enrollment flow.
 *
 * [GestureViewModel] is resolved once here, above the [SwipeDismissableNavHost], and
 * passed explicitly to both screens. Wear Compose Navigation scopes `viewModel()` to the
 * current NavBackStackEntry by default, so each screen calling `viewModel()` itself would
 * get its own separate instance (and its own separately-loaded custom-gesture state) -
 * enrollment would persist to disk correctly, but the main screen's own stale in-memory
 * copy would never see it until the app restarted.
 */
@Composable
fun AppNavGraph() {
    val navController = rememberSwipeDismissableNavController()
    val viewModel: GestureViewModel = viewModel()

    AppScaffold {
        SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_DETECT) {
            composable(ROUTE_DETECT) {
                GestureValidationScreen(onAddGesture = { navController.navigate(ROUTE_ENROLL) }, viewModel = viewModel)
            }
            composable(ROUTE_ENROLL) {
                EnrollmentScreen(onDone = { navController.popBackStack() }, viewModel = viewModel)
            }
        }
    }
}
