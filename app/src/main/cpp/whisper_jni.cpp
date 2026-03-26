#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

#include "whisper.h"

namespace {

constexpr const char * kLogTag = "WhisperJni";
constexpr float kPcm16Scale = 1.0f / 32768.0f;

struct WhisperHandle {
    whisper_context * context = nullptr;
    std::mutex mutex;
    std::vector<jshort> pcm16;
    std::vector<float> pcmf32;
    std::string language;
    int sample_rate_hz = WHISPER_SAMPLE_RATE;
    int thread_count = 4;
};

void android_log(ggml_log_level level, const char * text, void * /* user_data */) {
    if (text == nullptr || *text == '\0') {
        return;
    }
    const int priority = (level == GGML_LOG_LEVEL_ERROR) ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO;
    __android_log_print(priority, kLogTag, "%s", text);
}

std::string trim_copy(std::string text) {
    auto is_space = [](unsigned char ch) {
        return std::isspace(ch) != 0;
    };

    text.erase(text.begin(), std::find_if(text.begin(), text.end(), [&](unsigned char ch) {
        return !is_space(ch);
    }));
    text.erase(std::find_if(text.rbegin(), text.rend(), [&](unsigned char ch) {
        return !is_space(ch);
    }).base(), text.end());
    return text;
}

int clamp_thread_count(int requested) {
    return std::max(1, std::min(requested, 8));
}

void throw_java_exception(JNIEnv * env, const char * class_name, const std::string & message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

std::string get_string_utf(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return "";
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return "";
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

WhisperHandle * cast_handle(jlong handle) {
    return reinterpret_cast<WhisperHandle *>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_com_micklab_voicelistener_WhisperEngine_nativeLoadModel(
    JNIEnv * env,
    jobject /* thiz */,
    jstring model_path,
    jint sample_rate_hz,
    jstring language,
    jint thread_count
) {
    const std::string model_path_value = get_string_utf(env, model_path);
    if (model_path_value.empty()) {
        throw_java_exception(env, "java/lang/IllegalArgumentException", "Whisper model path is empty.");
        return 0L;
    }

    whisper_log_set(android_log, nullptr);

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    context_params.flash_attn = false;

    whisper_context * context = whisper_init_from_file_with_params(model_path_value.c_str(), context_params);
    if (context == nullptr) {
        throw_java_exception(env, "java/lang/IllegalStateException", "Failed to initialize whisper context.");
        return 0L;
    }

    std::unique_ptr<WhisperHandle> handle(new WhisperHandle());
    handle->context = context;
    handle->sample_rate_hz = sample_rate_hz <= 0 ? WHISPER_SAMPLE_RATE : sample_rate_hz;
    handle->thread_count = clamp_thread_count(thread_count);
    handle->language = get_string_utf(env, language);
    handle->pcmf32.reserve(static_cast<size_t>(handle->sample_rate_hz) * 4U);
    handle->pcm16.reserve(static_cast<size_t>(handle->sample_rate_hz) * 4U);

    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle.release()));
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_micklab_voicelistener_WhisperEngine_nativeTranscribe(
    JNIEnv * env,
    jobject /* thiz */,
    jlong native_handle,
    jshortArray audio_buffer
) {
    WhisperHandle * handle = cast_handle(native_handle);
    if (handle == nullptr || handle->context == nullptr || audio_buffer == nullptr) {
        return env->NewStringUTF("");
    }

    const jsize sample_count = env->GetArrayLength(audio_buffer);
    if (sample_count <= 0) {
        return env->NewStringUTF("");
    }

    std::lock_guard<std::mutex> guard(handle->mutex);

    handle->pcm16.resize(static_cast<size_t>(sample_count));
    handle->pcmf32.resize(static_cast<size_t>(sample_count));
    env->GetShortArrayRegion(audio_buffer, 0, sample_count, handle->pcm16.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    for (jsize index = 0; index < sample_count; ++index) {
        handle->pcmf32[static_cast<size_t>(index)] =
            static_cast<float>(handle->pcm16[static_cast<size_t>(index)]) * kPcm16Scale;
    }

    whisper_full_params full_params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    full_params.n_threads = handle->thread_count;
    full_params.translate = false;
    full_params.no_context = true;
    full_params.no_timestamps = true;
    full_params.single_segment = true;
    full_params.print_special = false;
    full_params.print_progress = false;
    full_params.print_realtime = false;
    full_params.print_timestamps = false;
    full_params.token_timestamps = false;
    full_params.temperature = 0.0f;
    full_params.max_tokens = 0;
    full_params.suppress_blank = true;
    full_params.suppress_nst = true;
    full_params.language = handle->language.empty() ? "auto" : handle->language.c_str();
    full_params.detect_language = handle->language.empty() || handle->language == "auto";

    const int result = whisper_full(
        handle->context,
        full_params,
        handle->pcmf32.data(),
        sample_count
    );
    if (result != 0) {
        throw_java_exception(env, "java/lang/IllegalStateException", "Whisper inference failed.");
        return nullptr;
    }

    const int segment_count = whisper_full_n_segments(handle->context);
    std::string transcription;
    for (int segment_index = 0; segment_index < segment_count; ++segment_index) {
        const char * segment_text = whisper_full_get_segment_text(handle->context, segment_index);
        if (segment_text != nullptr) {
            transcription.append(segment_text);
        }
    }

    transcription = trim_copy(transcription);
    return env->NewStringUTF(transcription.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_micklab_voicelistener_WhisperEngine_nativeRelease(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong native_handle
) {
    std::unique_ptr<WhisperHandle> handle(cast_handle(native_handle));
    if (handle == nullptr) {
        return;
    }

    std::lock_guard<std::mutex> guard(handle->mutex);
    if (handle->context != nullptr) {
        whisper_free(handle->context);
        handle->context = nullptr;
    }
    handle->pcmf32.clear();
    handle->pcm16.clear();
}
