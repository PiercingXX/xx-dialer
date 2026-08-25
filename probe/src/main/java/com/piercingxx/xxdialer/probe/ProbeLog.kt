package com.piercingxx.xxdialer.probe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object ProbeLog {

    private const val TAG = "XXProbe"
    private const val FILE_NAME = "probe_log.txt"
    private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    private val lock = Any()
    private val history = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var uiSink: ((String) -> Unit)? = null
    private var logFile: File? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile == null) {
                logFile = File(context.applicationContext.filesDir, FILE_NAME)
            }
        }
    }

    fun file(): File? = logFile

    fun attachSink(sink: (String) -> Unit) {
        uiSink = sink
        sink(snapshot())
    }

    fun detachSink() {
        uiSink = null
    }

    fun log(section: String, vararg kv: Pair<String, Any?>) =
        write(section, kv.joinToString(" ") { (key, value) -> "$key=$value" })

    fun event(section: String, message: String) = write(section, message)

    private fun snapshot(): String = synchronized(lock) { history.toString() }

    private fun write(section: String, body: String) {
        val line = "${OffsetDateTime.now().format(TIMESTAMP_FORMAT)} [$section] $body"
        synchronized(lock) {
            history.append(line).append('\n')
            logFile?.let { file ->
                try {
                    file.appendText(line + "\n")
                } catch (t: IOException) {
                    Log.e(TAG, "probe log append failed", t)
                }
            }
        }
        Log.i(TAG, line)
        val sink = uiSink ?: return
        val text = snapshot()
        if (Looper.myLooper() == Looper.getMainLooper()) sink(text)
        else mainHandler.post { uiSink?.invoke(text) }
    }
}
