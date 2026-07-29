package com.wisense.resident.presentation

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisense.resident.data.house.HouseRepository
import com.wisense.shared.firebase.AuthClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

sealed interface AuthUiState {
    data object SignedOut : AuthUiState
    data object Working : AuthUiState
    data class SignedIn(val houseId: String) : AuthUiState
    data class Error(val message: String) : AuthUiState
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val houseRepository: HouseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.SignedOut)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        if (AuthClient.currentUser != null) onSignedIn()
    }

    fun signUp(email: String, password: String) = withAuth { AuthClient.signUp(email, password) }
    fun signIn(email: String, password: String) = withAuth { AuthClient.signIn(email, password) }

    private fun withAuth(block: suspend () -> Unit) {
        _state.value = AuthUiState.Working
        viewModelScope.launch {
            try {
                block()
                onSignedIn()
            } catch (e: Exception) {
                _state.value = AuthUiState.Error(e.message ?: e.toString())
            }
        }
    }

    private fun onSignedIn() {
        viewModelScope.launch {
            try {
                val uid = AuthClient.currentUser?.uid ?: error("no user after sign-in")
                FirebaseFirestore.getInstance().collection("wisense_users").document(uid)
                    .set(mapOf("role" to "resident"), SetOptions.merge())
                    .await()
                val houseId = houseRepository.ensureHouse()
                _state.value = AuthUiState.SignedIn(houseId)
            } catch (e: Exception) {
                _state.value = AuthUiState.Error(e.message ?: e.toString())
            }
        }
    }
}
