#include <jni.h>
#include <cstring>
#include <oboe/Oboe.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <time.h>
#include "SBRealtime.h"

namespace {
constexpr int MAX_FRAMES = 8192;
void checked(oboe::Result result, const char *message) {
    if (result != oboe::Result::OK)
        throw std::runtime_error(std::string(message) + ": " + oboe::convertToText(result));
}
class Session final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
    std::shared_ptr<oboe::AudioStream> input, output;
    SBEngine *engine = nullptr;
    std::array<float, MAX_FRAMES * 2> interleaved{};
    std::array<float, MAX_FRAMES> inLeft{}, inRight{}, outLeft{}, outRight{};
    std::atomic<int32_t> error{0};
    int inputChannels = 1;
public:
    ~Session() { stop(); SBEngineDestroy(engine); }
    void stop() {
        if (output) { output->requestStop(); output->close(); output.reset(); }
        if (input) { input->requestStop(); input->close(); input.reset(); }
    }
    void start(int inputDevice, int outputDevice, double advance) {
        stop(); error.store(0);
        try {
            oboe::AudioStreamBuilder out;
            out.setDirection(oboe::Direction::Output)
                ->setSharingMode(oboe::SharingMode::Shared)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setFormat(oboe::AudioFormat::Float)->setFormatConversionAllowed(true)
                ->setChannelCount(2)->setChannelConversionAllowed(true)
                ->setSampleRate(48000)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setDataCallback(this)->setErrorCallback(this);
            if (outputDevice > 0) out.setDeviceId(outputDevice);
            checked(out.openStream(output), "No se pudo abrir la salida");
            const int rate = output->getSampleRate();
            oboe::AudioStreamBuilder in;
            in.setDirection(oboe::Direction::Input)
                ->setSharingMode(oboe::SharingMode::Shared)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setFormat(oboe::AudioFormat::Float)->setFormatConversionAllowed(true)
                ->setSampleRate(rate)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setInputPreset(oboe::InputPreset::Unprocessed)
                ->setErrorCallback(this);
            if (inputDevice > 0) in.setDeviceId(inputDevice);
            in.setChannelCount(inputDevice > 0 ? 2 : 1);
            auto result = in.openStream(input);
            if (result != oboe::Result::OK && inputDevice > 0) {
                if (input) { input->close(); input.reset(); }
                in.setChannelCount(1);
                result = in.openStream(input);
            }
            checked(result, "No se pudo abrir la entrada");
            inputChannels = input->getChannelCount();
            if (inputChannels < 1 || inputChannels > 2 || input->getSampleRate() != rate)
                throw std::runtime_error("Formato de entrada incompatible");
            if (engine) {
                if (!SBEngineReconfigureRate(engine, rate))
                    throw std::runtime_error("No se pudieron adaptar los loops a la ruta de audio");
            } else {
                engine = SBEngineCreate(rate, advance);
                if (!engine) throw std::runtime_error("No se pudo reservar el motor");
            }
            if (!SBEngineSetInitialAdvance(engine, advance))
                throw std::runtime_error("No se pudo aplicar el desfase guardado");
            // Dos ráfagas como objetivo; el dispositivo decide el tamaño admitido.
            output->setBufferSizeInFrames(output->getFramesPerBurst() * 2);
            checked(input->requestStart(), "No se pudo iniciar la entrada");
            checked(output->requestStart(), "No se pudo iniciar la salida");
        } catch (...) { stop(); throw; }
    }
    void onErrorAfterClose(oboe::AudioStream *, oboe::Result result) override {
        error.store(static_cast<int32_t>(result));
    }
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *data, int32_t frames) override {
        auto *out = static_cast<float *>(data);
        if (!out || frames <= 0) return oboe::DataCallbackResult::Continue;
        if (!engine || !input || frames > MAX_FRAMES) {
            std::fill_n(out, frames * 2, 0.0f); error.store(-1);
            return oboe::DataCallbackResult::Stop;
        }
        auto result = input->read(interleaved.data(), frames, 0);
        if (!result) {
            std::fill_n(out, frames * 2, 0.0f);
            error.store(static_cast<int32_t>(result.error()));
            return oboe::DataCallbackResult::Stop;
        }
        const int received = result.value();
        if (received < 0 || received > frames) {
            std::fill_n(out, frames * 2, 0.0f); error.store(-2);
            return oboe::DataCallbackResult::Stop;
        }
        for (int i = 0; i < frames; ++i) {
            inLeft[i] = i < received ? interleaved[i * inputChannels] : 0.0f;
            inRight[i] = i < received
                ? interleaved[i * inputChannels + (inputChannels > 1 ? 1 : 0)] : 0.0f;
        }
        SBEngineProcess(engine, inLeft.data(), inRight.data(), outLeft.data(), outRight.data(), frames);
        for (int i = 0; i < frames; ++i) {
            out[i * 2] = outLeft[i]; out[i * 2 + 1] = outRight[i];
        }
        return oboe::DataCallbackResult::Continue;
    }
    std::array<double, 37> snapshot() {
        std::array<double, 37> values{};
        values[3] = error.load();
        if (!engine) return values;
        SBSnapshot header{}; SBBankSnapshot banks[6]{};
        SBEngineReadSnapshot(engine, &header, banks);
        SBEngineCollectGarbage(engine);
        values[0] = SBEngineRate(engine); values[1] = header.clock;
        values[2] = header.inputPeak; values[4] = header.analysisDrops;
        values[5] = header.outputPeak; values[6] = header.safetyReductionDB;
        for (int i = 0; i < 6; ++i) {
            const int p = 7 + i * 5;
            values[p] = banks[i].state; values[p+1] = banks[i].divisions;
            values[p+2] = banks[i].bpm; values[p+3] = banks[i].advanceMS;
            values[p+4] = banks[i].progress;
        }
        return values;
    }
    bool arm(int bank, double bpm, double anchor, double tapSeconds) {
        if (!engine) return false;
        if (tapSeconds >= 0.0) {
            timespec now{};
            if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return false;
            const double seconds = now.tv_sec + now.tv_nsec / 1e9;
            anchor = static_cast<double>(SBEngineClock(engine)) +
                (tapSeconds - seconds) * SBEngineRate(engine);
        }
        return SBEngineArm(engine, bank, bpm, anchor) != 0;
    }
    bool command(int op, int bank, double value) {
        if (!engine) return false;
        switch (op) {
            case 1: return SBEngineToggle(engine, bank);
            case 2: return SBEngineErase(engine);
            case 3: return SBEngineEraseBank(engine, bank);
            case 4: return std::isfinite(value) && value >= -16 && value <= 16 &&
                SBEngineResizeBank(engine, bank, static_cast<int>(value));
            case 5: return SBEngineNudge(engine, value);
            case 6: return SBEngineNudgeBank(engine, bank, value);
            case 7: return SBEngineSetInitialAdvance(engine, value);
            default: return false;
        }
    }
    int read(float *samples, uint32_t capacity, uint64_t *start) {
        uint32_t count = 0;
        return engine && SBEngineReadAnalysis(engine, samples, capacity, start, &count)
            ? static_cast<int>(count) : 0;
    }
};
void report(JNIEnv *env, const char *message) {
    auto cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}
