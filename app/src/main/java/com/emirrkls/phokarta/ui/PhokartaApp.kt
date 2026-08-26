package com.emirrkls.phokarta.ui

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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.emirrkls.phokarta.core.auth.AuthState
import com.emirrkls.phokarta.feature.auth.LoginScreen
import com.emirrkls.phokarta.feature.auth.RegisterScreen
import com.emirrkls.phokarta.feature.explore.ExploreScreen
import com.emirrkls.phokarta.feature.map.MapScreen
import com.emirrkls.phokarta.feature.onboarding.OnboardingScreen
import com.emirrkls.phokarta.feature.onboarding.SplashScreen
import com.emirrkls.phokarta.feature.place.PlaceDetailScreen
import com.emirrkls.phokarta.feature.place.PlaceReviewsScreen
import com.emirrkls.phokarta.feature.profile.ProfileScreen
import com.emirrkls.phokarta.feature.rating.RatingScreen
import com.emirrkls.phokarta.feature.saved.WantToGoScreen
import com.emirrkls.phokarta.feature.search.SearchScreen
import com.emirrkls.phokarta.feature.secondary.ActivityScreen
import com.emirrkls.phokarta.feature.secondary.CollectionDetailScreen
import com.emirrkls.phokarta.feature.secondary.CollectionsScreen
import com.emirrkls.phokarta.feature.secondary.SuccessScreen
import com.emirrkls.phokarta.feature.social.PublicProfileScreen
import com.emirrkls.phokarta.feature.social.SocialListScreen
import com.emirrkls.phokarta.feature.social.UserSearchScreen
import com.emirrkls.phokarta.feature.splash.AppStartViewModel
import com.emirrkls.phokarta.ui.theme.Coral
import androidx.compose.ui.res.stringResource
import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.feature.settings.BlockedUsersScreen
import com.emirrkls.phokarta.feature.settings.SettingsScreen
import kotlinx.coroutines.launch

private object Route {
    const val Splash = "splash"
    const val Onboarding = "onboarding"
    const val Login = "login"
    const val Register = "register"
    const val Explore = "explore"
    const val Search = "search"
    const val Map = "map"
    const val Activity = "activity"
    const val Profile = "profile"
    const val Collections = "collections"
    const val WantToGo = "want-to-go"
    const val UserSearch = "user-search"
    const val PublicProfile = "user/{userId}"
    const val SocialList = "social/{kind}"
    const val Place = "place/{placeId}"
    const val PlaceReviews = "place/{placeId}/reviews?scope={scope}"
    const val Rating = "rating/{placeId}"
    const val Collection = "collection/{collectionId}"
    const val Success = "success/{placeName}"
    const val Settings = "settings"
    const val BlockedUsers = "settings/blocked-users"
}

private data class BottomDestination(val route: String, val labelRes: Int, val selected: ImageVector, val unselected: ImageVector)
private val bottomDestinations = listOf(
    BottomDestination(Route.Explore, R.string.nav_explore, Icons.Filled.Explore, Icons.Outlined.Explore),
    BottomDestination(Route.Map, R.string.nav_map, Icons.Filled.Map, Icons.Outlined.Map),
    BottomDestination(Route.Activity, R.string.nav_activity, Icons.Filled.Notifications, Icons.Outlined.Notifications),
    BottomDestination(Route.Profile, R.string.nav_profile, Icons.Filled.Person, Icons.Outlined.Person),
)

