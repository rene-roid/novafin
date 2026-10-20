package xyz.yuuki.novafin

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.delay
import xyz.yuuki.novafin.ui.AddressesScreen
import xyz.yuuki.novafin.ui.Bg
import xyz.yuuki.novafin.ui.Cosmos
import xyz.yuuki.novafin.ui.DetailsScreen
import xyz.yuuki.novafin.ui.DownloadsScreen
import xyz.yuuki.novafin.ui.Dusk
import xyz.yuuki.novafin.ui.HomeScreen
import xyz.yuuki.novafin.ui.LibraryScreen
import xyz.yuuki.novafin.ui.LocalNav
import xyz.yuuki.novafin.ui.LoginScreen
import xyz.yuuki.novafin.ui.Glow
import xyz.yuuki.novafin.ui.Nav
import xyz.yuuki.novafin.ui.NovafinTheme
import xyz.yuuki.novafin.ui.Raised
import xyz.yuuki.novafin.ui.Raised2
import xyz.yuuki.novafin.ui.Route
import xyz.yuuki.novafin.ui.SearchScreen
import xyz.yuuki.novafin.ui.SettingsScreen
import xyz.yuuki.novafin.ui.Sub
import xyz.yuuki.novafin.ui.isTablet

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            NovafinTheme {
                val loggedIn by App.it.loggedIn.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize()) {
                    Cosmos(Modifier.fillMaxSize())
                    if (loggedIn) Shell() else LoginScreen()
                }
            }
        }
    }
}

private data class Tab(val route: Route, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Route.Home, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(Route.Library, "Library", Icons.Outlined.VideoLibrary, Icons.Rounded.VideoLibrary),
    Tab(Route.Search, "Search", Icons.Rounded.Search, Icons.Rounded.Search),
    Tab(Route.Downloads, "Downloads", Icons.Outlined.DownloadForOffline, Icons.Rounded.DownloadForOffline),
)

@Composable
private fun Shell() {
    val nav = remember { Nav() }
    val tablet = isTablet()
    val online by App.it.api.online.collectAsStateWithLifecycle()
    val root = nav.stack.first()

    // Opened without a connection: go straight to what can be watched.
    LaunchedEffect(Unit) {
        delay(3_500)
        if (!App.it.api.online.value && nav.stack.toList() == listOf(Route.Home)) nav.tab(Route.Downloads)
    }

    CompositionLocalProvider(LocalNav provides nav) {
        Row(Modifier.fillMaxSize()) {
            if (tablet) NavigationRail(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
                containerColor = Color.Transparent,
            ) {
                Spacer(Modifier.padding(top = 8.dp))
                for (t in tabs) NavigationRailItem(
                    root == t.route, { nav.tab(t.route) }, { Icon(if (root == t.route) t.selectedIcon else t.icon, t.label) }, label = { Text(t.label) },
                    colors = NavigationRailItemDefaults.colors(indicatorColor = Glow.copy(alpha = .14f), selectedIconColor = Glow, selectedTextColor = Glow, unselectedIconColor = Sub, unselectedTextColor = Sub),
                )
                Spacer(Modifier.weight(1f))
                NavigationRailItem(
                    root == Route.Settings, { nav.tab(Route.Settings) },
                    { Icon(if (root == Route.Settings) Icons.Rounded.Settings else Icons.Outlined.Settings, "Settings") }, label = { Text("Settings") },
                    colors = NavigationRailItemDefaults.colors(indicatorColor = Glow.copy(alpha = .14f), selectedIconColor = Glow, selectedTextColor = Glow, unselectedIconColor = Sub, unselectedTextColor = Sub),
                )
            }
            Column(Modifier.weight(1f)) {
                AnimatedVisibility(!online) {
                    Text(
                        "Offline · showing downloads", Modifier.fillMaxWidth().background(Dusk.copy(alpha = .9f)).statusBarsPadding().padding(4.dp),
                        color = Bg, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    )
                }
                // The offline banner already covers the status bar.
                Box(Modifier.weight(1f).then(if (online) Modifier else Modifier.consumeWindowInsets(WindowInsets.statusBars))) {
                    NavDisplay(
                        backStack = nav.stack,
                        onBack = { nav.back() },
                        transitionSpec = { (slideInHorizontally(tween(260)) { it / 5 } + fadeIn(tween(260))) togetherWith fadeOut(tween(160)) },
                        popTransitionSpec = { fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(220))) },
                        predictivePopTransitionSpec = { fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(220))) },
                        entryProvider = { r ->
                            NavEntry(r) {
                                when (r) {
                                    Route.Home -> HomeScreen()
                                    Route.Library -> LibraryScreen()
                                    Route.Search -> SearchScreen()
                                    Route.Downloads -> DownloadsScreen()
                                    Route.Settings -> SettingsScreen()
                                    Route.Addresses -> AddressesScreen()
                                    is Route.Details -> DetailsScreen(r.id)
                                }
                            }
                        },
                    )
                }
                if (!tablet) NavigationBar(containerColor = Bg.copy(alpha = .78f)) {
                    for (t in tabs) NavigationBarItem(
                        root == t.route, { nav.tab(t.route) }, { Icon(if (root == t.route) t.selectedIcon else t.icon, t.label) }, label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Glow.copy(alpha = .14f), selectedIconColor = Glow, selectedTextColor = Glow, unselectedIconColor = Sub, unselectedTextColor = Sub),
                    )
                }
            }
        }
    }
}
