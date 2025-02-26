package com.audioparser

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder.AudioSource
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import com.facebook.react.bridge.WritableNativeArray

import com.facebook.react.bridge.WritableArray
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.ByteOrder


class AudioParserModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(
        reactContext
    ) {
    private var recordThread: RecordThread? = null
    private var fileThread: FileProcessingThread? = null
    private var urlThread: URLProcessingThread? = null
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

    data class WavHeader(val sampleRate: Int, val numChannels: Int, val bitDepth: Int)

    private inner class URLProcessingThread(
        private val urlString: String,
        private val reactContext: ReactApplicationContext
    ) : Thread() {
        private val eventEmitter = reactContext.getJSModule(ReactContext.RCTDeviceEventEmitter::class.java)
        var isProcessing = false
        var audioTrack: AudioTrack? = null
        var bytesRead: Int = 0
        var percentageRead: Double = 0.0
        var audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
        var numChannels: Int = 1

        override fun run() {
            isProcessing = true
            var inputStream: InputStream? = null
            try {
                val url = URL(urlString)
                val connection = url.openConnection()
                connection.connect()
                inputStream = connection.getInputStream()
                val totalSize = connection.contentLength.toFloat()

                val bufferSize = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                val buffer = ByteArray(bufferSize)
                var totalBytesRead = 0

                val header = parseWavHeader(inputStream)
                audioTrack = setupAudioTrack(header)
                audioTrack?.play()

                while (isProcessing && inputStream.read(buffer).also { bytesRead = it } != -1) {
                    totalBytesRead += bytesRead
                    percentageRead = (totalBytesRead / totalSize * 100.0)
                    processAndPlayChunk(buffer, bytesRead)
                }
            } catch (e: IOException) {
                Log.e("AudioParser", "Error processing URL", e)
            } finally {
                inputStream?.close()
                audioTrack?.stop()
                audioTrack?.release()
                isProcessing = false
            }
        }

        private fun parseWavHeader(inputStream: InputStream): WavHeader {
            val buffer = ByteArray(44)  // Standard size of WAV header
            inputStream.read(buffer, 0, 44)
            val wrappedBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

            // Skipping first 22 bytes (RIFF header, etc.)
            wrappedBuffer.position(22)
            val numChannels = wrappedBuffer.short.toInt()
            val sampleRate = wrappedBuffer.int
            wrappedBuffer.position(34)
            val bitDepth = wrappedBuffer.short.toInt()

            return WavHeader(sampleRate, numChannels, bitDepth)
        }

        private fun setupAudioTrack(header: WavHeader): AudioTrack {
            val channelConfig = if (header.numChannels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            audioFormat = if (header.bitDepth == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
            val minBufferSize = AudioTrack.getMinBufferSize(header.sampleRate, channelConfig, audioFormat)
            numChannels = header.numChannels
            return AudioTrack(
                AudioManager.STREAM_MUSIC,
                header.sampleRate,
                channelConfig,
                audioFormat,
                max(4096, minBufferSize),
                AudioTrack.MODE_STREAM
            )
        }


        private fun processAndPlayChunk(data: ByteArray, bytesRead: Int) {
            // Assuming data is already PCM encoded, if not, decoding needs to be handled
            audioTrack?.write(data, 0, bytesRead)
            processBuffer(data)
        }

        private fun processBuffer(sampleData: ByteArray) {
            if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
                val shortData = ByteBuffer.wrap(sampleData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val buffer = ShortArray(shortData.remaining())
                shortData.get(buffer)
                if (numChannels == 1) {
                    if (buffer.isNotEmpty())
                        processMono(buffer)
                } else {
                    if (buffer.isNotEmpty())
                        processStereo(buffer)
                }
            }
            if (audioFormat == AudioFormat.ENCODING_PCM_8BIT) {
                val buffer = ByteArray(sampleData.size)
                for (i in sampleData.indices) {
                    buffer[i] = sampleData[i]
                }
                if (numChannels == 1) {
                    processMono(buffer)
                } else {
                    processStereo(buffer)
                }
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
            val maxVolume = if (audioFormat == AudioFormat.ENCODING_PCM_8BIT) 128.0 else 32768.0
            val normalizedVolume = normalizeVolume(volume, maxVolume, 0.0, 10.0)
            val fftResult = performFFT(fftBuffer)
            val params = Arguments.createMap().apply {
                putDouble("volume", normalizedVolume)
                putArray("buckets", fftResult.toWritableArray())
                putDouble("percentageRead", percentageRead);
            }
            eventEmitter.emit("URLData", params)
        }

        private fun emitStereoResults(leftFftBuffer: FloatArray, leftVolume: Double, rightFftBuffer: FloatArray, rightVolume: Double) {
            val leftFftResult = performFFT(leftFftBuffer)
            val rightFftResult = performFFT(rightFftBuffer)

            val maxVolume = if (audioFormat == AudioFormat.ENCODING_PCM_8BIT) 128.0 else 32768.0

            val normalizedLeftVolume = normalizeVolume(leftVolume, maxVolume, 0.0, 10.0)
            val normalizedRightVolume = normalizeVolume(rightVolume, maxVolume, 0.0, 10.0)

            val params = Arguments.createMap().apply {
                putDouble("volume", (normalizedLeftVolume + normalizedRightVolume) / 2)
                putArray("buckets", leftFftResult.toWritableArray())
                putDouble("percentageRead", percentageRead);

                val channelData = Arguments.createMap().apply {
                    putMap("left", Arguments.createMap().apply {
                        putDouble("volume", normalizedLeftVolume)
                        putArray("buckets", leftFftResult.toWritableArray())
                    })
                    putMap("right", Arguments.createMap().apply {
                        putDouble("volume", normalizedRightVolume)
                        putArray("buckets", rightFftResult.toWritableArray())
                    })
                }
                putMap("channel", channelData)
            }
            eventEmitter.emit("URLData", params)
        }

        private fun performFFT(buffer: FloatArray): FloatArray {
            val fft = LibFFT(buffer.size / numChannels)
            val fftResult = fft.transform(buffer)
            return fftResult
        }

        private fun normalizeVolume(volume: Double, maxPossibleVolume: Double, minTargetVolume: Double, maxTargetVolume: Double): Double {
            return minTargetVolume + (volume / maxPossibleVolume) * (maxTargetVolume - minTargetVolume)
        }

        private fun FloatArray.toWritableArray(): WritableArray {
            return WritableNativeArray().also { array ->
                this.forEach { array.pushDouble(it.toDouble()) }
            }
        }
    }

    private inner class FileProcessingThread(
        private val uriString: String,
        private val reactContext: ReactApplicationContext
    ) : Thread() {
        lateinit var format: MediaFormat
        private val eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        var fileSize = 0L
        var bytesRead = 0L
        var isReading = false
        var audioTrack: AudioTrack? = null
        override fun run() {
            isReading = true;
            if (uriString.isEmpty()) {
                Log.e("AudioParser", "Invalid URI")
                return;
            }

            val uri = Uri.parse(uriString)
            if (!uri.isAbsolute || uri.scheme == null) {
                Log.e("AudioParser", "Invalid URI")
                return;
            }
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null

            try {
                fileSize = getFileSize(uri)
            }
            catch (e: IOException) {
                Log.e("AudioParser", "Error getting file size", e)
                return;
            }

            try {
                extractor.setDataSource(reactContext, uri, null)
                val trackIndex = selectTrack(extractor)
                if (trackIndex < 0) {
                    throw RuntimeException("No audio track found in file")
                }
                format = extractor.getTrackFormat(trackIndex)

                // Check if the file MIME type is WAV
                val mime = format.getString(MediaFormat.KEY_MIME)
                Log.d("AudioParser", "MIME type: $mime");
                if (mime == null || !(mime.equals("audio/x-wav") || mime.equals("audio/raw") || mime.equals("audio/x-raw") || mime.equals("audio/wav"))) {
                    val params = Arguments.createMap().apply {
                        putDouble("volume", 0.0)
                        putArray("buckets", WritableNativeArray())
                        putDouble("percentageRead", 0.0);
                    }
                    eventEmitter.emit("FileData", params)
                    return;
                }

                extractor.selectTrack(trackIndex)
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val bufferInfo = MediaCodec.BufferInfo()
                val inputBuffers = codec.inputBuffers
                val outputBuffers = codec.outputBuffers
                var isEOS = false

                audioTrack = setupAudioTrack(format)
                audioTrack?.play()

                while (!isEOS && isReading) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = inputBuffers[inputBufferIndex]
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                            val params = Arguments.createMap().apply {
                                putDouble("volume", 0.0)
                                putArray("buckets", WritableNativeArray())
                                putDouble("percentageRead", 100.0);
                            }
                            eventEmitter.emit("FileData", params)
                        } else {
                            bytesRead += sampleSize
                            codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    while (outputBufferIndex >= 0) {
                        val outputBuffer = outputBuffers[outputBufferIndex]
                        val sampleData = ByteArray(bufferInfo.size)
                        outputBuffer.get(sampleData)
                        outputBuffer.clear()

                        audioTrack?.write(sampleData, 0, bufferInfo.size)
                        processBuffer(sampleData, format)

                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e("AudioParser", "Error setting data source", e)
            } finally {
                codec?.stop()
                codec?.release()
                extractor.release()
                audioTrack?.stop()
                audioTrack?.release()
                isReading = false
            }
        }

        private fun setupAudioTrack(format: MediaFormat): AudioTrack {
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelConfig = if (format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2)
                AudioFormat.CHANNEL_OUT_STEREO
            else
                AudioFormat.CHANNEL_OUT_MONO

            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            return AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize,
                AudioTrack.MODE_STREAM
            )
        }

        private fun selectTrack(extractor: MediaExtractor): Int {
            val numTracks = extractor.trackCount
            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime != null) {
                    if (mime.startsWith("audio/")) {
                        return i
                    }
                }
            }
            return -1
        }

        private fun processBuffer(sampleData: ByteArray, format: MediaFormat) {
            val audioFormat = if (format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_8BIT) {
                AudioFormat.ENCODING_PCM_8BIT
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
                val shortData = ByteBuffer.wrap(sampleData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val buffer = ShortArray(shortData.remaining())
                shortData.get(buffer)
                if (format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) {
                    if (buffer.isNotEmpty())
                        processMono(buffer)
                } else {
                    if (buffer.isNotEmpty())
                        processStereo(buffer)
                }
            }
            if (audioFormat == AudioFormat.ENCODING_PCM_8BIT) {
                val buffer = ByteArray(sampleData.size)
                for (i in sampleData.indices) {
                    buffer[i] = sampleData[i]
                }
                if (format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) {
                    processMono(buffer)
                } else {
                    processStereo(buffer)
                }
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
            val maxVolume = if (format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_8BIT) 128.0 else 32768.0
            val normalizedVolume = normalizeVolume(volume, maxVolume, 0.0, 10.0)
            val fftResult = performFFT(fftBuffer)
            val params = Arguments.createMap().apply {
                putDouble("volume", normalizedVolume)
                putArray("buckets", fftResult.toWritableArray())
                putDouble("percentageRead", (bytesRead.toDouble() / fileSize.toDouble()) * 100);
            }
            eventEmitter.emit("FileData", params)
        }

        private fun emitStereoResults(leftFftBuffer: FloatArray, leftVolume: Double, rightFftBuffer: FloatArray, rightVolume: Double) {
            val leftFftResult = performFFT(leftFftBuffer)
            val rightFftResult = performFFT(rightFftBuffer)

            val maxVolume = if (format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_8BIT) 128.0 else 32768.0

            val normalizedLeftVolume = normalizeVolume(leftVolume, maxVolume, 0.0, 10.0)
            val normalizedRightVolume = normalizeVolume(rightVolume, maxVolume, 0.0, 10.0)

            val params = Arguments.createMap().apply {
                putDouble("volume", (normalizedLeftVolume + normalizedRightVolume) / 2)
                putArray("buckets", leftFftResult.toWritableArray())
                putDouble("percentageRead", (bytesRead.toDouble() / fileSize.toDouble()) * 100);

                val channelData = Arguments.createMap().apply {
                    putMap("left", Arguments.createMap().apply {
                        putDouble("volume", normalizedLeftVolume)
                        putArray("buckets", leftFftResult.toWritableArray())
                    })
                    putMap("right", Arguments.createMap().apply {
                        putDouble("volume", normalizedRightVolume)
                        putArray("buckets", rightFftResult.toWritableArray())
                    })
                }
                putMap("channel", channelData)
            }
            eventEmitter.emit("FileData", params)
        }

        private fun performFFT(buffer: FloatArray): FloatArray {
            val fft = LibFFT(buffer.size / format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            val fftResult = fft.transform(buffer)
            return fftResult
        }

        private fun normalizeVolume(volume: Double, maxPossibleVolume: Double, minTargetVolume: Double, maxTargetVolume: Double): Double {
            return minTargetVolume + (volume / maxPossibleVolume) * (maxTargetVolume - minTargetVolume)
        }

        private fun FloatArray.toWritableArray(): WritableArray {
            return WritableNativeArray().also { array ->
                this.forEach { array.pushDouble(it.toDouble()) }
            }
        }

        private fun getFileSize(uri: Uri): Long {
            val fileDescriptor = reactContext.contentResolver.openFileDescriptor(uri, "r") ?: return 0
            return fileDescriptor.statSize
        }
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
            emitResults(fftBuffer, volume, buffer)
        }

        private fun processStereo(buffer: Any) {
            val (leftBuffer, rightBuffer) = splitChannels(buffer)
            val (leftFftBuffer, leftVolume) = convertToFloatArrayAndCalculateVolume(leftBuffer)
            val (rightFftBuffer, rightVolume) = convertToFloatArrayAndCalculateVolume(rightBuffer)
            emitStereoResults(leftFftBuffer, leftVolume, rightFftBuffer, rightVolume, buffer)
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

        private fun emitResults(fftBuffer: FloatArray, volume: Double, rawBuffer: Any) {
            val maxVolume = if (audioFormat == AudioFormat.ENCODING_PCM_8BIT) 128.0 else 32768.0
            val normalizedVolume = normalizeVolume(volume, maxVolume, 0.0, 10.0)
            val fftResult = performFFT(fftBuffer)
            val params = Arguments.createMap().apply {
                putDouble("volume", normalizedVolume)
                putArray("buckets", fftResult.toWritableArray())
                putArray("rawBuffer", convertBufferToWritableArray(rawBuffer))
            }
            eventEmitter.emit("RecordingData", params)
        }

        private fun emitStereoResults(leftFftBuffer: FloatArray, leftVolume: Double, rightFftBuffer: FloatArray, rightVolume: Double, rawBuffer: Any) {
            val leftFftResult = performFFT(leftFftBuffer)
            val rightFftResult = performFFT(rightFftBuffer)

            val maxVolume = if (audioFormat == AudioFormat.ENCODING_PCM_8BIT) 128.0 else 32768.0

            val normalizedLeftVolume = normalizeVolume(leftVolume, maxVolume, 0.0, 10.0)
            val normalizedRightVolume = normalizeVolume(rightVolume, maxVolume, 0.0, 10.0)

            val params = Arguments.createMap().apply {
                putDouble("volume", (normalizedLeftVolume + normalizedRightVolume) / 2)
                putArray("buckets", leftFftResult.toWritableArray())

                val channelData = Arguments.createMap().apply {
                    putMap("left", Arguments.createMap().apply {
                        putDouble("volume", normalizedLeftVolume)
                        putArray("buckets", leftFftResult.toWritableArray())
                    })
                    putMap("right", Arguments.createMap().apply {
                        putDouble("volume", normalizedRightVolume)
                        putArray("buckets", rightFftResult.toWritableArray())
                    })
                }
                putMap("channel", channelData)
                putArray("rawBuffer", convertBufferToWritableArray(rawBuffer))
            }
            eventEmitter.emit("RecordingData", params)
        }

        private fun performFFT(buffer: FloatArray): FloatArray {
            val fft = LibFFT(bufferSize / if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2)
            val fftResult = fft.transform(buffer)
            return fftResult
        }

        private fun normalizeVolume(volume: Double, maxPossibleVolume: Double, minTargetVolume: Double, maxTargetVolume: Double): Double {
            return minTargetVolume + (volume / maxPossibleVolume) * (maxTargetVolume - minTargetVolume)
        }

        private fun FloatArray.toWritableArray(): WritableArray {
            return WritableNativeArray().also { array ->
                this.forEach { array.pushDouble(it.toDouble()) }
            }
        }

        private fun convertBufferToWritableArray(buffer: Any): WritableArray {
            val writableArray = WritableNativeArray()
            when (buffer) {
                is ByteArray -> buffer.forEach { writableArray.pushInt(it.toInt() and 0xFF) }
                is ShortArray -> buffer.forEach { writableArray.pushInt(it.toInt()) }
            }
            return writableArray
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
    fun startFromFile(uriString: String) {
        if (fileThread != null) {
            if (fileThread?.isReading == true) {
                return
            }
        }
        fileThread = FileProcessingThread(uriString, reactContext)
        fileThread?.start()
    }

    @ReactMethod
    fun stopReadingFile() {
        if (fileThread != null) {
            fileThread!!.isReading = false
            fileThread = null
        }
    }

    @ReactMethod
    fun startFromURL(urlString: String) {
        if (urlThread != null) {
            if (urlThread?.isProcessing == true) {
                return
            }
        }
        urlThread = URLProcessingThread(urlString, reactContext)
        urlThread?.start()
    }

    @ReactMethod
    fun stopReadingURL() {
        if (urlThread != null) {
            urlThread!!.isProcessing = false
            urlThread = null
        }
    }

    @ReactMethod
    fun addListener(eventName: String?) {
    }

    @ReactMethod
    fun removeListeners(count: Int?) {
    }
}

