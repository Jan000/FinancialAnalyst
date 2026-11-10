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
import net.jandev.financialanalyst.ui.banking.BankScreen
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.importing.ImportScreen
import net.jandev.financialanalyst.ui.loans.LoanDetailScreen
import net.jandev.financialanalyst.ui.loans.LoansScreen
import net.jandev.financialanalyst.ui.loans.OverdraftScreen
import net.jandev.financialanalyst.ui.more.MoreEntry
import net.jandev.financialanalyst.ui.more.MoreScreen
import net.jandev.financialanalyst.ui.overview.OverviewScreen
import net.jandev.financialanalyst.ui.analysis.CategoryTransactionsScreen
import net.jandev.financialanalyst.ui.groups.GroupDetailScreen
import net.jandev.financialanalyst.ui.groups.GroupsScreen
import net.jandev.financialanalyst.ui.planning.BudgetsScreen
import net.jandev.financialanalyst.ui.planning.GoalsScreen
import net.jandev.financialanalyst.ui.recurring.RecurringEditScreen
import net.jandev.financialanalyst.ui.recurring.RecurringScreen
import net.jandev.financialanalyst.ui.settings.BackupScreen
import net.jandev.financialanalyst.ui.settings.NotificationScreen
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
    const val LOANS = "loans"
    const val BUDGETS = "budgets"
    const val GROUPS = "groups"
    const val GOALS = "goals"
    const val BACKUP = "backup"
    const val NOTIFICATIONS = "notifications"
    const val BANKS = "banks"
    const val OVERDRAFT = "overdraft"
    fun loan(id: Long) = "loan/$id"
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
    val pendingBankCallback by app.pendingBankCallback.collectAsStateWithLifecycle()
    LaunchedEffect(pendingBankCallback) {
        if (pendingBankCallback != null && nav.currentDestination?.route != Routes.BANKS) {
            nav.navigate(Routes.BANKS) { launchSingleTop = true }
        }
    }
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
                    onOpenBudgets = { nav.navigate(Routes.BUDGETS) },
                )
            }
            composable(TopLevel.Transactions.route) {
                TransactionsScreen(
                    onEdit = { nav.navigate(Routes.transactionEdit(it)) },
                    onAddAccount = { nav.navigate(Routes.accountEdit(0)) },
                )
            }
            composable(TopLevel.Analysis.route) {
                AnalysisScreen(
                    onOpenCategory = { id, from, to, expense ->
                        nav.navigate("category/$id/${from.toEpochDay()}/${to.toEpochDay()}/$expense")
                    },
                )
            }
            composable(
                "category/{id}/{from}/{to}/{expense}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("from") { type = NavType.LongType },
                    navArgument("to") { type = NavType.LongType },
                    navArgument("expense") { type = NavType.BoolType },
                ),
            ) { entry ->
                val args = entry.arguments
                CategoryTransactionsScreen(
                    categoryId = args?.getLong("id") ?: 0,
                    from = java.time.LocalDate.ofEpochDay(args?.getLong("from") ?: 0),
                    to = java.time.LocalDate.ofEpochDay(args?.getLong("to") ?: 0),
                    expense = args?.getBoolean("expense") ?: true,
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate(Routes.transactionEdit(it)) },
                )
            }
            composable(TopLevel.Recurring.route) {
                RecurringScreen(onEdit = { nav.navigate(Routes.recurringEdit(it)) })
            }
            composable("recurring/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                RecurringEditScreen(
                    itemId = entry.arguments?.getLong("id") ?: 0,
                    onDone = { nav.popBackStack() },
                )
            }
            composable(Routes.BANKS) { BankScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.NOTIFICATIONS) { NotificationScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.BACKUP) { BackupScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.GROUPS) {
                GroupsScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate("group/$it") })
            }
            composable("group/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                GroupDetailScreen(
                    groupId = entry.arguments?.getLong("id") ?: 0,
                    onBack = { nav.popBackStack() },
                    onEditTransaction = { nav.navigate(Routes.transactionEdit(it)) },
                )
            }
            composable(Routes.BUDGETS) { BudgetsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.GOALS) { GoalsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.LOANS) {
                LoansScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.loan(it)) })
            }
            composable("loan/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                LoanDetailScreen(
                    loanId = entry.arguments?.getLong("id") ?: 0,
                    onDone = { nav.popBackStack() },
                )
            }
            composable(Routes.OVERDRAFT) {
                OverdraftScreen(onBack = { nav.popBackStack() }, onEditAccount = { nav.navigate(Routes.accountEdit(it)) })
            }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable(TopLevel.More.route) {
                MoreScreen(
                    entries = listOf(
                        MoreEntry("Konten", "Konten anlegen, Kontostand und Dispo pflegen") { nav.navigate(Routes.ACCOUNTS) },
                        MoreEntry("Bankverbindungen", "Umsätze automatisch direkt von der Bank abrufen") { nav.navigate(Routes.BANKS) },
                        MoreEntry("Zahlungen sofort erfassen", "Benachrichtigungen der Banking-Apps als vorläufige Umsätze mitlesen") { nav.navigate(Routes.NOTIFICATIONS) },
                        MoreEntry("Gruppen", "Umsätze eines Projekts oder Vorhabens zusammenfassen und den Saldo sehen") { nav.navigate(Routes.GROUPS) },
                        MoreEntry("Budgets", "Obergrenzen je Kategorie für den Budgetzeitraum") { nav.navigate(Routes.BUDGETS) },
                        MoreEntry("Sparziele", "Worauf du sparst und wie viel dafür monatlich nötig ist") { nav.navigate(Routes.GOALS) },
                        MoreEntry("Kredite", "Restschuld, Zinsen und Tilgungsplan laufender Kredite") { nav.navigate(Routes.LOANS) },
                        MoreEntry("Dispozinsen", "Was dich das Überziehen deiner Konten kostet") { nav.navigate(Routes.OVERDRAFT) },
                        MoreEntry("Sicherung", "Verschlüsselt in Google Drive oder als Datei sichern und wiederherstellen") { nav.navigate(Routes.BACKUP) },
                        MoreEntry("Einstellungen", "Budgetzeitraum, Reserve und App-Sperre") { nav.navigate(Routes.SETTINGS) },
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
