// Adapted from TensorFlow Lite Micro's audio frontend, Copyright 2018 The TensorFlow Authors
// (Apache-2.0), and Kiss FFT, Copyright (c) 2003-2010, Mark Borgerding (BSD-3-Clause; the full
// notice is in THIRD_PARTY_NOTICES.md).
package io.github.mattgrechtech.openpane.wake

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.pow

/**
 * The audio features microWakeWord models are trained on: 40 log-mel channels every 10 ms from
 * 16 kHz, 16-bit audio, with noise reduction and per-channel gain control (PCAN).
 *
 * A Kotlin port of TensorFlow Lite Micro's audio frontend (`tensorflow/lite/experimental/
 * microfrontend`, Apache-2.0) with its fixed-point Kiss FFT (BSD-3-Clause, Mark Borgerding),
 * matching it value for value so a model hears what it was trained on. Settings are microWakeWord's:
 * 30 ms windows, 10 ms steps, 125-7500 Hz. No Android dependencies.
 */
class MicroFrontend {

    private val windowSize = WINDOW_MS * SAMPLE_RATE / 1000
    private val step = STEP_MS * SAMPLE_RATE / 1000
    private val fftSize = Integer.highestOneBit(windowSize - 1) shl 1
    private val spectrumSize = fftSize / 2 + 1

    // Single-precision, as the frontend's newer versions (the ones microWakeWord uses) compute it.
    private val coefficients = IntArray(windowSize) { i ->
        val arg = Math.PI.toFloat() * 2.0f / windowSize.toFloat()
        val value = 0.5f - 0.5f * cos((arg * (i + 0.5f)).toDouble()).toFloat()
        floor(value * (1 shl WINDOW_BITS) + 0.5f).toInt()
    }
    private val input = ShortArray(windowSize)
    private var inputUsed = 0
    private val windowed = IntArray(windowSize)

    private val fft = RealFft(fftSize)
    private val energy = IntArray(spectrumSize)

    private val filterbank = Filterbank(spectrumSize)
    private val estimate = LongArray(CHANNELS)
    private val gainLut = gainLut()

    /**
     * Feeds [count] samples from [samples] at [offset]; returns how many it used and, when that
     * completed a window, the 40 features (0-65535) in [features]. Feed 160 samples (10 ms) at a time.
     */
    fun process(samples: ShortArray, offset: Int, count: Int, features: IntArray): Pair<Int, Boolean> {
        val copy = minOf(windowSize - inputUsed, count)
        System.arraycopy(samples, offset, input, inputUsed, copy)
        inputUsed += copy
        if (inputUsed < windowSize) return copy to false

        // Window.
        var maxAbs = 0
        for (i in 0 until windowSize) {
            val value = ((input[i].toInt() * coefficients[i]) shr WINDOW_BITS).toShort().toInt()
            windowed[i] = value
            val abs = if (value < 0) (-value).toShort().toInt() else value
            if (abs > maxAbs) maxAbs = abs
        }
        System.arraycopy(input, step, input, 0, windowSize - step)
        inputUsed -= step

        // FFT, scaled up so the fixed-point FFT keeps as much resolution as it can.
        val inputShift = 15 - msb32(maxAbs.toLong())
        fft.compute(windowed, windowSize, inputShift)
        for (i in filterbank.startIndex until filterbank.endIndex) {
            val re = fft.outRe[i]
            val im = fft.outIm[i]
            energy[i] = re * re + im * im
        }
        val signal = filterbank.accumulate(energy, inputShift)

        // Noise reduction.
        for (i in 0 until CHANNELS) {
            val smoothing = if ((i and 1) == 0) EVEN_SMOOTHING else ODD_SMOOTHING
            val scaledUp = (signal[i] shl SMOOTHING_BITS) and U32
            var est = ((scaledUp * smoothing + estimate[i] * ((1L shl NOISE_BITS) - smoothing)) ushr NOISE_BITS) and U32
            estimate[i] = est
            if (est > scaledUp) est = scaledUp
            val floor = ((signal[i] * MIN_SIGNAL_REMAINING) ushr NOISE_BITS) and U32
            val subtracted = (scaledUp - est) ushr SMOOTHING_BITS
            signal[i] = if (subtracted > floor) subtracted else floor
        }

        // PCAN gain control.
        for (i in 0 until CHANNELS) {
            val gain = wideDynamic(estimate[i]).toLong() and U32
            val snr = ((signal[i] * gain) ushr SNR_SHIFT) and U32
            signal[i] = if (snr < (2L shl PCAN_SNR_BITS)) {
                ((snr * snr) ushr (2 + 2 * PCAN_SNR_BITS - PCAN_OUTPUT_BITS)) and U32
            } else {
                ((snr ushr (PCAN_SNR_BITS - PCAN_OUTPUT_BITS)) - (1L shl PCAN_OUTPUT_BITS)) and U32
            }
        }

        // Log scale.
        for (i in 0 until CHANNELS) {
            var value = (signal[i] shl CORRECTION_BITS) and U32
            value = if (value > 1) log(value) else 0
            features[i] = minOf(value, 0xFFFF).toInt()
        }
        return copy to true
    }

