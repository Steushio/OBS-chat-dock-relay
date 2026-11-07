package com.obs.chat.relay.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sin

class SoundPlayer {

    private val scope = CoroutineScope(Dispatchers.Default)

    fun playTone(type: String) {
        scope.launch {
            try {
                if (type == "alert") {
                    // Two-tone fanfare for SuperChat / Membership
                    generateAndPlayTone(587.33, 120) // D5
                    generateAndPlayTone(880.00, 220) // A5
                } else {
                    // Soft pop/click for chat
                    generateAndPlayTone(800.0, 45)
                }
            } catch (e: Exception) {
                // Ignore audio errors
            }
        }
    }

    private fun generateAndPlayTone(freqHz: Double, durationMs: Int) {
        val sampleRate = 44100
        val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
        val sample = DoubleArray(numSamples)
        val generatedSnd = ByteArray(2 * numSamples)

        for (i in 0 until numSamples) {
            val factor = 1.0 - (i.toDouble() / numSamples) // decay envelope
            sample[i] = sin(2.0 * Math.PI * i / (sampleRate / freqHz)) * factor
        }

        var idx = 0
        for (dVal in sample) {
            val `val` = (dVal * 32767).toInt().toShort()
            generatedSnd[idx++] = (`val`.toInt() and 0x00ff).toByte()
            generatedSnd[idx++] = (`val`.toInt() shr 8 and 0x00ff).toByte()
        }

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(generatedSnd.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(generatedSnd, 0, generatedSnd.size)
        audioTrack.play()

        Thread.sleep(durationMs.toLong() + 20)
        audioTrack.release()
    }
}
