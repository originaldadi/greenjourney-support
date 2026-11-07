package io.github.originaldadi.soundcast.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.originaldadi.soundcast.R
import io.github.originaldadi.soundcast.core.Network
import io.github.originaldadi.soundcast.core.SonosClient
import io.github.originaldadi.soundcast.core.SonosGroup
import io.github.originaldadi.soundcast.core.WavStreamServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Captures what the phone is playing and serves it to the chosen Sonos group.
 *
 * Runs as a foreground service of type mediaProjection, which Android requires
 * for capturing other apps' audio.
 */
class CastService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sonos = SonosClient()

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var server: WavStreamServer? = null
    private var group: SonosGroup? = null
    @Volatile private var capturing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCasting(stopSpeaker = true)
                return START_NOT_STICKY
            }
            ACTION_START -> start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = intent.parcelable<Intent>(EXTRA_RESULT_DATA)
        val target = SonosGroup(
            name = intent.getStringExtra(EXTRA_GROUP_NAME).orEmpty(),
            coordinatorHost = intent.getStringExtra(EXTRA_GROUP_HOST).orEmpty(),
            coordinatorUuid = intent.getStringExtra(EXTRA_GROUP_UUID).orEmpty(),
            memberCount = 1,
        )
        if (resultData == null || target.coordinatorHost.isEmpty()) {
            fail("Missing permission result or speaker")
            return
        }

        // Android 14+ requires the foreground service to be running before the projection is created.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(getString(R.string.notif_casting_to, target.name)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        // Restarting on a new speaker: tear down the previous session first.
        if (projection != null) stopCasting(stopSpeaker = true, keepService = true)

        group = target
        CastState.set(CastStatus.Starting(target))

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpm.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            fail("Android did not allow audio capture")
            return
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // User tapped "stop sharing" in the status bar. Ignore the callback from a
                // projection we already replaced when switching speakers.
                if (projection === mp) stopCasting(stopSpeaker = true)
            }
        }, mainHandler)

        val record = try {
            buildRecorder(mp)
        } catch (e: Exception) {
            fail("Could not start audio capture: ${e.message}")
            return
        }
        recorder = record

        val wav = WavStreamServer(SAMPLE_RATE, CHANNELS, PREFERRED_PORT)
        val port = try {
            wav.start()
        } catch (e: Exception) {
            fail("Could not open stream port: ${e.message}")
            return
        }
        server = wav

        capturing = true
        record.startRecording()
        thread(name = "audio-capture", isDaemon = true) { captureLoop(record, wav) }

        scope.launch {
            try {
                val ip = Network.localAddressFacing(target.coordinatorHost)
                val url = "http://$ip:$port${WavStreamServer.STREAM_PATH}"
                sonos.playStream(target.coordinatorHost, url, getString(R.string.stream_title, Build.MODEL))
                CastState.set(CastStatus.Casting(target, url))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start playback", e)
                fail(e.message ?: "Could not start the speaker")
            }
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked below.
    private fun buildRecorder(mp: MediaProjection): AudioRecord {
        check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "microphone/audio permission not granted"
        }
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()
            .also { check(it.state == AudioRecord.STATE_INITIALIZED) { "recorder failed to initialise" } }
    }

    private fun captureLoop(record: AudioRecord, wav: WavStreamServer) {
        val buffer = ByteArray(CHUNK_BYTES)
        while (capturing) {
            val n = record.read(buffer, 0, buffer.size)
            if (n > 0) {
                wav.publish(buffer, n)
            } else if (n < 0) {
                Log.w(TAG, "AudioRecord.read returned $n")
                break
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Safe to call from any thread; teardown always happens on the main thread. */
    private fun fail(message: String) {
        CastState.set(CastStatus.Failed(message))
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopCasting(stopSpeaker = false, keepStatus = true)
        } else {
            mainHandler.post { stopCasting(stopSpeaker = false, keepStatus = true) }
        }
    }

    private fun stopCasting(stopSpeaker: Boolean, keepService: Boolean = false, keepStatus: Boolean = false) {
        capturing = false
        recorder?.let { r -> runCatching { r.stop() }; r.release() }
        recorder = null
        server?.stop()
        server = null
        projection?.let { p -> projection = null; runCatching { p.stop() } }

        val host = group?.coordinatorHost
        group = null
        if (stopSpeaker && host != null) {
            // Best effort; the speaker also stops on its own once the stream closes.
            scope.launch { runCatching { sonos.stop(host) } }
        }
        if (!keepStatus) CastState.set(CastStatus.Idle)
        if (!keepService) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (projection != null) stopCasting(stopSpeaker = true, keepService = true)
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CastService"
        const val ACTION_START = "soundcast.START"
        const val ACTION_STOP = "soundcast.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_GROUP_NAME = "groupName"
        const val EXTRA_GROUP_HOST = "groupHost"
        const val EXTRA_GROUP_UUID = "groupUuid"

        private const val CHANNEL_ID = "casting"
        private const val NOTIFICATION_ID = 1
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        private const val CHUNK_BYTES = SAMPLE_RATE * CHANNELS * 2 / 20 // 50 ms
        private const val PREFERRED_PORT = 8090

        fun start(context: Context, resultCode: Int, data: Intent, group: SonosGroup) {
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_GROUP_NAME, group.name)
                .putExtra(EXTRA_GROUP_HOST, group.coordinatorHost)
                .putExtra(EXTRA_GROUP_UUID, group.coordinatorUuid)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_STOP))
        }
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelable(key: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key)
