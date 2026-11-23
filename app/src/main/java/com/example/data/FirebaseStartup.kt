package com.example.data

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp

/**
 * Ensures [FirebaseApp] is initialized defensively before any [FirebaseAuth] or
 * [FirebaseFirestore] singleton is accessed (covering cold starts, background receivers,
 * and Robolectric unit test environments).
 */
object FirebaseStartup {
    private const val TAG = "XMaxFirebaseStartup"

    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context? = null) {
        if (initialized && runCatching { FirebaseApp.getInstance() }.isSuccess) return
        synchronized(this) {
            if (runCatching { FirebaseApp.getInstance() }.isSuccess) {
                initialized = true
                return
            }
            val appCtx = context?.applicationContext
            if (appCtx != null) {
                runCatching {
                    if (FirebaseApp.getApps(appCtx).isEmpty()) {
                        FirebaseApp.initializeApp(appCtx)
                    }
                    initialized = runCatching { FirebaseApp.getInstance() }.isSuccess
                }.onFailure { err ->
                    Log.w(TAG, "FirebaseApp initialization skipped or failed: ${err.message}")
                }
            }
        }
    }
}
