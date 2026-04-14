package com.example.voicegmail.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.voicegmail.R
import com.example.voicegmail.viewmodel.AuthViewModel

@Composable
fun SignInScreen(
    onAuthenticated: () -> Unit,
    onSignInClick: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Respond to authentication success
    if (state is AuthViewModel.AuthState.Authenticated) {
        onAuthenticated()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Email,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(80.dp)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Voice Gmail",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "A hands-free Gmail experience",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(48.dp))

        when (state) {
            is AuthViewModel.AuthState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.semantics {
                        contentDescription = "Signing in, please wait"
                    }
                )
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.signing_in))
            }

            is AuthViewModel.AuthState.Error -> {
                Text(
                    text = stringResource(R.string.error_prefix) +
                            (state as AuthViewModel.AuthState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onSignInClick,
                    modifier = Modifier.semantics {
                        contentDescription = "Retry sign in with Google"
                    }
                ) {
                    Text(stringResource(R.string.sign_in_button))
                }
            }

            else -> {
                Button(
                    onClick = onSignInClick,
                    modifier = Modifier.semantics {
                        contentDescription = "Sign in with Google to access your Gmail"
                    }
                ) {
                    Text(stringResource(R.string.sign_in_button))
                }
            }
        }
    }
}