    fun reset() {
        input.fill(0)
        inputUsed = 0
        estimate.fill(0)
    }

    private fun wideDynamic(x: Long): Int {
        if (x <= 2) return gainLut[x.toInt()].toInt()
        val interval = msb32(x)
        val base = 4 * interval - 6
        val frac = ((if (interval < 11) x shl (11 - interval) else x ushr (interval - 11)) and 0x3FF).toInt()
        var result = (gainLut[base + 2] * frac) shr 5
        result += gainLut[base + 1].toInt() shl 5
        result *= frac
        result = (result + (1 shl 14)) shr 15
        result += gainLut[base]
        return result.toShort().toInt()
    }

    private fun gainLut(): ShortArray {
        val inputBits = SMOOTHING_BITS - CORRECTION_BITS
        fun lookup(x: Long): Short {
            val xf = x.toFloat() / (1L shl inputBits).toFloat()
            val gain = (1L shl GAIN_BITS).toFloat() * (xf + PCAN_OFFSET).toDouble().pow(-PCAN_STRENGTH.toDouble()).toFloat()
            return if (gain > Short.MAX_VALUE) Short.MAX_VALUE else (gain + 0.5f).toInt().toShort()
        }
        val lut = ShortArray(4 * WIDE_DYNAMIC_BITS - 3)
        lut[0] = lookup(0)
        lut[1] = lookup(1)
        for (interval in 2..WIDE_DYNAMIC_BITS) {
            val x0 = 1L shl (interval - 1)
            val x1 = x0 + (x0 shr 1)
            val x2 = if (interval == WIDE_DYNAMIC_BITS) x0 + (x0 - 1) else 2 * x0
            val y0 = lookup(x0).toInt()
            val y1 = lookup(x1).toInt()
            val y2 = lookup(x2).toInt()
            val a1 = 4 * (y1 - y0) - (y2 - y0)
            val a2 = (y2 - y0) - a1
            lut[4 * interval - 6] = y0.toShort()
            lut[4 * interval - 5] = a1.toShort()
            lut[4 * interval - 4] = a2.toShort()
        }
        return lut
    }

    /** The mel filterbank, with the weights and padding the C version uses. */
    private class Filterbank(spectrumSize: Int) {
        val startIndex: Int
        var endIndex = 0
            private set
        private val frequencyStarts = IntArray(CHANNELS + 1)
        private val weightStarts = IntArray(CHANNELS + 1)
        private val widths = IntArray(CHANNELS + 1)
        private val weights: ShortArray
        private val unweights: ShortArray
        private val work = LongArray(CHANNELS + 1)
        private val output = LongArray(CHANNELS)

