package net.jandev.financialanalyst.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.jandev.financialanalyst.ui.accounts.AccountEditScreen
import net.jandev.financialanalyst.ui.accounts.AccountsScreen
import net.jandev.financialanalyst.ui.analysis.AnalysisScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.importing.ImportScreen
import net.jandev.financialanalyst.ui.more.MoreEntry
import net.jandev.financialanalyst.ui.more.MoreScreen
import net.jandev.financialanalyst.ui.overview.OverviewScreen
import net.jandev.financialanalyst.ui.recurring.RecurringEditScreen
import net.jandev.financialanalyst.ui.recurring.RecurringScreen
import net.jandev.financialanalyst.ui.settings.SettingsScreen
import net.jandev.financialanalyst.ui.transactions.TransactionEditScreen
import net.jandev.financialanalyst.ui.transactions.TransactionsScreen

/** Die fünf Hauptbereiche der unteren Navigationsleiste. */
enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    Overview("overview", "Übersicht", Icons.Filled.Home),
    Transactions("transactions", "Umsätze", Icons.AutoMirrored.Filled.List),
    Analysis("analysis", "Analyse", Icons.Filled.DateRange),
    Recurring("recurring", "Fixkosten", Icons.Filled.Refresh),
    More("more", "Mehr", Icons.Filled.Menu),
}

object Routes {
    const val ACCOUNTS = "accounts"
    const val IMPORT = "import"
    const val SETTINGS = "settings"
    fun recurringEdit(id: Long) = "recurring/$id"
    fun accountEdit(id: Long) = "account/$id"
    fun transactionEdit(id: Long) = "transaction/$id"
}

private fun NavHostController.openTopLevel(item: TopLevel) {
    navigate(item.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // Eine an die App übergebene Datei öffnet den Import, egal wo man gerade ist.
    val app = LocalApp.current
    val pendingImport by app.pendingImport.collectAsStateWithLifecycle()
    LaunchedEffect(pendingImport) {
        if (pendingImport != null && nav.currentDestination?.route != Routes.IMPORT) {
            nav.navigate(Routes.IMPORT) { launchSingleTop = true }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                TopLevel.entries.forEach { item ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                        onClick = { nav.openTopLevel(item) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = TopLevel.Overview.route,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable(TopLevel.Overview.route) {
                OverviewScreen(
                    onOpenAccounts = { nav.navigate(Routes.ACCOUNTS) },
                    onAddAccount = { nav.navigate(Routes.accountEdit(0)) },
                    onOpenTransactions = { nav.openTopLevel(TopLevel.Transactions) },
                    onEditTransaction = { nav.navigate(Routes.transactionEdit(it)) },
                    onOpenRecurring = { nav.openTopLevel(TopLevel.Recurring) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(TopLevel.Transactions.route) {
                TransactionsScreen(
                    onEdit = { nav.navigate(Routes.transactionEdit(it)) },
                    onAddAccount = { nav.navigate(Routes.accountEdit(0)) },
                )
            }
            composable(TopLevel.Analysis.route) { AnalysisScreen() }
            composable(TopLevel.Recurring.route) {
                RecurringScreen(onEdit = { nav.navigate(Routes.recurringEdit(it)) })
            }
            composable("recurring/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                RecurringEditScreen(
                    itemId = entry.arguments?.getLong("id") ?: 0,
                    onDone = { nav.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable(TopLevel.More.route) {
                MoreScreen(
                    entries = listOf(
                        MoreEntry("Konten", "Konten anlegen, Kontostand und Dispo pflegen") { nav.navigate(Routes.ACCOUNTS) },
                        MoreEntry("Einstellungen", "Budgetzeitraum und Reserve festlegen") { nav.navigate(Routes.SETTINGS) },
                        MoreEntry("Umsätze importieren", "CSV-Datei von DKB, N26, Amex, PayPal und anderen einlesen") { nav.navigate(Routes.IMPORT) },
                    ),
                )
            }
            composable(Routes.IMPORT) {
                ImportScreen(
                    onBack = { nav.popBackStack() },
                    onAddAccount = { nav.navigate(Routes.accountEdit(0)) },
                    onFinished = {
                        nav.popBackStack()
                        nav.openTopLevel(TopLevel.Transactions)
                    },
                )
            }
            composable(Routes.ACCOUNTS) {
                AccountsScreen(onBack = { nav.popBackStack() }, onEdit = { nav.navigate(Routes.accountEdit(it)) })
            }
            composable("account/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                AccountEditScreen(
                    accountId = entry.arguments?.getLong("id") ?: 0,
                    onDone = { nav.popBackStack() },
                )
            }
            composable("transaction/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                TransactionEditScreen(
                    transactionId = entry.arguments?.getLong("id") ?: 0,
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }
}
