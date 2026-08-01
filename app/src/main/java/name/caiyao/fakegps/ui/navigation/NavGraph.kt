package name.caiyao.fakegps.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import name.caiyao.fakegps.ui.screen.collection.CollectionScreen
import name.caiyao.fakegps.ui.screen.editor.ProfileEditorScreen
import name.caiyao.fakegps.ui.screen.map.MapScreen
import name.caiyao.fakegps.ui.screen.settings.SettingsScreen
import name.caiyao.fakegps.ui.screen.verify.VerifyScreen

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Map) {
        composable<Screen.Map> { entry ->
            MapScreen(
                onAddProfile = { lat, lon ->
                    entry.navigateWhenResumed {
                        navController.navigate(Screen.Editor(lat = lat, lon = lon))
                    }
                },
                onOpenCollection = {
                    entry.navigateWhenResumed { navController.navigate(Screen.Collection) }
                },
                onOpenSettings = {
                    entry.navigateWhenResumed { navController.navigate(Screen.Settings) }
                },
                onOpenVerify = {
                    entry.navigateWhenResumed { navController.navigate(Screen.Verify) }
                },
            )
        }
        composable<Screen.Collection> { entry ->
            CollectionScreen(
                onEditProfile = { id, lat, lon ->
                    entry.navigateWhenResumed {
                        navController.navigate(
                            Screen.Editor(profileId = id, lat = lat, lon = lon),
                        )
                    }
                },
                onBack = {
                    entry.navigateWhenResumed { navController.popBackStack() }
                },
            )
        }
        composable<Screen.Editor> { backStackEntry ->
            val route = backStackEntry.toRoute<Screen.Editor>()
            ProfileEditorScreen(
                profileId = route.profileId,
                lat = route.lat,
                lon = route.lon,
                onBack = {
                    backStackEntry.navigateWhenResumed { navController.popBackStack() }
                },
                onVerify = {
                    backStackEntry.navigateWhenResumed {
                        // Replace the editor in the back stack: after verifying, "back" should
                        // return to the profile list, not to a stale already-saved editor.
                        navController.popBackStack()
                        navController.navigate(Screen.Verify)
                    }
                },
            )
        }
        composable<Screen.Settings> { entry ->
            SettingsScreen(
                onBack = {
                    entry.navigateWhenResumed { navController.popBackStack() }
                },
            )
        }
        composable<Screen.Verify> { entry ->
            VerifyScreen(
                onBack = {
                    entry.navigateWhenResumed { navController.popBackStack() }
                },
            )
        }
    }
}

private fun NavBackStackEntry.navigateWhenResumed(action: () -> Unit): Boolean =
    NavigationActionGuard.runWhenResumed(lifecycle.currentState, action)
