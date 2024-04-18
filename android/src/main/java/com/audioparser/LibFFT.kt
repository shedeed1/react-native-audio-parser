package com.audioparser

import kotlin.math.*

class LibFFT(bufferSize: Int) {
    private val fftNLog: Int
    private val fftN: Int
    private val miny: Double
    private val real: MutableList<Double>
    private val imag: MutableList<Double>
    private val sinTable: MutableList<Double>
    private val cosTable: MutableList<Double>
    private val bitReverse: MutableList<Int>

    init {
        fftNLog = round(log(bufferSize.toDouble(), 2.0)).toInt()
        fftN = 1 shl fftNLog
        miny = (fftN shl 2) * sqrt(2.0)

        real = MutableList(fftN) { 0.0 }
        imag = MutableList(fftN) { 0.0 }
        sinTable = MutableList(fftN / 2) { 0.0 }
        cosTable = MutableList(fftN / 2) { 0.0 }
        bitReverse = MutableList(fftN) { 0 }

        var i: Int
        var j: Int
        var k: Int
        var reve: Int
        for (i in 0 until fftN) {
            k = i
            reve = 0
            for (j in 0 until fftNLog) {
                reve = reve shl 1 or (k and 1)
                k = k ushr 1
            }
            bitReverse[i] = reve
        }

        val dt = 2 * PI / fftN
        for (i in fftN / 2 - 1 downTo 1) {
            val theta = i * dt
            cosTable[i] = cos(theta)
            sinTable[i] = sin(theta)
        }
    }

    fun transform(inBuffer: FloatArray): FloatArray {
        val paddedInput = if (inBuffer.size < fftN) {
            FloatArray(fftN) { index ->
                if (index < inBuffer.size) inBuffer[index] else 0f
            }
        } else {
            inBuffer
        }

        var j0 = 1
        var idx = fftNLog - 1
        var cosv: Double
        var sinv: Double
        var tmpr: Double
        var tmpi: Double
        for (i in 0 until fftN) {
            real[i] = paddedInput[bitReverse[i]].toDouble()
            imag[i] = 0.0
        }

        var i = fftNLog
        while (i != 0) {
            var j = 0
            while (j != j0) {
                cosv = cosTable[j shl idx]
                sinv = sinTable[j shl idx]
                var k = j
                while (k < fftN) {
                    val ir = k + j0
                    tmpr = cosv * real[ir] - sinv * imag[ir]
                    tmpi = cosv * imag[ir] + sinv * real[ir]
                    real[ir] = real[k] - tmpr
                    imag[ir] = imag[k] - tmpi
                    real[k] += tmpr
                    imag[k] += tmpi
                    k += j0 shl 1
                }
                j++
            }
            i--
            j0 = j0 shl 1
            idx--
        }

        val outBuffer = FloatArray(fftN / 2)
        sinv = miny
        cosv = -miny
        for (i in fftN / 2 downTo 1) {
            tmpr = real[i]
            tmpi = imag[i]
            outBuffer[i - 1] = if (tmpr > cosv && tmpr < sinv && tmpi > cosv && tmpi < sinv) 0f else round(tmpr * tmpr + tmpi * tmpi).toFloat()
        }
        return outBuffer
    }

    val bufferSize: Int
        get() = fftN
}
