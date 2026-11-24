package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object NetworkConnectivityMonitor {
    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    @Volatile
    private var registered = false

    @Volatile
    private var appContext: Context? = null

    private fun resolveFirestore(context: Context): FirebaseFirestore? {
        return runCatching {
            FirebaseStartup.ensureInitialized(context)
            val dbId = runCatching { context.applicationContext.getString(R.string.firestore_database_id) }
                .getOrNull()?.trim().orEmpty().ifBlank { FirebaseStartup.EXPECTED_DATABASE_ID }
            FirebaseFirestore.getInstance(dbId)
        }.getOrNull()
    }

    private fun hasActiveInternet(cm: ConnectivityManager): Boolean {
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (registered) return
        synchronized(this) {
            if (registered) return
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching {
                cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        _isOnline.value = true
                        resolveFirestore(appCtx)?.enableNetwork()
                    }

                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        val hasNet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        if (hasNet) {
                            _isOnline.value = true
                            resolveFirestore(appCtx)?.enableNetwork()
                        } else {
                            _isOnline.value = hasActiveInternet(cm)
                        }
                    }

                    override fun onLost(network: Network) {
                        val stillOnline = hasActiveInternet(cm)
                        _isOnline.value = stillOnline
                        if (stillOnline) {
                            resolveFirestore(appCtx)?.enableNetwork()
                        }
                    }
                })
                registered = true
            }
            // Only mark offline if ConnectivityManager reports an explicit state and has no internet
            if (cm.activeNetwork != null) {
                _isOnline.value = hasActiveInternet(cm)
            }
        }
    }

    fun forceReconnect(context: Context? = appContext) {
        val ctx = context ?: return
        resolveFirestore(ctx)?.enableNetwork()
    }
}

@Composable
fun FirestoreConnectionBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isOnline by NetworkConnectivityMonitor.isOnline.collectAsState()
    var isAttemptingReconnect by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        NetworkConnectivityMonitor.init(context)
    }

    // Automatic non-blocking reconnect attempt loop when offline
    LaunchedEffect(isOnline) {
        if (!isOnline) {
            while (!isOnline) {
                delay(10_000L)
                isAttemptingReconnect = true
                NetworkConnectivityMonitor.forceReconnect(context)
                delay(1_500L)
                isAttemptingReconnect = false
            }
        }
    }

    AnimatedVisibility(
        visible = !isOnline,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("firestore_offline_banner"),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            tonalElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudOff,
                        contentDescription = "Offline",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = if (isAttemptingReconnect) "Reconnecting to Firestore…" else "Firestore Connection Lost",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Working from local cache. Auto-reconnecting in background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                    }
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            isAttemptingReconnect = true
                            NetworkConnectivityMonitor.forceReconnect(context)
                            delay(1_200L)
                            isAttemptingReconnect = false
                        }
                    },
                    modifier = Modifier.testTag("firestore_reconnect_button")
                ) {
                    if (isAttemptingReconnect) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Retry Connection",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}
