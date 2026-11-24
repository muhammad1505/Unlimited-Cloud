package com.unlimitedcloud.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.unlimitedcloud.ui.components.AudioPlayerControls
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jflac.FLACDecoder
import org.jflac.PCMProcessor
import org.jflac.metadata.StreamInfo
import org.jflac.util.ByteData

actual fun decodeImageFile(path: String): ImageBitmap? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null
    Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
}.getOrNull()

@Composable
actual fun PlatformMediaPreview(path: String, fileName: String, modifier: Modifier) {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    when {
        extension in previewAudioExtensions ->
            DesktopAudioPreview(path = path, fileName = fileName, modifier = modifier)
        extension == "pdf" -> DesktopLimitation(
            what = "PDF",
            modifier = modifier,
        )
        else -> DesktopLimitation(
            what = "Video",
            modifier = modifier,
        )
    }
}

@Composable
private fun DesktopLimitation(what: String, modifier: Modifier) {
    Text(
        text = "Pratinjau $what belum tersedia di aplikasi desktop. " +
            "Unduh atau bagikan berkas untuk membukanya di aplikasi lain.",
        modifier = modifier.padding(12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * Desktop audio uses the JDK's own mixer, so no native dependency is bundled.
 * WAV/AIFF are read by the JDK, MP3 and FLAC are decoded to PCM by pure-Java
 * libraries; other containers stay a download.
 */
@Composable
private fun DesktopAudioPreview(path: String, fileName: String, modifier: Modifier) {
    val extension = fileName.substringAfterLast('.', "").lowercase()

    var clip by remember(path) { mutableStateOf<Clip?>(null) }
    var decodeFailed by remember(path) { mutableStateOf(false) }

    // Decoding a whole track is slow enough to jank the dialog, so it runs off
    // the UI thread and the controls appear once the audio is ready.
    LaunchedEffect(path) {
        val loaded = withContext(Dispatchers.IO) { decodeClip(extension, File(path)) }
        clip = loaded
        decodeFailed = loaded == null
    }

    DisposableEffect(clip) {
        onDispose { clip?.close() }
    }

    val ready = clip
    if (ready == null) {
        Text(
            text = if (decodeFailed) {
                "Format audio ini belum bisa diputar di desktop. " +
                    "MP3, FLAC, dan WAV didukung; unduh atau bagikan berkas untuk " +
                    "membukanya di aplikasi lain."
            } else {
                "Menyiapkan audio…"
            },
            modifier = modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        return
    }

    var playing by remember(path) { mutableStateOf(false) }
    var positionMs by remember(path) { mutableIntStateOf(0) }
    val durationMs = remember(ready) { (ready.microsecondLength / 1000L).toInt().coerceAtLeast(0) }

    // A Clip does not report progress, so the position is polled while playing.
    LaunchedEffect(playing, ready) {
        while (playing) {
            val position = ready.microsecondPosition
            positionMs = (position / 1000L).toInt()
            // A Clip stops on its own at the end but keeps reporting "running",
            // so the button used to stay on "Jeda" for a finished track.
            if (ready.microsecondLength > 0 && position >= ready.microsecondLength) {
                ready.stop()
                playing = false
            }
            delay(400)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AudioPlayerControls(
            positionMs = positionMs,
            durationMs = durationMs,
            playing = playing,
            enabled = true,
            onSeek = { target ->
                ready.microsecondPosition = target * 1000L
                positionMs = target
            },
            onTogglePlay = {
                if (playing) {
                    ready.stop()
                } else {
                    if (ready.microsecondPosition >= ready.microsecondLength) {
                        ready.microsecondPosition = 0
                    }
                    ready.start()
                }
                playing = !playing
            },
        )
    }
}

/** PCM decoded from an audio file, ready to hand to the JDK mixer. */
private class PcmAudio(val format: AudioFormat, val bytes: ByteArray)

/** Decodes [file] and loads it into a ready-to-play [Clip]. */
private fun decodeClip(extension: String, file: File): Clip? {
    val audio = decodeAudio(extension, file) ?: return null
    val frameSize = audio.format.frameSize
    if (frameSize <= 0) return null
    return runCatching {
        AudioInputStream(
            ByteArrayInputStream(audio.bytes),
            audio.format,
            (audio.bytes.size / frameSize).toLong(),
        ).use { stream ->
            AudioSystem.getClip().apply { open(stream) }
        }
    }.getOrNull()
}

private fun decodeAudio(extension: String, file: File): PcmAudio? {
    if (!file.isFile) return null
    return when (extension) {
        "mp3" -> decodeMp3(file)
        "flac" -> decodeFlac(file)
        // The JDK reads WAV, AIFF and AU on its own.
        else -> decodeWithJdk(file)
    }
}

private fun decodeWithJdk(file: File): PcmAudio? = runCatching {
    AudioSystem.getAudioInputStream(file).use { stream ->
        PcmAudio(format = stream.format, bytes = stream.readBytes())
    }
}.getOrNull()

private fun decodeMp3(file: File): PcmAudio? = runCatching {
    val pcm = ByteArrayOutputStream()
    val decoder = Decoder()
    var format: AudioFormat? = null
    val bitstream = Bitstream(BufferedInputStream(FileInputStream(file)))
    try {
        while (true) {
            val header = bitstream.readFrame() ?: break
            val samples = decoder.decodeFrame(header, bitstream) as? SampleBuffer ?: break
            if (format == null) {
                format = AudioFormat(
                    decoder.outputFrequency.toFloat(),
                    16,
                    decoder.outputChannels.coerceAtLeast(1),
                    true,
                    false,
                )
            }
            // JLayer hands back signed 16-bit samples; the mixer wants them
            // little-endian, the same layout a WAV file carries.
            val buffer = samples.buffer
            for (index in 0 until samples.bufferLength) {
                val sample = buffer[index].toInt()
                pcm.write(sample and 0xFF)
                pcm.write((sample shr 8) and 0xFF)
            }
            bitstream.closeFrame()
        }
    } finally {
        bitstream.close()
    }
    format?.let { PcmAudio(it, pcm.toByteArray()) }
}.getOrNull()

private fun decodeFlac(file: File): PcmAudio? = runCatching {
    val pcm = ByteArrayOutputStream()
    var format: AudioFormat? = null
    var bitsPerSample = 16

    val decoder = FLACDecoder(BufferedInputStream(FileInputStream(file)))
    decoder.addPCMProcessor(object : PCMProcessor {
        override fun processStreamInfo(streamInfo: StreamInfo) {
            bitsPerSample = streamInfo.bitsPerSample
            val sampleBits = bitsPerSample.coerceAtMost(16)
            // jFLAC marks its output big-endian, but it writes little-endian
            // samples, so the format is built here instead of reusing its own.
            format = AudioFormat(
                streamInfo.sampleRate.toFloat(),
                sampleBits,
                streamInfo.channels.coerceAtLeast(1),
                sampleBits > 8,
                false,
            )
        }

        override fun processPCM(decoded: ByteData) {
            val data = decoded.data
            val length = decoded.len
            if (bitsPerSample <= 16) {
                pcm.write(data, 0, length)
                return
            }
            // 24-bit tracks are narrowed to 16-bit by dropping the low byte,
            // which is what the mixers here accept.
            var index = 0
            while (index + 2 < length) {
                pcm.write(data[index + 1].toInt() and 0xFF)
                pcm.write(data[index + 2].toInt() and 0xFF)
                index += 3
            }
        }
    })
    decoder.decode()

    format?.let { PcmAudio(it, pcm.toByteArray()) }
}.getOrNull()
