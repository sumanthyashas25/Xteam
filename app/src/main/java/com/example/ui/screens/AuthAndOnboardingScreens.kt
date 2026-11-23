package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.PhoneVerificationSession
import com.example.model.MembershipStatus
import com.example.ui.AgencyUiState
import com.example.ui.components.*
import com.example.ui.theme.*

private fun Modifier.cleanLightBackdrop(): Modifier = this.background(CrispCanvas)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    authInProgress: Boolean,
    errorMessage: String?,
    phoneSession: PhoneVerificationSession = PhoneVerificationSession(),
    onGoogleSignIn: () -> Unit,
    onSendPhoneOtp: (String) -> Unit = {},
    onVerifyPhoneOtp: (String) -> Unit = {},
    onResetPhoneAuth: () -> Unit = {}
) {
    var showPhoneSheet by remember { mutableStateOf(false) }
    var activeLegalTopic by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxSize()
                .align(Alignment.Center)
                .systemBarsPadding()
        ) {
            val minScreenHeight = maxHeight
            val topSpacer = (minScreenHeight * 0.14f).coerceIn(24.dp, 88.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minScreenHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top subtle X.MAX wordmark badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    XMaxLogoImage(tint = ObsidianCharcoal.copy(alpha = 0.78f), height = 14.dp)
                }

                // Main centered identity, headline, and auth actions
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = topSpacer * 0.35f, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LoginGreenUserEmblem(
                        modifier = Modifier
                            .size(64.dp)
                            .testTag("login_hero_emblem")
                    )

                    Spacer(Modifier.height(22.dp))

                    Text(
                        text = "Sign in to continue",
                        fontSize = 27.sp,
                        lineHeight = 33.sp,
                        letterSpacing = (-0.5).sp,
                        fontWeight = FontWeight.Bold,
                        color = ObsidianCharcoal,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = "Please try one of the following ways to\nregister or log in to your account.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Normal,
                        color = StudioTextSecondary,
                        textAlign = TextAlign.Center
                    )

                    if (!errorMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(16.dp))
                        XMaxToastBanner(
                            title = "Sign-in Issue",
                            message = errorMessage,
                            tone = ToastTone.ERROR,
                            onDismiss = {}
                        )
                    }

                    Spacer(Modifier.height(34.dp))

                    // Primary Solid Jet-Black Google Button (matching Login_ui_referance_f765934b.jpg)
                    Surface(
                        onClick = { if (!authInProgress) onGoogleSignIn() },
                        enabled = !authInProgress,
                        shape = RoundedCornerShape(999.dp),
                        color = ObsidianCharcoal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("continue_with_google_button")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (authInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = PureWhite
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Signing in…",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PureWhite
                                )
                            } else {
                                GoogleGLogo(modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "Continue with Google",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PureWhite
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Clean Outlined White Pill: Continue with Phone
                    Surface(
                        onClick = {
                            if (!authInProgress) {
                                onResetPhoneAuth()
                                showPhoneSheet = true
                            }
                        },
                        enabled = !authInProgress,
                        shape = RoundedCornerShape(999.dp),
                        color = PureWhite,
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("continue_with_phone_button")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PhoneAndroid,
                                contentDescription = null,
                                tint = ObsidianCharcoal,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Continue with Phone",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ObsidianCharcoal
                            )
                        }
                    }
                }

                // Bottom green-accent legal footer matching Login_ui_referance_f765934b.jpg
                val legalCaption = remember {
                    buildAnnotatedString {
                        append("By continuing, you confirm that you've read\nand agreed to the ")
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "terms",
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = LoginGreenAccent,
                                        fontWeight = FontWeight.Medium,
                                        textDecoration = TextDecoration.None
                                    )
                                ),
                                linkInteractionListener = { activeLegalTopic = "Terms of Service" }
                            )
                        ) {
                            append("Terms of Service")
                        }
                        append(" and\nconsent the ")
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "privacy",
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = LoginGreenAccent,
                                        fontWeight = FontWeight.Medium,
                                        textDecoration = TextDecoration.None
                                    )
                                ),
                                linkInteractionListener = { activeLegalTopic = "Privacy Policy" }
                            )
                        ) {
                            append("Privacy Policy")
                        }
                        append(".")
                    }
                }

                Text(
                    text = legalCaption,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = StudioTextSecondary,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .testTag("login_terms_caption")
                )
            }
        }
    }

    if (showPhoneSheet) {
        PhoneAuthBottomSheet(
            session = phoneSession,
            onDismiss = {
                showPhoneSheet = false
                onResetPhoneAuth()
            },
            onSendOtp = onSendPhoneOtp,
            onVerifyOtp = onVerifyPhoneOtp,
            onChangeNumber = onResetPhoneAuth
        )
    }

    if (activeLegalTopic != null) {
        ModalBottomSheet(
            onDismissRequest = { activeLegalTopic = null },
            containerColor = PureWhite,
            contentColor = ObsidianCharcoal
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = activeLegalTopic ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = ObsidianCharcoal
                )
                Text(
                    text = if (activeLegalTopic == "Terms of Service") {
                        "Access to this workspace is restricted to authorized team members and approved collaborators. Client quotations, deliverables, and pricing details shared inside the workspace remain confidential."
                    } else {
                        "Authentication identifiers (Google account email or verified phone number) and profile details submitted during onboarding are stored securely in Firebase Authentication and Cloud Firestore for team role access and activity auditing."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioTextSecondary
                )
                Button(
                    onClick = { activeLegalTopic = null },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ObsidianCharcoal,
                        contentColor = PureWhite
                    ),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("Close", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun LoginGreenUserEmblem(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 3.2.dp.toPx()
        val w = size.width
        val h = size.height
        // Head circle
        drawCircle(
            color = LoginGreenAccent,
            radius = w * 0.16f,
            center = Offset(w * 0.42f, h * 0.30f),
            style = Stroke(width = stroke)
        )
        // Rounded shoulder capsule
        drawRoundRect(
            color = LoginGreenAccent,
            topLeft = Offset(w * 0.14f, h * 0.56f),
            size = Size(w * 0.56f, h * 0.28f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.16f, h * 0.14f),
            style = Stroke(width = stroke)
        )
        // Plus (+) badge on upper right
        val px = w * 0.78f
        val py = h * 0.30f
        val arm = w * 0.10f
        drawLine(
            color = LoginGreenAccent,
            start = Offset(px - arm, py),
            end = Offset(px + arm, py),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = LoginGreenAccent,
            start = Offset(px, py - arm),
            end = Offset(px, py + arm),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneAuthBottomSheet(
    session: PhoneVerificationSession,
    onDismiss: () -> Unit,
    onSendOtp: (String) -> Unit,
    onVerifyOtp: (String) -> Unit,
    onChangeNumber: () -> Unit
) {
    var phoneInput by remember(session.phoneNumber) { mutableStateOf(session.phoneNumber.ifBlank { "+91" }) }
    var otpInput by remember { mutableStateOf("") }
    val awaitingOtp = !session.verificationId.isNullOrBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PureWhite,
        contentColor = ObsidianCharcoal
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (awaitingOtp) "Enter Verification Code" else "Continue with Phone",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = ObsidianCharcoal
            )
            Text(
                text = if (awaitingOtp) {
                    "Enter the SMS code sent to ${session.phoneNumber}."
                } else {
                    "Enter your mobile number with country code (for example +919876543210)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary
            )

            if (!awaitingOtp) {
                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = { phoneInput = it },
                    label = { Text("Phone Number (E.164)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CrispCanvas,
                        unfocusedContainerColor = CrispCanvas,
                        focusedBorderColor = ObsidianCharcoal,
                        unfocusedBorderColor = SubtleOutline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("phone_number_input")
                )
            } else {
                OutlinedTextField(
                    value = otpInput,
                    onValueChange = { ch -> otpInput = ch.filter { it.isDigit() }.take(6) },
                    label = { Text("6-Digit SMS Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CrispCanvas,
                        unfocusedContainerColor = CrispCanvas,
                        focusedBorderColor = ObsidianCharcoal,
                        unfocusedBorderColor = SubtleOutline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("phone_otp_input")
                )
            }

            if (!session.errorMessage.isNullOrBlank()) {
                Text(
                    text = session.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = CrimsonBlocked
                )
            }

            Button(
                onClick = {
                    if (awaitingOtp) onVerifyOtp(otpInput) else onSendOtp(phoneInput)
                },
                enabled = !session.inProgress,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ObsidianCharcoal,
                    contentColor = PureWhite
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("phone_auth_submit_button")
            ) {
                if (session.inProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = PureWhite
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", fontWeight = FontWeight.SemiBold)
                } else {
                    Text(
                        text = if (awaitingOtp) "Verify & Continue" else "Send Verification Code",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (awaitingOtp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            otpInput = ""
                            onChangeNumber()
                        }
                    ) {
                        Text("Change Number", color = StudioTextSecondary)
                    }
                    TextButton(
                        onClick = { onSendOtp(session.phoneNumber) },
                        enabled = !session.inProgress
                    ) {
                        Text("Resend Code", color = ObsidianCharcoal, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun GoogleGLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.20f
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)

        drawArc(
            color = Color(0xFFEA4335),
            startAngle = -155f,
            sweepAngle = 115f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 145f,
            sweepAngle = 60f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 45f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -5f,
            sweepAngle = 55f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(size.width * 0.52f, size.height * 0.5f),
            end = Offset(size.width * 0.92f, size.height * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Butt
        )
    }
}

@Composable
fun OnboardingScreen(
    state: AgencyUiState,
    onSubmitOnboarding: (company: String, fullName: String, age: Int, dept: String, role: String, phone: String) -> Unit,
    onBootstrapSuperAdmin: (fullName: String, age: Int) -> Unit,
    onSignOut: () -> Unit
) {
    val user = state.firebaseUser
    var companyName by remember { mutableStateOf("X.MAX") }
    var fullName by remember(user?.uid) { mutableStateOf(user?.displayName ?: "") }
    var ageText by remember { mutableStateOf("") }
    var phone by remember(user?.uid) { mutableStateOf(user?.phoneNumber ?: "") }
    var requestedDept by remember { mutableStateOf("Creative") }
    var requestedRole by remember { mutableStateOf("Graphic Designer") }
    var validationError by remember { mutableStateOf<String?>(null) }

    val deptSuggestions = listOf("Creative", "Web", "Video", "Marketing", "Operations")
    val roleSuggestions = listOf(
        "Graphic Designer", "Brand Strategist", "Web Developer",
        "UI/UX Designer", "Video Editor", "Performance Marketer",
        "SEO & Content Specialist", "Project Manager"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .cleanLightBackdrop()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                XMaxWordmarkBadge("X.MAX Onboarding")
                Surface(
                    onClick = onSignOut,
                    shape = RoundedCornerShape(999.dp),
                    color = MutedSurface,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 40.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Sign Out", color = ObsidianCharcoal, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!state.errorMessage.isNullOrBlank()) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = SoftBlush)
                    ) {
                        Text(
                            text = state.errorMessage,
                            color = DeepBlushInk,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                if (state.isBootstrappedEmail) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = ObsidianCharcoal,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(shape = RoundedCornerShape(999.dp), color = InfoPillBg) {
                                    Text(
                                        text = "Super Admin Ready",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = InfoPillInk,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Text(
                                "Trusted Authority Setup",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = PureWhite
                            )
                            Text(
                                "Verified Google account (${user?.email}) is authorized to provision the X.MAX workspace, starting 40-service catalogue, 5 packages, 11 add-ons, and Super Admin access.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB5B9C4)
                            )
                            Button(
                                onClick = {
                                    val parsedAge = ageText.toIntOrNull()?.coerceIn(16, 100) ?: 28
                                    onBootstrapSuperAdmin(fullName.ifBlank { "X.MAX Director" }, parsedAge)
                                },
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PureWhite,
                                    contentColor = ObsidianCharcoal
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("bootstrap_super_admin_button")
                            ) {
                                Icon(Icons.Filled.Shield, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Provision X.MAX as Super Admin", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MutedSurface),
                    border = BorderStroke(1.dp, SubtleOutline),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Employee Membership Request",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ObsidianCharcoal
                        )
                        Text(
                            "Signed in as ${user?.email ?: user?.phoneNumber ?: "Unknown"}. Requests require Super Admin approval unless an invitation already matches your email.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )

                        OutlinedTextField(
                            value = companyName,
                            onValueChange = { companyName = it },
                            label = { Text("Company Name") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = PureWhite,
                                unfocusedContainerColor = PureWhite
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_company_input")
                        )

                        OutlinedTextField(
                            value = fullName,
                            onValueChange = { fullName = it },
                            label = { Text("Employee Full Name") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = PureWhite,
                                unfocusedContainerColor = PureWhite
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_name_input")
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = ageText,
                                onValueChange = { ageText = it.filter { ch -> ch.isDigit() }.take(3) },
                                label = { Text("Age (Private)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = PureWhite,
                                    unfocusedContainerColor = PureWhite
                                ),
                                modifier = Modifier
                                    .weight(0.42f)
                                    .testTag("onboarding_age_input")
                            )
                            OutlinedTextField(
                                value = phone,
                                onValueChange = { phone = it },
                                label = { Text("Phone (Optional)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = PureWhite,
                                    unfocusedContainerColor = PureWhite
                                ),
                                modifier = Modifier.weight(0.58f)
                            )
                        }
                        Text(
                            "Privacy: Your age & contact details are stored separately and visible only to you and Super Admins.",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary
                        )

                        Text("Requested Department", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ObsidianCharcoal)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            deptSuggestions.take(3).forEach { dept ->
                                FilterChip(
                                    selected = requestedDept.equals(dept, ignoreCase = true),
                                    onClick = { requestedDept = dept },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = ObsidianCharcoal,
                                        selectedLabelColor = PureWhite,
                                        containerColor = PureWhite,
                                        labelColor = ObsidianCharcoal
                                    ),
                                    border = null,
                                    label = { Text(dept, fontWeight = FontWeight.SemiBold) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = requestedDept,
                            onValueChange = { requestedDept = it },
                            label = { Text("Department") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = PureWhite,
                                unfocusedContainerColor = PureWhite
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_department_input")
                        )

                        Text("Requested Job Role", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ObsidianCharcoal)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            roleSuggestions.take(2).forEach { r ->
                                FilterChip(
                                    selected = requestedRole.equals(r, ignoreCase = true),
                                    onClick = { requestedRole = r },
                                    shape = RoundedCornerShape(999.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = ObsidianCharcoal,
                                        selectedLabelColor = PureWhite,
                                        containerColor = PureWhite,
                                        labelColor = ObsidianCharcoal
                                    ),
                                    border = null,
                                    label = { Text(r, maxLines = 1, fontWeight = FontWeight.SemiBold) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = requestedRole,
                            onValueChange = { requestedRole = it },
                            label = { Text("Job Role") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = PureWhite,
                                unfocusedContainerColor = PureWhite
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_job_role_input")
                        )

                        validationError?.let { err ->
                            Text(err, color = CrimsonBlocked, style = MaterialTheme.typography.bodySmall)
                        }

                        Button(
                            onClick = {
                                val age = ageText.toIntOrNull()
                                when {
                                    companyName.isBlank() -> validationError = "Enter your company name."
                                    fullName.isBlank() -> validationError = "Enter your full name."
                                    age == null || age !in 16..100 -> validationError = "Enter a valid age between 16 and 100."
                                    requestedDept.isBlank() || requestedRole.isBlank() -> validationError = "Specify department and job role."
                                    else -> {
                                        validationError = null
                                        onSubmitOnboarding(companyName, fullName, age, requestedDept, requestedRole, phone)
                                    }
                                }
                            },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ObsidianCharcoal,
                                contentColor = PureWhite
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("submit_onboarding_button")
                        ) {
                            Text("Submit Membership Request", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun PendingApprovalScreen(
    state: AgencyUiState,
    onBootstrapSuperAdmin: (fullName: String) -> Unit,
    onSignOut: () -> Unit
) {
    val member = state.myMember
    val status = member?.statusEnum() ?: MembershipStatus.PENDING

    Box(
        modifier = Modifier
            .fillMaxSize()
            .cleanLightBackdrop(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = MutedSurface),
                border = BorderStroke(1.dp, SubtleOutline),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    XMaxLogoImage(tint = ObsidianCharcoal, height = 18.dp)
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.isBootstrappedEmail || status == MembershipStatus.PENDING) {
                                    InfoPillBg
                                } else {
                                    SoftBlush
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (state.isBootstrappedEmail) Icons.Filled.Shield else if (status == MembershipStatus.PENDING) Icons.Filled.HourglassEmpty else Icons.Filled.Block,
                            contentDescription = null,
                            tint = if (state.isBootstrappedEmail || status == MembershipStatus.PENDING) InfoPillInk else CrimsonBlocked,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Text(
                        text = when {
                            state.isBootstrappedEmail -> "Default Super Admin Ready"
                            status == MembershipStatus.PENDING -> "Pending Approval"
                            status == MembershipStatus.DECLINED -> "Request Declined"
                            status == MembershipStatus.SUSPENDED -> "Account Inactive"
                            else -> "Awaiting Workspace"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = ObsidianCharcoal,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = when {
                            state.isBootstrappedEmail ->
                                "Signed in as default Super Admin (${state.firebaseUser?.email}). Tap below to activate Super Admin access."
                            status == MembershipStatus.PENDING ->
                                "Your request to join X.MAX as ${member?.requestedJobRole?.ifBlank { "Team Member" }} (${member?.requestedDepartment?.ifBlank { "Department" }}) is waiting for a Super Admin to approve your account and assign permissions."
                            else ->
                                "Your account currently does not have active workspace access. Please contact an X.MAX Super Admin."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioTextSecondary,
                        textAlign = TextAlign.Center
                    )
                    if (!state.errorMessage.isNullOrBlank()) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = SoftBlush)
                        ) {
                            Text(
                                text = state.errorMessage,
                                color = DeepBlushInk,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    if (member != null) {
                        HorizontalDivider(color = SubtleOutline)
                        Text(
                            "Signed in: ${state.firebaseUser?.email ?: member.fullName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioTextSecondary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    if (state.isBootstrappedEmail) {
                        Button(
                            onClick = {
                                onBootstrapSuperAdmin(
                                    member?.fullName?.ifBlank { null }
                                        ?: state.firebaseUser?.displayName
                                        ?: "X.MAX Director"
                                )
                            },
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ObsidianCharcoal,
                                contentColor = PureWhite
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("pending_bootstrap_super_admin_button")
                        ) {
                            Icon(Icons.Filled.Shield, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Activate Super Admin Access", fontWeight = FontWeight.Bold)
                        }
                    }
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
                            .height(50.dp)
                            .testTag("pending_sign_out_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Sign Out", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
