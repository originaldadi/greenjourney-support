package io.github.originaldadi.soundcast.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import io.github.originaldadi.soundcast.core.AudioStreamServer

/**
 * Encodes 16-bit PCM to AAC-LC with Android's built-in encoder and hands out
 * complete ADTS frames (each playable on its own, which is what a live stream needs).
 *
 * Not thread-safe: call [encode] and [release] from the capture thread.
 */
class AacEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    bitrate: Int,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private val frequencyIndex = AudioStreamServer.adtsFrequencyIndex(sampleRate)
    private val bytesPerSecond = sampleRate * channels * 2
    private var presentationUs = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encode(pcm: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val input = codec.getInputBuffer(index) ?: error("encoder input buffer missing")
                input.clear()
                val n = minOf(input.remaining(), length - offset)
                input.put(pcm, offset, n)
                codec.queueInputBuffer(index, 0, n, presentationUs, 0)
                presentationUs += n * 1_000_000L / bytesPerSecond
                offset += n
            }
            drain()
        }
    }

    private fun drain() {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) return // INFO_TRY_AGAIN_LATER: nothing more right now
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!isConfig && info.size > 0) {
                val output = codec.getOutputBuffer(index) ?: error("encoder output buffer missing")
                output.position(info.offset)
                output.limit(info.offset + info.size)
                val frame = ByteArray(ADTS_HEADER + info.size)
                AudioStreamServer.writeAdtsHeader(frame, frame.size, frequencyIndex, channels)
                output.get(frame, ADTS_HEADER, info.size)
                onFrame(frame)
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
    }

    private companion object {
        const val ADTS_HEADER = 7
    }
}
