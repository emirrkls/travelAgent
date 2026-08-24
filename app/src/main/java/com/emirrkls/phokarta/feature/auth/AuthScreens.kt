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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emirrkls.phokarta.R
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
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.auth_sign_in_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = form.identifier,
            onValueChange = { viewModel.updateLogin(identifier = it) },
            label = { Text(stringResource(R.string.auth_email_or_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !form.loading,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.password,
            onValueChange = { viewModel.updateLogin(password = it) },
            label = { Text(stringResource(R.string.auth_password)) },
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
                        contentDescription = stringResource(R.string.a11y_toggle_password),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        form.error?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Text(stringResource(msg), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = viewModel::login,
            enabled = !form.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (form.loading) CircularProgressIndicator(Modifier.size(22.dp))
            else Text(stringResource(R.string.auth_sign_in))
        }
        TextButton(onClick = onCreateAccount, enabled = !form.loading, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.auth_create_account_link))
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
        Text(stringResource(R.string.auth_register), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.auth_register_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = form.displayName,
            onValueChange = { viewModel.updateRegister(displayName = it) },
            label = { Text(stringResource(R.string.auth_display_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !form.loading,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.username,
            onValueChange = { viewModel.updateRegister(username = it) },
            label = { Text(stringResource(R.string.auth_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !form.loading,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.email,
            onValueChange = { viewModel.updateRegister(email = it) },
            label = { Text(stringResource(R.string.auth_email)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !form.loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.password,
            onValueChange = { viewModel.updateRegister(password = it) },
            label = { Text(stringResource(R.string.auth_password)) },
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
                        contentDescription = stringResource(R.string.a11y_toggle_password),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        form.error?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Text(stringResource(msg), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = viewModel::register,
            enabled = !form.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (form.loading) CircularProgressIndicator(Modifier.size(22.dp))
            else Text(stringResource(R.string.auth_register))
        }
        TextButton(onClick = onHaveAccount, enabled = !form.loading, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.auth_already_have_account))
        }
    }
}
