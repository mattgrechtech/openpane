package io.github.mattgrechtech.openpane.wake

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * One microWakeWord model: takes [MicroFrontend] features three frames (30 ms) at a time and says
 * when the average probability over the last few runs passes the model's cutoff.
 */
class WakeWordModel(context: Context, word: String) : AutoCloseable {

    private val interpreter: Interpreter
    private val stride: Int
    private val inputScale: Float
    private val inputZero: Int
    private val outputScale: Float
    private val outputZero: Int
    private val input: ByteBuffer
    private val output = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
    private var frames = 0
    private val cutoff = CUTOFFS[word] ?: 0.97f
    private val recent = FloatArray(WINDOW)
    private var runs = 0

    init {
        val model = context.assets.openFd("wake/$word.tflite").use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(1))
        interpreter.allocateTensors()
        val inTensor = interpreter.getInputTensor(0)
        stride = inTensor.shape()[1]
        inputScale = inTensor.quantizationParams().scale
        inputZero = inTensor.quantizationParams().zeroPoint
        val outTensor = interpreter.getOutputTensor(0)
        outputScale = outTensor.quantizationParams().scale
        outputZero = outTensor.quantizationParams().zeroPoint
        input = ByteBuffer.allocateDirect(stride * MicroFrontend.CHANNELS).order(ByteOrder.nativeOrder())
    }

    /** Adds one frame of features; true when the wake word was just heard. */
    fun add(features: IntArray): Boolean {
        for (value in features) {
            val quantized = (value * FEATURE_SCALE / inputScale).roundToInt() + inputZero
            input.put(quantized.coerceIn(-128, 127).toByte())
        }
        if (++frames < stride) return false
        frames = 0
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        input.rewind()
        val probability = ((output.get(0).toInt() and 0xFF) - outputZero) * outputScale
        recent[runs % WINDOW] = probability
        runs++
        // Let the model's streaming state settle after a (re)start before believing it.
        if (runs < SETTLE_RUNS) return false
        if (recent.average() <= cutoff) return false
        reset()
        return true
    }

    /** Forgets what it heard (after a detection). */
    fun reset() {
        interpreter.resetVariableTensors()
        input.rewind()
        frames = 0
        runs = 0
        recent.fill(0f)
    }

    override fun close() = interpreter.close()

    private companion object {
        /** Frontend output (0-65535) to the float features microWakeWord trains on. */
        const val FEATURE_SCALE = 0.0390625f
        /** Probabilities averaged (the models' sliding_window_size). */
        const val WINDOW = 5
        /** About a second of audio. */
        const val SETTLE_RUNS = 33
        /** The models' probability_cutoff. */
        val CUTOFFS = mapOf("okay_nabu" to 0.97f, "hey_jarvis" to 0.97f, "hey_mycroft" to 0.95f)
    }
}
