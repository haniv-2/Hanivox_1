package expo.modules.hanivoxwake

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * "Hey Vox" wake word. Optional add-on: the app works without it.
 * An offline Vosk model listens for the phrase; no audio leaves the phone.
 */
class HanivoxWakeModule : Module() {
  private var wakeModel: Model? = null
  @Volatile private var wakeRunning = false
  private var wakeThread: Thread? = null

  private val context: Context
    get() = appContext.reactContext
      ?: throw CodedException("NO_CONTEXT", "Android context is not available", null)

  override fun definition() = ModuleDefinition {
    Name("HanivoxWake")

    Events("onWakeDetected", "onWakeDownload", "onWakeError")

    AsyncFunction("wakeModelInstalled") {
      File(wakeDir(), "conf").exists()
    }

    AsyncFunction("wakeDownloadModel") {
      downloadWakeModel()
      Unit
    }

    AsyncFunction("wakeStart") {
      startWakeLoop()
      Unit
    }

    AsyncFunction("wakeStop") {
      stopWakeLoop()
      Unit
    }
  }

  private fun wakeDir(): File = File(context.filesDir, "vosk-model-small-en-us-0.15")

  private fun downloadWakeModel() {
    val ctx = context
    val zipFile = File(ctx.cacheDir, "vosk-model.zip")
    val conn = URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").openConnection() as HttpURLConnection
    conn.connectTimeout = 15000
    conn.readTimeout = 30000
    conn.connect()
    if (conn.responseCode != 200) {
      throw CodedException("DOWNLOAD", "Download failed (HTTP " + conn.responseCode + ")", null)
    }
    val total = conn.contentLengthLong
    var done = 0L
    var lastPct = -1
    conn.inputStream.use { input ->
      FileOutputStream(zipFile).use { out ->
        val buf = ByteArray(32768)
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          out.write(buf, 0, n)
          done += n
          if (total > 0) {
            val pct = (done * 90 / total).toInt()
            if (pct != lastPct) {
              lastPct = pct
              sendEvent("onWakeDownload", mapOf("percent" to pct))
            }
          }
        }
      }
    }
    val target = ctx.filesDir
    val targetPath = target.canonicalPath + File.separator
    ZipInputStream(FileInputStream(zipFile)).use { zin ->
      var entry = zin.nextEntry
      while (entry != null) {
        val outFile = File(target, entry.name)
        if (!outFile.canonicalPath.startsWith(targetPath)) {
          throw CodedException("ZIP", "Unexpected file in the model download", null)
        }
        if (entry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()
          FileOutputStream(outFile).use { zin.copyTo(it) }
        }
        zin.closeEntry()
        entry = zin.nextEntry
      }
    }
    zipFile.delete()
    wakeModel = null
    if (!File(wakeDir(), "conf").exists()) {
      throw CodedException("ZIP", "The model did not unpack correctly", null)
    }
    sendEvent("onWakeDownload", mapOf("percent" to 100))
  }

  private fun isWakePhrase(text: String): Boolean {
    val t = text.trim()
    return t == "hey vox" || t == "hey box" || t == "hey fox" || t == "hey vocs"
  }

  private fun startWakeLoop() {
    if (wakeRunning) return
    val dir = wakeDir()
    if (!File(dir, "conf").exists()) {
      sendEvent("onWakeError", mapOf("message" to "The wake word model is not installed."))
      return
    }
    wakeRunning = true
    val thread = Thread {
      var audio: AudioRecord? = null
      var recognizerRef: Recognizer? = null
      try {
        if (wakeModel == null) wakeModel = Model(dir.absolutePath)
        val rec = Recognizer(wakeModel, 16000.0f, "[\"hey vox\", \"hey box\", \"hey fox\", \"hey vocs\", \"[unk]\"]")
        recognizerRef = rec
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(
          MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
          AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
          Math.max(minBuf * 2, 8192)
        )
        audio = ar
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
          throw IllegalStateException("The microphone could not be opened.")
        }
        ar.startRecording()
        val buf = ShortArray(2048)
        while (wakeRunning) {
          val n = ar.read(buf, 0, buf.size)
          if (n < 0) break
          if (n == 0) continue
          if (rec.acceptWaveForm(buf, n)) {
            val text = JSONObject(rec.result).optString("text", "")
            if (isWakePhrase(text)) {
              wakeRunning = false
              sendEvent("onWakeDetected", mapOf("text" to text))
              break
            }
          }
        }
      } catch (e: Exception) {
        wakeRunning = false
        sendEvent("onWakeError", mapOf("message" to (e.message ?: "Wake word error")))
      } finally {
        try { audio?.stop() } catch (e: Exception) { }
        try { audio?.release() } catch (e: Exception) { }
        try { recognizerRef?.close() } catch (e: Exception) { }
      }
    }
    thread.isDaemon = true
    wakeThread = thread
    thread.start()
  }

  private fun stopWakeLoop() {
    wakeRunning = false
    val t = wakeThread
    if (t != null) {
      try { t.join(2000) } catch (e: Exception) { }
    }
    wakeThread = null
  }
}
