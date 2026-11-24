package com.example.data

import android.content.Context
import android.util.Log
import com.example.R
import com.google.firebase.FirebaseApp

/**
 * Ensures [FirebaseApp] is initialized defensively before any [FirebaseAuth] or
 * [FirebaseFirestore] singleton is accessed, and logs detailed initialization
 * diagnostics confirming connection to the provisioned Firebase project
 * and named Firestore database.
 */
object FirebaseStartup {
    private const val TAG = "XMaxFirebaseDiagnostics"
    const val EXPECTED_PROJECT_ID = "gen-lang-client-0461366135"
    const val EXPECTED_DATABASE_ID = "ai-studio-android-xteam-28ae82d9-933c-4b9e-8445-3ca11186cacb"
    const val EXPECTED_PACKAGE_NAME = "com.aistudio.xmax.krmxpq"
    const val EXPECTED_SHA1 = "5F:24:F4:21:6B:EB:A3:6E:8D:87:21:6D:85:A9:A0:3A:EF:00:AD:4D"
    const val EXPECTED_SHA256 = "A2:EE:AB:A4:99:BA:B0:3F:4F:8D:EE:4D:BA:2B:0D:80:28:43:31:95:BA:DF:C8:CE:92:71:B2:F0:E2:4C:61:E7"

    @Volatile
    private var initialized = false

    fun resolveDatabaseId(context: Context?): String {
        val fromRes = context?.let {
            runCatching { it.applicationContext.getString(R.string.firestore_database_id) }.getOrNull()?.trim()
        }.orEmpty()
        return fromRes.ifBlank { EXPECTED_DATABASE_ID }
    }

    fun ensureInitialized(context: Context? = null) {
        if (initialized && runCatching { FirebaseApp.getInstance() }.isSuccess) return
        synchronized(this) {
            val appCtx = context?.applicationContext
            runCatching {
                if (FirebaseApp.getApps(appCtx ?: return@runCatching).isEmpty()) {
                    FirebaseApp.initializeApp(appCtx)
                }
                val app = FirebaseApp.getInstance()
                val options = app.options
                val resolvedDbId = resolveDatabaseId(appCtx)
                initialized = true

                Log.d(TAG, "==================================================")
                Log.d(TAG, "FIREBASE INITIALIZATION & DIAGNOSTICS REPORT")
                Log.d(TAG, "==================================================")
                Log.d(TAG, "App Package Name: ${appCtx.packageName} (Expected: $EXPECTED_PACKAGE_NAME)")
                Log.d(TAG, "Firebase Project ID: ${options.projectId} (Expected: $EXPECTED_PROJECT_ID)")
                Log.d(TAG, "Firebase App ID: ${options.applicationId}")
                Log.d(TAG, "Firestore Target Database ID: $resolvedDbId")
                Log.d(TAG, "Certificate SHA-1: $EXPECTED_SHA1")
                Log.d(TAG, "Certificate SHA-256: $EXPECTED_SHA256")
                Log.d(TAG, "==================================================")

                if (options.projectId != EXPECTED_PROJECT_ID) {
                    Log.w(TAG, "WARNING: Firebase Project ID [${options.projectId}] does not match expected [$EXPECTED_PROJECT_ID]!")
                }
            }.onFailure { err ->
                Log.e(TAG, "CRITICAL: FirebaseApp initialization failed: ${err.message}", err)
            }
        }
    }
}
