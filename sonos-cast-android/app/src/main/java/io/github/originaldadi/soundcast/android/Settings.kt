package io.github.originaldadi.soundcast.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.originaldadi.soundcast.core.AudioStreamServer
import io.github.originaldadi.soundcast.core.LinkType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where audio is captured from. */
enum class CaptureSource { NETEASE, ALL_APPS }

/** What the phone sends to the speaker. */
enum class AudioFormatChoice(val bitrate: Int) {
    WAV(0), AAC_320(320_000), AAC_256(256_000), AAC_128(128_000);

    val isAac: Boolean get() = this != WAV
}

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
    private const val KEY_FORMAT = "format"
    private const val KEY_RATE = "sampleRate"
    private const val KEY_LINK = "linkType"

    val SAMPLE_RATES = listOf(44_100, 48_000)
    private const val DEFAULT_RATE = 44_100

    private val _bufferMillis = MutableStateFlow(AudioStreamServer.DEFAULT_QUEUE_MS)
    val bufferMillis: StateFlow<Int> = _bufferMillis.asStateFlow()

    private val _source = MutableStateFlow(CaptureSource.NETEASE)
    val source: StateFlow<CaptureSource> = _source.asStateFlow()

    private val _format = MutableStateFlow(AudioFormatChoice.WAV)
    val format: StateFlow<AudioFormatChoice> = _format.asStateFlow()

    private val _sampleRate = MutableStateFlow(DEFAULT_RATE)
    val sampleRate: StateFlow<Int> = _sampleRate.asStateFlow()

    private val _linkType = MutableStateFlow(LinkType.STANDARD)
    val linkType: StateFlow<LinkType> = _linkType.asStateFlow()

    @Volatile private var loaded = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _bufferMillis.value = prefs.getInt(KEY_BUFFER_MS, AudioStreamServer.DEFAULT_QUEUE_MS)
            .coerceIn(AudioStreamServer.MIN_QUEUE_MS, AudioStreamServer.MAX_QUEUE_MS)
        val defaultSource = if (isNeteaseInstalled(context)) CaptureSource.NETEASE else CaptureSource.ALL_APPS
        _source.value = prefs.getString(KEY_SOURCE, null)
            ?.let { runCatching { CaptureSource.valueOf(it) }.getOrNull() }
            ?: defaultSource
        _format.value = prefs.getString(KEY_FORMAT, null)
            ?.let { runCatching { AudioFormatChoice.valueOf(it) }.getOrNull() } ?: AudioFormatChoice.WAV
        _sampleRate.value = prefs.getInt(KEY_RATE, DEFAULT_RATE).takeIf { it in SAMPLE_RATES } ?: DEFAULT_RATE
        _linkType.value = prefs.getString(KEY_LINK, null)
            ?.let { runCatching { LinkType.valueOf(it) }.getOrNull() } ?: LinkType.STANDARD
        loaded = true
    }

    fun setBufferMillis(context: Context, ms: Int) {
        val v = ms.coerceIn(AudioStreamServer.MIN_QUEUE_MS, AudioStreamServer.MAX_QUEUE_MS)
        _bufferMillis.value = v
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_BUFFER_MS, v).apply()
    }

    fun setSource(context: Context, source: CaptureSource) {
        _source.value = source
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SOURCE, source.name).apply()
    }

    fun setFormat(context: Context, value: AudioFormatChoice) {
        _format.value = value
        prefs(context).edit().putString(KEY_FORMAT, value.name).apply()
    }

    fun setSampleRate(context: Context, value: Int) {
        if (value !in SAMPLE_RATES) return
        _sampleRate.value = value
        prefs(context).edit().putInt(KEY_RATE, value).apply()
    }

    fun setLinkType(context: Context, value: LinkType) {
        _linkType.value = value
        prefs(context).edit().putString(KEY_LINK, value.name).apply()
    }

    /** Back to the defaults, keeping the capture source. */
    fun resetStreaming(context: Context) {
        prefs(context).edit().remove(KEY_BUFFER_MS).remove(KEY_FORMAT).remove(KEY_RATE).remove(KEY_LINK).apply()
        _bufferMillis.value = AudioStreamServer.DEFAULT_QUEUE_MS
        _format.value = AudioFormatChoice.WAV
        _sampleRate.value = DEFAULT_RATE
        _linkType.value = LinkType.STANDARD
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
