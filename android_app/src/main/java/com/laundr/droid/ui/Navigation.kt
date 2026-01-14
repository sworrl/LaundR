package com.laundr.droid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.laundr.droid.ble.BleScanner
import com.laundr.droid.ble.CSCExploit
import com.laundr.droid.ble.GattManager
import com.laundr.droid.ble.LaundryRoomManager
import com.laundr.droid.nfc.CardRepository
import com.laundr.droid.nfc.NfcManager
import com.laundr.droid.ui.screens.*

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Scan : Screen("scan")
    object Nfc : Screen("nfc")
    object QRScan : Screen("qr_scan")
    object MasterCard : Screen("master_card")
    object SavedCards : Screen("saved_cards")
    object LaundryRooms : Screen("laundry_rooms")
    object Device : Screen("device/{address}") {
        fun createRoute(address: String) = "device/$address"
    }
    object CSCExploit : Screen("csc_exploit/{address}") {
        fun createRoute(address: String) = "csc_exploit/$address"
    }
    object Log : Screen("log")
    object About : Screen("about")
}

@Composable
fun LaunDRoidNavHost(nfcManager: NfcManager) {
    val navController = rememberNavController()
    val context = LocalContext.current

    val bleScanner = remember { BleScanner(context) }
    val gattManager = remember { GattManager(context) }
    val cscExploit = remember { CSCExploit(context) }
    val cardRepository = remember { CardRepository(context) }
    // Use singleton LaundryRoomManager from Application
    val laundryRoomManager = (context.applicationContext as com.laundr.droid.LaunDRoidApp).laundryRoomManager

    NavHost(navController = navController, startDestination = Screen.Home.route) {

        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToScan = { navController.navigate(Screen.Scan.route) },
                onNavigateToNfc = { navController.navigate(Screen.Nfc.route) },
                onNavigateToQRScan = { navController.navigate(Screen.QRScan.route) },
                onNavigateToMasterCard = { navController.navigate(Screen.MasterCard.route) },
                onNavigateToSavedCards = { navController.navigate(Screen.SavedCards.route) },
                onNavigateToLaundryRooms = { navController.navigate(Screen.LaundryRooms.route) },
                onNavigateToLog = { navController.navigate(Screen.Log.route) },
                onNavigateToAbout = { navController.navigate(Screen.About.route) }
            )
        }

        composable(Screen.Nfc.route) {
            NfcScreen(
                nfcManager = nfcManager,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.QRScan.route) {
            QRScanScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.MasterCard.route) {
            MasterCardScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Scan.route) {
            ScanScreen(
                bleScanner = bleScanner,
                onDeviceClick = { device ->
                    navController.navigate(Screen.Device.createRoute(device.address))
                },
                onCSCExploit = { device ->
                    navController.navigate(Screen.CSCExploit.createRoute(device.address))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Device.route,
            arguments = listOf(navArgument("address") { type = NavType.StringType })
        ) { backStackEntry ->
            val address = backStackEntry.arguments?.getString("address") ?: return@composable
            val device = bleScanner.devices.value[address]

            DeviceScreen(
                deviceInfo = device,
                gattManager = gattManager,
                onBack = { navController.popBackStack() },
                onCSCExploit = {
                    navController.navigate(Screen.CSCExploit.createRoute(address))
                }
            )
        }

        composable(
            route = Screen.CSCExploit.route,
            arguments = listOf(navArgument("address") { type = NavType.StringType })
        ) { backStackEntry ->
            val address = backStackEntry.arguments?.getString("address") ?: return@composable
            val device = bleScanner.devices.value[address]

            CSCExploitScreen(
                deviceInfo = device,
                cscExploit = cscExploit,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Log.route) {
            LogScreen(
                bleScanner = bleScanner,
                gattManager = gattManager,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.SavedCards.route) {
            SavedCardsScreen(
                cardRepository = cardRepository,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.LaundryRooms.route) {
            LaundryRoomScreen(
                laundryRoomManager = laundryRoomManager,
                bleScanner = bleScanner,
                onBack = { navController.popBackStack() },
                onNavigateToDevice = { address ->
                    navController.navigate(Screen.Device.createRoute(address))
                }
            )
        }
    }
}
