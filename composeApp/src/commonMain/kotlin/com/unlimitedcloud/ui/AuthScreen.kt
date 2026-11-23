package com.unlimitedcloud.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unlimitedcloud.auth.AuthUiState
import com.unlimitedcloud.auth.TelegramEngine
import com.unlimitedcloud.ui.auth.CountryCode
import com.unlimitedcloud.ui.auth.CountryCodes
import com.unlimitedcloud.ui.auth.DefaultCountry
import com.unlimitedcloud.ui.auth.isValidPhone
import com.unlimitedcloud.ui.auth.normalizePhone
import com.unlimitedcloud.ui.components.AppIcons
import com.unlimitedcloud.ui.components.AppTextField
import com.unlimitedcloud.ui.components.BrandMark
import com.unlimitedcloud.ui.components.GradientButton
import com.unlimitedcloud.ui.components.InfoBanner
import com.unlimitedcloud.ui.components.PasswordField
import com.unlimitedcloud.ui.theme.BrandGradient
import com.unlimitedcloud.ui.theme.BrandGradientDeep
import org.koin.compose.koinInject

@Composable
fun AuthScreen() {
    val engine = koinInject<TelegramEngine>()
    val state by engine.authState.collectAsState()
    val error by engine.errorMessage.collectAsState()
    val busy by engine.busy.collectAsState()
    var submittedPhone by remember { mutableStateOf("") }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 980.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        ) {
            AuthBackdrop()

            if (wide) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    HeroPanel(modifier = Modifier.weight(1.05f).fillMaxHeight())
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AuthCard(
                            state = state,
                            error = error,
                            busy = busy,
                            submittedPhone = submittedPhone,
                            engine = engine,
                            onPhoneSubmitted = { submittedPhone = it },
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .imePadding()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CompactBrandHeader()
                    Spacer(Modifier.height(22.dp))
                    AuthCard(
                        state = state,
                        error = error,
                        busy = busy,
                        submittedPhone = submittedPhone,
                        engine = engine,
                        onPhoneSubmitted = { submittedPhone = it },
                    )
                    Spacer(Modifier.height(22.dp))
                    AuthFooter()
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun AuthCard(
    state: AuthUiState,
    error: String?,
    busy: Boolean,
    submittedPhone: String,
    engine: TelegramEngine,
    onPhoneSubmitted: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
        ),
        shadowElevation = 20.dp,
    ) {
        Column(modifier = Modifier.padding(26.dp)) {
            when (val current = state) {
                AuthUiState.Initializing -> LoadingContent("Menyiapkan mesin Telegram…")

                AuthUiState.WaitingForPhoneNumber -> PhoneForm(
                    engine = engine,
                    error = error,
                    busy = busy,
                    onDismissError = engine::clearError,
                    onSubmitted = onPhoneSubmitted,
                )

                is AuthUiState.WaitingForCode -> CodeForm(
                    state = current,
                    engine = engine,
                    submittedPhone = submittedPhone,
                    error = error,
                    busy = busy,
                    onDismissError = engine::clearError,
                )

                AuthUiState.WaitingForRegistration -> RegistrationForm(
                    engine = engine,
                    error = error,
                    busy = busy,
                    onDismissError = engine::clearError,
                )

                is AuthUiState.WaitingForPassword -> PasswordForm(
                    state = current,
                    engine = engine,
                    error = error,
                    busy = busy,
                    onDismissError = engine::clearError,
                )

                is AuthUiState.Ready -> LoadingContent("Menyiapkan ruang kerja…")

                is AuthUiState.Failure -> FailureContent(current.message)

                AuthUiState.LoggingOut -> LoadingContent("Keluar dari akun…")

                AuthUiState.Closed -> ClosedContent()
            }
        }
    }
}

