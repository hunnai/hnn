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
#include <vector>
#include "SBRealtime.h"

namespace {
constexpr int MAX_FRAMES = 8192;
static_assert(std::atomic<uint32_t>::is_always_lock_free, "Audio IO counters must be lock-free");
void checked(oboe::Result result, const char *message) {
    if (result != oboe::Result::OK)
        throw std::runtime_error(std::string(message) + ": " + oboe::convertToText(result));
}
// Máximo desde la última lectura de control. IEEE float positivo mantiene
// el orden al comparar sus bits; los atómicos uint32 son lock-free en Android.
class InputPeak final {
    std::atomic<uint32_t> bits{0};
public:
    void add(float value) {
        if (!std::isfinite(value) || value <= 0) return;
        uint32_t desired = 0;
        std::memcpy(&desired, &value, sizeof(desired));
        auto previous = bits.load(std::memory_order_relaxed);
        while (previous < desired &&
               !bits.compare_exchange_weak(previous, desired, std::memory_order_relaxed)) {}
    }
    float take() {
        const auto value = bits.exchange(0, std::memory_order_relaxed);
        float result = 0;
        std::memcpy(&result, &value, sizeof(result));
        return result;
    }
};
static_assert(sizeof(float) == sizeof(uint32_t), "Input metering requires 32-bit floats");

// Entrada y salida pueden usar callbacks de tamaños distintos.
// Un productor (captura) y un consumidor (salida); nunca hay esperas ni reservas.
class CaptureRing final {
public:
    static constexpr uint32_t capacity = 32768;
private:
    struct Frame { float left = 0, right = 0; };
    std::array<Frame, capacity> frames{};
    std::atomic<uint32_t> writeIndex{0}, readIndex{0}, droppedFrames{0};
public:
    void clear() {
        // Solo después de cerrar ambos streams.
        writeIndex.store(0); readIndex.store(0); droppedFrames.store(0);
    }
    uint32_t push(const float *data, uint32_t count, int channels) {
        if (!data || (channels != 1 && channels != 2)) return 0;
        const uint32_t write = writeIndex.load(std::memory_order_relaxed);
        const uint32_t read = readIndex.load(std::memory_order_acquire);
        const uint32_t accepted = std::min(count, capacity - (write - read));
        for (uint32_t i = 0; i < accepted; ++i)
            frames[(write + i) & (capacity - 1)] = { data[i * channels], data[i * channels + channels - 1] };
        writeIndex.store(write + accepted, std::memory_order_release);
        droppedFrames.fetch_add(count - accepted, std::memory_order_relaxed);
        return accepted;
    }
    uint32_t pop(float *left, float *right, uint32_t count) {
        const uint32_t read = readIndex.load(std::memory_order_relaxed);
        const uint32_t write = writeIndex.load(std::memory_order_acquire);
        const uint32_t received = std::min(count, write - read);
        for (uint32_t i = 0; i < received; ++i) {
            const auto &frame = frames[(read + i) & (capacity - 1)];
            left[i] = frame.left; right[i] = frame.right;
        }
        readIndex.store(read + received, std::memory_order_release);
        std::fill_n(left + received, count - received, 0.0f);
        std::fill_n(right + received, count - received, 0.0f);
        return received;
    }
    uint32_t queued() const {
        const uint32_t read = readIndex.load(std::memory_order_acquire);
        const uint32_t write = writeIndex.load(std::memory_order_acquire);
        return std::min(capacity, write - read);
    }
    uint32_t dropped() const { return droppedFrames.load(std::memory_order_relaxed); }
};

// Siempre entrega el primer par. Solo reconstruye grupos de ocho cuando una
// calibración previa de esta ruta lo confirmó o coinciden cadencia y estructura.
class AutomaticInputRouter final {
    int rate = 48000, declared = 2, stride = 2;
    bool baseline = false;
    uint64_t seenFrames = 0, baseFrames = 0;
    uint32_t baseOutput = 0;
    double baseTime = 0;
    int candidateLayout = 0, layoutVotes = 0;
    std::array<float, 8> group{};
    int filled = 0, skip = 0;
    std::array<double, 8> history{};
    uint64_t historyCount = 0;
    double difference2 = 0, difference8 = 0;
    std::atomic<uint32_t> resolvedChannels{0};
public:
    void configure(int sampleRate, int channels, bool usb, int validatedHint) {
        rate = sampleRate; declared = channels;
        stride = usb && validatedHint == 8 && channels == 2 ? 8 : declared;
        seenFrames = baseFrames = 0; baseOutput = 0; baseTime = 0; baseline = false;
        candidateLayout = layoutVotes = 0; filled = skip = 0;
        history.fill(0); historyCount = 0; difference2 = difference8 = 0;
        resolvedChannels.store(!usb || validatedHint >= 0 ? stride : 0, std::memory_order_release);
    }
    bool ready() const { return channels() != 0; }
    int channels() const { return static_cast<int>(resolvedChannels.load(std::memory_order_acquire)); }
    uint32_t process(const float *source, uint32_t frames, uint32_t outputFrames,
                     double now, float *destination) {
        const uint64_t startScalar = seenFrames * declared;
        seenFrames += frames;
        const uint32_t count = frames * declared;
        if (!ready()) {
            if (declared == 2) for (uint32_t i = 0; i < count; ++i) {
                const double value = std::isfinite(source[i]) ? source[i] : 0;
                if (historyCount >= 8) {
                    const double a = value - history[(historyCount - 2) & 7];
                    const double b = value - history[historyCount & 7];
                    difference2 += a*a; difference8 += b*b;
                }
                history[historyCount & 7] = value; ++historyCount;
            }
            if (!baseline) {
                baseline = true; baseFrames = seenFrames; baseOutput = outputFrames; baseTime = now;
                difference2 = difference8 = 0;
                return 0;
            }
            const uint32_t elapsedFrames = outputFrames - baseOutput;
            if (elapsedFrames < static_cast<uint32_t>(rate / 2)) return 0;
            const double seconds = now - baseTime;
            const double outRate = seconds > 0 ? elapsedFrames / seconds : 0;
            const double ratio = static_cast<double>(seenFrames - baseFrames) / elapsedFrames;
            const bool periodicEight = difference2 > 1e-14 && difference8 < .55 * difference2;
            int layout = 0;
            if (outRate > rate * .75 && outRate < rate * 1.25) {
                if (ratio > .80 && ratio < 1.20) layout = declared;
                else if (declared == 2 && ratio > 3.5 && ratio < 4.5 && periodicEight) layout = 8;
            }
            if (layout != 0 && candidateLayout == layout) ++layoutVotes;
            else { candidateLayout = layout; layoutVotes = layout ? 1 : 0; }
            baseFrames = seenFrames; baseOutput = outputFrames; baseTime = now;
            difference2 = difference8 = 0;
            if (layoutVotes < 2) return 0;
            stride = layout;
            skip = static_cast<int>((stride - startScalar % stride) % stride);
            resolvedChannels.store(static_cast<uint32_t>(stride), std::memory_order_release);
        }
        uint32_t produced = 0;
        for (uint32_t i = 0; i < count; ++i) {
            if (skip) { --skip; continue; }
            group[filled++] = source[i];
            if (filled != stride) continue;
            filled = 0;
            destination[produced * 2] = group[0];
            destination[produced * 2 + 1] = group[stride > 1 ? 1 : 0];
            ++produced;
        }
        return produced;
    }
};

// Solo se activa por petición del usuario. PCM previo al anillo y sus descartes.
// El control reserva el buffer; el callback escribe y publica Ready con release.
class InputDiagnostic final {
    enum : uint32_t { Idle, Requested, Writing, Ready };
    std::atomic<uint32_t> phase{Idle};
    std::vector<float> samples;
    size_t written = 0;
    int rate = 0, channels = 0;
    double firstTime = 0, elapsed = 0;
    static double now() {
        timespec t{};
        clock_gettime(CLOCK_MONOTONIC, &t);
        return t.tv_sec + t.tv_nsec / 1e9;
    }
public:
    // Control; nunca se modifica un buffer que está escribiendo el callback.
    bool begin(int sampleRate, int channelCount) {
        const auto state = phase.load(std::memory_order_acquire);
        if (state == Requested || state == Writing || sampleRate < 8000 ||
            sampleRate > 192000 || channelCount < 1 || channelCount > 2) return false;
        samples.resize(static_cast<size_t>(sampleRate) * channelCount * 4);
        rate = sampleRate; channels = channelCount; written = 0; elapsed = 0;
        phase.store(Requested, std::memory_order_release);
        return true;
    }
    void append(const float *input, int frames) {
        auto state = phase.load(std::memory_order_acquire);
        if (!input || frames <= 0 || (state != Requested && state != Writing)) return;
        if (state == Requested) {
            firstTime = now();
            phase.store(Writing, std::memory_order_relaxed);
        }
        const size_t count = std::min(samples.size() - written, static_cast<size_t>(frames) * channels);
        std::copy_n(input, count, samples.data() + written);
        written += count;
        if (written == samples.size()) {
            elapsed = now() - firstTime;
            phase.store(Ready, std::memory_order_release);
        }
    }
    bool ready() const { return phase.load(std::memory_order_acquire) == Ready; }
    // Lectura solo en Ready: el productor ya no toca el buffer hasta otro begin.
    const std::vector<float> &data() const { return samples; }
    std::array<float, 4> header() const {
        return {static_cast<float>(rate), static_cast<float>(channels),
                static_cast<float>(elapsed), static_cast<float>(written / channels)};
    }
    void clear() {
        // Solo después de cerrar el stream de entrada.
        phase.store(Idle); samples.clear();
    }
};

class Session final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
    std::shared_ptr<oboe::AudioStream> input, output;
    SBEngine *engine = nullptr;
    CaptureRing capture;
    InputDiagnostic diagnostic;
    InputPeak leftPeak, rightPeak;
    AutomaticInputRouter inputRouter;
    std::array<float, MAX_FRAMES * 2> selectedInput{};
    int layoutHint = -1;
    std::atomic<uint32_t> deliveredFrames{0};
    std::array<float, MAX_FRAMES> inLeft{}, inRight{}, outLeft{}, outRight{};
    std::atomic<int32_t> error{0};
    int inputChannels = 1;
    int requestedInputDevice = 0, requestedOutputDevice = 0;
    // Contadores de diagnóstico: atómicos de 32 bits, sin logs en el callback.
    std::atomic<uint32_t> requestedFrames{0}, receivedFrames{0}, emptyReads{0}, activeReads{0}, inputCallbacks{0};
public:
    ~Session() { stop(); SBEngineDestroy(engine); }
    void stop() {
        if (output) { output->requestStop(); output->close(); output.reset(); }
        if (input) { input->requestStop(); input->close(); input.reset(); }
        diagnostic.clear();
        leftPeak.take(); rightPeak.take();
    }
    void configureInput(int hint) {
        if (input || output) throw std::runtime_error("El audio debe estar detenido para configurar la entrada");
        if (hint != -1 && hint != 0 && hint != 8)
            throw std::runtime_error("Configuración de entrada inválida");
        layoutHint = hint;
    }
    void start(int inputDevice, int outputDevice, double advance) {
        stop(); error.store(0);
        requestedInputDevice = inputDevice;
        requestedOutputDevice = outputDevice;
        requestedFrames.store(0); receivedFrames.store(0);
        emptyReads.store(0); activeReads.store(0); inputCallbacks.store(0);
        capture.clear();
        deliveredFrames.store(0);
        try {
            oboe::AudioStreamBuilder out;
            out.setDirection(oboe::Direction::Output)
                ->setSharingMode(oboe::SharingMode::Shared)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setFormat(oboe::AudioFormat::Float)->setFormatConversionAllowed(true)
                ->setChannelCount(2)->setChannelConversionAllowed(true)
                ->setSampleRate(oboe::kUnspecified)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setDataCallback(this)->setErrorCallback(this);
            if (outputDevice > 0) out.setDeviceId(outputDevice);
            checked(out.openStream(output), "No se pudo abrir la salida");
            int rate = output->getSampleRate();
            // Preferir la frecuencia nativa dentro del rango del motor.
            // Una tarjeta de alta resolución puede anunciar más de 192 kHz.
            if (rate < 8000 || rate > 192000) {
                output->close(); output.reset();
                out.setSampleRate(48000);
                checked(out.openStream(output), "No se pudo abrir la salida a una frecuencia compatible");
                rate = output->getSampleRate();
            }
            oboe::AudioStreamBuilder in;
            in.setDirection(oboe::Direction::Input)
                ->setSharingMode(oboe::SharingMode::Shared)
                ->setPerformanceMode(inputDevice > 0 ? oboe::PerformanceMode::None : oboe::PerformanceMode::LowLatency)
                ->setFormat(oboe::AudioFormat::Float)->setFormatConversionAllowed(true)
                ->setSampleRate(rate)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setInputPreset(inputDevice > 0 ? oboe::InputPreset::Generic : oboe::InputPreset::Unprocessed)
                ->setDataCallback(this)->setErrorCallback(this);
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
            inputRouter.configure(rate, inputChannels, inputDevice > 0, layoutHint);
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
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *data, int32_t frames) override {
        if (stream && stream->getDirection() == oboe::Direction::Input) {
            timespec stamp{}; clock_gettime(CLOCK_MONOTONIC, &stamp);
            receiveInput(static_cast<const float *>(data), frames, stamp.tv_sec + stamp.tv_nsec / 1e9);
            return oboe::DataCallbackResult::Continue;
        }
        auto *out = static_cast<float *>(data);
        if (!out || frames <= 0) return oboe::DataCallbackResult::Continue;
        if (!engine || !input || frames > MAX_FRAMES) {
            std::fill_n(out, frames * 2, 0.0f); error.store(-1);
            return oboe::DataCallbackResult::Stop;
        }
        requestedFrames.fetch_add(static_cast<uint32_t>(frames), std::memory_order_relaxed);
        const uint32_t received = capture.pop(inLeft.data(), inRight.data(), static_cast<uint32_t>(frames));
        if (received == 0) emptyReads.fetch_add(1, std::memory_order_relaxed);
        SBEngineProcess(engine, inLeft.data(), inRight.data(), outLeft.data(), outRight.data(), frames);
        for (int i = 0; i < frames; ++i) {
            out[i * 2] = outLeft[i]; out[i * 2 + 1] = outRight[i];
        }
        return oboe::DataCallbackResult::Continue;
    }
    // Método compartido por el callback real y el transporte de prueba.
    void receiveInput(const float *data, int frames, double now) {
        inputCallbacks.fetch_add(1, std::memory_order_relaxed);
        if (!data || frames <= 0) return;
        receivedFrames.fetch_add(static_cast<uint32_t>(frames), std::memory_order_relaxed);
        diagnostic.append(data, frames);
        bool active = false;
        float peakL = 0, peakR = 0;
        for (int offset = 0; offset < frames;) {
            const int chunk = std::min(frames - offset, MAX_FRAMES);
            const uint32_t mapped = inputRouter.process(data + static_cast<size_t>(offset) * inputChannels,
                chunk, requestedFrames.load(std::memory_order_relaxed), now, selectedInput.data());
            capture.push(selectedInput.data(), mapped, 2);
            deliveredFrames.fetch_add(mapped, std::memory_order_relaxed);
            for (uint32_t i = 0; i < mapped; ++i) {
                const float l = std::fabs(selectedInput[i * 2]);
                const float r = std::fabs(selectedInput[i * 2 + 1]);
                if (std::isfinite(l)) peakL = std::max(peakL, l);
                if (std::isfinite(r)) peakR = std::max(peakR, r);
            }
            offset += chunk;
        }
        leftPeak.add(peakL); rightPeak.add(peakR);
        active = peakL > 1e-5f || peakR > 1e-5f;
        if (active) activeReads.fetch_add(1, std::memory_order_relaxed);
    }
    std::array<double, 61> snapshot() {
        // Los primeros 37 campos conservan su significado y orden.
        std::array<double, 61> values{};
        values[3] = error.load();
        values[37] = requestedInputDevice;
        values[38] = input ? input->getDeviceId() : 0;
        values[39] = requestedOutputDevice;
        values[40] = output ? output->getDeviceId() : 0;
        values[41] = input ? input->getChannelCount() : 0;
        values[42] = output ? output->getChannelCount() : 0;
        values[43] = input ? static_cast<int>(input->getAudioApi()) : 0;
        values[44] = output ? static_cast<int>(output->getAudioApi()) : 0;
        values[45] = requestedFrames.load(std::memory_order_relaxed);
        values[46] = receivedFrames.load(std::memory_order_relaxed);
        values[47] = emptyReads.load(std::memory_order_relaxed);
        values[48] = input ? static_cast<int>(input->getInputPreset()) : 0;
        values[49] = activeReads.load(std::memory_order_relaxed);
        values[50] = capture.dropped();
        values[51] = capture.queued();
        values[52] = inputCallbacks.load(std::memory_order_relaxed);
        values[53] = input ? static_cast<int>(input->getState()) : 0;
        values[54] = output ? static_cast<int>(output->getState()) : 0;
        values[55] = deliveredFrames.load(std::memory_order_relaxed);
        values[56] = inputRouter.channels() != inputChannels ? inputRouter.channels() : 0;
        values[57] = 0;
        values[58] = inputRouter.ready() ? 1 : 0;
        values[59] = leftPeak.take();
        values[60] = rightPeak.take();
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
    bool beginDiagnostic() {
        return input && diagnostic.begin(input->getSampleRate(), inputChannels);
    }
    const InputDiagnostic &inputDiagnostic() const { return diagnostic; }
    bool arm(int bank, double bpm, double anchor, double tapSeconds) {
        if (!engine || !inputRouter.ready()) return false;
        if (tapSeconds >= 0.0) {
            timespec now{};
            if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return false;
            const double seconds = now.tv_sec + now.tv_nsec / 1e9;
            anchor = static_cast<double>(SBEngineClock(engine)) +
                (tapSeconds - seconds) * SBEngineRate(engine);
        }
        const bool accepted = SBEngineArm(engine, bank, bpm, anchor) != 0;
        return accepted;
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
extern "C" JNIEXPORT void JNICALL Java_com_example_soinubiderxl_NativeAudio_configureInput(
    JNIEnv *env, jobject, jlong handle, jint hint) {
    auto *s = get(env, handle); if (!s) return;
    try { s->configureInput(hint); }
    catch (const std::exception &e) { report(env, e.what()); }
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
extern "C" JNIEXPORT jboolean JNICALL Java_com_example_soinubiderxl_NativeAudio_beginDiagnostic(
    JNIEnv *env, jobject, jlong handle) {
    auto *s = get(env, handle); if (!s) return JNI_FALSE;
    try { return s->beginDiagnostic() ? JNI_TRUE : JNI_FALSE; }
    catch (const std::exception &e) { report(env, e.what()); return JNI_FALSE; }
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_com_example_soinubiderxl_NativeAudio_readDiagnostic(
    JNIEnv *env, jobject, jlong handle) {
    auto *s = get(env, handle); if (!s) return nullptr;
    const auto &capture = s->inputDiagnostic();
    if (!capture.ready()) return nullptr;
    const auto header = capture.header();
    const auto &data = capture.data();
    auto result = env->NewFloatArray(static_cast<jsize>(data.size() + header.size()));
    if (result) {
        env->SetFloatArrayRegion(result, 0, header.size(), header.data());
        env->SetFloatArrayRegion(result, header.size(), data.size(), data.data());
    }
    return result;
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
