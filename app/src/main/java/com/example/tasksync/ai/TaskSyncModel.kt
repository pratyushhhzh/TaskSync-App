package com.example.tasksync.ai

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.LongBuffer

/** Keeps the two local ONNX sessions alive for reuse across voice requests. */
class TaskSyncModel private constructor(
    private val tokenizer: TaskSyncTokenizer,
    private val encoder: OrtSession,
    private val decoder: OrtSession
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()

    suspend fun generate(input: String, maxOutputTokens: Int = MAX_OUTPUT_TOKENS): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(input.isNotBlank()) { "The transcript is empty." }
            require(maxOutputTokens in 1..MAX_OUTPUT_TOKENS) { "Output limit must be between 1 and $MAX_OUTPUT_TOKENS." }
            val ids = tokenizer.encode(input).map(Int::toLong).toLongArray()
            val mask = LongArray(ids.size) { 1L }
            val encIds = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
            val encMask = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), longArrayOf(1, mask.size.toLong()))
            val encoderResult = try {
                encoder.run(mapOf("input_ids" to encIds, "attention_mask" to encMask))
            } finally {
                encIds.close()
                encMask.close()
            }
            // The decoder consumes this tensor directly; the encoder result stays open until decoding ends.
            val hiddenInput = encoderResult[0] as? OnnxTensor
                ?: run { encoderResult.close(); error("Encoder returned an unexpected output tensor.") }
            val encoderMask = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), longArrayOf(1, mask.size.toLong()))
            try {
                val generated = mutableListOf(DECODER_START_ID)
                repeat(maxOutputTokens) {
                    val decoderIds = OnnxTensor.createTensor(
                        environment,
                        LongBuffer.wrap(generated.map(Int::toLong).toLongArray()),
                        longArrayOf(1, generated.size.toLong())
                    )
                    val token = try {
                        decoder.run(
                            mapOf(
                                "encoder_attention_mask" to encoderMask,
                                "input_ids" to decoderIds,
                                "encoder_hidden_states" to hiddenInput
                            )
                        ).use { result ->
                            val logits = result[0].value as Array<Array<FloatArray>>
                            logits[0].last().indices.maxByOrNull { logits[0].last()[it] }
                                ?: error("Decoder returned empty logits.")
                        }
                    } finally {
                        decoderIds.close()
                    }
                    if (token == TaskSyncTokenizer.EOS_ID) return@runCatching tokenizer.decode(generated.drop(1))
                    generated += token
                }
                tokenizer.decode(generated.drop(1))
            } finally {
                encoderResult.close()
                encoderMask.close()
            }
        }
    }

    override fun close() {
        decoder.close()
        encoder.close()
        tokenizer.close()
    }

    companion object {
        const val MAX_OUTPUT_TOKENS = 128
        private const val DECODER_START_ID = 0
        private val initLock = Any()
        @Volatile private var shared: TaskSyncModel? = null

        suspend fun get(context: Context): TaskSyncModel = withContext(Dispatchers.IO) {
            shared ?: synchronized(initLock) {
                shared ?: create(context.applicationContext).also { shared = it }
            }
        }

        private fun create(context: Context): TaskSyncModel {
            val directory = ModelAssetStore.install(context)
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
            try {
                val environment = OrtEnvironment.getEnvironment()
                val encoder = environment.createSession(directory.resolve("encoder_model.onnx").absolutePath, options)
                try {
                    val decoder = environment.createSession(directory.resolve("decoder_model.onnx").absolutePath, options)
                    return TaskSyncModel(TaskSyncTokenizer.fromFile(directory.resolve("spiece.model")), encoder, decoder)
                } catch (error: Throwable) {
                    encoder.close()
                    throw error
                }
            } finally {
                options.close()
            }
        }
    }
}