@Composable
private fun PhoneForm(
    engine: TelegramEngine,
    error: String?,
    busy: Boolean,
    onDismissError: () -> Unit,
    onSubmitted: (String) -> Unit,
) {
    var country by remember { mutableStateOf(DefaultCountry) }
    var number by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val valid = isValidPhone(country, number)

    AuthStepLayout(
        icon = AppIcons.Phone,
        title = "Masuk ke Unlimited Cloud",
        subtitle = "Gunakan nomor Telegram yang aktif. Kode verifikasi dikirim lewat Telegram.",
        error = error,
        onDismissError = onDismissError,
    ) {
        PhoneNumberField(
            country = country,
            number = number,
            onNumberChange = { raw ->
                number = raw.filter { it.isDigit() || it == ' ' || it == '-' }.take(18)
                attempted = false
            },
            onCountryClick = { showPicker = true },
            enabled = !busy,
            isError = attempted && !valid,
        )

        if (attempted && !valid) {
            Text(
                text = "Nomor belum valid. Periksa panjang nomor dan kode negara.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        GradientButton(
            text = "Kirim kode",
            onClick = {
                attempted = true
                if (valid) {
                    val normalized = normalizePhone(country, number)
                    engine.clearError()
                    onSubmitted(normalized)
                    engine.submitPhoneNumber(normalized)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            loading = busy,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = AppIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = "Nomor hanya dipakai untuk login Telegram, bukan disimpan di server kami.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showPicker) {
        CountryPickerDialog(
            selected = country,
            onDismiss = { showPicker = false },
            onSelect = {
                country = it
                showPicker = false
            },
        )
    }
}

@Composable
private fun PhoneNumberField(
    country: CountryCode,
    number: String,
    onNumberChange: (String) -> Unit,
    onCountryClick: () -> Unit,
    enabled: Boolean,
    isError: Boolean,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        isError -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .clickable(enabled = enabled, onClick = onCountryClick)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = country.flag, fontSize = 18.sp)
                Text(
                    text = country.dialCode,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = AppIcons.ChevronDown,
                    contentDescription = "Pilih kode negara",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            BasicTextField(
                value = number,
                onValueChange = onNumberChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
                    .onFocusChanged { focused = it.isFocused },
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (number.isEmpty()) {
                            Text(
                                text = "812 3456 7890",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
private fun CountryPickerDialog(
    selected: CountryCode,
    onDismiss: () -> Unit,
    onSelect: (CountryCode) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            CountryCodes
        } else {
            CountryCodes.filter { country ->
                country.name.lowercase().contains(needle) ||
                    country.dialCode.contains(needle) ||
                    country.iso.lowercase().contains(needle)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(20.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 24.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Kode negara", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Pilih negara nomor Telegram",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(AppIcons.Close, contentDescription = "Tutup")
                    }
                }

                AppTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Cari negara atau kode",
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(
                            imageVector = AppIcons.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.iso }) { country ->
                        CountryRow(
                            country = country,
                            selected = country == selected,
                            onClick = { onSelect(country) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryRow(
    country: CountryCode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = country.flag, fontSize = 20.sp)
        Text(
            text = country.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = country.dialCode,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun CodeForm(
    state: AuthUiState.WaitingForCode,
    engine: TelegramEngine,
    submittedPhone: String,
    error: String?,
    busy: Boolean,
    onDismissError: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val maxLength = if (state.codeLength > 0) state.codeLength else 8
    val submit = {
        if (code.isNotBlank() && !busy) {
            engine.clearError()
            engine.submitCode(code)
        }
    }

    AuthStepLayout(
        icon = AppIcons.Shield,
        title = "Cek kode verifikasi",
        subtitle = if (submittedPhone.isBlank()) {
            "Masukkan kode yang dikirim Telegram."
        } else {
            "Kode dikirim ke $submittedPhone."
        },
        error = error,
        onDismissError = onDismissError,
    ) {
        AppTextField(
            value = code,
            onValueChange = { raw -> code = raw.filter { it.isDigit() }.take(maxLength) },
            label = "Kode verifikasi",
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 6.sp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            supportingText = if (state.codeLength > 0) {
                "Kode terdiri dari ${state.codeLength} digit"
            } else {
                null
            },
        )

        GradientButton(
            text = "Verifikasi",
            onClick = submit,
            modifier = Modifier.fillMaxWidth(),
            enabled = code.isNotBlank() && !busy,
            loading = busy,
        )

        Text(
            text = "Belum menerima kode? Tunggu beberapa saat, lalu periksa aplikasi Telegram Anda.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RegistrationForm(
    engine: TelegramEngine,
    error: String?,
    busy: Boolean,
    onDismissError: () -> Unit,
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }

    AuthStepLayout(
        icon = AppIcons.Person,
        title = "Lengkapi profil",
        subtitle = "Nomor ini belum punya akun Telegram. Buat profil singkat untuk melanjutkan.",
        error = error,
        onDismissError = onDismissError,
    ) {
        AppTextField(
            value = firstName,
            onValueChange = { firstName = it },
            label = "Nama depan",
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        AppTextField(
            value = lastName,
            onValueChange = { lastName = it },
            label = "Nama belakang (opsional)",
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        GradientButton(
            text = "Buat akun",
            onClick = {
                engine.clearError()
                engine.submitRegistration(firstName, lastName)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = firstName.isNotBlank() && !busy,
            loading = busy,
        )
    }
}

@Composable
private fun PasswordForm(
    state: AuthUiState.WaitingForPassword,
    engine: TelegramEngine,
    error: String?,
    busy: Boolean,
    onDismissError: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val submit = {
        if (password.isNotBlank() && !busy) {
            engine.clearError()
            engine.submitPassword(password)
        }
    }

    AuthStepLayout(
        icon = AppIcons.Lock,
        title = "Verifikasi dua langkah",
        subtitle = "Akun ini dilindungi password tambahan dari Telegram.",
        error = error,
        onDismissError = onDismissError,
    ) {
        if (state.hint.isNotBlank()) {
            InfoBanner(message = "Petunjuk password: ${state.hint}")
        }

        PasswordField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Password dua langkah",
            enabled = !busy,
            onDone = submit,
        )

        GradientButton(
            text = "Masuk",
            onClick = submit,
            modifier = Modifier.fillMaxWidth(),
            enabled = password.isNotBlank() && !busy,
            loading = busy,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = AppIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = "Password dikirim langsung ke Telegram dan tidak pernah disimpan aplikasi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuthStepLayout(
    icon: ImageVector,
    title: String,
    subtitle: String,
    error: String?,
    onDismissError: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(BrandGradient)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(visible = error != null) {
            error?.let {
                InfoBanner(
                    message = it,
                    isError = true,
                    onDismiss = onDismissError,
                )
            }
        }

        content()
    }
}

@Composable
private fun LoadingContent(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandMark(size = 64.dp)
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 3.dp,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FailureContent(message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
        }
        Text("Tidak bisa terhubung", style = MaterialTheme.typography.titleLarge)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoBanner(
            message = "Periksa konfigurasi TELEGRAM_API_ID dan TELEGRAM_API_HASH, lalu build ulang aplikasi.",
            isError = true,
        )
    }
}

@Composable
private fun ClosedContent() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Text("Sesi Telegram ditutup", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Tutup lalu buka kembali aplikasi untuk masuk dengan akun lain.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompactBrandHeader() {
    Row(
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandMark(size = 48.dp)
        Column {
            Text("Unlimited Cloud", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Penyimpanan tanpa batas di Telegram",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuthFooter() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = AppIcons.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = "Data tetap di akun Telegram Anda",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeroPanel(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Brush.linearGradient(BrandGradientDeep)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width * 0.15f, size.height * 0.08f),
                    radius = size.minDimension * 0.8f,
                ),
                radius = size.minDimension * 0.8f,
                center = Offset(size.width * 0.15f, size.height * 0.08f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF06B6D4).copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.9f, size.height * 0.92f),
                    radius = size.minDimension * 0.9f,
                ),
                radius = size.minDimension * 0.9f,
                center = Offset(size.width * 0.9f, size.height * 0.92f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(56.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BrandMark(size = 52.dp)
                Column {
                    Text(
                        text = "Unlimited Cloud",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    Text(
                        text = "Private cloud di Telegram",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.62f),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = "Penyimpanan tanpa batas,\ndi dalam Telegram.",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Simpan berkas apa pun ke grup topik pribadi di Telegram. " +
                    "Tanpa langganan, tanpa batas kuota, dan tetap bisa diakses dari Android maupun Desktop.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.70f),
                modifier = Modifier.widthIn(max = 460.dp),
            )

            Spacer(Modifier.height(30.dp))

            HeroFeature(AppIcons.Cloud, "Kapasitas tanpa batas", "Selama akun Telegram Anda aktif.")
            HeroFeature(AppIcons.Shield, "Channel pribadi", "Berkas hanya ada di akun Anda sendiri.")
            HeroFeature(AppIcons.Transfer, "Android & Desktop", "Satu ruang kerja di semua perangkat.")

            Spacer(Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Lock,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Didukung TDLib · Data tidak melewati server pihak ketiga",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.45f),
                )
            }
        }
    }
}

@Composable
private fun HeroFeature(icon: ImageVector, title: String, description: String) {
    Row(
        modifier = Modifier.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFFA5F3FC),
                modifier = Modifier.size(19.dp),
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun AuthBackdrop() {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primary.copy(alpha = 0.10f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.08f, size.height * 0.04f),
                radius = size.minDimension * 0.65f,
            ),
            radius = size.minDimension * 0.65f,
            center = Offset(size.width * 0.08f, size.height * 0.04f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF06B6D4).copy(alpha = 0.08f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.95f, size.height * 0.95f),
                radius = size.minDimension * 0.75f,
            ),
            radius = size.minDimension * 0.75f,
            center = Offset(size.width * 0.95f, size.height * 0.95f),
        )
    }
}