        init {
            val count = CHANNELS + 1
            val melLow = freqToMel(LOWER_HZ)
            val melHi = freqToMel(UPPER_HZ)
            val spacing = (melHi - melLow) / count.toFloat()
            val centers = FloatArray(count) { melLow + spacing * (it + 1) }
            val hzPerBin = (0.5 * SAMPLE_RATE / (spectrumSize.toFloat() - 1)).toFloat()
            startIndex = (1.5 + LOWER_HZ / hzPerBin).toInt()
            val actualStarts = IntArray(count)
            val actualWidths = IntArray(count)
            var chanStart = startIndex
            var weightStart = 0
            var needsZeros = false
            for (chan in 0 until count) {
                var freq = chanStart
                while (freqToMel(freq * hzPerBin) <= centers[chan]) freq++
                val width = freq - chanStart
                actualStarts[chan] = chanStart
                actualWidths[chan] = width
                if (width == 0) {
                    frequencyStarts[chan] = 0
                    weightStarts[chan] = 0
                    widths[chan] = BLOCK
                    if (!needsZeros) {
                        needsZeros = true
                        for (j in 0 until chan) weightStarts[j] += BLOCK
                        weightStart += BLOCK
                    }
                } else {
                    val alignedStart = (chanStart / ALIGNMENT) * ALIGNMENT
                    val alignedWidth = chanStart - alignedStart + width
                    val padded = ((alignedWidth - 1) / BLOCK + 1) * BLOCK
                    frequencyStarts[chan] = alignedStart
                    weightStarts[chan] = weightStart
                    widths[chan] = padded
                    weightStart += padded
                }
                chanStart = freq
            }
            weights = ShortArray(weightStart)
            unweights = ShortArray(weightStart)
            for (chan in 0 until count) {
                var frequency = actualStarts[chan]
                val offset = frequency - frequencyStarts[chan]
                val denominator = if (chan == 0) melLow else centers[chan - 1]
                for (j in 0 until actualWidths[chan]) {
                    val weight = (centers[chan] - freqToMel(frequency * hzPerBin)) / (centers[chan] - denominator)
                    val index = weightStarts[chan] + offset + j
                    weights[index] = floor(weight * (1 shl FILTERBANK_BITS) + 0.5f).toInt().toShort()
                    unweights[index] = floor((1.0f - weight) * (1 shl FILTERBANK_BITS) + 0.5f).toInt().toShort()
                    frequency++
                }
                if (frequency > endIndex) endIndex = frequency
            }
        }

        /** Channel energies, square-rooted and scaled back down: unsigned 32-bit values in a LongArray. */
        fun accumulate(energy: IntArray, scaleDownShift: Int): LongArray {
            var weighted = 0L
            var unweighted = 0L
            for (i in 0..CHANNELS) {
                val start = frequencyStarts[i]
                val weightStart = weightStarts[i]
                for (j in 0 until widths[i]) {
                    val magnitude = energy[start + j].toLong()
                    weighted += weights[weightStart + j] * magnitude
                    unweighted += unweights[weightStart + j] * magnitude
                }
                work[i] = weighted
                weighted = unweighted
                unweighted = 0
            }
            for (i in 0 until CHANNELS) output[i] = (sqrt64(work[i + 1]) ushr scaleDownShift) and U32
            return output
        }
    }

    /** Kiss FFT's fixed-point (16-bit) real FFT, as the frontend uses it. */
    private class RealFft(private val size: Int) {
        private val half = size / 2
        private val inRe = IntArray(half)
        private val inIm = IntArray(half)
        private val tmpRe = IntArray(half)
        private val tmpIm = IntArray(half)
        private val twRe = IntArray(half)
        private val twIm = IntArray(half)
        private val superRe = IntArray(half / 2)
        private val superIm = IntArray(half / 2)
        private val factors = factor(half)
        private val time = IntArray(size)
        val outRe = IntArray(half + 1)
        val outIm = IntArray(half + 1)

        init {
            val pi = 3.141592653589793238462643383279502884197169399375105820974944
            for (i in 0 until half) {
                val phase = -2 * pi * i / half
                twRe[i] = floor(0.5 + SAMP_MAX * cos(phase)).toInt()
                twIm[i] = floor(0.5 + SAMP_MAX * kotlin.math.sin(phase)).toInt()
            }
            for (i in 0 until half / 2) {
                val phase = -3.14159265358979323846264338327 * ((i + 1).toDouble() / half + .5)
                superRe[i] = floor(0.5 + SAMP_MAX * cos(phase)).toInt()
                superIm[i] = floor(0.5 + SAMP_MAX * kotlin.math.sin(phase)).toInt()
            }
        }

