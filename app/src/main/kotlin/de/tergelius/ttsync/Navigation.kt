package de.tergelius.ttsync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import de.tergelius.ttsync.settings.SetupStep
import de.tergelius.ttsync.ui.LocalViewModelFactory
import de.tergelius.ttsync.ui.MainViewModel
import de.tergelius.ttsync.ui.about.AboutScreen
import de.tergelius.ttsync.ui.login.LoginScreen
import de.tergelius.ttsync.ui.reliability.KeepRunningScreen
import de.tergelius.ttsync.ui.reliability.ReliabilityScreen
import de.tergelius.ttsync.ui.settings.SettingsNavigation
import de.tergelius.ttsync.ui.settings.SettingsScreen
import de.tergelius.ttsync.ui.sources.SourcesScreen
import de.tergelius.ttsync.ui.status.StatusNavigation
import de.tergelius.ttsync.ui.status.StatusScreen
import de.tergelius.ttsync.ui.target.TargetScreen

/** Waits for the start step (which follows setup progress), then shows the navigation. */
@Composable
fun AppRoot(main: MainViewModel = viewModel(factory = LocalViewModelFactory.current)) {
  val startStep by main.startStep.collectAsStateWithLifecycle()
  val step = startStep
  if (step == null) {
    Box(Modifier.fillMaxSize())
  } else {
    MainNavigation(step)
  }
}

/** Navigation shell (DESIGN.md section 7): Login → Sources → Target → Keep running → Status. */
@Composable
fun MainNavigation(startStep: SetupStep) {
  val backStack = rememberNavBackStack(startStep.route())
  val navigator = remember(backStack) { AppNavigator(backStack) }
  val back: (() -> Unit)? = if (navigator.canGoBack) navigator::back else null

  NavDisplay(
    backStack = backStack,
    onBack = navigator::back,
    entryDecorators =
      listOf(
        rememberSaveableStateHolderNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator(),
      ),
    entryProvider =
      entryProvider {
        entry<LoginRoute> { key ->
          LoginScreen(
            reauth = key.reauth,
            onSignedIn = { navigator.onSetupProgress(key, it) },
            onBack = if (key.reauth) back else null,
          )
        }
        entry<SourcesRoute> { key ->
          SourcesScreen(
            setup = key.setup,
            onDone = { navigator.onSetupProgress(key, it) },
            onBack = back,
            onSignInAgain = { navigator.push(LoginRoute(reauth = true)) },
            onSignedOut = navigator::signedOut,
          )
        }
        entry<TargetRoute> { key ->
          TargetScreen(
            setup = key.setup,
            onDone = { navigator.onSetupProgress(key, it) },
            onBack = back,
            onSignedOut = navigator::signedOut,
          )
        }
        entry<KeepRunningRoute> { key ->
          KeepRunningScreen(
            onDone = { navigator.onSetupProgress(key, it) },
            onBack = back,
            onSignedOut = navigator::signedOut,
          )
        }
        entry<StatusRoute> {
          StatusScreen(
            navigation =
              StatusNavigation(
                openSettings = { navigator.push(SettingsRoute) },
                openReliability = { navigator.push(ReliabilityRoute) },
                chooseTarget = { navigator.push(TargetRoute(setup = false)) },
                signInAgain = { navigator.push(LoginRoute(reauth = true)) },
                continueSetup = { navigator.push(it.route()) },
              )
          )
        }
        entry<ReliabilityRoute> { ReliabilityScreen(onBack = navigator::back) }
        entry<SettingsRoute> {
          SettingsScreen(
            navigation =
              SettingsNavigation(
                back = navigator::back,
                sources = { navigator.push(SourcesRoute(setup = false)) },
                target = { navigator.push(TargetRoute(setup = false)) },
                reliability = { navigator.push(ReliabilityRoute) },
                about = { navigator.push(AboutRoute) },
                continueSetup = navigator::resetToStep,
                signedOut = navigator::signedOut,
              )
          )
        }
        entry<AboutRoute> { AboutScreen(onBack = navigator::back) }
      },
  )
}

/**
 * Back stack operations. Works on any [MutableList] (the Nav3 back stack in the app, a plain list
 * in tests); the stack is never left empty.
 */
class AppNavigator(private val backStack: MutableList<NavKey>) {
  val canGoBack: Boolean
    get() = backStack.size > 1

  fun push(key: NavKey) {
    if (backStack.lastOrNull() != key) backStack.add(key)
  }

  fun back() {
    if (canGoBack) backStack.removeAt(backStack.lastIndex)
  }

  /** Replaces the whole stack with [key]. */
  fun resetTo(key: NavKey) {
    backStack.add(key)
    while (backStack.size > 1) backStack.removeAt(0)
  }

  fun resetToStep(step: SetupStep) = resetTo(step.route())

  /** Sign-out wiped everything: start again at Login. */
  fun signedOut() = resetTo(LoginRoute())

  /**
   * A setup screen finished. [next] is the step that follows from what is stored now, so a
   * re-login, an edit from Settings and a first setup all end up in the right place.
   */
  fun onSetupProgress(from: NavKey, next: SetupStep) {
    val editing = (from is SourcesRoute && !from.setup) || (from is TargetRoute && !from.setup)
    when {
      next == SetupStep.LOGIN -> resetTo(LoginRoute())
      editing -> back()
      next == SetupStep.DONE -> {
        val status = backStack.indexOf(StatusRoute)
        if (status >= 0) {
          while (backStack.size > status + 1) backStack.removeAt(backStack.lastIndex)
        } else {
          resetTo(StatusRoute)
        }
      }
      // Signed in: there is no way back to the Login screen.
      from is LoginRoute -> resetTo(next.route())
      else -> push(next.route())
    }
  }
}
