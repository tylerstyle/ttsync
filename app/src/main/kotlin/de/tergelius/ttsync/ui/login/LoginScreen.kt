package de.tergelius.ttsync.ui.login

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tergelius.ttsync.R
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.ui.LocalViewModelFactory
import de.tergelius.ttsync.ui.common.ErrorBox
import de.tergelius.ttsync.ui.common.Hint
import de.tergelius.ttsync.ui.common.SectionCard
import de.tergelius.ttsync.ui.common.TtScaffold
import de.tergelius.ttsync.ui.theme.TTCalendarSyncTheme

/**
 * Login (DESIGN.md section 7.1). [reauth] is true when Status sends the user here because the
 * stored sign-in stopped working; then there is a back arrow.
 */
@Composable
fun LoginScreen(
  reauth: Boolean,
  onSignedIn: (SetupStep) -> Unit,
  onBack: (() -> Unit)?,
  viewModel: LoginViewModel = viewModel(factory = LocalViewModelFactory.current),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(state.next) {
    state.next?.let {
      viewModel.onNavigated()
      onSignedIn(it)
    }
  }
  LoginContent(
    state = state,
    reauth = reauth,
    onBack = onBack,
    onEmailChange = viewModel::onEmailChange,
    onPasswordChange = viewModel::onPasswordChange,
    onTogglePassword = viewModel::togglePasswordVisible,
    onSubmit = viewModel::submit,
  )
}

@Composable
fun LoginContent(
  state: LoginUiState,
  reauth: Boolean,
  onBack: (() -> Unit)?,
  onEmailChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit,
  onTogglePassword: () -> Unit,
  onSubmit: () -> Unit,
) {
  TtScaffold(
    title = stringResource(if (reauth) R.string.login_title_again else R.string.login_title),
    onBack = onBack,
  ) {
    Text(
      stringResource(if (reauth) R.string.login_intro_again else R.string.login_intro),
      style = MaterialTheme.typography.bodyLarge,
    )
    Hint(stringResource(R.string.login_privacy))
    SectionCard(title = stringResource(R.string.login_social_title)) {
      Text(stringResource(R.string.login_social_text), style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedTextField(
      value = state.email,
      onValueChange = onEmailChange,
      label = { Text(stringResource(R.string.login_email)) },
      singleLine = true,
      enabled = !state.busy,
      keyboardOptions =
        KeyboardOptions(
          keyboardType = KeyboardType.Email,
          imeAction = ImeAction.Next,
          autoCorrectEnabled = false,
        ),
      modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
    )
    OutlinedTextField(
      value = state.password,
      onValueChange = onPasswordChange,
      label = { Text(stringResource(R.string.login_password)) },
      singleLine = true,
      enabled = !state.busy,
      visualTransformation =
        if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
      keyboardOptions =
        KeyboardOptions(
          keyboardType = KeyboardType.Password,
          imeAction = ImeAction.Done,
          autoCorrectEnabled = false,
        ),
      keyboardActions = KeyboardActions(onDone = { onSubmit() }),
      trailingIcon = {
        IconButton(onClick = onTogglePassword) {
          Icon(
            painterResource(
              if (state.passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
            ),
            contentDescription =
              stringResource(
                if (state.passwordVisible) R.string.login_hide_password
                else R.string.login_show_password
              ),
          )
        }
      },
      modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
    )
    state.error?.let { error ->
      ErrorBox(message = loginErrorText(error, state.retryAfterMinutes), onRetry = null)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Button(onClick = onSubmit, enabled = state.canSubmit) {
        Text(stringResource(R.string.login_submit))
      }
      if (state.busy) {
        Spacer(Modifier.size(16.dp))
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
      }
    }
  }
}

@Composable
private fun loginErrorText(error: LoginError, retryAfterMinutes: Long?): String =
  when (error) {
    LoginError.REJECTED -> stringResource(R.string.login_error_rejected)
    LoginError.RATE_LIMITED -> {
      val minutes = (retryAfterMinutes ?: 30L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
      pluralStringResource(R.plurals.login_error_rate_limited, minutes, minutes)
    }
    LoginError.OFFLINE -> stringResource(R.string.login_error_offline)
    LoginError.API_CHANGED -> stringResource(R.string.error_api_changed)
    LoginError.UNKNOWN -> stringResource(R.string.error_unknown)
  }

@Preview(showBackground = true)
@Composable
private fun LoginPreview() {
  TTCalendarSyncTheme {
    LoginContent(
      state = LoginUiState(email = "me@example.org", error = LoginError.REJECTED),
      reauth = false,
      onBack = null,
      onEmailChange = {},
      onPasswordChange = {},
      onTogglePassword = {},
      onSubmit = {},
    )
  }
}
