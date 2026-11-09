package com.tapnex.autoclicker.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(onBack: () -> Unit, onAuthenticated: () -> Unit = {}) {
    var uid by remember { mutableStateOf(AccountService.currentUid) }

    // Keep the screen in sync if the user signs in or out elsewhere.
    DisposableEffect(Unit) {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener {
            uid = it.currentUser?.uid
            if (it.currentUser != null) onAuthenticated()
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Profile", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        val currentUid = uid
        if (currentUid == null) {
            SignInSection()
        } else {
            SignedInSection(currentUid)
        }
    }
}

@Composable
private fun SignInSection() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sign in", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Sign in to manage your TAPNEX account. Create an account if you do not have one.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (min. 6 characters)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }

            Button(
                enabled = !busy,
                onClick = {
                    val validation = AccountService.validate(email, password)
                    if (validation != null) {
                        error = validation
                        return@Button
                    }
                    busy = true
                    error = null
                    info = null
                    scope.launch {
                        try {
                            error = AccountService.signIn(email, password)
                        } finally {
                            busy = false
                        }
                        Unit
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Please wait..." else "Sign in") }

            OutlinedButton(
                enabled = !busy,
                onClick = {
                    val validation = AccountService.validate(email, password)
                    if (validation != null) {
                        error = validation
                        return@OutlinedButton
                    }
                    busy = true
                    error = null
                    info = null
                    scope.launch {
                        try {
                            error = AccountService.register(email, password)
                        } finally {
                            busy = false
                        }
                        Unit
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Create account") }

            // Google button appears only after OAuth is configured in google-services.json.
            if (GoogleSignInService.webClientId(context) != null) {
            HorizontalDivider()

            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    info = null
                    scope.launch {
                        try {
                            val result = GoogleSignInService.signIn(context)
                            if (result is GoogleSignInResult.Success) {
                                info = if (result.isNewUser) "Account created with Google." else "Signed in with Google."
                            } else if (result is GoogleSignInResult.Failure) {
                                error = result.message
                            }
                        } finally {
                            busy = false
                        }
                        Unit
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Continue with Google") }
            }
        }
    }
}

@Composable
private fun SignedInSection(uid: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Email: ${AccountService.currentEmail ?: "(no email)"}")
            AccountService.currentDisplayName?.let { Text("Name: $it") }
            Text("Sign-in method: ${AccountService.signInMethod}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { AccountService.signOut() }, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: Long, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
