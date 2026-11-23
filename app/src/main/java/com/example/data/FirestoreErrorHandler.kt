package com.example.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType {
    CREATE, UPDATE, DELETE, LIST, GET, WRITE
}

fun handleFirestoreError(
    error: Throwable,
    operationType: OperationType,
    path: String
) {
    val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
    val code = (error as? FirebaseFirestoreException)?.code?.name ?: error::class.java.simpleName
    val payload = JSONObject().apply {
        put("error", "${operationType.name} $path: $code - ${error.message ?: "Unknown error"}")
        put("operationType", operationType.name.lowercase())
        put("path", path)
        put("authInfo", JSONObject().apply {
            put("userId", user?.uid ?: "null")
            put("email", user?.email ?: "null")
            put("emailVerified", user?.isEmailVerified ?: false)
            put("providerInfo", JSONArray().apply {
                user?.providerData?.forEach { info ->
                    put(JSONObject().apply {
                        put("providerId", info.providerId)
                        put("displayName", info.displayName ?: "null")
                        put("email", info.email ?: "null")
                    })
                }
            })
        })
    }
    Log.e("FirestoreError", payload.toString(), error)
}
