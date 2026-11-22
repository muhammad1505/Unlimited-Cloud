package com.unlimitedcloud.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.unlimitedcloud.auth.AuthUiState
import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.cloud.CloudChannelManager
import com.unlimitedcloud.cloud.CloudChannelState
import org.koin.compose.koinInject

@Composable
fun AuthScreen() {
    val engine = koinInject<TelegramEngine>()
    val state by engine.authState.collectAsState()

    LaunchedEffect(Unit) { engine.start() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Unlimited Cloud",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Unlimited storage on top of Telegram",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))

        when (val current = state) {
            AuthUiState.Initializing -> CircularProgressIndicator()
            AuthUiState.WaitingForPhoneNumber -> PhoneForm(engine)
            is AuthUiState.WaitingForCode -> CodeForm(current, engine)
            AuthUiState.WaitingForRegistration -> RegistrationForm(engine)
            is AuthUiState.WaitingForPassword -> PasswordForm(current, engine)
            is AuthUiState.Ready -> ReadySection(current)
            is AuthUiState.Failure -> Text(
                text = current.message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )

            AuthUiState.LoggingOut -> CircularProgressIndicator()
            AuthUiState.Closed -> Text("Sesi ditutup")
        }
    }
}

@Composable
private fun ReadySection(state: AuthUiState.Ready) {
    val channelManager = koinInject<CloudChannelManager>()
    val channelState by channelManager.channelState.collectAsState()
    var retry by remember { mutableStateOf(0) }

    LaunchedEffect(retry) { channelManager.ensureRootChannel() }

    Text(
        text = "Masuk sebagai user ${state.userId}",
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )

    when (val channel = channelState) {
        CloudChannelState.Idle, CloudChannelState.Resolving -> {
            CircularProgressIndicator()
            Text("Menyiapkan penyimpanan cloud…", style = MaterialTheme.typography.bodySmall)
        }

        is CloudChannelState.Ready -> Text(
            text = "Penyimpanan siap (channel ${channel.chatId})",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        is CloudChannelState.Failed -> {
            Text(
                text = channel.message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Button(onClick = { retry++ }) { Text("Coba lagi") }
        }
    }
}

@Composable
private fun PhoneForm(engine: TelegramEngine) {
    var phone by remember { mutableStateOf("") }

    Text("Masuk dengan nomor Telegram", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text("Nomor telepon (+62...)") },
        singleLine = true,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    )
    Button(onClick = { engine.submitPhoneNumber(phone) }, enabled = phone.isNotBlank()) {
        Text("Kirim kode")
    }
}

@Composable
private fun CodeForm(state: AuthUiState.WaitingForCode, engine: TelegramEngine) {
    var code by remember { mutableStateOf("") }

    Text("Masukkan kode verifikasi", style = MaterialTheme.typography.titleMedium)
    if (state.codeLength > 0) {
        Text("Kode terdiri dari ${state.codeLength} digit", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("Kode") },
        singleLine = true,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    )
    Button(onClick = { engine.submitCode(code) }, enabled = code.isNotBlank()) {
        Text("Verifikasi")
    }
}

@Composable
private fun RegistrationForm(engine: TelegramEngine) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }

    Text("Nomor ini belum punya akun Telegram", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = firstName,
        onValueChange = { firstName = it },
        label = { Text("Nama depan") },
        singleLine = true,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    )
    OutlinedTextField(
        value = lastName,
        onValueChange = { lastName = it },
        label = { Text("Nama belakang (opsional)") },
        singleLine = true,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    )
    Button(
        onClick = { engine.submitRegistration(firstName, lastName) },
        enabled = firstName.isNotBlank(),
    ) {
        Text("Buat akun")
    }
}

@Composable
private fun PasswordForm(state: AuthUiState.WaitingForPassword, engine: TelegramEngine) {
    var password by remember { mutableStateOf("") }

    Text("Akun ini memakai verifikasi dua langkah", style = MaterialTheme.typography.titleMedium)
    if (state.hint.isNotBlank()) {
        Text("Petunjuk: ${state.hint}", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    )
    Button(onClick = { engine.submitPassword(password) }, enabled = password.isNotBlank()) {
        Text("Masuk")
    }
}