Session *get(JNIEnv *env, jlong handle) {
    auto *session = reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
    if (!session) report(env, "No hay una sesion de audio");
    return session;
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_example_soinubiderxl_NativeAudio_create(JNIEnv *env, jobject) {
    try { return static_cast<jlong>(reinterpret_cast<intptr_t>(new Session())); }
    catch (const std::exception &e) { report(env, e.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_example_soinubiderxl_NativeAudio_start(
    JNIEnv *env, jobject, jlong handle, jint input, jint output, jdouble advance) {
    auto *s = get(env, handle); if (!s) return;
    try { s->start(input, output, advance); }
    catch (const std::exception &e) { report(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL Java_com_example_soinubiderxl_NativeAudio_stop(JNIEnv *env, jobject, jlong handle) {
    auto *s = get(env, handle); if (s) s->stop();
}
extern "C" JNIEXPORT void JNICALL Java_com_example_soinubiderxl_NativeAudio_destroy(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_com_example_soinubiderxl_NativeAudio_snapshot(JNIEnv *env, jobject, jlong handle) {
    auto *s = get(env, handle); if (!s) return nullptr;
    auto values = s->snapshot();
    auto array = env->NewDoubleArray(values.size());
    if (array) env->SetDoubleArrayRegion(array, 0, values.size(), values.data());
    return array;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_example_soinubiderxl_NativeAudio_arm(
    JNIEnv *env, jobject, jlong handle, jint bank, jdouble bpm, jdouble anchor, jdouble tap) {
    auto *s = get(env, handle);
    return s && s->arm(bank, bpm, anchor, tap) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_example_soinubiderxl_NativeAudio_command(
    JNIEnv *env, jobject, jlong handle, jint op, jint bank, jdouble value) {
    auto *s = get(env, handle);
    return s && s->command(op, bank, value) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jint JNICALL Java_com_example_soinubiderxl_NativeAudio_readAnalysis(
    JNIEnv *env, jobject, jlong handle, jfloatArray samples, jlongArray start) {
    auto *s = get(env, handle); if (!s) return 0;
    if (!samples || !start || env->GetArrayLength(samples) < 1024 || env->GetArrayLength(start) < 1) {
        report(env, "Buffers de analisis invalidos"); return 0;
    }
    auto *data = env->GetFloatArrayElements(samples, nullptr); if (!data) return 0;
    uint64_t position = 0;
    int count = s->read(data, env->GetArrayLength(samples), &position);
    env->ReleaseFloatArrayElements(samples, data, count ? 0 : JNI_ABORT);
    if (count) { jlong p = position; env->SetLongArrayRegion(start, 0, 1, &p); }
    return count;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_example_soinubiderxl_NativeAudio_fft(
    JNIEnv *env, jobject, jfloatArray samples, jdoubleArray magnitudes) {
    if (!samples || !magnitudes) return JNI_FALSE;
    auto n = env->GetArrayLength(samples);
    if (n < 2 || (n & (n-1)) || env->GetArrayLength(magnitudes) < n/2+1) return JNI_FALSE;
    auto *in = env->GetFloatArrayElements(samples, nullptr); if (!in) return JNI_FALSE;
    auto *out = env->GetDoubleArrayElements(magnitudes, nullptr);
    if (!out) { env->ReleaseFloatArrayElements(samples, in, JNI_ABORT); return JNI_FALSE; }
    const int ok = SBFFTRealMagnitudes(in, n, out);
    env->ReleaseDoubleArrayElements(magnitudes, out, ok ? 0 : JNI_ABORT);
    env->ReleaseFloatArrayElements(samples, in, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
