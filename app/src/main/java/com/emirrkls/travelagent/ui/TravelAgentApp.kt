package com.emirrkls.travelagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.emirrkls.travelagent.feature.explore.ExploreScreen
import com.emirrkls.travelagent.feature.onboarding.OnboardingScreen
import com.emirrkls.travelagent.feature.onboarding.SplashScreen
import com.emirrkls.travelagent.feature.place.PlaceDetailScreen
import com.emirrkls.travelagent.feature.profile.ProfileScreen
import com.emirrkls.travelagent.feature.rating.RatingScreen
import com.emirrkls.travelagent.feature.search.SearchScreen
import com.emirrkls.travelagent.feature.secondary.ActivityScreen
import com.emirrkls.travelagent.feature.secondary.CollectionDetailScreen
import com.emirrkls.travelagent.feature.secondary.CollectionsScreen
import com.emirrkls.travelagent.feature.secondary.MapScreen
import com.emirrkls.travelagent.feature.secondary.SuccessScreen
import com.emirrkls.travelagent.feature.splash.AppStartViewModel
import com.emirrkls.travelagent.ui.theme.Coral

private object Route {
    const val Splash = "splash"
    const val Onboarding = "onboarding"
    const val Explore = "explore"
    const val Search = "search"
    const val Map = "map"
    const val Activity = "activity"
    const val Profile = "profile"
    const val Collections = "collections"
    const val Place = "place/{placeId}"
    const val Rating = "rating/{placeId}"
    const val Collection = "collection/{collectionId}"
    const val Success = "success/{placeName}"
}

private data class BottomDestination(val route: String, val label: String, val selected: ImageVector, val unselected: ImageVector)
private val bottomDestinations = listOf(
    BottomDestination(Route.Explore, "Explore", Icons.Filled.Explore, Icons.Outlined.Explore),
    BottomDestination(Route.Map, "Map", Icons.Filled.Map, Icons.Outlined.Map),
    BottomDestination(Route.Activity, "Activity", Icons.Filled.Notifications, Icons.Outlined.Notifications),
    BottomDestination(Route.Profile, "Profile", Icons.Filled.Person, Icons.Outlined.Person),
)

@Composable
fun TravelAgentApp() {
    val navController = rememberNavController()
    val startViewModel: AppStartViewModel = hiltViewModel()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in bottomDestinations.map { it.route }
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = { if (showBottomBar) TravelBottomBar(currentRoute, navController) { showAddSheet = true } },
    ) { scaffoldPadding ->
        Box(Modifier.fillMaxSize().padding(scaffoldPadding)) {
            NavHost(
                navController = navController,
                startDestination = Route.Splash,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable(Route.Splash) {
                    SplashScreen {
                        navController.navigate(if (startViewModel.isOnboardingComplete()) Route.Explore else Route.Onboarding) { popUpTo(Route.Splash) { inclusive = true } }
                    }
                }
                composable(Route.Onboarding) {
                    OnboardingScreen {
                        startViewModel.completeOnboarding()
                        navController.navigate(Route.Explore) { popUpTo(Route.Onboarding) { inclusive = true } }
                    }
                }
                composable(Route.Explore) { ExploreScreen({ navController.navigate(Route.Search) }, { navController.navigate("place/$it") }, { navController.navigate(Route.Collections) }) }
                composable(Route.Search) { SearchScreen({ navController.popBackStack() }, { navController.navigate("place/$it") }) }
                composable(Route.Map) { MapScreen(onPlace = { navController.navigate("place/$it") }) }
                composable(Route.Activity) { ActivityScreen({ navController.navigate("place/$it") }, { navController.navigate("collection/$it") }) }
                composable(Route.Profile) { ProfileScreen({ navController.navigate("place/$it") }, { navController.navigate("collection/$it") }) }
                composable(Route.Collections) { CollectionsScreen({ navController.popBackStack() }, { navController.navigate("collection/$it") }) }
                composable(Route.Place, arguments = listOf(navArgument("placeId") { type = NavType.StringType })) { PlaceDetailScreen({ navController.popBackStack() }, { navController.navigate("rating/${it.arguments?.getString("placeId")}") }) }
                composable(Route.Rating, arguments = listOf(navArgument("placeId") { type = NavType.StringType })) { RatingScreen({ navController.popBackStack() }, { name -> navController.navigate("success/${android.net.Uri.encode(name)}") { popUpTo(Route.Explore) } }) }
                composable(Route.Collection, arguments = listOf(navArgument("collectionId") { type = NavType.StringType })) { entry -> CollectionDetailScreen(checkNotNull(entry.arguments?.getString("collectionId")), { navController.popBackStack() }, { navController.navigate("place/$it") }) }
                composable(Route.Success, arguments = listOf(navArgument("placeName") { type = NavType.StringType })) { entry -> SuccessScreen(entry.arguments?.getString("placeName").orEmpty(), { navController.navigate(Route.Profile) { popUpTo(Route.Explore); launchSingleTop = true } }, { navController.navigate(Route.Explore) { popUpTo(Route.Explore) { inclusive = true } } }) }
            }
        }
    }

    if (showAddSheet) {
        AddActionSheet(
            onDismiss = { showAddSheet = false },
            onRate = { showAddSheet = false; navController.navigate(Route.Search) },
        )
    }
}

@Composable
private fun TravelBottomBar(currentRoute: String?, navController: NavHostController, onAdd: () -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.navigationBarsPadding(), tonalElevation = 8.dp) {
        bottomDestinations.take(2).forEach { destination -> BottomItem(destination, currentRoute, navController) }
        NavigationBarItem(
            selected = false, onClick = onAdd,
            icon = { Box(Modifier.size(50.dp).background(Coral, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, "Add", tint = Color.White, modifier = Modifier.size(28.dp)) } },
            label = { Text("Add") }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
        )
        bottomDestinations.drop(2).forEach { destination -> BottomItem(destination, currentRoute, navController) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomItem(destination: BottomDestination, currentRoute: String?, navController: NavHostController) {
    val selected = currentRoute == destination.route
    NavigationBarItem(
        selected = selected,
        onClick = {
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
        icon = { Icon(if (selected) destination.selected else destination.unselected, destination.label) },
        label = { Text(destination.label) },
        colors = NavigationBarItemDefaults.colors(selectedIconColor = Coral, selectedTextColor = Coral, indicatorColor = MaterialTheme.colorScheme.primaryContainer),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AddActionSheet(onDismiss: () -> Unit, onRate: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Add to your journey", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                Icon(Icons.Rounded.Close, "Close", Modifier.clickable(onClick = onDismiss))
            }
            Text("Capture a plan or a memory.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            AddAction(Icons.Rounded.AddLocationAlt, "Rate a place", "Record a visit and what stood out", onRate)
            AddAction(Icons.Rounded.BookmarkAdd, "Add to Want to Go", "Save a place for later") {}
            AddAction(Icons.Outlined.Explore, "Check in", "Mark where you are now") {}
            AddAction(Icons.AutoMirrored.Rounded.Notes, "Add travel note", "Keep a private memory") {}
            AddAction(Icons.Rounded.FolderCopy, "Create collection", "Curate places around an idea") {}
        }
    }
}

@Composable
private fun AddAction(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Coral) }
        Column(Modifier.padding(start = 14.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
    }
}
