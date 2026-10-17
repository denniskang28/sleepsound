package com.miansheng.sleep.audio

import android.content.Context
import com.miansheng.sleep.domain.Prediction
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Bundled YAMNet baseline. Thresholds are provisional, not clinically validated. */
class SoundClassifier(context: Context) : AutoCloseable {
    private val model = context.assets.open("yamnet.tflite").use { it.readBytes() }.let {
        ByteBuffer.allocateDirect(it.size).order(ByteOrder.nativeOrder()).apply { put(it); rewind() }
    }
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    val windowSize: Int = interpreter.getInputTensor(0).shape().reduce(Int::times)
    private val scores = Array(1) { FloatArray(521) }
    private val input = FloatArray(windowSize)
    private val labels = context.assets.open("yamnet_class_map.csv").bufferedReader().useLines { lines ->
        lines.drop(1).map { it.substringAfter(',').substringAfter(',').trim('"') }.toList()
    }
    private val snoreIndex = labels.indexOf("Snoring").also { check(it >= 0) }
    private val speechIndexes = labels.mapIndexedNotNull { i, label ->
        i.takeIf { label == "Speech" || label == "Mumbling" || label == "Whispering" ||
            label == "Male speech, man speaking" || label == "Female speech, woman speaking" }
    }
    private val mediaIndexes = labels.mapIndexedNotNull { i, label -> i.takeIf { label == "Music" || label == "Television" } }

    fun classify(pcm: ShortArray): Prediction {
        require(pcm.size == windowSize)
        var energy = 0.0
        pcm.forEachIndexed { i, value -> input[i] = value / 32768f; energy += input[i] * input[i] }
        val rms = sqrt(energy / pcm.size).toFloat()
        interpreter.run(input, scores)
        val snore = scores[0][snoreIndex]
        val speech = speechIndexes.maxOf { scores[0][it] }
        val media = mediaIndexes.maxOfOrNull { scores[0][it] } ?: 0f
        val kind = when {
            rms < .00008f -> null
            snore >= .35f && snore >= speech -> "snore"
            speech >= .55f && media < .45f -> "talk"
            else -> null
        }
        return Prediction(kind, if (kind == "snore") snore else speech, rms)
    }
    override fun close() { interpreter.close() }
}
