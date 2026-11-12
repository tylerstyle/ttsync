package de.tergelius.ttsync

import androidx.navigation3.runtime.NavKey
import de.tergelius.ttsync.settings.SetupStep
import kotlinx.serialization.Serializable

/*
 * Navigation keys (DESIGN.md section 7). Setup screens carry `setup = true` while they are a setup
 * step (Next continues setup) and `false` when opened from Settings or Status (Save goes back).
 */

/** [reauth]: Status sent the user here because the stored sign-in stopped working. */
@Serializable data class LoginRoute(val reauth: Boolean = false) : NavKey

@Serializable data class SourcesRoute(val setup: Boolean) : NavKey

@Serializable data class TargetRoute(val setup: Boolean) : NavKey

@Serializable data object KeepRunningRoute : NavKey

@Serializable data object StatusRoute : NavKey

@Serializable data object ReliabilityRoute : NavKey

@Serializable data object SettingsRoute : NavKey

@Serializable data object AboutRoute : NavKey

/** The screen of a setup step. */
fun SetupStep.route(): NavKey =
  when (this) {
    SetupStep.LOGIN -> LoginRoute()
    SetupStep.SOURCES -> SourcesRoute(setup = true)
    SetupStep.TARGET -> TargetRoute(setup = true)
    SetupStep.KEEP_RUNNING -> KeepRunningRoute
    SetupStep.DONE -> StatusRoute
  }
