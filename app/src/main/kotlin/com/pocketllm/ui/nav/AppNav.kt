package com.pocketllm.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocketllm.R
import com.pocketllm.ui.chat.ChatScreen
import com.pocketllm.ui.models.ModelsScreen
import com.pocketllm.ui.settings.SettingsScreen

sealed class Dest(val route: String, val label: Int, val icon: ImageVector) {
    data object Chat     : Dest("chat",     R.string.nav_chat,     Icons.Outlined.ChatBubbleOutline)
    data object Models   : Dest("models",   R.string.nav_models,   Icons.Outlined.Memory)
    // Download 已合并到 Models 页，Dest 保留以备将来重新启用
//  data object Download : Dest("download", R.string.nav_download, Icons.Outlined.CloudDownload)
    data object Settings : Dest("settings", R.string.nav_settings, Icons.Outlined.Settings)
}

private val bottomNav = listOf(Dest.Chat, Dest.Models, Dest.Settings)

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val routes = bottomNav.map { it.route }

    Scaffold(
        bottomBar = {
            if (currentRoute in routes) {
                NavigationBar {
                    bottomNav.forEach { d ->
                        NavigationBarItem(
                            selected = currentRoute == d.route,
                            onClick = {
                                nav.navigate(d.route) {
                                    popUpTo(Dest.Chat.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(d.icon, contentDescription = null) },
                            label = { Text(stringResource(d.label)) }
                        )
                    }
                }
            }
        }
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Dest.Chat.route,
            modifier = Modifier.padding(inner),
            enterTransition = {
                fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 12 }
            },
            exitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -it / 12 } },
            popEnterTransition = {
                fadeIn(tween(200)) + slideInHorizontally(tween(200)) { -it / 12 }
            },
            popExitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it / 12 } }
        ) {
            composable(Dest.Chat.route)     { ChatScreen() }
            composable(Dest.Models.route)   { ModelsScreen() }
            composable(Dest.Settings.route) { SettingsScreen() }
        }
    }
}
