package com.example.ui.navigation

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.AuthSessionStatus
import com.example.ui.AgencyUiState
import com.example.ui.AgencyViewModel
import com.example.ui.UserProfileState
import com.example.ui.components.XMaxLogoImage
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.PendingApprovalScreen
import com.example.ui.screens.SignInScreen
import com.example.ui.theme.CrispCanvas
import com.example.ui.theme.DeepBlushInk
import com.example.ui.theme.ObsidianCharcoal
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SoftBlush
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.SubtleOutline
import kotlinx.coroutines.delay

sealed class XMaxRoute(val route: String) {
    data object Loading : XMaxRoute("loading")
    data object Login : XMaxRoute("login")
    data object Onboarding : XMaxRoute("onboarding")
    data object PendingApproval : XMaxRoute("pending_approval")
    data object Dashboard : XMaxRoute("dashboard")
}

fun resolveAuthRoute(
    state: AgencyUiState,
    userProfileState: UserProfileState? = null
): XMaxRoute {
    return when (state.authSessionStatus) {
        AuthSessionStatus.Initializing -> XMaxRoute.Loading
        AuthSessionStatus.SignedOut -> XMaxRoute.Login
        is AuthSessionStatus.SignedIn -> {
            if (!state.memberLoaded || state.autoBootstrapInProgress) {
                XMaxRoute.Loading
            } else {
                val loadedUser = (userProfileState as? UserProfileState.Loaded)?.user
                when {
                    state.myMember == null && loadedUser == null -> XMaxRoute.Onboarding
                    !state.isApprovedMember -> XMaxRoute.PendingApproval
                    else -> XMaxRoute.Dashboard
                }
            }
        }
    }
}

@Composable
fun XMaxNavGraph(
    state: AgencyUiState,
    viewModel: AgencyViewModel,
    userProfileState: UserProfileState? = null,
    navController: NavHostController = rememberNavController(),
    dashboardShell: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val targetRoute = resolveAuthRoute(state, userProfileState)
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    val isDarkDestination = false
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            runCatching {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !isDarkDestination
                controller.isAppearanceLightNavigationBars = !isDarkDestination
            }
        }
    }

    LaunchedEffect(targetRoute, currentRoute) {
        val activeRoute = currentRoute
        if (activeRoute != null && activeRoute != targetRoute.route) {
            runCatching {
                navController.navigate(targetRoute.route) {
                    popUpTo(navController.graph.id) {
                        inclusive = true
                    }
                    launchSingleTop = true
                }
            }
        }
    }

    val profileError = (userProfileState as? UserProfileState.Error)?.message ?: state.errorMessage

    NavHost(
        navController = navController,
        startDestination = XMaxRoute.Loading.route
    ) {
        composable(XMaxRoute.Loading.route) {
            val label = when {
                state.authSessionStatus is AuthSessionStatus.Initializing -> "Initializing X.MAX…"
                state.isBootstrappedEmail -> "Activating X.MAX Super Admin…"
                else -> "Loading your X.MAX profile…"
            }
            LoadingDestinationScreen(
                label = label,
                errorMessage = profileError,
                canSignOut = state.authSessionStatus is AuthSessionStatus.SignedIn,
                onRetry = { viewModel.retryProfileSync() },
                onSignOut = { viewModel.signOut(context) }
            )
        }

        composable(XMaxRoute.Login.route) {
            val phoneSession by viewModel.phoneSession.collectAsStateWithLifecycle()
            SignInScreen(
                authInProgress = state.authInProgress,
                errorMessage = state.errorMessage,
                phoneSession = phoneSession,
                onGoogleSignIn = { viewModel.signInWithGoogle(context) },
                onSendPhoneOtp = { phoneNumber -> viewModel.startPhoneVerification(context, phoneNumber) },
                onVerifyPhoneOtp = { code -> viewModel.verifyPhoneOtp(code) },
                onResetPhoneAuth = { viewModel.resetPhoneVerification() }
            )
        }

        composable(XMaxRoute.Onboarding.route) {
            val uid = (state.authSessionStatus as? AuthSessionStatus.SignedIn)?.user?.uid
            key(uid) {
                OnboardingScreen(
                    state = state,
                    onSubmitOnboarding = { company, name, age, dept, role, phone ->
                        viewModel.submitOnboarding(company, name, age, dept, role, phone)
                    },
                    onBootstrapSuperAdmin = { name, age -> viewModel.bootstrapXMax(name, age) },
                    onSignOut = { viewModel.signOut(context) }
                )
            }
        }

        composable(XMaxRoute.PendingApproval.route) {
            val uid = (state.authSessionStatus as? AuthSessionStatus.SignedIn)?.user?.uid
            key(uid) {
                PendingApprovalScreen(
                    state = state,
                    onBootstrapSuperAdmin = { name -> viewModel.bootstrapXMax(name, null) },
                    onSignOut = { viewModel.signOut(context) }
                )
            }
        }

        composable(XMaxRoute.Dashboard.route) {
            val uid = (state.authSessionStatus as? AuthSessionStatus.SignedIn)?.user?.uid
            if (uid != null && state.isApprovedMember) {
                key(uid) {
                    dashboardShell()
                }
            } else {
                LoadingDestinationScreen(
                    label = "Syncing workspace session…",
                    errorMessage = profileError,
                    canSignOut = state.authSessionStatus is AuthSessionStatus.SignedIn,
                    onRetry = { viewModel.retryProfileSync() },
                    onSignOut = { viewModel.signOut(context) }
                )
            }
        }
    }
}

@Composable
internal fun LoadingDestinationScreen(
    label: String,
    errorMessage: String? = null,
    canSignOut: Boolean = false,
    timeoutMillis: Long = 7_000L,
    onRetry: () -> Unit = {},
    onSignOut: () -> Unit = {}
) {
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(label, errorMessage) {
        timedOut = false
        delay(timeoutMillis)
        timedOut = true
    }

    val showRecovery = timedOut || !errorMessage.isNullOrBlank()

    Surface(modifier = Modifier.fillMaxSize(), color = CrispCanvas) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            XMaxLogoImage(tint = ObsidianCharcoal, height = 22.dp)
            Spacer(Modifier.height(18.dp))
            CircularProgressIndicator(color = ObsidianCharcoal)
            Spacer(Modifier.height(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = StudioTextSecondary,
                textAlign = TextAlign.Center
            )

            if (showRecovery) {
                Spacer(Modifier.height(20.dp))
                Surface(
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = if (!errorMessage.isNullOrBlank()) SoftBlush else PureWhite,
                    border = BorderStroke(1.dp, SubtleOutline)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = errorMessage ?: "Still waiting for network sync. Check connectivity or retry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!errorMessage.isNullOrBlank()) DeepBlushInk else StudioTextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = {
                                timedOut = false
                                onRetry()
                            },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ObsidianCharcoal,
                                contentColor = PureWhite
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("loading_retry_button")
                        ) {
                            Text("Retry Sync", fontWeight = FontWeight.SemiBold)
                        }
                        if (canSignOut) {
                            OutlinedButton(
                                onClick = onSignOut,
                                shape = RoundedCornerShape(999.dp),
                                border = BorderStroke(1.dp, SubtleOutline),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = PureWhite,
                                    contentColor = ObsidianCharcoal
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("loading_sign_out_button")
                            ) {
                                Text("Sign Out", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}
