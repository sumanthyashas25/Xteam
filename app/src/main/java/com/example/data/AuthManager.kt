package com.example.data

import android.content.Context
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.Flow

/**
 * Compatibility facade delegating to a shared [AuthenticationRepository].
 */
object AuthManager {
    private val repository by lazy { AuthenticationRepository() }

    val authStateFlow: Flow<FirebaseUser?>
        get() = repository.authStateFlow

    val sessionStatusFlow: Flow<AuthSessionStatus>
        get() = repository.sessionStatusFlow

    suspend fun attemptAutoSignIn(context: Context): FirebaseUser? =
        repository.attemptAutoSignIn(context)

    suspend fun onGoogleSignInClicked(activityContext: Context): GoogleSignInResult =
        repository.signInWithGoogle(activityContext)

    suspend fun signOut(context: Context) =
        repository.signOut(context)
}
