package de.tergelius.ttsync.timetree

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * The real [TimeTreeClient] (phase 2). [baseUrl] is replaceable so tests can point it at
 * `mockwebserver3`.
 */
class OkHttpTimeTreeClient(
  private val http: OkHttpClient,
  private val sessions: TtSessionStore,
  private val pacer: RequestPacer,
  private val rawSink: RawResponseSink,
  private val baseUrl: HttpUrl = TimeTreeApi.BASE_URL.toHttpUrl(),
) : TimeTreeClient {
  override suspend fun signIn(email: String, password: String): String = notYet()

  override fun beginRun(runId: String): Unit = notYet()

  override suspend fun calendars(): List<TtCalendar> = notYet()

  override suspend fun labels(calendarId: Long): List<TtLabel> = notYet()

  override suspend fun events(calendarId: Long, since: Long?): EventsResult = notYet()

  private fun notYet(): Nothing =
    TODO("phase 2: TimeTree client ($baseUrl, $http, $sessions, $pacer, $rawSink)")
}
