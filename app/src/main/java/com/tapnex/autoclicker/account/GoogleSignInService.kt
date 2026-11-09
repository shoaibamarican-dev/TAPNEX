package com.tapnex.autoclicker.account

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

sealed class GoogleSignInResult {
    data class Success(val isNewUser: Boolean) : GoogleSignInResult()
    data class Failure(val message: String) : GoogleSignInResult()
    object Cancelled : GoogleSignInResult()
}

/**
 * Google Sign-In through Credential Manager. The Google ID token is exchanged for a Firebase
 * credential, so Firebase creates the account the first time and reuses it afterwards.
 *
 * Firebase only generates `default_web_client_id` when google-services.json contains a web OAuth
 * client (client_type 3). If it is missing, we show a setup message instead of crashing.
 */
object GoogleSignInService {

    fun webClientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id)
    }

    suspend fun signIn(context: Context): GoogleSignInResult {
        val serverClientId = webClientId(context)
            ?: return GoogleSignInResult.Failure(
                "Google sign-in is currently unavailable. Please use email and password."
            )
        return try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val response = CredentialManager.create(context).getCredential(context, request)
            val credential = response.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return GoogleSignInResult.Failure("Unexpected sign-in response. Please try again.")
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val result = FirebaseAuth.getInstance()
                .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .await()
            GoogleSignInResult.Success(isNewUser = result.additionalUserInfo?.isNewUser == true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleSignInResult.Failure("No Google account was found on this phone. Add one in Settings and try again.")
        } catch (e: GoogleIdTokenParsingException) {
            GoogleSignInResult.Failure("Google returned an invalid sign-in token. Please try again.")
        } catch (e: GetCredentialException) {
            GoogleSignInResult.Failure("Google sign-in failed. Check your connection and try again.")
        } catch (e: FirebaseAuthUserCollisionException) {
            GoogleSignInResult.Failure(
                "This Google email already has a TAPNEX account with a password. Sign in with email and password instead."
            )
        } catch (e: FirebaseNetworkException) {
            GoogleSignInResult.Failure("Network error. Check your connection and try again.")
        } catch (e: Exception) {
            GoogleSignInResult.Failure("Google sign-in is currently unavailable. Please use email and password.")
        }
    }
}
