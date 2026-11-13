package com.example.tasksync.ai

import java.io.Closeable
import java.io.File

/** SentencePiece tokenizer for the supplied T5 model. */
class TaskSyncTokenizer private constructor(modelFile: File) : Closeable {
    private var nativeHandle: Long = nativeCreate(modelFile.absolutePath)

    init {
        check(nativeHandle != 0L) { "Could not load SentencePiece model from ${modelFile.path}" }
    }

    @Synchronized
    fun encode(text: String): List<Int> {
        checkOpen()
        val pieces = nativeEncode(nativeHandle, text)
        val maxPieceCount = MAX_INPUT_TOKENS - 1 // reserve one slot for EOS
        return buildList(minOf(pieces.size, maxPieceCount) + 1) {
            for (index in 0 until minOf(pieces.size, maxPieceCount)) add(pieces[index])
            add(EOS_ID)
        }
    }

    @Synchronized
    fun decode(ids: List<Int>): String {
        checkOpen()
        val contentIds = ids.filter { it != PAD_ID && it != EOS_ID }.toIntArray()
        return nativeDecode(nativeHandle, contentIds)
    }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            nativeClose(nativeHandle)
            nativeHandle = 0L
        }
    }

    private fun checkOpen() {
        check(nativeHandle != 0L) { "Tokenizer has already been closed." }
    }

    companion object {
        const val PAD_ID = 0
        const val EOS_ID = 1
        const val MAX_INPUT_TOKENS = 256

        init {
            System.loadLibrary("tasksync_sentencepiece")
        }

        /** modelFile must be a copy in the app's private files directory. */
        fun fromFile(modelFile: File): TaskSyncTokenizer {
            require(modelFile.isFile) { "SentencePiece model file is missing: ${modelFile.path}" }
            return TaskSyncTokenizer(modelFile)
        }
    }

    private external fun nativeCreate(modelPath: String): Long
    private external fun nativeEncode(handle: Long, text: String): IntArray
    private external fun nativeDecode(handle: Long, ids: IntArray): String
    private external fun nativeClose(handle: Long)
}
