package com.micklab.voicelistener

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object WhisperModelAssetInstaller {
    private const val ASSET_MODELS_DIR = "models"
    private const val DESTINATION_DIR = "whisper/models"
    private val copyLock = Any()

    @JvmStatic
    fun getBundledModelAssetName(context: Context): String? {
        return context.assets.list(ASSET_MODELS_DIR)
            ?.filter { it.endsWith(".gguf", ignoreCase = true) }
            ?.sorted()
            ?.firstOrNull()
    }

    @JvmStatic
    fun hasBundledModel(context: Context): Boolean = getBundledModelAssetName(context) != null

    @JvmStatic
    fun getInstalledModelFile(context: Context): File? {
        val assetName = getBundledModelAssetName(context) ?: return null
        val destination = resolveDestinationFile(context, assetName)
        return destination.takeIf { it.isFile && it.length() > 0L }
    }

    @JvmStatic
    fun ensureBundledModelCopied(context: Context): File? = synchronized(copyLock) {
        val assetName = getBundledModelAssetName(context) ?: return null
        val destination = resolveDestinationFile(context, assetName)
        if (destination.isFile && destination.length() > 0L) {
            return destination
        }

        destination.parentFile?.mkdirs()
        val temporaryFile = File(destination.parentFile, "${destination.name}.tmp")
        context.assets.open("$ASSET_MODELS_DIR/$assetName").use { input ->
            FileOutputStream(temporaryFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }

        if (!temporaryFile.renameTo(destination)) {
            temporaryFile.copyTo(destination, overwrite = true)
            temporaryFile.delete()
        }
        destination
    }

    private fun resolveDestinationFile(context: Context, assetName: String): File {
        val modelsRoot = File(context.filesDir, DESTINATION_DIR)
        return File(modelsRoot, File(assetName).name)
    }
}
