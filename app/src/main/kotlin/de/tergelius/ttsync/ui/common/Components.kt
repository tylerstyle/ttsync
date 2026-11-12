package de.tergelius.ttsync.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.tergelius.ttsync.R

/** Maximum content width, so screens stay readable on tablets and in landscape. */
private val MaxContentWidth = 640.dp

/**
 * The frame of every screen: a top app bar (with a back arrow if [onBack] is set), edge-to-edge
 * insets (`safeDrawing`, which includes the keyboard), an optional [bottomBar], and scrolling
 * content of limited width.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtScaffold(
  title: String,
  onBack: (() -> Unit)?,
  modifier: Modifier = Modifier,
  actions: @Composable RowScope.() -> Unit = {},
  snackbarHostState: SnackbarHostState? = null,
  bottomBar: @Composable () -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = {
          if (onBack != null) {
            IconButton(onClick = onBack) {
              Icon(
                painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.action_back),
              )
            }
          }
        },
        actions = actions,
      )
    },
    bottomBar = bottomBar,
    snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
    contentWindowInsets = WindowInsets.safeDrawing,
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .consumeWindowInsets(innerPadding)
          .verticalScroll(rememberScrollState()),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier =
          Modifier.widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
      )
    }
  }
}

/** Buttons pinned to the bottom of a screen, above the navigation bar and the keyboard. */
@Composable
fun BottomActionBar(content: @Composable RowScope.() -> Unit) {
  Surface(tonalElevation = 3.dp) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Row(
        modifier =
          Modifier.widthIn(max = MaxContentWidth)
            .fillMaxWidth()
            .windowInsetsPadding(
              WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
      )
    }
  }
}

/** A titled group of content. */
@Composable
fun SectionCard(
  modifier: Modifier = Modifier,
  title: String? = null,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = containerColor),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (title != null) {
        Text(
          title,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.semantics { heading() },
        )
      }
      content()
    }
  }
}

/** Visual state of a checklist row or a warning. */
enum class Tone {
  OK,
  PROBLEM,
  UNKNOWN,
  INFO,
}

@Composable
fun ToneIcon(tone: Tone, modifier: Modifier = Modifier) {
  @DrawableRes
  val icon =
    when (tone) {
      Tone.OK -> R.drawable.ic_check_circle
      Tone.PROBLEM -> R.drawable.ic_error
      Tone.UNKNOWN -> R.drawable.ic_help
      Tone.INFO -> R.drawable.ic_info
    }
  val tint =
    when (tone) {
      Tone.OK -> MaterialTheme.colorScheme.primary
      Tone.PROBLEM -> MaterialTheme.colorScheme.error
      Tone.UNKNOWN,
      Tone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
  val description =
    when (tone) {
      Tone.OK -> stringResource(R.string.check_state_ok)
      Tone.PROBLEM -> stringResource(R.string.check_state_problem)
      Tone.UNKNOWN -> stringResource(R.string.check_state_unknown)
      Tone.INFO -> stringResource(R.string.check_state_info)
    }
  Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = modifier)
}

/**
 * One row with a state icon, a title, an explanation and an optional action, used by the
 * reliability checklist, the "Keep running" step and the Status warnings.
 */
@Composable
fun CheckRow(
  tone: Tone,
  title: String,
  modifier: Modifier = Modifier,
  detail: String? = null,
  action: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    ToneIcon(tone, modifier = Modifier.padding(top = 2.dp).size(24.dp))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      if (detail != null) {
        Text(
          detail,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (action != null) action()
    }
  }
}

@Composable fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

/** Body text with the default secondary colour. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    modifier = modifier,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** A centered spinner for screens that are still loading. */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
  }
}

/** An error text with a retry button. */
@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
  SectionCard(modifier = modifier, containerColor = MaterialTheme.colorScheme.errorContainer) {
    Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
    if (onRetry != null) {
      TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
  }
}

/** A yes/no question before something destructive. */
@Composable
fun ConfirmDialog(
  title: String,
  text: String,
  confirmLabel: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  busy: Boolean = false,
  extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text(title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text)
        extra?.invoke(this)
        if (busy) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.working))
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onConfirm, enabled = !busy) { Text(confirmLabel) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, enabled = !busy) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}

/** A message with a single OK button. */
@Composable
fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(text) },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
    },
  )
}
