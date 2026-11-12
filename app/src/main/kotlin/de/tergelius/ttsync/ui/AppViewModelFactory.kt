package de.tergelius.ttsync.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.tergelius.ttsync.AppGraph
import de.tergelius.ttsync.ui.login.LoginViewModel
import de.tergelius.ttsync.ui.reliability.KeepRunningViewModel
import de.tergelius.ttsync.ui.reliability.ReliabilityViewModel
import de.tergelius.ttsync.ui.settings.SettingsViewModel
import de.tergelius.ttsync.ui.settings.SignOutViewModel
import de.tergelius.ttsync.ui.sources.SourcesViewModel
import de.tergelius.ttsync.ui.status.StatusViewModel
import de.tergelius.ttsync.ui.target.TargetViewModel

/**
 * Creates every ViewModel of the app from the [AppGraph] (manual DI). Provided to the screens
 * through [LocalViewModelFactory]; tests construct the ViewModels directly with fakes.
 */
fun appViewModelFactory(graph: AppGraph, context: Context): ViewModelProvider.Factory {
  val appContext = context.applicationContext
  val deviceChecks: DeviceChecks by lazy { AndroidDeviceChecks(appContext) }
  val actions: UserActions by lazy {
    UserActions(
      client = graph.timeTreeClient,
      credentials = graph.credentialStore,
      settings = graph.settingsRepository,
      status = graph.statusRepository,
      engine = graph.syncEngine,
      scheduler = graph.syncScheduler,
      gateway = graph.calendarGateway,
      notifier = graph.syncNotifier,
      wipeLocalFiles = {
        graph.syncDatabase.wipe()
        graph.rawResponseSink.wipe()
      },
    )
  }
  return viewModelFactory {
    initializer { MainViewModel(actions) }
    initializer { LoginViewModel(actions) }
    initializer { SourcesViewModel(graph.settingsRepository, actions) }
    initializer {
      TargetViewModel(graph.calendarGateway, graph.settingsRepository, deviceChecks, actions)
    }
    initializer { KeepRunningViewModel(deviceChecks, actions) }
    initializer {
      StatusViewModel(
        settings = graph.settingsRepository,
        status = graph.statusRepository,
        scheduler = graph.syncScheduler,
        gateway = graph.calendarGateway,
        deviceChecks = deviceChecks,
        actions = actions,
      )
    }
    initializer {
      ReliabilityViewModel(
        deviceChecks,
        graph.settingsRepository,
        graph.calendarGateway,
        graph.statusRepository,
      )
    }
    initializer { SettingsViewModel(graph.settingsRepository, actions) }
    initializer { SignOutViewModel(graph.settingsRepository, actions) }
  }
}

/** The factory for `viewModel(factory = LocalViewModelFactory.current)` in every screen. */
val LocalViewModelFactory =
  staticCompositionLocalOf<ViewModelProvider.Factory> {
    error("LocalViewModelFactory is not provided; MainActivity provides AppGraph.viewModelFactory")
  }
