package name.caiyao.fakegps.ui.navigation

import androidx.compose.runtime.Composable
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
        composable<Screen.Map> {
            MapScreen(
                onAddProfile = { lat, lon ->
                    navController.navigate(Screen.Editor(lat = lat, lon = lon))
                },
                onOpenCollection = {
                    navController.navigate(Screen.Collection)
                },
                onOpenSettings = {
                    navController.navigate(Screen.Settings)
                },
                onOpenVerify = {
                    navController.navigate(Screen.Verify)
                },
            )
        }
        composable<Screen.Collection> {
            CollectionScreen(
                onEditProfile = { id, lat, lon ->
                    navController.navigate(Screen.Editor(profileId = id, lat = lat, lon = lon))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Screen.Editor> { backStackEntry ->
            val route = backStackEntry.toRoute<Screen.Editor>()
            ProfileEditorScreen(
                profileId = route.profileId,
                lat = route.lat,
                lon = route.lon,
                onBack = { navController.popBackStack() },
            )
        }
        composable<Screen.Settings> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<Screen.Verify> {
            VerifyScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
