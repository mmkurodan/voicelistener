#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cctype>
#include <chrono>
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

long long elapsed_ms(
    const std::chrono::steady_clock::time_point & started_at,
    const std::chrono::steady_clock::time_point & finished_at
) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(finished_at - started_at).count();
}

long long samples_to_ms(int sample_count, int sample_rate_hz) {
    if (sample_count <= 0 || sample_rate_hz <= 0) {
        return 0LL;
    }
    return (static_cast<long long>(sample_count) * 1000LL) / static_cast<long long>(sample_rate_hz);
}

void log_perf(long long trace_id, const char * stage, const std::string & details) {
    const std::string trace_value = trace_id >= 0 ? std::to_string(trace_id) : "none";
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "WhisperPerf trace=%s stage=%s %s",
        trace_value.c_str(),
        stage,
        details.c_str()
    );
}

void log_perf(JNIEnv * env, long long trace_id, const char * stage, const std::string & details) {
    log_perf(trace_id, stage, details);
    if (env == nullptr || env->ExceptionCheck()) {
        return;
    }

    jclass logger_class = env->FindClass("com/micklab/voicelistener/WhisperPerfLogger");
    if (logger_class == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Unable to resolve WhisperPerfLogger for file logging.");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return;
    }

    jmethodID log_method = env->GetStaticMethodID(
        logger_class,
        "logFromNative",
        "(JLjava/lang/String;Ljava/lang/String;)V"
    );
    if (log_method == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Unable to resolve WhisperPerfLogger.logFromNative.");
        env->DeleteLocalRef(logger_class);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return;
    }

    jstring stage_value = env->NewStringUTF(stage);
    jstring details_value = env->NewStringUTF(details.c_str());
    if (stage_value == nullptr || details_value == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to allocate JNI strings for WhisperPerf forwarding.");
        if (stage_value != nullptr) {
            env->DeleteLocalRef(stage_value);
        }
        if (details_value != nullptr) {
            env->DeleteLocalRef(details_value);
        }
        env->DeleteLocalRef(logger_class);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return;
    }

    env->CallStaticVoidMethod(
        logger_class,
        log_method,
        static_cast<jlong>(trace_id),
        stage_value,
        details_value
    );
    if (env->ExceptionCheck()) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "WhisperPerfLogger.logFromNative threw an exception.");
        env->ExceptionClear();
    }

    env->DeleteLocalRef(details_value);
    env->DeleteLocalRef(stage_value);
    env->DeleteLocalRef(logger_class);
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
    const std::string language_value = get_string_utf(env, language);
    const int thread_count_value = clamp_thread_count(thread_count);
    const auto load_started_at = std::chrono::steady_clock::now();
    if (model_path_value.empty()) {
        log_perf(env, -1, "native.load.error", "reason=empty-model-path");
        throw_java_exception(env, "java/lang/IllegalArgumentException", "Whisper model path is empty.");
        return 0L;
    }

    whisper_log_set(android_log, nullptr);

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    context_params.flash_attn = false;

    whisper_context * context = whisper_init_from_file_with_params(model_path_value.c_str(), context_params);
    const auto load_finished_at = std::chrono::steady_clock::now();
    const long long load_ms = elapsed_ms(load_started_at, load_finished_at);
    if (context == nullptr) {
        log_perf(
            env,
            -1,
            "native.load.error",
            "path=" + model_path_value
                + " sampleRateHz=" + std::to_string(sample_rate_hz)
                + " language=" + (language_value.empty() ? std::string("auto") : language_value)
                + " threadCount=" + std::to_string(thread_count_value)
                + " elapsedMs=" + std::to_string(load_ms)
        );
        throw_java_exception(env, "java/lang/IllegalStateException", "Failed to initialize whisper context.");
        return 0L;
    }

    std::unique_ptr<WhisperHandle> handle(new WhisperHandle());
    handle->context = context;
    handle->sample_rate_hz = sample_rate_hz <= 0 ? WHISPER_SAMPLE_RATE : sample_rate_hz;
    handle->thread_count = thread_count_value;
    handle->language = language_value;
    handle->pcmf32.reserve(static_cast<size_t>(handle->sample_rate_hz) * 4U);
    handle->pcm16.reserve(static_cast<size_t>(handle->sample_rate_hz) * 4U);

    log_perf(
        env,
        -1,
        "native.load",
        "path=" + model_path_value
            + " sampleRateHz=" + std::to_string(handle->sample_rate_hz)
            + " language=" + (handle->language.empty() ? std::string("auto") : handle->language)
            + " threadCount=" + std::to_string(handle->thread_count)
            + " reserveSamples=" + std::to_string(handle->sample_rate_hz * 4)
            + " elapsedMs=" + std::to_string(load_ms)
    );

    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle.release()));
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_micklab_voicelistener_WhisperEngine_nativeTranscribe(
    JNIEnv * env,
    jobject /* thiz */,
    jlong native_handle,
    jshortArray audio_buffer,
    jlong trace_id
) {
    const long long trace_id_value = static_cast<long long>(trace_id);
    WhisperHandle * handle = cast_handle(native_handle);
    if (handle == nullptr || handle->context == nullptr || audio_buffer == nullptr) {
        log_perf(env, trace_id_value, "native.transcribe.skip", "reason=invalid-input");
        return env->NewStringUTF("");
    }

    const jsize sample_count = env->GetArrayLength(audio_buffer);
    if (sample_count <= 0) {
        log_perf(env, trace_id_value, "native.transcribe.skip", "reason=empty-buffer");
        return env->NewStringUTF("");
    }

    const auto total_started_at = std::chrono::steady_clock::now();
    const auto lock_requested_at = total_started_at;
    std::lock_guard<std::mutex> guard(handle->mutex);
    const auto lock_acquired_at = std::chrono::steady_clock::now();
    const long long lock_wait_ms = elapsed_ms(lock_requested_at, lock_acquired_at);

    const std::string language_value = handle->language.empty() ? "auto" : handle->language;
    log_perf(
        env,
        trace_id_value,
        "native.transcribe.begin",
        "samples=" + std::to_string(sample_count)
            + " bufferMs=" + std::to_string(samples_to_ms(sample_count, handle->sample_rate_hz))
            + " sampleRateHz=" + std::to_string(handle->sample_rate_hz)
            + " threadCount=" + std::to_string(handle->thread_count)
            + " language=" + language_value
            + " lockWaitMs=" + std::to_string(lock_wait_ms)
    );

    handle->pcm16.resize(static_cast<size_t>(sample_count));
    handle->pcmf32.resize(static_cast<size_t>(sample_count));
    const auto copy_started_at = std::chrono::steady_clock::now();
    env->GetShortArrayRegion(audio_buffer, 0, sample_count, handle->pcm16.data());
    const auto copy_finished_at = std::chrono::steady_clock::now();
    const long long copy_ms = elapsed_ms(copy_started_at, copy_finished_at);
    if (env->ExceptionCheck()) {
        log_perf(
            env,
            trace_id_value,
            "native.copy.error",
            "samples=" + std::to_string(sample_count)
                + " copyMs=" + std::to_string(copy_ms)
        );
        return nullptr;
    }

    const auto convert_started_at = std::chrono::steady_clock::now();
    for (jsize index = 0; index < sample_count; ++index) {
        handle->pcmf32[static_cast<size_t>(index)] =
            static_cast<float>(handle->pcm16[static_cast<size_t>(index)]) * kPcm16Scale;
    }
    const auto convert_finished_at = std::chrono::steady_clock::now();
    const long long convert_ms = elapsed_ms(convert_started_at, convert_finished_at);

    whisper_full_params full_params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    full_params.n_threads = handle->thread_count;
    full_params.translate = false;
    full_params.no_context = true;
    full_params.no_timestamps = true;
    full_params.single_segment = false;
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

    const auto inference_started_at = std::chrono::steady_clock::now();
    const int result = whisper_full(
        handle->context,
        full_params,
        handle->pcmf32.data(),
        sample_count
    );
    const auto inference_finished_at = std::chrono::steady_clock::now();
    const long long inference_ms = elapsed_ms(inference_started_at, inference_finished_at);
    if (result != 0) {
        log_perf(
            env,
            trace_id_value,
            "native.infer.error",
            "samples=" + std::to_string(sample_count)
                + " bufferMs=" + std::to_string(samples_to_ms(sample_count, handle->sample_rate_hz))
                + " copyMs=" + std::to_string(copy_ms)
                + " convertMs=" + std::to_string(convert_ms)
                + " inferMs=" + std::to_string(inference_ms)
                + " result=" + std::to_string(result)
        );
        throw_java_exception(env, "java/lang/IllegalStateException", "Whisper inference failed.");
        return nullptr;
    }

    const auto extract_started_at = std::chrono::steady_clock::now();
    const int segment_count = whisper_full_n_segments(handle->context);
    std::string transcription;
    for (int segment_index = 0; segment_index < segment_count; ++segment_index) {
        const char * segment_text = whisper_full_get_segment_text(handle->context, segment_index);
        if (segment_text != nullptr) {
            transcription.append(segment_text);
        }
    }
    const auto extract_finished_at = std::chrono::steady_clock::now();
    const long long extract_ms = elapsed_ms(extract_started_at, extract_finished_at);

    transcription = trim_copy(transcription);
    const auto total_finished_at = std::chrono::steady_clock::now();
    const long long total_ms = elapsed_ms(total_started_at, total_finished_at);
    log_perf(
        env,
        trace_id_value,
        "native.transcribe",
        "samples=" + std::to_string(sample_count)
            + " bufferMs=" + std::to_string(samples_to_ms(sample_count, handle->sample_rate_hz))
            + " sampleRateHz=" + std::to_string(handle->sample_rate_hz)
            + " threadCount=" + std::to_string(handle->thread_count)
            + " language=" + language_value
            + " lockWaitMs=" + std::to_string(lock_wait_ms)
            + " copyMs=" + std::to_string(copy_ms)
            + " convertMs=" + std::to_string(convert_ms)
            + " inferMs=" + std::to_string(inference_ms)
            + " extractMs=" + std::to_string(extract_ms)
            + " totalMs=" + std::to_string(total_ms)
            + " segments=" + std::to_string(segment_count)
            + " chars=" + std::to_string(transcription.size())
            + " detectLanguage=" + std::string(full_params.detect_language ? "true" : "false")
    );
    return env->NewStringUTF(transcription.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_micklab_voicelistener_WhisperEngine_nativeRelease(
    JNIEnv * env,
    jobject /* thiz */,
    jlong native_handle
) {
    std::unique_ptr<WhisperHandle> handle(cast_handle(native_handle));
    if (handle == nullptr) {
        return;
    }

    std::lock_guard<std::mutex> guard(handle->mutex);
    log_perf(
        env,
        -1,
        "native.release",
        "hasContext=" + std::string(handle->context != nullptr ? "true" : "false")
            + " sampleRateHz=" + std::to_string(handle->sample_rate_hz)
            + " threadCount=" + std::to_string(handle->thread_count)
            + " language=" + (handle->language.empty() ? std::string("auto") : handle->language)
    );
    if (handle->context != nullptr) {
        whisper_free(handle->context);
        handle->context = nullptr;
    }
    handle->pcmf32.clear();
    handle->pcm16.clear();
}