        fun compute(input: IntArray, inputSize: Int, shift: Int) {
            for (i in 0 until size) time[i] = if (i < inputSize) s16((input[i] and 0xFFFF) shl shift) else 0
            for (i in 0 until half) {
                inRe[i] = time[2 * i]
                inIm[i] = time[2 * i + 1]
            }
            work(0, 0, 1, 0)

            var dcRe = tmpRe[0]
            var dcIm = tmpIm[0]
            dcRe = fixDiv(dcRe, 2)
            dcIm = fixDiv(dcIm, 2)
            outRe[0] = s16(dcRe + dcIm)
            outRe[half] = s16(dcRe - dcIm)
            outIm[0] = 0
            outIm[half] = 0
            for (k in 1..half / 2) {
                val pkRe = fixDiv(tmpRe[k], 2)
                val pkIm = fixDiv(tmpIm[k], 2)
                val pnkRe = fixDiv(tmpRe[half - k], 2)
                val pnkIm = fixDiv(s16(-tmpIm[half - k]), 2)
                val f1Re = s16(pkRe + pnkRe)
                val f1Im = s16(pkIm + pnkIm)
                val f2Re = s16(pkRe - pnkRe)
                val f2Im = s16(pkIm - pnkIm)
                val twr = sround(f2Re * superRe[k - 1] - f2Im * superIm[k - 1])
                val twi = sround(f2Re * superIm[k - 1] + f2Im * superRe[k - 1])
                outRe[k] = s16((f1Re + twr) shr 1)
                outIm[k] = s16((f1Im + twi) shr 1)
                outRe[half - k] = s16((f1Re - twr) shr 1)
                outIm[half - k] = s16((twi - f1Im) shr 1)
            }
        }

        /** kf_work: out = tmp at [out], input from [inputAt] with [stride], factors from [factorAt]. */
        private fun work(out: Int, inputAt: Int, stride: Int, factorAt: Int) {
            val p = factors[factorAt]
            val m = factors[factorAt + 1]
            if (m == 1) {
                for (j in 0 until p) {
                    tmpRe[out + j] = inRe[inputAt + j * stride]
                    tmpIm[out + j] = inIm[inputAt + j * stride]
                }
            } else {
                for (j in 0 until p) work(out + j * m, inputAt + j * stride, stride * p, factorAt + 2)
            }
            when (p) {
                2 -> bfly2(out, stride, m)
                4 -> bfly4(out, stride, m)
                else -> error("radix $p")
            }
        }

        private fun bfly2(out: Int, stride: Int, m: Int) {
            var tw = 0
            for (k in 0 until m) {
                val a = out + k
                val b = a + m
                tmpRe[a] = fixDiv(tmpRe[a], 2); tmpIm[a] = fixDiv(tmpIm[a], 2)
                tmpRe[b] = fixDiv(tmpRe[b], 2); tmpIm[b] = fixDiv(tmpIm[b], 2)
                val tr = sround(tmpRe[b] * twRe[tw] - tmpIm[b] * twIm[tw])
                val ti = sround(tmpRe[b] * twIm[tw] + tmpIm[b] * twRe[tw])
                tw += stride
                tmpRe[b] = s16(tmpRe[a] - tr); tmpIm[b] = s16(tmpIm[a] - ti)
                tmpRe[a] = s16(tmpRe[a] + tr); tmpIm[a] = s16(tmpIm[a] + ti)
            }
        }

