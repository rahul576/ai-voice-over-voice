package com.voicestudio.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Same limit as the server; the model itself keeps the best 20 seconds or less. */
const val MAX_SAMPLE_SECONDS = 60
const val MIN_SAMPLE_SECONDS = 3

class Sample(val pcm: ShortArray, val sampleRate: Int) {
    val seconds: Double get() = pcm.size.toDouble() / sampleRate
    fun toWav(): ByteArray = encodeWav(pcm, sampleRate)
}

/** Records mono 16-bit audio from the microphone until [stop] or the time limit. */
class Recorder(private val sampleRate: Int = 24_000) {
    @Volatile private var running = false

    fun stop() { running = false }

    /** Blocking; call from a background thread. [onLevel] gets elapsed seconds and a 0..1 level. */
    @SuppressLint("MissingPermission")
    fun record(onLevel: (Double, Float) -> Unit): Sample {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sampleRate),
        )
        val max = sampleRate * MAX_SAMPLE_SECONDS
        val out = ShortArray(max)
        var n = 0
        val chunk = ShortArray(sampleRate / 20)
        running = true
        rec.startRecording()
        try {
            while (running && n < max) {
                val read = rec.read(chunk, 0, minOf(chunk.size, max - n))
                if (read <= 0) continue
                System.arraycopy(chunk, 0, out, n, read)
                n += read
                var peak = 0
                for (i in 0 until read) peak = maxOf(peak, kotlin.math.abs(chunk[i].toInt()))
                onLevel(n.toDouble() / sampleRate, peak / 32768f)
            }
        } finally {
            rec.stop()
            rec.release()
            running = false
        }
        return Sample(out.copyOf(n), sampleRate)
    }
}

/** Decodes any audio file Android can play into mono 16-bit PCM, keeping the first 15 seconds. */
fun decodeAudio(context: Context, uri: Uri): Sample {
    val extractor = MediaExtractor()
    extractor.setDataSource(context, uri, null)
    val track = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: throw IllegalArgumentException("That file has no audio track.")
    extractor.selectTrack(track)
    val format = extractor.getTrackFormat(track)
    val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
    codec.configure(format, null, null, 0)
    codec.start()

    var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val mono = ArrayList<Short>()
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false
    try {
        while (!outputDone && mono.size < sampleRate * MAX_SAMPLE_SECONDS) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    codec.outputFormat.let {
                        sampleRate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
                outIdx >= 0 -> {
                    val buf: ByteBuffer = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset).limit(info.offset + info.size)
                    val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (shorts.remaining() >= channels) {
                        var sum = 0
                        repeat(channels) { sum += shorts.get() }
                        mono.add((sum / channels).toShort())
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
    } finally {
        codec.stop()
        codec.release()
        extractor.release()
    }
    val pcm = mono.take(sampleRate * MAX_SAMPLE_SECONDS).toShortArray()
    return Sample(pcm, sampleRate)
}

fun encodeWav(pcm: ShortArray, rate: Int): ByteArray {
    val data = pcm.size * 2
    val out = ByteArrayOutputStream(44 + data)
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
    h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    h.put("data".toByteArray()).putInt(data)
    out.write(h.array())
    val body = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
    body.asShortBuffer().put(pcm)
    out.write(body.array())
    return out.toByteArray()
}