@Composable
fun PhokartaApp() {
    val navController = rememberNavController()
    val startViewModel: AppStartViewModel = hiltViewModel()
    val authState by startViewModel.authState.collectAsStateWithLifecycle()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in bottomDestinations.map { it.route }
    var showAddSheet by remember { mutableStateOf(false) }
    var splashFinished by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val userBlockedMessage = stringResource(R.string.user_blocked)
    val reportThanksMessage = stringResource(R.string.report_thanks)

    LaunchedEffect(authState, splashFinished) {
        if (!splashFinished) return@LaunchedEffect
        when (authState) {
            AuthState.Loading -> Unit
            AuthState.LoggedOut -> {
                val onAuth = currentRoute == Route.Login || currentRoute == Route.Register ||
                    currentRoute == Route.Onboarding || currentRoute == Route.Splash
                if (!onAuth) {
                    navController.navigate(Route.Login) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                }
            }
            is AuthState.Authenticated -> {
                if (currentRoute == Route.Login || currentRoute == Route.Register ||
                    currentRoute == Route.Splash || currentRoute == Route.Onboarding
                ) {
                    navController.navigate(Route.Explore) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        splashFinished = true
                        when {
                            !startViewModel.isOnboardingComplete() ->
                                navController.navigate(Route.Onboarding) {
                                    popUpTo(Route.Splash) { inclusive = true }
                                }
                            authState is AuthState.Authenticated ->
                                navController.navigate(Route.Explore) {
                                    popUpTo(Route.Splash) { inclusive = true }
                                }
                            authState is AuthState.LoggedOut ->
                                navController.navigate(Route.Login) {
                                    popUpTo(Route.Splash) { inclusive = true }
                                }
                            else -> {
                                // Still restoring session — stay briefly; LaunchedEffect will route.
                            }
                        }
                    }
                }
                composable(Route.Onboarding) {
                    OnboardingScreen {
                        startViewModel.completeOnboarding()
                        navController.navigate(Route.Login) {
                            popUpTo(Route.Onboarding) { inclusive = true }
                        }
                    }
                }
                composable(Route.Login) {
                    LoginScreen(onCreateAccount = { navController.navigate(Route.Register) })
                }
                composable(Route.Register) {
                    RegisterScreen(onHaveAccount = { navController.popBackStack() })
                }
                composable(Route.Explore) {
                    ExploreScreen(
                        onSearch = { navController.navigate(Route.Search) },
                        onPlace = { navController.navigate("place/$it") },
                        onCollections = { navController.navigate(Route.Collections) },
                        onWantToGo = { navController.navigate(Route.WantToGo) },
                    )
                }
                composable(Route.Search) { SearchScreen({ navController.popBackStack() }, { navController.navigate("place/$it") }) }
                composable(Route.Map) { MapScreen(onPlace = { navController.navigate("place/$it") }) }
                composable(Route.Activity) {
                    ActivityScreen(
                        onPlace = { navController.navigate("place/$it") },
                        onAuthor = { userId -> navController.navigateToUser(userId, authState) },
                    )
                }
                composable(Route.Profile) {
                    ProfileScreen(
                        onPlace = { navController.navigate("place/$it") },
                        onEditVisit = { placeId ->
                            navController.navigate("rating/$placeId") {
                                launchSingleTop = true
                            }
                        },
                        onCollection = { navController.navigate("collection/$it") },
                        onWantToGo = { navController.navigate(Route.WantToGo) },
                        onUserSearch = { navController.navigate(Route.UserSearch) },
                        onFollowers = { navController.navigate("social/followers") },
                        onFollowing = { navController.navigate("social/following") },
                        onFriends = { navController.navigate("social/friends") },
                        onSettings = { navController.navigate(Route.Settings) },
                    )
                }
                composable(Route.Settings) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onSignOut = startViewModel::logout,
                        onBlockedUsers = { navController.navigate(Route.BlockedUsers) },
                    )
                }
                composable(Route.BlockedUsers) {
                    BlockedUsersScreen(onBack = { navController.popBackStack() })
                }
                composable(Route.UserSearch) {
                    UserSearchScreen(
                        onBack = { navController.popBackStack() },
                        onUser = { userId -> navController.navigateToUser(userId, authState) },
                    )
                }
                composable(
                    Route.PublicProfile,
                    arguments = listOf(navArgument("userId") { type = NavType.StringType }),
                ) {
                    PublicProfileScreen(
                        onBack = { navController.popBackStack() },
                        onSocialList = { kind -> navController.navigate("social/${kind.routeValue}") },
                        onUserBlocked = {
                            navController.popBackStack()
                            snackbarScope.launch { snackbarHostState.showSnackbar(userBlockedMessage) }
                        },
                    )
                }
                composable(
                    Route.SocialList,
                    arguments = listOf(navArgument("kind") { type = NavType.StringType }),
                ) {
                    SocialListScreen(
                        onBack = { navController.popBackStack() },
                        onUser = { userId -> navController.navigateToUser(userId, authState) },
                    )
                }
                composable(Route.WantToGo) {
                    WantToGoScreen(
                        onBack = { navController.popBackStack() },
                        onPlace = { navController.navigate("place/$it") },
                    )
                }
                composable(Route.Collections) { CollectionsScreen({ navController.popBackStack() }, { navController.navigate("collection/$it") }) }
                composable(Route.Place, arguments = listOf(navArgument("placeId") { type = NavType.StringType })) {
                    val backStackEntry = it
                    val placeId = backStackEntry.arguments?.getString("placeId").orEmpty()
                    PlaceDetailScreen(
                        onBack = { navController.popBackStack() },
                        onRate = { navController.navigate("rating/$placeId") },
                        onSeeAllReviews = { scope ->
                            navController.navigate("place/$placeId/reviews?scope=${scope.queryParam}")
                        },
                        onAuthor = { userId -> navController.navigateToUser(userId, authState) },
                        visitPublished = backStackEntry.savedStateHandle.get<Boolean>("visitPublished") == true,
                        onVisitPublishedConsumed = { backStackEntry.savedStateHandle["visitPublished"] = false },
                    )
                }
                composable(
                    Route.PlaceReviews,
                    arguments = listOf(
                        navArgument("placeId") { type = NavType.StringType },
                        navArgument("scope") {
                            type = NavType.StringType
                            defaultValue = "community"
                        },
                    ),
                ) {
                    PlaceReviewsScreen(
                        onBack = { navController.popBackStack() },
                        onAuthor = { userId -> navController.navigateToUser(userId, authState) },
                    )
                }
                composable(Route.Rating, arguments = listOf(navArgument("placeId") { type = NavType.StringType })) {
                    RatingScreen(
                        onBack = { navController.popBackStack() },
                        onPublished = {
                            navController.previousBackStackEntry
                                ?.savedStateHandle
                                ?.set("visitPublished", true)
                            navController.popBackStack()
                        },
                    )
                }
                composable(Route.Collection, arguments = listOf(navArgument("collectionId") { type = NavType.StringType })) { entry -> CollectionDetailScreen(checkNotNull(entry.arguments?.getString("collectionId")), { navController.popBackStack() }, { navController.navigate("place/$it") }) }
                composable(Route.Success, arguments = listOf(navArgument("placeName") { type = NavType.StringType })) { entry -> SuccessScreen(entry.arguments?.getString("placeName").orEmpty(), { navController.navigate(Route.Profile) { popUpTo(Route.Explore); launchSingleTop = true } }, { navController.navigate(Route.Explore) { popUpTo(Route.Explore) { inclusive = true } } }) }
            }
        }
    }

    if (showAddSheet) {
        AddActionSheet(
            onDismiss = { showAddSheet = false },
            onRate = { showAddSheet = false; navController.navigate(Route.Search) },
            onWantToGo = { showAddSheet = false; navController.navigate(Route.WantToGo) },
            onCreateCollection = { showAddSheet = false; navController.navigate(Route.Collections) },
        )
    }
}

