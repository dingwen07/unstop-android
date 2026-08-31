package net.extrawdw.apps.unstop

/** Supported delays for the FCM connection watchdog. */
internal object FcmPollingInterval {
    const val DEFAULT_MILLIS = 30_000L

    val OPTIONS_MILLIS = listOf(DEFAULT_MILLIS, 60_000L, 120_000L)

    fun isSupported(intervalMillis: Long): Boolean = intervalMillis in OPTIONS_MILLIS

    fun normalize(intervalMillis: Long): Long =
        intervalMillis.takeIf(::isSupported) ?: DEFAULT_MILLIS
}
