package com.custom.hyperisland.service

import android.content.Context
import android.media.audiofx.Visualizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot

class AudioVisualizerManager private constructor(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var visualizer: Visualizer? = null
    private var pollingJob: Job? = null

    private val _fftBands = MutableStateFlow(FloatArray(4) { 0.05f })
    val fftBands: StateFlow<FloatArray> = _fftBands.asStateFlow()

    private val _waveformData = MutableStateFlow(FloatArray(24) { 0f })
    val waveformData: StateFlow<FloatArray> = _waveformData.asStateFlow()

    fun startCapturing(isPlaying: Boolean) {
        if (!isPlaying) {
            stopCapturing()
            return
        }
        if (visualizer != null) return

        try {
            visualizer = Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.CAPTURE_SIZE_128
                setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS)
                enabled = true
            }

            pollingJob = scope.launch {
                val fftBytes = ByteArray(visualizer?.captureSize ?: 64)
                val waveBytes = ByteArray(visualizer?.captureSize ?: 64)

                while (isActive) {
                    val viz = visualizer
                    if (viz != null && viz.enabled) {
                        viz.getFft(fftBytes)
                        viz.getWaveForm(waveBytes)

                        val bands = FloatArray(4)
                        val binSize = fftBytes.size / 8

                        for (b in 0 until 4) {
                            var sum = 0f
                            for (i in 0 until binSize) {
                                val idx = 2 * (b * binSize + i)
                                if (idx + 1 < fftBytes.size) {
                                    val r = fftBytes[idx].toFloat()
                                    val im = fftBytes[idx + 1].toFloat()
                                    sum += hypot(r, im)
                                }
                            }
                            bands[b] = (sum / (binSize * 128f)).coerceIn(0.05f, 1f)
                        }
                        _fftBands.value = bands

                        val wave = FloatArray(24)
                        val step = waveBytes.size / 24
                        for (w in 0 until 24) {
                            val byteVal = waveBytes[(w * step).coerceIn(0, waveBytes.size - 1)].toInt()
                            wave[w] = ((byteVal + 128f) / 256f).coerceIn(0f, 1f)
                        }
                        _waveformData.value = wave
                    }
                    delay(35L)
                }
            }
        } catch (_: Exception) {
            releaseVisualizer()
        }
    }

    fun stopCapturing() {
        pollingJob?.cancel()
        pollingJob = null
        releaseVisualizer()
        _fftBands.value = FloatArray(4) { 0.05f }
    }

    private fun releaseVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {}
        visualizer = null
    }

    companion object {
        @Volatile
        private var INSTANCE: AudioVisualizerManager? = null

        fun getInstance(context: Context): AudioVisualizerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AudioVisualizerManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

