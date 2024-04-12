package com.audioparser

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.util.Base64
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import com.facebook.react.bridge.WritableNativeArray

import com.facebook.react.bridge.WritableArray
import java.nio.ByteOrder


class AudioParserModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(
        reactContext
    ) {
    private var recordThread: RecordThread? = null
    override fun getName(): String {
        return "AudioParser"
    }

    @ReactMethod
    fun init(options: ReadableMap) {
        var sampleRateInHz = 44100
        if (options.hasKey("sampleRate")) {
            sampleRateInHz = options.getInt("sampleRate")
        }
        var channelConfig = AudioFormat.CHANNEL_IN_MONO
        if (options.hasKey("channels")) {
            if (options.getInt("channels") == 2) {
                channelConfig = AudioFormat.CHANNEL_IN_STEREO
            }
        }
        var audioFormat = AudioFormat.ENCODING_PCM_16BIT
        if (options.hasKey("bitsPerSample")) {
            if (options.getInt("bitsPerSample") == 8) {
                audioFormat = AudioFormat.ENCODING_PCM_8BIT
            }
        }
        var audioSource = AudioSource.VOICE_RECOGNITION
        if (options.hasKey("audioSource")) {
            audioSource = options.getInt("audioSource")
        }
        var bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        if (options.hasKey("bucketCount")) {
            bufferSize = max(bufferSize, options.getInt("bucketCount") * 2)
        }
        recordThread = RecordThread(
            audioSource, sampleRateInHz, channelConfig, audioFormat, bufferSize,
            reactContext
        )
    }

    @SuppressLint("MissingPermission")
    private inner class RecordThread(
        private val audioSource: Int,
        private val sampleRateInHz: Int,
        private val channelConfig: Int,
        private val audioFormat: Int,
        private val bufferSize: Int,
        reactContext: ReactApplicationContext
    ) : Thread() {
        private val eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        private var recorder: AudioRecord? = null
        var isRecording = false

        init {
            val recordingBufferSize = bufferSize * 3
            recorder = AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, recordingBufferSize)
        }

        override fun run() {
            recorder?.startRecording()
            try {
                while (isRecording) {
                    if (audioFormat == AudioFormat.ENCODING_PCM_8BIT) {
                        val buffer = ByteArray(bufferSize)
                        val bytesRead = recorder!!.read(buffer, 0, bufferSize)
                        if (bytesRead > 0) {
                            if (channelConfig == AudioFormat.CHANNEL_IN_MONO) {
                                processMono(buffer)
                            } else {
                                processStereo(buffer)
                            }
                        }
                    } else if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
                        val buffer = ShortArray(bufferSize)
                        val bytesRead = recorder!!.read(buffer, 0, bufferSize)
                        if (bytesRead > 0) {
                            if (channelConfig == AudioFormat.CHANNEL_IN_MONO) {
                                processMono(buffer)
                            } else {
                                processStereo(buffer)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                recorder?.stop()
                recorder?.release()
                recorder = null
            }
        }

        private fun processMono(buffer: Any) {
            val (fftBuffer, volume) = convertToFloatArrayAndCalculateVolume(buffer)
            emitResults(fftBuffer, volume)
        }

        private fun processStereo(buffer: Any) {
            val (leftBuffer, rightBuffer) = splitChannels(buffer)
            val (leftFftBuffer, leftVolume) = convertToFloatArrayAndCalculateVolume(leftBuffer)
            val (rightFftBuffer, rightVolume) = convertToFloatArrayAndCalculateVolume(rightBuffer)
            emitStereoResults(leftFftBuffer, leftVolume, rightFftBuffer, rightVolume)
        }

        private fun splitChannels(buffer: Any): Pair<Any, Any> {
            if (buffer is ByteArray) {
                val left = ByteArray(buffer.size / 2)
                val right = ByteArray(buffer.size / 2)
                for (i in buffer.indices step 2) {
                    left[i / 2] = buffer[i]
                    right[i / 2] = buffer[i + 1]
                }
                return Pair(left, right)
            } else if (buffer is ShortArray) {
                val left = ShortArray(buffer.size / 2)
                val right = ShortArray(buffer.size / 2)
                for (i in buffer.indices step 2) {
                    left[i / 2] = buffer[i]
                    right[i / 2] = buffer[i + 1]
                }
                return Pair(left, right)
            }
            throw IllegalArgumentException("Unknown buffer type")
        }

        private fun convertToFloatArrayAndCalculateVolume(buffer: Any): Pair<FloatArray, Double> {
            var volume = 0.0
            val floatBuffer = when (buffer) {
                is ByteArray -> FloatArray(buffer.size) {
                    ((buffer[it].toInt() and 0xFF) - 128).also { byteValue ->
                        volume += abs(byteValue)
                    }.toFloat()
                }
                is ShortArray -> FloatArray(buffer.size) {
                    buffer[it].toFloat().also { shortValue ->
                        volume += abs(shortValue)
                    }
                }
                else -> throw IllegalArgumentException("Unknown buffer type")
            }
            volume /= floatBuffer.size
            return Pair(floatBuffer, volume)
        }

        private fun emitResults(fftBuffer: FloatArray, volume: Double) {
            val fftResult = performFFT(fftBuffer)
            val params = Arguments.createMap().apply {
                putDouble("volume", volume)
                putArray("buckets", fftResult.toWritableArray())
            }
            eventEmitter.emit("audioData", params)
        }

        private fun emitStereoResults(leftFftBuffer: FloatArray, leftVolume: Double, rightFftBuffer: FloatArray, rightVolume: Double) {
            val leftFftResult = performFFT(leftFftBuffer)
            val rightFftResult = performFFT(rightFftBuffer)

            val params = Arguments.createMap().apply {
                putDouble("volume", (leftVolume + rightVolume) / 2)
                putArray("buckets", leftFftResult.toWritableArray())

                val channelData = Arguments.createMap().apply {
                    putMap("left", Arguments.createMap().apply {
                        putDouble("volume", leftVolume)
                        putArray("buckets", leftFftResult.toWritableArray())
                    })
                    putMap("right", Arguments.createMap().apply {
                        putDouble("volume", rightVolume)
                        putArray("buckets", rightFftResult.toWritableArray())
                    })
                }
                putMap("channel", channelData)
            }
            eventEmitter.emit("audioData", params)
        }

        private fun performFFT(buffer: FloatArray): FloatArray {
            val fft = LibFFT(bufferSize / if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2)
            val fftResult = fft.transform(buffer)
            return fftResult
        }

        private fun FloatArray.toWritableArray(): WritableArray {
            return WritableNativeArray().also { array ->
                this.forEach { array.pushDouble(it.toDouble()) }
            }
        }
    }

    @ReactMethod
    fun start() {
        if (recordThread == null || recordThread!!.isRecording) {
            return
        }
        recordThread!!.isRecording = true
        recordThread!!.start()
    }

    @ReactMethod
    fun stop() {
        if (recordThread != null) {
            recordThread!!.isRecording = false
            recordThread = null
        }
    }

    @ReactMethod
    fun addListener(eventName: String?) {
    }

    @ReactMethod
    fun removeListeners(count: Int?) {
    }
}

