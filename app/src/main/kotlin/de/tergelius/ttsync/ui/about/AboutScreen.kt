package de.tergelius.ttsync.ui.about

import android.content.Context
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.pm.PackageInfoCompat
import de.tergelius.ttsync.BuildConfig
import de.tergelius.ttsync.R
import de.tergelius.ttsync.ui.common.Hint
import de.tergelius.ttsync.ui.common.SectionCard
import de.tergelius.ttsync.ui.common.SystemIntents
import de.tergelius.ttsync.ui.common.TtScaffold
import de.tergelius.ttsync.ui.theme.TTCalendarSyncTheme
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The source repository, shown on About once it is published (DESIGN.md section 13, open question
 * 2). Empty hides the link.
 */
const val SOURCE_URL = ""

/** About and privacy (DESIGN.md section 7.8). */
@Composable
fun AboutScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val fingerprint by
    produceState<String?>(initialValue = null, context) {
      value = withContext(Dispatchers.IO) { signingCertificateSha256(context) }
    }
  AboutContent(
    version = BuildConfig.VERSION_NAME,
    fingerprint = fingerprint,
    sourceUrl = SOURCE_URL.ifBlank { null },
    onBack = onBack,
    onOpenSource = { url -> SystemIntents.start(context, SystemIntents.browse(url)) },
  )
}

@Composable
fun AboutContent(
  version: String,
  fingerprint: String?,
  sourceUrl: String?,
  onBack: () -> Unit,
  onOpenSource: (String) -> Unit,
) {
  TtScaffold(title = stringResource(R.string.about_title), onBack = onBack) {
    SectionCard(title = stringResource(R.string.about_what_title)) {
      Text(stringResource(R.string.about_what_text))
    }
    SectionCard(title = stringResource(R.string.about_data_title)) {
      Text(stringResource(R.string.about_data_text))
    }
    SectionCard(title = stringResource(R.string.about_password_title)) {
      Text(stringResource(R.string.about_password_text))
    }
    SectionCard(title = stringResource(R.string.about_unofficial_title)) {
      Text(stringResource(R.string.about_unofficial_text))
    }
    SectionCard(title = stringResource(R.string.about_app_title)) {
      Text(stringResource(R.string.about_version, version))
      Text(stringResource(R.string.about_certificate), style = MaterialTheme.typography.titleSmall)
      SelectionContainer {
        Text(
          fingerprint ?: stringResource(R.string.about_certificate_unknown),
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
        )
      }
      Hint(stringResource(R.string.about_certificate_hint))
      Text(stringResource(R.string.about_license))
      if (sourceUrl != null) {
        OutlinedButton(onClick = { onOpenSource(sourceUrl) }) {
          Text(stringResource(R.string.about_source))
        }
      }
    }
  }
}

/** SHA-256 of the APK's signing certificate, or null if it cannot be read. */
fun signingCertificateSha256(context: Context): String? =
  runCatching {
      PackageInfoCompat.getSignatures(context.packageManager, context.packageName)
        .firstOrNull()
        ?.let {
          Fingerprint.format(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }
    }
    .getOrNull()

object Fingerprint {
  private const val HEX = "0123456789ABCDEF"

  /** `AB:CD:…`, the form `apksigner verify --print-certs` and the README use. */
  fun format(bytes: ByteArray): String =
    bytes.joinToString(":") { byte ->
      val v = byte.toInt() and 0xFF
      "${HEX[v shr 4]}${HEX[v and 0x0F]}"
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutPreview() {
  TTCalendarSyncTheme {
    AboutContent(
      version = "0.1.0",
      fingerprint = Fingerprint.format(ByteArray(32) { it.toByte() }),
      sourceUrl = null,
      onBack = {},
      onOpenSource = {},
    )
  }
}
