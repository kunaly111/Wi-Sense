package com.wisense.caregiver.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.wisense.caregiver.data.registerFcmToken
import com.wisense.shared.firebase.AuthClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface AuthUiState {
    data object SignedOut : AuthUiState
    data object Working : AuthUiState
    data object SignedIn : AuthUiState
    data class Error(val message: String) : AuthUiState
}

class AuthViewModel : ViewModel() {

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.SignedOut)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        if (AuthClient.currentUser != null) _state.value = AuthUiState.SignedIn
    }

    fun signUp(email: String, password: String) = withAuth { AuthClient.signUp(email, password) }
    fun signIn(email: String, password: String) = withAuth { AuthClient.signIn(email, password) }

    private fun withAuth(block: suspend () -> Unit) {
        _state.value = AuthUiState.Working
        viewModelScope.launch {
            try {
                block()
                val uid = AuthClient.currentUser?.uid ?: error("no user after sign-in")
                FirebaseFirestore.getInstance().collection("wisense_users").document(uid)
                    .set(mapOf("role" to "caregiver"), SetOptions.merge())
                    .await()
                registerFcmToken()
                _state.value = AuthUiState.SignedIn
            } catch (e: Exception) {
                _state.value = AuthUiState.Error(e.message ?: e.toString())
            }
        }
    }
}
