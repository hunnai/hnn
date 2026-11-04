#include <cassert>
#include <iostream>
#include <thread>
#include <vector>
#include "../../app/src/main/cpp/SBAudioAndroid.cpp"

static void testRing() {
    auto ring = std::make_unique<CaptureRing>();
    float mono[] = {.1f, .2f, .3f};
    float left[8]{}, right[8]{};
    assert(ring->push(mono, 3, 1) == 3);
    assert(ring->pop(left, right, 8) == 3);
    for (int i=0;i<8;++i) {
        assert(left[i] == right[i]);
        assert(left[i] == (i<3 ? mono[i] : 0.0f));
    }
    std::vector<float> input((CaptureRing::capacity+9)*2, .5f);
    assert(ring->push(input.data(), CaptureRing::capacity+9, 2) == CaptureRing::capacity);
    assert(ring->dropped() == 9 && ring->queued() == CaptureRing::capacity);
    std::vector<float> a(CaptureRing::capacity+5), b(a.size());
    assert(ring->pop(a.data(), b.data(), a.size()) == CaptureRing::capacity);
    assert(a.back()==0 && b.back()==0);
    ring->clear();
    assert(ring->queued()==0 && ring->dropped()==0);

    constexpr uint32_t total=200000;
    std::thread producer([&] {
        uint32_t position=0; std::array<float, 127*2> block{};
        while(position<total) {
            uint32_t count=std::min(127u,total-position);
            for(uint32_t i=0;i<count;++i) {block[i*2]=float(position+i+1);block[i*2+1]=-block[i*2];}
            const auto accepted=ring->push(block.data(),count,2);
            position+=accepted;
            if(!accepted) std::this_thread::yield();
        }
    });
    uint32_t position=0; std::array<float,333> l{},r{};
    while(position<total) {
        const auto count=ring->pop(l.data(),r.data(),333);
        for(uint32_t i=0;i<count;++i) {assert(l[i]==float(position+i+1));assert(r[i]==-l[i]);}
        position+=count;
        if(!count) std::this_thread::yield();
    }
    producer.join();
    assert(ring->queued()==0);
    std::cout<<"PASS capture ring: mono/stereo, underflow padding, overflow, wrap and two-thread ordering\n";
}
static void testSession(int inputDevice) {
    oboe::opened.clear();
    auto session=std::make_unique<Session>();
    session->start(inputDevice,inputDevice?261:0,0);
    assert(oboe::opened.size()==2);
    auto output=oboe::opened[0];auto input=oboe::opened[1];
    assert(input->callback && output->callback);
    assert(input->getPerformanceMode()==(inputDevice?oboe::PerformanceMode::None:oboe::PerformanceMode::LowLatency));
    assert(output->getPerformanceMode()==oboe::PerformanceMode::LowLatency);
    assert(input->getDeviceId()==inputDevice);
    assert(input->getInputPreset()==(inputDevice?oboe::InputPreset::Generic:oboe::InputPreset::Unprocessed));
    const int frames = inputDevice ? 1538 : 256;
    std::vector<float> out(frames * 2), data(frames * 2);
    int callbacks = 0;
    // La salida puede arrancar antes de la primera entrega de captura.
    assert(output->callback->onAudioReady(output.get(),out.data(),256)==oboe::DataCallbackResult::Continue);
    assert(session->snapshot()[46]==0 && session->snapshot()[47]==1);
    assert(session->arm(0,120,0,-1));
    uint64_t position=0;bool audible=false;
    for(int block=0;block<600;++block) {
        for(int i=0;i<frames;++i) {
            float value=float(.2*std::sin(2*3.141592653589793*440*(position+i)/48000));
            if(inputDevice) {data[i*2]=value;data[i*2+1]=value*.5f;} else data[i]=value;
        }
        // Captura por fragmentos de hasta 256; salida USB de 1538, como el registro real.
        for(int offset=0;offset<frames;) {
            const int chunk=std::min(256,frames-offset);
            assert(input->callback->onAudioReady(input.get(),data.data()+offset*input->channels,chunk)==oboe::DataCallbackResult::Continue);
            offset+=chunk; ++callbacks;
        }
        assert(output->callback->onAudioReady(output.get(),out.data(),frames)==oboe::DataCallbackResult::Continue);
        const auto snapshot=session->snapshot();
        assert(snapshot[2]>.1 && snapshot[46]==(block+1)*frames);
        assert(snapshot[37]==inputDevice && snapshot[38]==inputDevice);
        assert(snapshot[49]>0 && snapshot[50]==0 && snapshot[51]==0);
        assert(snapshot[52]==callbacks && snapshot[53]==4 && snapshot[54]==4);
        assert(snapshot[4]==0);
        for(float value:out) {assert(std::isfinite(value) && std::fabs(value)<=.967f);audible|=std::fabs(value)>.1f;}
        std::array<float,1024> analysis{};uint64_t start=0;
        while(session->read(analysis.data(),analysis.size(),&start)>0) {}
        position+=frames;
    }
    assert(audible && session->snapshot()[7]==3 && session->snapshot()[9]==120);
    assert(oboe::readCalls==0); // El transporte simulado read() siempre devuelve cero.
    session->stop();session->start(inputDevice,inputDevice?261:0,0);
    output=oboe::opened[2];
    output->callback->onAudioReady(output.get(),out.data(),256);
    auto snapshot=session->snapshot();
    assert(snapshot[46]==0 && snapshot[49]==0 && snapshot[2]==0);
    std::cout<<"PASS Session "<<(inputDevice?"USB stereo":"phone mono")<<": callbacks feed real C engine, record/play, fixed routing and cleared capture on restart; no read() calls\n";
}
int main() {testRing();testSession(266);testSession(0);}
