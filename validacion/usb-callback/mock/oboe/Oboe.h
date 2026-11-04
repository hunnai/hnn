#pragma once
// Doble de transporte para probar el código de Session real sin Android.
#include <cstdint>
#include <memory>
#include <vector>
namespace oboe {
constexpr int kUnspecified = 0;
enum class Direction { Output, Input };
enum class SharingMode { Shared };
enum class PerformanceMode { None, LowLatency };
enum class AudioFormat { Float, I16 };
enum class SampleRateConversionQuality { Medium };
enum class InputPreset { Generic = 1, Unprocessed = 9 };
enum class AudioApi { AAudio = 2 };
enum class StreamState { Started = 4, Closed = 12 };
enum class Result { OK = 0, ErrorClosed = -899 };
enum class DataCallbackResult { Continue, Stop };
inline const char *convertToText(Result) { return "mock-result"; }
class AudioStream;
class AudioStreamDataCallback {
public: virtual ~AudioStreamDataCallback() = default;
    virtual DataCallbackResult onAudioReady(AudioStream *, void *, int32_t) = 0;
};
class AudioStreamErrorCallback {
public: virtual ~AudioStreamErrorCallback() = default;
    virtual void onErrorAfterClose(AudioStream *, Result) {}
};
struct FakeRead {
    explicit operator bool() const { return true; }
    int value() const { return 0; }
    Result error() const { return Result::OK; }
};
inline int readCalls = 0;
inline int nativeUsbRate = 96000;
class AudioStream {
public:
    AudioFormat format = AudioFormat::Float;
    Direction direction = Direction::Output;
    int channels = 2, rate = 48000, device = 0;
    AudioStreamDataCallback *callback = nullptr;
    PerformanceMode performance = PerformanceMode::None;
    InputPreset preset = InputPreset::Generic;
    StreamState state = StreamState::Started;
    AudioFormat getFormat() const { return format; }
    Direction getDirection() const { return direction; }
    int getChannelCount() const { return channels; }
    int getSampleRate() const { return rate; }
    int getDeviceId() const { return device; }
    int getFramesPerBurst() const { return 256; }
    InputPreset getInputPreset() const { return preset; }
    PerformanceMode getPerformanceMode() const { return performance; }
    AudioApi getAudioApi() const { return AudioApi::AAudio; }
    StreamState getState() const { return state; }
    Result requestStop() { return Result::OK; }
    Result requestStart() { state = StreamState::Started; return Result::OK; }
    Result close() { state = StreamState::Closed; return Result::OK; }
    int setBufferSizeInFrames(int n) { return n; }
    FakeRead read(void *, int32_t, int64_t) { ++readCalls; return {}; }
};
inline std::vector<std::shared_ptr<AudioStream>> opened;
class AudioStreamBuilder {
    AudioStream settings;
public:
    AudioStreamBuilder *setDirection(Direction v) { settings.direction=v; return this; }
    AudioStreamBuilder *setSharingMode(SharingMode) { return this; }
    AudioStreamBuilder *setPerformanceMode(PerformanceMode mode) { settings.performance=mode; return this; }
    AudioStreamBuilder *setFormat(AudioFormat v) { settings.format=v; return this; }
    AudioStreamBuilder *setFormatConversionAllowed(bool) { return this; }
    AudioStreamBuilder *setChannelConversionAllowed(bool) { return this; }
    AudioStreamBuilder *setChannelCount(int v) { settings.channels=v; return this; }
    AudioStreamBuilder *setSampleRate(int v) { settings.rate=v; return this; }
    AudioStreamBuilder *setSampleRateConversionQuality(SampleRateConversionQuality) { return this; }
    AudioStreamBuilder *setDataCallback(AudioStreamDataCallback *v) { settings.callback=v; return this; }
    AudioStreamBuilder *setErrorCallback(AudioStreamErrorCallback *) { return this; }
    AudioStreamBuilder *setDeviceId(int v) { settings.device=v; return this; }
    AudioStreamBuilder *setInputPreset(InputPreset v) { settings.preset=v; return this; }
    Result openStream(std::shared_ptr<AudioStream> &out) {
        out = std::make_shared<AudioStream>(settings);
        if (out->rate == kUnspecified) out->rate = out->device > 0 ? nativeUsbRate : 48000;
        opened.push_back(out); return Result::OK;
    }
};
}
