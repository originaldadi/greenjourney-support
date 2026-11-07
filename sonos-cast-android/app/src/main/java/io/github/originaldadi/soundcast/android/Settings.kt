package io.github.originaldadi.soundcast.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.originaldadi.soundcast.core.WavStreamServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where audio is captured from. */
enum class CaptureSource { NETEASE, ALL_APPS }

/**
 * User settings, saved on the phone. Exposed as flows so a change made on the
 * screen reaches the running service immediately.
 */
object Settings {
    /** NetEase Cloud Music's Android package. */
    const val NETEASE_PACKAGE = "com.netease.cloudmusic"

    private const val PREFS = "settings"
    private const val KEY_BUFFER_MS = "bufferMs"
    private const val KEY_SOURCE = "captureSource"

    private val _bufferMillis = MutableStateFlow(WavStreamServer.DEFAULT_QUEUE_MS)
    val bufferMillis: StateFlow<Int> = _bufferMillis.asStateFlow()

    private val _source = MutableStateFlow(CaptureSource.NETEASE)
    val source: StateFlow<CaptureSource> = _source.asStateFlow()

    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _bufferMillis.value = prefs.getInt(KEY_BUFFER_MS, WavStreamServer.DEFAULT_QUEUE_MS)
            .coerceIn(WavStreamServer.MIN_QUEUE_MS, WavStreamServer.MAX_QUEUE_MS)
        val defaultSource = if (isNeteaseInstalled(context)) CaptureSource.NETEASE else CaptureSource.ALL_APPS
        _source.value = prefs.getString(KEY_SOURCE, null)
            ?.let { runCatching { CaptureSource.valueOf(it) }.getOrNull() }
            ?: defaultSource
        loaded = true
    }

    fun setBufferMillis(context: Context, ms: Int) {
        val v = ms.coerceIn(WavStreamServer.MIN_QUEUE_MS, WavStreamServer.MAX_QUEUE_MS)
        _bufferMillis.value = v
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_BUFFER_MS, v).apply()
    }

    fun setSource(context: Context, source: CaptureSource) {
        _source.value = source
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SOURCE, source.name).apply()
    }

    fun neteaseUid(context: Context): Int? = try {
        context.packageManager.getApplicationInfo(NETEASE_PACKAGE, 0).uid
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun isNeteaseInstalled(context: Context): Boolean = neteaseUid(context) != null

    fun neteaseLaunchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(NETEASE_PACKAGE)
}
