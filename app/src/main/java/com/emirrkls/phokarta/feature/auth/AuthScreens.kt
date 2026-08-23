package com.emirrkls.phokarta.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.ui.theme.Sand

@Composable
fun LoginScreen(
    onCreateAccount: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val form by viewModel.loginForm.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .background(Sand)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Phokarta", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Sign in to continue", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = form.identifier,
            onValueChange = { viewModel.updateLogin(identifier = it) },
            label = { Text("Email or username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !form.loading,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.password,
            onValueChange = { viewModel.updateLogin(password = it) },
            label = { Text("Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !form.loading,
            visualTransformation = if (form.passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = viewModel::toggleLoginPasswordVisible) {
                    Icon(
                        if (form.passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = "Toggle password",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        form.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = viewModel::login,
            enabled = !form.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (form.loading) CircularProgressIndicator(Modifier.size(22.dp))
            else Text("Sign in")
        }
        TextButton(onClick = onCreateAccount, enabled = !form.loading, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Create an account")
        }
    }
}

@Composable
fun RegisterScreen(
    onHaveAccount: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val form by viewModel.registerForm.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .background(Sand)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Create account", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Join Phokarta with email and password", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = form.displayName,
            onValueChange = { viewModel.updateRegister(displayName = it) },
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !form.loading,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.username,
            onValueChange = { viewModel.updateRegister(username = it) },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !form.loading,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.email,
            onValueChange = { viewModel.updateRegister(email = it) },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !form.loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.password,
            onValueChange = { viewModel.updateRegister(password = it) },
            label = { Text("Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !form.loading,
            visualTransformation = if (form.passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = viewModel::toggleRegisterPasswordVisible) {
                    Icon(
                        if (form.passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = "Toggle password",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        form.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = viewModel::register,
            enabled = !form.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (form.loading) CircularProgressIndicator(Modifier.size(22.dp))
            else Text("Create account")
        }
        TextButton(onClick = onHaveAccount, enabled = !form.loading, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Already have an account")
        }
    }
}
