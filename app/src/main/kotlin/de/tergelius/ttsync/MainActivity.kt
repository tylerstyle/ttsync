package de.tergelius.ttsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import de.tergelius.ttsync.ui.LocalViewModelFactory
import de.tergelius.ttsync.ui.theme.TTCalendarSyncTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    val factory = appGraph.viewModelFactory
    setContent {
      TTCalendarSyncTheme {
        CompositionLocalProvider(LocalViewModelFactory provides factory) {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppRoot()
          }
        }
      }
    }
  }
}