        private fun bfly4(out: Int, stride: Int, m: Int) {
            var tw1 = 0
            var tw2 = 0
            var tw3 = 0
            for (k in 0 until m) {
                val i0 = out + k
                val i1 = i0 + m
                val i2 = i0 + 2 * m
                val i3 = i0 + 3 * m
                for (i in intArrayOf(i0, i1, i2, i3)) {
                    tmpRe[i] = fixDiv(tmpRe[i], 4); tmpIm[i] = fixDiv(tmpIm[i], 4)
                }
                val s0r = sround(tmpRe[i1] * twRe[tw1] - tmpIm[i1] * twIm[tw1])
                val s0i = sround(tmpRe[i1] * twIm[tw1] + tmpIm[i1] * twRe[tw1])
                val s1r = sround(tmpRe[i2] * twRe[tw2] - tmpIm[i2] * twIm[tw2])
                val s1i = sround(tmpRe[i2] * twIm[tw2] + tmpIm[i2] * twRe[tw2])
                val s2r = sround(tmpRe[i3] * twRe[tw3] - tmpIm[i3] * twIm[tw3])
                val s2i = sround(tmpRe[i3] * twIm[tw3] + tmpIm[i3] * twRe[tw3])
                val s5r = s16(tmpRe[i0] - s1r)
                val s5i = s16(tmpIm[i0] - s1i)
                tmpRe[i0] = s16(tmpRe[i0] + s1r); tmpIm[i0] = s16(tmpIm[i0] + s1i)
                val s3r = s16(s0r + s2r)
                val s3i = s16(s0i + s2i)
                val s4r = s16(s0r - s2r)
                val s4i = s16(s0i - s2i)
                tmpRe[i2] = s16(tmpRe[i0] - s3r); tmpIm[i2] = s16(tmpIm[i0] - s3i)
                tw1 += stride
                tw2 += stride * 2
                tw3 += stride * 3
                tmpRe[i0] = s16(tmpRe[i0] + s3r); tmpIm[i0] = s16(tmpIm[i0] + s3i)
                tmpRe[i1] = s16(s5r + s4i); tmpIm[i1] = s16(s5i - s4r)
                tmpRe[i3] = s16(s5r - s4i); tmpIm[i3] = s16(s5i + s4r)
            }
        }