@Composable
private fun TravelBottomBar(currentRoute: String?, navController: NavHostController, onAdd: () -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.navigationBarsPadding(), tonalElevation = 8.dp) {
        bottomDestinations.take(2).forEach { destination -> BottomItem(destination, currentRoute, navController) }
        NavigationBarItem(
            selected = false, onClick = onAdd,
            icon = { Box(Modifier.size(50.dp).background(Coral, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, stringResource(R.string.nav_add), tint = Color.White, modifier = Modifier.size(28.dp)) } },
            label = { Text(stringResource(R.string.nav_add)) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
        )
        bottomDestinations.drop(2).forEach { destination -> BottomItem(destination, currentRoute, navController) }
    }
}

private fun NavHostController.navigateToUser(userId: String, authState: AuthState) {
    val currentId = (authState as? AuthState.Authenticated)?.user?.id
    if (currentId != null && currentId.equals(userId, ignoreCase = true)) {
        navigate(Route.Profile) { launchSingleTop = true }
    } else {
        navigate("user/$userId")
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
        icon = { Icon(if (selected) destination.selected else destination.unselected, stringResource(destination.labelRes)) },
        label = { Text(stringResource(destination.labelRes)) },
        colors = NavigationBarItemDefaults.colors(selectedIconColor = Coral, selectedTextColor = Coral, indicatorColor = MaterialTheme.colorScheme.primaryContainer),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AddActionSheet(
    onDismiss: () -> Unit,
    onRate: () -> Unit,
    onWantToGo: () -> Unit,
    onCreateCollection: () -> Unit,
) {
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
                Text(stringResource(R.string.add_to_your_journey), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                Icon(Icons.Rounded.Close, stringResource(R.string.action_close), Modifier.clickable(onClick = onDismiss))
            }
            Text(stringResource(R.string.capture_plan_or_memory), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            AddAction(Icons.Rounded.AddLocationAlt, stringResource(R.string.rate_a_place), stringResource(R.string.rate_place_subtitle), onRate)
            AddAction(Icons.Rounded.BookmarkAdd, stringResource(R.string.add_to_want_to_go), stringResource(R.string.add_want_to_go_subtitle), onWantToGo)
            AddAction(Icons.Outlined.Explore, stringResource(R.string.check_in), stringResource(R.string.check_in_subtitle)) {}
            AddAction(Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.add_travel_note), stringResource(R.string.add_travel_note_subtitle)) {}
            AddAction(Icons.Rounded.FolderCopy, stringResource(R.string.create_collection), stringResource(R.string.create_collection_subtitle), onCreateCollection)
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
