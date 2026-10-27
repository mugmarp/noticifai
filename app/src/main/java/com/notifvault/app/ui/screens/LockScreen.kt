package com.notifvault.app.ui.screens

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.notifvault.app.ui.VaultViewModel

/**
 * The gate in front of the whole app. Shown instead of the navigation host
 * whenever a lock is configured and the session has not been unlocked yet.
 */
@Composable
fun LockScreen(
    viewModel: VaultViewModel,
    hasPin: Boolean,
    biometricEnabled: Boolean,
    onUnlocked: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val canUseBiometrics = biometricEnabled && activity != null &&
        remember { viewModel.hasBiometricsAvailable() }

    fun attemptBiometric() {
        val host = activity ?: return
        val prompt = BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(host),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // The user cancelling is not an error worth showing.
                    if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                        code != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        error = true
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Notification Vault")
            .setSubtitle("Confirm it's you to see your history")
            .setAllowedAuthenticators(
                BiometricManagerCompat.AUTHENTICATORS,
            )
            .build()
        prompt.authenticate(info)
    }

    LaunchedEffect(Unit) {
        if (canUseBiometrics) attemptBiometric()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Notification Vault is locked",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Text(
                text = "Enter your PIN to continue",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PinDots(
                length = pin.length,
                hasError = error,
                modifier = Modifier.padding(vertical = 24.dp),
            )

            Keypad(
                onDigit = { if (pin.length < 8) { pin += it; error = false } },
                onBackspace = {
                    if (pin.isNotEmpty()) pin = pin.dropLast(1)
                    error = false
                },
                onSubmit = {
                    if (viewModel.verifyPin(pin)) {
                        onUnlocked()
                    } else {
                        error = true
                        pin = ""
                    }
                },
                submitEnabled = pin.isNotEmpty() && hasPin,
            )

            if (canUseBiometrics) {
                TextButton(onClick = { attemptBiometric() }) {
                    Icon(
                        imageVector = Icons.Filled.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text("  Use biometrics")
                }
            }

            if (error) {
                Text(
                    text = "That PIN did not match. Try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun PinDots(length: Int, hasError: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(6) { index ->
            val filled = index < length
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            hasError -> MaterialTheme.colorScheme.error
                            filled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
            )
        }
    }
}

@Composable
private fun Keypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    submitEnabled: Boolean,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { digit -> KeypadKey(digit) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(72.dp))
            KeypadKey("0") { onDigit("0") }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBackspace),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Delete",
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Button(
            onClick = onSubmit,
            enabled = submitEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) { Text("Unlock") }
    }
}

@Composable
private fun KeypadKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Authenticator constants kept in one place so the prompt cannot drift. */
private object BiometricManagerCompat {
    val AUTHENTICATORS =
        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
}
