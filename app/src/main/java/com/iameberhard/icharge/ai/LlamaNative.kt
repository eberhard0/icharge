package com.iameberhard.icharge.ai

import android.util.Log
import java.io.File

/**
 * The llama.cpp engine compiled into the app (see src/main/cpp). Two copies of the library exist:
 * a baseline one for any arm64 phone and a fast one (dotprod/fp16/i8mm) that would crash with
 * SIGILL on chips older than about 2022, so the fast one is only loaded when /proc/cpuinfo lists
 * every feature it was built for.
 */
object LlamaNative {
    private const val TAG = "LlamaNative"

    /** Which library ended up loaded, or null when this build carries no engine. */
    val loaded: String? by lazy { load() }

    val available: Boolean get() = loaded != null

    private fun load(): String? {
        val fast = cpuHas("asimddp", "asimdhp", "i8mm")
        val order = if (fast) listOf("icharge_llama_fast", "icharge_llama") else listOf("icharge_llama")
        for (name in order) {
            try {
                System.loadLibrary(name)
                Log.i(TAG, "loaded $name")
                return name
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "no $name: ${e.message}")
            }
        }
        return null
    }

    private fun cpuHas(vararg features: String): Boolean {
        val text = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        val line = text.lineSequence().firstOrNull { it.startsWith("Features") } ?: return false
        val have = line.substringAfter(':').trim().split(' ', '\t').filter { it.isNotBlank() }.toSet()
        return features.all { it in have }
    }

    interface TokenListener {
        /** A piece of generated text as UTF-8 bytes; return false to stop generating. */
        fun onToken(bytes: ByteArray): Boolean
    }

    external fun init(path: String, nCtx: Int, nThreads: Int): Long
    external fun free(handle: Long)
    external fun stop(handle: Long)
    external fun countTokens(handle: Long, text: String): Int
    external fun generate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, listener: TokenListener): Int
    external fun systemInfo(): String
}
