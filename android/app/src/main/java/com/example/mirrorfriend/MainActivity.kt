package com.example.mirrorfriend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.mirrorfriend.ui.theme.MirrorFriendTheme

// ---------------------------------------------------------------------------
// Route constants — the unique string "address" for each screen in the graph
// ---------------------------------------------------------------------------

private const val ROUTE_HOME    = "home"
private const val ROUTE_MIRROR  = "mirror"   // camera / emotion-detection screen
private const val ROUTE_CHAT    = "chat"
private const val ROUTE_PROFILE = "profile"

// Ordered list used by both the bottom bar and the NavHost below.
// Adding a new screen = add one entry here, one NavigationBarItem, one composable.
private data class NavTab(
    val route: String,
    val icon: String,
    val label: String
)

private val NAV_TABS = listOf(
    NavTab(ROUTE_HOME,    "🏠", "Home"),
    NavTab(ROUTE_MIRROR,  "🪞", "Mirror"),
    NavTab(ROUTE_CHAT,    "💬", "Chat"),
    NavTab(ROUTE_PROFILE, "👤", "Profile"),
)

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // enableEdgeToEdge lets the app draw behind the system status/nav bars
        enableEdgeToEdge()
        setContent {
            MirrorFriendTheme {
                MirrorFriendApp()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Root composable
// ---------------------------------------------------------------------------

/**
 * Root composable that owns the [NavController] and the [Scaffold].
 *
 * The [Scaffold] provides:
 *  - A four-tab bottom navigation bar (Home, Mirror, Chat, Profile).
 *  - [innerPadding] so each screen's content sits above the nav bar.
 *
 * The [NavHost] renders the correct screen for whichever route is active.
 * Home is the start destination so the dashboard is the first thing users see.
 */
@Composable
fun MirrorFriendApp() {
    // NavController manages which screen is visible and the back stack
    val navController = rememberNavController()

    // Read the current destination so we can highlight the right tab
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Helper: navigate to a tab without duplicating it on the back stack
    fun navigateTo(route: String) {
        navController.navigate(route) {
            // Always pop back to Home rather than building a deep back stack
            popUpTo(ROUTE_HOME) { inclusive = false }
            launchSingleTop = true   // don't create a duplicate if already there
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                // Build one tab per entry in NAV_TABS — adding a new screen
                // only requires adding a NavTab to the list above.
                NAV_TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick  = { navigateTo(tab.route) },
                        icon     = { Text(tab.icon, style = MaterialTheme.typography.headlineSmall) },
                        label    = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        // innerPadding contains the bottom nav bar height (and status bar height
        // when edge-to-edge is on) — applying it here keeps content visible.
        NavHost(
            navController    = navController,
            startDestination = ROUTE_HOME,   // ← Home is now the landing screen
            modifier         = Modifier.padding(innerPadding)
        ) {
            // Home dashboard — reads live DataStore data, has a "Start" button
            composable(ROUTE_HOME) {
                HomeScreen(
                    // When the user taps "Start Mirror Session", navigate there
                    onNavigateToMirror = { navigateTo(ROUTE_MIRROR) }
                )
            }

            // Mirror (camera) screen — detects emotions and saves them to DataStore
            composable(ROUTE_MIRROR) {
                CameraEmotionScreen()
            }

            // Chat screen — conversational AI companion
            composable(ROUTE_CHAT) {
                // TODO: Replace "neutral" with the real live emotion once a shared
                //       ViewModel is wired between CameraEmotionScreen and ChatScreen.
                ChatScreen(currentEmotion = "neutral")
            }

            // Profile screen — placeholder until the next build step
            composable(ROUTE_PROFILE) {
                ProfileScreen()
            }
        }
    }
}
