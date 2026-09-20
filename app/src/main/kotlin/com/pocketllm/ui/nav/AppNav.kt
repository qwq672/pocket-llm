package com.pocketllm.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
    data object Settings : Dest("settings", R.string.nav_settings, Icons.Outlined.Settings)
}

/** 侧边栏的菜单项 */
private val menuItems = listOf(Dest.Chat, Dest.Models, Dest.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "PocketLLM",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "本地大模型推理",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                menuItems.forEach { d ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(d.label)) },
                        selected = currentRoute == d.route,
                        icon = { Icon(d.icon, contentDescription = null) },
                        colors = NavigationDrawerItemDefaults.colors(),
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (currentRoute != d.route) {
                                nav.navigate(d.route) {
                                    popUpTo(Dest.Chat.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    ) {
        androidx.compose.material3.Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val current = menuItems.firstOrNull { it.route == currentRoute }
                        Text(stringResource(current?.label ?: R.string.app_name))
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, contentDescription = "菜单")
                        }
                    }
                )
            }
        ) { inner ->
            NavHost(
                navController = nav,
                startDestination = Dest.Chat.route,
                modifier = Modifier.padding(inner),
                enterTransition = {
                    fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 12 }
                },
                exitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -it / 12 }
                },
                popEnterTransition = {
                    fadeIn(tween(200)) + slideInHorizontally(tween(200)) { -it / 12 }
                },
                popExitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it / 12 }
                }
            ) {
                composable(Dest.Chat.route)     { ChatScreen() }
                composable(Dest.Models.route)   { ModelsScreen() }
                composable(Dest.Settings.route) { SettingsScreen() }
            }
        }
    }
}
