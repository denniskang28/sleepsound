package com.miansheng.sleep.audio

import android.media.*
import java.io.File
import kotlin.math.abs

object ClipWriter {
    /** AAC-LC, 32 kbit/s mono. Files remain in app-private storage. */
    fun write(destination: File, pcm: ShortArray, rate: Int = 16000) {
        val temporary = File(destination.parentFile, destination.name + ".partial")
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(temporary.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var track = -1
        try {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 32000)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var offset = 0
            var inputDone = false
            var outputDone = false
            var idlePolls = 0
            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        buffer.clear()
                        val count = minOf(buffer.remaining() / 2, pcm.size - offset)
                        repeat(count) { buffer.putShort(pcm[offset + it]) }
                        val eos = offset + count >= pcm.size
                        codec.queueInputBuffer(index, 0, count * 2, offset * 1000000L / rate,
                            if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        offset += count; inputDone = eos
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        idlePolls = 0
                    }
                    else -> { check(++idlePolls < 1000) { "Audio encoder timeout" } }
                }
            }
            muxer.stop(); muxerStarted = false
            check(temporary.renameTo(destination))
        } finally {
            runCatching { codec.stop() }; codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
            temporary.delete()
        }
    }

    fun waveform(pcm: ShortArray, bars: Int = 80): List<Float> = List(bars) { bar ->
        val start = bar * pcm.size / bars
        val end = ((bar + 1) * pcm.size / bars).coerceAtLeast(start + 1).coerceAtMost(pcm.size)
        (start until end).maxOfOrNull { abs(pcm[it].toInt()) / 32768f } ?: 0f
    }.let { amplitudes ->
        val max = amplitudes.maxOrNull()?.coerceAtLeast(.001f) ?: 1f
        amplitudes.map { it / max }
    }
}
