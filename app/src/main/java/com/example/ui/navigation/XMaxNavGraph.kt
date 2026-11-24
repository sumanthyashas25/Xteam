package com.example.ui.navigation

import android.app.Activity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.ui.components.formatTitleCase
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.PendingApprovalScreen
import com.example.ui.screens.SignInScreen
import com.example.ui.theme.*
import kotlinx.coroutines.delay

private fun resolveLoadingUserName(state: AgencyUiState): String {
    val raw = state.myMember?.fullName
        ?: state.firebaseUser?.displayName
        ?: (state.authSessionStatus as? AuthSessionStatus.SignedIn)?.user?.displayName
        ?: ""
    val first = raw.trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() }
    return if (!first.isNullOrBlank()) formatTitleCase(first) else "Alex"
}

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
    val authStatus = state.authSessionStatus

    if (authStatus is AuthSessionStatus.Initializing) {
        return XMaxRoute.Loading
    }

    if (authStatus is AuthSessionStatus.SignedOut) {
        return XMaxRoute.Login
    }

    if (authStatus is AuthSessionStatus.SignedIn) {
        val userEmail = authStatus.user.email.orEmpty().trim().lowercase()
        if (userEmail == "xdotmax@gmail.com") {
            return XMaxRoute.Dashboard
        }

        if (state.errorMessage != null && !state.memberLoaded && !state.isApprovedMember) {
            return XMaxRoute.Loading
        }

        if (userProfileState is UserProfileState.Loading ||
            (!state.memberLoaded && state.myMember == null)
        ) {
            return XMaxRoute.Loading
        }

        val member = state.myMember
        if (member == null) {
            if (!state.memberLoaded) return XMaxRoute.Loading
            return XMaxRoute.Onboarding
        }

        if (!state.isApprovedMember) {
            return XMaxRoute.PendingApproval
        }

        return XMaxRoute.Dashboard
    }

    return XMaxRoute.Login
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
        startDestination = XMaxRoute.Loading.route,
        enterTransition = { fadeIn(animationSpec = tween(220)) },
        exitTransition = { fadeOut(animationSpec = tween(160)) }
    ) {
        composable(XMaxRoute.Loading.route) {
            val label = when {
                state.authSessionStatus is AuthSessionStatus.Initializing -> "Initializing X.MAX…"
                state.isBootstrappedEmail -> "Activating X.MAX Super Admin…"
                else -> "Loading your X.MAX profile…"
            }
            LoadingDestinationScreen(
                label = label,
                userName = resolveLoadingUserName(state),
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
                    userName = resolveLoadingUserName(state),
                    errorMessage = profileError,
                    canSignOut = state.authSessionStatus is AuthSessionStatus.SignedIn,
                    onRetry = { viewModel.retryProfileSync() },
                    onSignOut = { viewModel.signOut(context) }
                )
            }
        }
    }
}

/**
 * Signature Apple-inspired loading screen matching design reference:
 * - Pure white top canvas
 * - Bold SF Pro Rounded "Welcome, \n Alex" typography
 * - Vibrant vertical neon lime-yellow gradient animation at the bottom
 * - Minimalist 270° rotating black arc spinner centered near bottom
 * - Resilient network error and retry sync affordances
 */
@Composable
fun LoadingDestinationScreen(
    label: String,
    userName: String? = null,
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

    val displayFirst = remember(userName) {
        val clean = userName?.trim().orEmpty()
        val first = clean.split(Regex("\\s+")).firstOrNull { it.isNotBlank() }
        if (!first.isNullOrBlank()) formatTitleCase(first) else "Alex"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "loading_screen_anim")
    val gradientShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gradient_shift"
    )

    val spinnerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinner_rotation"
    )

    val topTransitionY = 0.40f + 0.08f * gradientShift
    val midStop1 = topTransitionY + 0.12f
    val midStop2 = midStop1 + 0.18f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("loading_screen")
            .semantics { contentDescription = "$label Welcome, $displayFirst" }
    ) {
        // 1. Animated Canvas with smooth vertical gradient and radiant bottom glow
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Smooth animated vertical gradient matching screenshot
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.White,
                        topTransitionY to Color.White,
                        midStop1.coerceAtMost(0.78f) to Color(0xFFF7FFC8).copy(alpha = 0.95f),
                        midStop2.coerceAtMost(0.92f) to Color(0xFFE2FF4A),
                        0.90f to Color(0xFFCEFF00),
                        1.0f to Color(0xFFB8F800)
                    ),
                    startY = 0f,
                    endY = h
                )
            )

            // Radiant ambient pulse at bottom center
            val glowY = h * (0.86f + 0.04f * gradientShift)
            val glowRadius = w * (0.72f + 0.12f * gradientShift)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFEDFF6B).copy(alpha = 0.45f + 0.15f * gradientShift),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.5f, glowY),
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = Offset(w * 0.5f, glowY)
            )
        }

        // 2. Foreground content: Typography on top, rotating black arc spinner at bottom
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Spacer(Modifier.height(88.dp))

            // Welcome, [Name] matching reference typography & layout
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 34.dp)
            ) {
                Text(
                    text = "Welcome,",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFamily = SfProRoundedFontFamily,
                        fontSize = 38.sp,
                        lineHeight = 44.sp,
                        letterSpacing = (-0.6).sp
                    ),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F1015)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = displayFirst,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFamily = SfProRoundedFontFamily,
                        fontSize = 38.sp,
                        lineHeight = 44.sp,
                        letterSpacing = (-0.6).sp
                    ),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F1015)
                )
            }

            Spacer(Modifier.weight(1f))

            // Recovery card if timed out or error occurs
            if (showRecovery) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .align(Alignment.CenterHorizontally)
                        .testTag("loading_recovery_card"),
                    shape = RoundedCornerShape(22.dp),
                    color = if (!errorMessage.isNullOrBlank()) SoftBlush else PureWhite,
                    border = BorderStroke(1.dp, SubtleOutline),
                    shadowElevation = 8.dp
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
                            Button(
                                onClick = onSignOut,
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFECEDF1),
                                    contentColor = ObsidianCharcoal
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
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
                Spacer(Modifier.height(18.dp))
            }

            // Minimalist rotating black arc spinner matching screenshot
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 72.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .size(38.dp)
                        .graphicsLayer { rotationZ = spinnerRotation }
                        .testTag("loading_spinner")
                ) {
                    drawArc(
                        color = Color(0xFF0F1015),
                        startAngle = 0f,
                        sweepAngle = 270f,
                        useCenter = false,
                        style = Stroke(
                            width = 3.2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    )
                }
            }
        }
    }
}
