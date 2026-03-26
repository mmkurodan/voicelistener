package com.micklab.voicelistener

import android.content.Context
import android.os.Environment
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object WhisperModelManager {
    const val DEFAULT_MODEL_URL =
        "https://huggingface.co/vonjack/whisper-large-v3-gguf/resolve/main/whisper-large-v3-f16.gguf?download=true"

    private const val PREFS_NAME = "VoiceListenerPrefs"
    const val PREF_ACTIVE_MODEL_NAME = "whisper_active_model_name"
    const val PREF_MODEL_DOWNLOAD_ACTIVE = "whisper_model_download_active"
    const val PREF_MODEL_DOWNLOAD_PROGRESS = "whisper_model_download_progress"
    const val PREF_MODEL_DOWNLOAD_NAME = "whisper_model_download_name"

    private const val VOICE_LISTENER_DIR = "VoiceListener"
    private const val WHISPER_MODELS_DIR = "whisper-models"

    @JvmStatic
    fun normalizeModelUrl(url: String?): String {
        val trimmed = url?.trim().orEmpty()
        return if (trimmed.isEmpty()) DEFAULT_MODEL_URL else trimmed
    }

    @JvmStatic
    fun normalizeModelName(modelName: String?): String? {
        val trimmed = modelName?.trim().orEmpty()
        return trimmed.ifEmpty { null }
    }

    @JvmStatic
    fun deriveModelNameFromUrl(url: String?): String {
        val normalizedUrl = normalizeModelUrl(url)
        val rawSegment = extractLastPathSegment(normalizedUrl)
            ?: throw IllegalArgumentException("WhisperモデルURLからファイル名を判別できません")
        val decodedSegment = URLDecoder.decode(rawSegment, StandardCharsets.UTF_8.name()).trim()
        if (decodedSegment.isEmpty()) {
            throw IllegalArgumentException("WhisperモデルURLからファイル名を判別できません")
        }
        if (!decodedSegment.lowercase(Locale.US).endsWith(".gguf")) {
            throw IllegalArgumentException("WhisperモデルURLは .gguf を指している必要があります")
        }
        val sanitized = decodedSegment.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (sanitized.isBlank() || sanitized == ".gguf") {
            throw IllegalArgumentException("Whisperモデル名が不正です")
        }
        return sanitized
    }

    @JvmStatic
    fun getModelsRootDir(context: Context): File {
        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(File(documentsDir, VOICE_LISTENER_DIR), WHISPER_MODELS_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    @JvmStatic
    fun getModelFileForName(context: Context, modelName: String?): File? {
        val normalizedName = normalizeModelName(modelName) ?: return null
        return File(getModelsRootDir(context), normalizedName)
    }

    @JvmStatic
    fun getModelFileForUrl(context: Context, url: String?): File {
        return getModelFileForName(context, deriveModelNameFromUrl(url))
            ?: throw IllegalStateException("Whisper model file resolution failed")
    }

    @JvmStatic
    fun hasModelContent(modelFile: File?): Boolean = modelFile?.isFile == true && modelFile.length() > 0L

    @JvmStatic
    fun listDownloadedModelNames(context: Context): List<String> {
        return getModelsRootDir(context)
            .listFiles()
            ?.filter { hasModelContent(it) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name }
            .orEmpty()
    }

    @JvmStatic
    fun getSelectedModelName(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return normalizeModelName(prefs.getString(PREF_ACTIVE_MODEL_NAME, null))
    }

    @JvmStatic
    fun setSelectedModelName(context: Context, modelName: String?) {
        val normalizedName = normalizeModelName(modelName)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (normalizedName == null) {
                    remove(PREF_ACTIVE_MODEL_NAME)
                } else {
                    putString(PREF_ACTIVE_MODEL_NAME, normalizedName)
                }
            }
            .apply()
    }

    @JvmStatic
    fun resolveSelectedDownloadedModelFile(context: Context): File? {
        return getModelFileForName(context, getSelectedModelName(context))
            ?.takeIf { hasModelContent(it) }
    }

    @JvmStatic
    fun resolvePreferredDownloadedModelFile(context: Context): File? {
        resolveSelectedDownloadedModelFile(context)?.let { return it }
        val firstDownloadedModel = listDownloadedModelNames(context).firstOrNull() ?: return null
        return getModelFileForName(context, firstDownloadedModel)?.takeIf { hasModelContent(it) }
    }

    @JvmStatic
    fun hasAnyModelSource(context: Context): Boolean {
        return resolvePreferredDownloadedModelFile(context) != null
            || WhisperModelAssetInstaller.hasBundledModel(context)
            || WhisperModelAssetInstaller.getInstalledModelFile(context) != null
    }

    private fun extractLastPathSegment(url: String): String? {
        val path = try {
            val uri = URI(url)
            uri.rawPath ?: uri.path
        } catch (_: Exception) {
            url.substringBefore('?').substringBefore('#')
        }
        val segment = path?.substringAfterLast('/')?.trim().orEmpty()
        return segment.ifEmpty { null }
    }
}
