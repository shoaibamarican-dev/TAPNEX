package com.tapnex.autoclicker.account

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Email/password Firebase Authentication. Firebase persists the signed-in session on the device,
 * so the user stays signed in across app restarts until they sign out.
 * No credentials or users are hardcoded here.
 */
object AccountService {
    private val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    val currentUid: String?
        get() = auth.currentUser?.uid

    val currentEmail: String?
        get() = auth.currentUser?.email

    private val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    /** Returns a user-facing message for invalid input, or null when the input is acceptable. */
    fun validate(email: String, password: String): String? = when {
        !emailPattern.matches(email.trim()) -> "Enter a valid email address."
        password.length < 6 -> "Password must be at least 6 characters."
        else -> null
    }

    /** Returns null on success, otherwise a user-facing error message. */
    suspend fun signIn(email: String, password: String): String? = try {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        describe(e)
    }

    suspend fun register(email: String, password: String): String? = try {
        auth.createUserWithEmailAndPassword(email.trim(), password).await()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        describe(e)
    }

    val currentDisplayName: String?
        get() = auth.currentUser?.displayName

    /** True when the signed-in Firebase user has a Google provider linked. */
    val isGoogleAccount: Boolean
        get() = auth.currentUser?.providerData?.any { it.providerId == "google.com" } == true

    val signInMethod: String
        get() = if (isGoogleAccount) "Google" else "Email and password"

    fun signOut() {
        auth.signOut()
    }

    private fun describe(e: Exception): String {
        val code = (e as? FirebaseAuthException)?.errorCode
        return when (code) {
            "ERROR_INVALID_EMAIL" -> "That email address is not valid."
            "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND" ->
                "Email or password is incorrect."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "An account already exists for this email. Sign in instead."
            "ERROR_WEAK_PASSWORD" -> "Password is too weak. Use at least 6 characters."
            "ERROR_NETWORK_REQUEST_FAILED" -> "Network error. Check your connection and try again."
            "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Wait a few minutes and try again."
            "ERROR_USER_DISABLED" -> "This account has been disabled."
            else -> "Authentication failed. Please try again."
        }
    }
}
