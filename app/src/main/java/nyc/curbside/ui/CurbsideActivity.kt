package nyc.curbside.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import nyc.curbside.asp.AspDatasetInstaller
import nyc.curbside.detect.CarConnectionMonitor
import nyc.curbside.detect.DriveCoordinator
import nyc.curbside.ui.home.HomeScreen
import nyc.curbside.ui.map.MapScreen
import nyc.curbside.ui.onboarding.PermissionsScreen
import nyc.curbside.ui.onboarding.PermissionsViewModel
import nyc.curbside.ui.settings.SettingsScreen

@AndroidEntryPoint
class CurbsideActivity : ComponentActivity() {

    @Inject lateinit var carConnection: CarConnectionMonitor

    @Inject lateinit var coordinator: DriveCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // While the app is in the foreground we get the sharpest possible drive signal for free.
        // It stops with the activity; the always-on detection does not depend on it.
        carConnection.start(lifecycleScope)

        // Flush anything the system dropped while the app was not running.
        lifecycleScope.launch { coordinator.reconcile() }
        AspDatasetInstaller.schedule(this)

        setContent { CurbsideTheme { CurbsideApp() } }
    }

    override fun onDestroy() {
        lifecycleScope.launch { carConnection.stop() }
        super.onDestroy()
    }
}

private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "My car", Icons.Filled.DirectionsCar),
    MAP("map", "Rules map", Icons.Filled.Map),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

@Composable
private fun CurbsideApp() {
    val permissions: PermissionsViewModel = hiltViewModel()
    val explained by permissions.explained.collectAsStateWithLifecycle()
    var reviewing by remember { mutableStateOf(false) }

    // The explanation comes before the app rather than inside it: the four permissions it covers
    // are the difference between Curbside working and Curbside doing nothing at all, and all four
    // fail silently, at a moment when nobody is looking at the screen.
    when {
        explained == null -> Unit // Still reading the stored flag; one frame at most.

        explained == false || reviewing ->
            PermissionsScreen(viewModel = permissions, onDone = { reviewing = false })

        else -> CurbsideNav(onReviewPermissions = { reviewing = true })
    }
}

@Composable
private fun CurbsideNav(onReviewPermissions: () -> Unit) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.HOME.route) { HomeScreen(onOpenMap = { navController.navigate(Destination.MAP.route) }) }
            composable(Destination.MAP.route) { MapScreen() }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(onReviewPermissions = onReviewPermissions)
            }
        }
    }
}

@Composable
fun CurbsideTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(primary = Color(0xFF7FB8EE), secondary = Color(0xFF4FD18B))
        } else {
            lightColorScheme(primary = Color(0xFF1F5C93), secondary = Color(0xFF2E8B57))
        },
        content = content,
    )
}
