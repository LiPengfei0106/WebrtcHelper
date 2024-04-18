package cn.cleartv.webrtchelper

import android.util.Log
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

/**
 * Log both to Android logger (so that logs are visible in "adb logcat") and standard output/error (so that they are visible in the terminal
 * directly).
 */
internal object L {
    private const val TAG = "WebrtcHelper"
    private const val PREFIX = "[WebrtcHelper] "
    private const val STACK_TRACE = 2
    private val CONSOLE_OUT = PrintStream(FileOutputStream(FileDescriptor.out))
    private val CONSOLE_ERR = PrintStream(FileOutputStream(FileDescriptor.err))
    private var threshold = Level.INFO

    fun disableSystemStreams() {
        val nullStream = PrintStream(NullOutputStream())
        System.setOut(nullStream)
        System.setErr(nullStream)
    }

    /**
     * Initialize the log level.
     *
     *
     * Must be called before starting any new thread.
     *
     * @param level the log level
     */
    fun setLogLevel(level: Level) {
        threshold = level
    }

    fun getLogThreshold(): Level {
        return threshold
    }

    fun isEnabled(level: Level): Boolean {
        return level.ordinal >= threshold.ordinal
    }

    private fun getStackTrace(trace: Int): String {
        if (isEnabled(Level.DEBUG)) {
            // debug 开启的情况下才打印
            val element = Throwable().stackTrace.getOrNull(trace) ?: return ""
            return "(${element.fileName}:${element.lineNumber})"
        }
        return ""
    }

    fun v(message: String?) {
        if (isEnabled(Level.VERBOSE)) {
            val msg = "${message ?: "null"}${getStackTrace(STACK_TRACE)}"
            Log.v(TAG, msg)
            CONSOLE_OUT.print("${PREFIX}VERBOSE: $message\n".trimIndent())
        }
    }

    fun d(message: String?) {
        if (isEnabled(Level.DEBUG)) {
            val msg = "${message ?: "null"}${getStackTrace(STACK_TRACE)}"
            Log.d(TAG, msg)
            CONSOLE_OUT.print("${PREFIX}DEBUG: $message\n".trimIndent())
        }
    }

    fun i(message: String?) {
        if (isEnabled(Level.INFO)) {
            val msg = "${message ?: "null"}${getStackTrace(STACK_TRACE)}"
            Log.i(TAG, msg)
            CONSOLE_OUT.print("${PREFIX}INFO: $message\n".trimIndent())
        }
    }

    fun w(message: String?, throwable: Throwable? = null) {
        if (isEnabled(Level.WARN)) {
            val msg = "${message ?: "null"}${getStackTrace(STACK_TRACE + 1)}"
            Log.w(TAG, msg, throwable)
            CONSOLE_OUT.print("${PREFIX}WARN: $message\n".trimIndent())
            throwable?.printStackTrace(CONSOLE_ERR)
        }
    }

    fun e(message: String?, throwable: Throwable? = null) {
        if (isEnabled(Level.ERROR)) {
            val msg = "${message ?: "null"}${getStackTrace(STACK_TRACE + 1)}"
            Log.e(TAG, msg, throwable)
            CONSOLE_OUT.print("${PREFIX}ERROR: $message\n".trimIndent())
            throwable?.printStackTrace(CONSOLE_ERR)
        }
    }

    enum class Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }

    internal class NullOutputStream : OutputStream() {
        override fun write(b: ByteArray) {
            // ignore
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            // ignore
        }

        override fun write(b: Int) {
            // ignore
        }
    }
}