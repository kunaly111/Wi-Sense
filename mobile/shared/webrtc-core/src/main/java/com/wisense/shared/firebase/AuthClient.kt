package com.wisense.shared.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await

/**
 * Minimal email/password auth wrapper shared by both apps — hobby-project
 * scope: no phone auth, no invite flow, no password reset UI yet.
 */
object AuthClient {
    private val auth get() = FirebaseAuth.getInstance()

    val currentUser: FirebaseUser? get() = auth.currentUser

    suspend fun signUp(email: String, password: String): FirebaseUser {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        return result.user ?: error("sign up succeeded but no user returned")
    }

    suspend fun signIn(email: String, password: String): FirebaseUser {
        val result = auth.signInWithEmailAndPassword(email, password).await()
        return result.user ?: error("sign in succeeded but no user returned")
    }

    fun signOut() = auth.signOut()
}
