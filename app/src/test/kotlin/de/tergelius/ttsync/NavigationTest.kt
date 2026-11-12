package de.tergelius.ttsync

import androidx.navigation3.runtime.NavKey
import de.tergelius.ttsync.settings.SetupStep
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTest {
  private fun navigator(vararg keys: NavKey): Pair<MutableList<NavKey>, AppNavigator> {
    val stack = mutableListOf(*keys)
    return stack to AppNavigator(stack)
  }

  @Test
  fun firstSetupWalksForwardAndEndsOnStatusAlone() {
    val (stack, nav) = navigator(LoginRoute())
    nav.onSetupProgress(LoginRoute(), SetupStep.SOURCES)
    assertEquals(listOf(SourcesRoute(setup = true)), stack) // no way back to Login
    nav.onSetupProgress(SourcesRoute(setup = true), SetupStep.TARGET)
    nav.onSetupProgress(TargetRoute(setup = true), SetupStep.KEEP_RUNNING)
    assertEquals(listOf(SourcesRoute(true), TargetRoute(true), KeepRunningRoute), stack)
    nav.onSetupProgress(KeepRunningRoute, SetupStep.DONE)
    assertEquals(listOf<NavKey>(StatusRoute), stack)
  }

  @Test
  fun signingInAgainReturnsToStatus() {
    val (stack, nav) = navigator(StatusRoute, LoginRoute(reauth = true))
    nav.onSetupProgress(LoginRoute(reauth = true), SetupStep.DONE)
    assertEquals(listOf<NavKey>(StatusRoute), stack)
  }

  @Test
  fun editsFromSettingsGoBackToSettings() {
    val (stack, nav) = navigator(StatusRoute, SettingsRoute, TargetRoute(setup = false))
    nav.onSetupProgress(TargetRoute(setup = false), SetupStep.DONE)
    assertEquals(listOf(StatusRoute, SettingsRoute), stack)
  }

  @Test
  fun aDeletedTargetIsFixedFromStatus() {
    val (stack, nav) = navigator(StatusRoute)
    nav.push(TargetRoute(setup = false))
    nav.onSetupProgress(TargetRoute(setup = false), SetupStep.DONE)
    assertEquals(listOf<NavKey>(StatusRoute), stack)
  }

  @Test
  fun signOutAndRemoveAllReset() {
    val (stack, nav) = navigator(StatusRoute, SettingsRoute)
    nav.resetToStep(SetupStep.SOURCES)
    assertEquals(listOf<NavKey>(SourcesRoute(setup = true)), stack)
    nav.signedOut()
    assertEquals(listOf<NavKey>(LoginRoute()), stack)
  }

  @Test
  fun backNeverEmptiesTheStack() {
    val (stack, nav) = navigator(StatusRoute)
    nav.back()
    assertEquals(listOf<NavKey>(StatusRoute), stack)
  }
}