        private companion object {
            const val SAMP_MAX = 32767

            fun s16(value: Int) = value.toShort().toInt()
            fun sround(product: Int) = s16((product + (1 shl 14)) shr 15)
            fun fixDiv(value: Int, divisor: Int) = sround(value * (SAMP_MAX / divisor))

            /** Kiss FFT's factoring: powers of 4, then 2. */
            fun factor(n: Int): IntArray {
                val out = mutableListOf<Int>()
                var rest = n
                var p = 4
                val floorSqrt = floor(kotlin.math.sqrt(n.toDouble()))
                do {
                    while (rest % p != 0) {
                        p = when (p) { 4 -> 2; 2 -> 3; else -> p + 2 }
                        if (p > floorSqrt) p = rest
                    }
                    rest /= p
                    out += p
                    out += rest
                } while (rest > 1)
                return out.toIntArray()
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 40
        const val STEP_MS = 10
        /** Samples in one step: feed this many at a time. */
        const val STEP_SAMPLES = STEP_MS * SAMPLE_RATE / 1000
        private const val WINDOW_MS = 30
        private const val LOWER_HZ = 125.0f
        private const val UPPER_HZ = 7500.0f
        private const val WINDOW_BITS = 12
        private const val FILTERBANK_BITS = 12
        private const val ALIGNMENT = 2
        private const val BLOCK = 4
        private const val NOISE_BITS = 14
        private const val SMOOTHING_BITS = 10
        private const val EVEN_SMOOTHING = 409L // 0.025 in 14-bit fixed point
        private const val ODD_SMOOTHING = 983L // 0.06
        private const val MIN_SIGNAL_REMAINING = 819L // 0.05
        private const val PCAN_SNR_BITS = 12
        private const val PCAN_OUTPUT_BITS = 6
        private const val GAIN_BITS = 21
        private const val PCAN_STRENGTH = 0.95f
        private const val PCAN_OFFSET = 80.0f
        private const val WIDE_DYNAMIC_BITS = 32
        /** msb(fft size 512) - 1 - filterbank bits / 2. */
        private const val CORRECTION_BITS = 3
        private const val SNR_SHIFT = GAIN_BITS - CORRECTION_BITS - PCAN_SNR_BITS
        private const val LOG_SCALE_SHIFT = 6
        private const val U32 = 0xFFFFFFFFL

        private fun freqToMel(freq: Float): Float = 1127.0f * ln1p((freq / 700.0f).toDouble()).toFloat()

        private fun msb32(n: Long): Int = 64 - java.lang.Long.numberOfLeadingZeros(n and U32)

        private fun sqrt32(value: Long): Long {
            var num = value
            if (num == 0L) return 0
            var res = 0L
            var maxBit = 32 - msb32(num)
            maxBit = maxBit or 1
            var bit = 1L shl (31 - maxBit)
            var iterations = (31 - maxBit) / 2 + 1
            while (iterations-- > 0) {
                if (num >= res + bit) {
                    num -= res + bit
                    res = (res ushr 1) + bit
                } else {
                    res = res ushr 1
                }
                bit = bit ushr 2
            }
            if (num > res && res != 0xFFFFL) res++
            return res
        }

        private fun sqrt64(value: Long): Long {
            if (value ushr 32 == 0L) return sqrt32(value)
            var num = value
            var res = 0L
            var maxBit = java.lang.Long.numberOfLeadingZeros(num)
            maxBit = maxBit or 1
            var bit = 1L shl (63 - maxBit)
            var iterations = (63 - maxBit) / 2 + 1
            while (iterations-- > 0) {
                if (java.lang.Long.compareUnsigned(num, res + bit) >= 0) {
                    num -= res + bit
                    res = (res ushr 1) + bit
                } else {
                    res = res ushr 1
                }
                bit = bit ushr 2
            }
            if (java.lang.Long.compareUnsigned(num, res) > 0 && res != 0xFFFFFFFFL) res++
            return res
        }

        private val LOG_LUT = intArrayOf(
            0, 224, 442, 654, 861, 1063, 1259, 1450, 1636, 1817, 1992, 2163,
            2329, 2490, 2646, 2797, 2944, 3087, 3224, 3358, 3487, 3611, 3732, 3848,
            3960, 4068, 4172, 4272, 4368, 4460, 4549, 4633, 4714, 4791, 4864, 4934,
            5001, 5063, 5123, 5178, 5231, 5280, 5326, 5368, 5408, 5444, 5477, 5507,
            5533, 5557, 5578, 5595, 5610, 5622, 5631, 5637, 5640, 5641, 5638, 5633,
            5626, 5615, 5602, 5586, 5568, 5547, 5524, 5498, 5470, 5439, 5406, 5370,
            5332, 5291, 5249, 5203, 5156, 5106, 5054, 5000, 4944, 4885, 4825, 4762,
            4697, 4630, 4561, 4490, 4416, 4341, 4264, 4184, 4103, 4020, 3935, 3848,
            3759, 3668, 3575, 3481, 3384, 3286, 3186, 3084, 2981, 2875, 2768, 2659,
            2549, 2437, 2323, 2207, 2090, 1971, 1851, 1729, 1605, 1480, 1353, 1224,
            1094, 963, 830, 695, 559, 421, 282, 142, 0, 0,
        )

        /** Natural log in fixed point, as the frontend's log scale computes it. */
        private fun log(x: Long): Long {
            val integer = msb32(x) - 1
            var frac = (x - (1L shl integer)).toInt()
            frac = if (integer < 16) frac shl (16 - integer) else frac shr (integer - 16)
            val baseSeg = frac ushr (16 - 7)
            val segUnit = (1 shl 16) shr 7
            val c0 = LOG_LUT[baseSeg]
            val c1 = LOG_LUT[baseSeg + 1]
            val relPos = ((c1 - c0) * (frac - segUnit * baseSeg)) shr 16
            val fraction = (frac + c0 + relPos).toLong() and U32
            val log2 = ((integer.toLong() shl 16) + fraction) and U32
            val round = 32768L
            val loge = ((45426L * log2 + round) ushr 16) and U32
            return ((((loge shl LOG_SCALE_SHIFT) and U32) + round) ushr 16) and U32
        }
    }
}
