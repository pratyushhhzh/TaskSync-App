package com.example.tasksync.ai

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

data class ModelCopyProgress(val copiedBytes: Long, val totalBytes: Long, val fileName: String) {
    val fraction: Float get() = if (totalBytes <= 0L) 1f else (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** Copies bundled model files to private app storage without loading them into memory. */
object ModelAssetStore {
    private val modelNames = listOf("encoder_model.onnx", "decoder_model.onnx", "spiece.model")
    private const val ASSET_DIRECTORY = "model"

    fun modelDirectory(context: Context): File = File(context.filesDir, "task_model")

    fun install(context: Context, onProgress: (ModelCopyProgress) -> Unit = {}): File {
        val appContext = context.applicationContext
        val outputDirectory = modelDirectory(appContext)
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            error("Could not create the private model folder.")
        }

        val lengths = modelNames.associateWith { name ->
            appContext.assets.openFd("$ASSET_DIRECTORY/$name").use { it.length }
        }
        val expectedHashes = mapOf(
            "encoder_model.onnx" to "0BDD09EAED25CA079B581CC0BA10AADA1921778332376D91A74D7C405ED290A4",
            "decoder_model.onnx" to "CB7A288F31D1F16FB431FCE0531FBD9E8A3E280CA5406C7E18881B90672026C5",
            "spiece.model" to "D60ACB128CF7B7F2536E8F38A5B18A05535C9E14C7A355904270E15B0945EA86"
        )
        val totalBytes = lengths.values.sum()
        var copiedBefore = modelNames.sumOf { name ->
            val target = File(outputDirectory, name)
            if (target.isFile && target.length() == lengths.getValue(name) && sha256(target) == expectedHashes.getValue(name)) target.length() else 0L
        }

        for (name in modelNames) {
            val target = File(outputDirectory, name)
            val expectedLength = lengths.getValue(name)
            if (target.isFile && target.length() == expectedLength && sha256(target) == expectedHashes.getValue(name)) {
                onProgress(ModelCopyProgress(copiedBefore, totalBytes, name))
                continue
            }

            val temporary = File(outputDirectory, "$name.part")
            temporary.delete()
            var copiedForFile = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            appContext.assets.open("$ASSET_DIRECTORY/$name").use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copiedForFile += count
                        onProgress(ModelCopyProgress(copiedBefore + copiedForFile, totalBytes, name))
                    }
                    output.fd.sync()
                }
            }
            check(temporary.length() == expectedLength) {
                "Copied $name is incomplete. Please retry loading the model."
            }
            val copiedHash = digest.digest().joinToString("") { "%02X".format(it) }
            check(copiedHash == expectedHashes.getValue(name)) {
                temporary.delete()
                "Copied $name failed its integrity check. Please retry loading the model."
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            copiedBefore += copiedForFile
        }
        onProgress(ModelCopyProgress(totalBytes, totalBytes, "Ready"))
        return outputDirectory
    }

    private const val COPY_BUFFER_SIZE = 64 * 1024

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }.uppercase(Locale.ROOT)
    }
}
