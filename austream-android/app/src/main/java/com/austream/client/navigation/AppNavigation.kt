package com.austream.client.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.austream.client.ui.screens.DiscoveryScreen
import com.austream.client.ui.screens.PlaybackScreen
import java.net.URLDecoder
import java.net.URLEncoder

sealed class Screen(val route: String) {
    object Discovery : Screen("discovery")
    object Playback : Screen("playback/{serverAddress}/{serverName}?pin={pin}") {
        fun createRoute(address: String, name: String, pin: String = ""): String {
            val encodedAddress = URLEncoder.encode(address, "UTF-8")
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val encodedPin = URLEncoder.encode(pin, "UTF-8")
            return "playback/$encodedAddress/$encodedName?pin=$encodedPin"
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = Screen.Discovery.route
    ) {
        composable(Screen.Discovery.route) {
            DiscoveryScreen(
                onServerSelected = { address, name, pin ->
                    navController.navigate(Screen.Playback.createRoute(address, name, pin))
                }
            )
        }
        
        composable(
            route = Screen.Playback.route,
            arguments = listOf(
                navArgument("serverAddress") { type = NavType.StringType },
                navArgument("serverName") { type = NavType.StringType },
                navArgument("pin") { 
                    type = NavType.StringType 
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val serverAddress = URLDecoder.decode(
                backStackEntry.arguments?.getString("serverAddress") ?: "",
                "UTF-8"
            )
            val serverName = URLDecoder.decode(
                backStackEntry.arguments?.getString("serverName") ?: "",
                "UTF-8"
            )
            val pin = URLDecoder.decode(
                backStackEntry.arguments?.getString("pin") ?: "",
                "UTF-8"
            )
            
            PlaybackScreen(
                serverAddress = serverAddress,
                serverName = serverName,
                prefilledPin = pin,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
