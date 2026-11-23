package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AgencyRepository
import com.example.data.AuthSessionStatus
import com.example.data.AuthenticationRepository
import com.example.data.OperationType
import com.example.data.handleFirestoreError
import com.example.model.User
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

sealed interface UserProfileState {
    data object Unauthenticated : UserProfileState
    data object Loading : UserProfileState
    data class Loaded(val user: User?, val email: String?) : UserProfileState
    data class Error(val message: String) : UserProfileState
}

/**
 * Retrieves and streams the authenticated user's [User] profile, access level,
 * and membership status from Firestore once signed in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserViewModel(application: Application) : AndroidViewModel(application) {

    private val authRepository = AuthenticationRepository(application)
    private val agencyRepository = AgencyRepository(application)

    val userProfileState: StateFlow<UserProfileState> = authRepository.sessionStatusFlow
        .flatMapLatest { session ->
            when (session) {
                AuthSessionStatus.Initializing -> flowOf(UserProfileState.Loading)
                AuthSessionStatus.SignedOut -> flowOf(UserProfileState.Unauthenticated)
                is AuthSessionStatus.SignedIn -> {
                    val uid = session.user.uid
                    if (uid.isBlank()) {
                        flowOf(UserProfileState.Unauthenticated)
                    } else {
                        combine(
                            agencyRepository.observeMyMember(uid),
                            agencyRepository.observeMyPrivateInfo(uid)
                                .onStart { emit(null) }
                                .catch { emit(null) }
                        ) { member, privateInfo ->
                            val domainUser = member?.let {
                                User.fromMember(
                                    member = it,
                                    privateInfo = privateInfo,
                                    email = session.user.email.orEmpty()
                                )
                            }
                            UserProfileState.Loaded(
                                user = domainUser,
                                email = session.user.email
                            ) as UserProfileState
                        }.onStart {
                            emit(UserProfileState.Loading)
                        }.catch { err ->
                            if (err is CancellationException) throw err
                            if (err is FirebaseFirestoreException) {
                                handleFirestoreError(err, OperationType.GET, "companies/xmax/members/$uid")
                            }
                            emit(UserProfileState.Error(err.localizedMessage ?: "Failed to retrieve user profile from Firestore."))
                        }
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserProfileState.Loading
        )
}
