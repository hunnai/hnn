#include <cassert>
#include <iostream>
#include <fstream>
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
    const int opens = inputDevice && (oboe::nativeUsbRate < 8000 || oboe::nativeUsbRate > 192000) ? 3 : 2;
    assert(oboe::opened.size()==static_cast<size_t>(opens));
    auto output=oboe::opened[opens-2];auto input=oboe::opened[opens-1];
    assert(input->callback && output->callback);
    assert(input->getPerformanceMode()==(inputDevice?oboe::PerformanceMode::None:oboe::PerformanceMode::LowLatency));
    assert(output->getPerformanceMode()==oboe::PerformanceMode::LowLatency);
    assert(input->getDeviceId()==inputDevice);
    assert(input->getInputPreset()==(inputDevice?oboe::InputPreset::Generic:oboe::InputPreset::Unprocessed));
    const int sampleRate = inputDevice && opens==2 ? oboe::nativeUsbRate : 48000;
    assert(input->getSampleRate()==sampleRate && output->getSampleRate()==sampleRate);
    assert(session->snapshot()[0]==sampleRate);
    const int frames = inputDevice ? (sampleRate==96000?3076:1538) : 256;
    std::vector<float> out(frames * 2), data(frames * 2);
    int callbacks = 0;
    // La salida puede arrancar antes de la primera entrega de captura.
    assert(output->callback->onAudioReady(output.get(),out.data(),256)==oboe::DataCallbackResult::Continue);
    assert(session->snapshot()[46]==0 && session->snapshot()[47]==1);
    assert(session->arm(0,120,0,-1));
    uint64_t position=0;bool audible=false;
    for(int block=0;block<600;++block) {
        for(int i=0;i<frames;++i) {
            float value=float(.2*std::sin(2*3.141592653589793*440*(position+i)/sampleRate));
            if(inputDevice) {data[i*2]=value;data[i*2+1]=value*.5f;} else data[i]=value;
        }
        // Captura por fragmentos de hasta 256; salida USB nativa de 3076 (96 kHz), evitando la conversión a 1538/48 kHz.
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
    output=oboe::opened[2*opens-2];
    output->callback->onAudioReady(output.get(),out.data(),256);
    auto snapshot=session->snapshot();
    assert(snapshot[46]==0 && snapshot[49]==0 && snapshot[2]==0);
    std::cout<<"PASS Session "<<(inputDevice?"USB stereo":"phone mono")<<": callbacks feed real C engine, record/play, fixed routing and cleared capture on restart; no read() calls\n";
}
static void testInputDiagnostic() {
    oboe::opened.clear();
    oboe::nativeUsbRate=48000;
    auto session=std::make_unique<Session>();
    session->start(266,261,0);
    auto input=oboe::opened.back();
    assert(!session->inputDiagnostic().ready());
    assert(session->beginDiagnostic());
    assert(!session->beginDiagnostic());
    constexpr int total=48000*4;
    std::thread producer([&] {
        std::array<float,137*2> block{};
        for (int position=0; position<total;) {
            const int count=std::min(137,total-position);
            for(int i=0;i<count;++i) {
                block[i*2]=float(position+i)-100000;
                block[i*2+1]=-block[i*2];
            }
            input->callback->onAudioReady(input.get(),block.data(),count);
            position+=count;
        }
    });
    while(!session->inputDiagnostic().ready()) std::this_thread::yield();
    const auto &diagnostic=session->inputDiagnostic();
    const auto header=diagnostic.header();
    assert(header[0]==48000 && header[1]==2 && header[2]>=0 && header[3]==total);
    const auto &samples=diagnostic.data();
    assert(samples.size()==total*2);
    for(int i=0;i<total;++i) {
        assert(samples[i*2]==float(i)-100000);
        assert(samples[i*2+1]==-samples[i*2]);
    }
    producer.join();
    // No se consumió el anillo: verificar captura íntegra pese a sus descartes.
    assert(session->snapshot()[50]>0);
    assert(session->beginDiagnostic());
    assert(!session->inputDiagnostic().ready());
    session->stop();
    assert(!session->inputDiagnostic().ready() && !session->beginDiagnostic());
    std::cout<<"PASS input diagnostic: raw samples before FIFO drops, concurrent publish, bounded capture, restart and stop\n";
}
static void testPairDecoder() {
    std::vector<float> source(1003*8);
    for (int frame=0;frame<1003;++frame)
        for(int lane=0;lane<8;++lane) source[frame*8+lane]=float(frame*10+lane);
    for(int pair=0;pair<4;++pair) {
        InputPairDecoder decoder;decoder.reset(pair*2);
        std::vector<float> output(source.size());uint32_t produced=0;
        // Límites de callback que cortan grupos, incluso a mitad de un par.
        for(size_t offset=0;offset<source.size();) {
            const auto count=std::min<size_t>(37,source.size()-offset);
            produced+=decoder.decode(source.data()+offset,count,output.data()+produced*2);
            offset+=count;
        }
        assert(produced==1003);
        for(int frame=0;frame<1003;++frame) {
            assert(output[frame*2]==source[frame*8+pair*2]);
            assert(output[frame*2+1]==source[frame*8+pair*2+1]);
        }
        decoder.reset(pair*2);
        assert(decoder.decode(source.data(),7,output.data())==0);
        decoder.reset(pair*2);
        assert(decoder.decode(source.data()+8,8,output.data())==1);
        assert(output[0]==source[8+pair*2]);
    }
    std::cout<<"PASS eight-channel decoder: all pairs, fragmented callbacks and cleared partial group\n";
}
static void testRecoverySession(int pair) {
    oboe::opened.clear(); oboe::nativeUsbRate=96000;
    auto session=std::make_unique<Session>();
    session->setInputLayout(8,pair);
    session->start(266,261,0);
    auto output=oboe::opened[0], input=oboe::opened[1];
    bool rejected=false;
    try { session->setInputLayout(0,0); } catch(const std::runtime_error &) { rejected=true; }
    assert(rejected);
    assert(session->arm(0,120,0,-1));
    constexpr int frames=1024;
    std::array<float,frames*8> source{};
    std::array<float,frames*2> out{};
    int position=0; bool audible=false;
    for(int block=0;block<150;++block) {
        for(int frame=0;frame<frames;++frame)
            for(int lane=0;lane<8;++lane)
                source[frame*8+lane]=(lane/2==pair ? .2f : .9f)*
                    std::sin(float((position+frame)*2*3.141592653589793*(440+lane/2*100)/96000));
        for(int offset=0;offset<frames*4;) {
            const int count=std::min(257,frames*4-offset);
            input->callback->onAudioReady(input.get(),source.data()+offset*2,count);
            offset+=count;
        }
        output->callback->onAudioReady(output.get(),out.data(),frames);
        const auto snapshot=session->snapshot();
        assert(snapshot[46]==(block+1)*frames*4); // Frames que declara Android.
        assert(snapshot[55]==(block+1)*frames);   // Frames completos reconstruidos.
        assert(snapshot[50]==0 && snapshot[51]==0);
        assert(snapshot[56]==8 && snapshot[57]==pair);
        assert(snapshot[2]>.19 && snapshot[2]<.201); // No se filtra ningún otro par.
        for(float value:out) {assert(std::isfinite(value));audible|=std::fabs(value)>.1f;}
        std::array<float,1024> analysis{};uint64_t start=0;
        while(session->read(analysis.data(),analysis.size(),&start)>0) {}
        position+=frames;
    }
    assert(audible && session->snapshot()[7]==3);
    session->stop();session->setInputLayout(0,0);session->start(266,261,0);
    assert(session->snapshot()[56]==0 && session->snapshot()[55]==0);
    std::cout<<"PASS recovery Session pair "<<pair+1<<": reconstructed rate, no FIFO drops, selected audio and real C loop playback\n";
}
// Fixture privada opcional: no se publica audio subido por el usuario.
static void decodeUploadedFixture(const char *path) {
    std::ifstream file(path,std::ios::binary);
    std::vector<char> wav((std::istreambuf_iterator<char>(file)),std::istreambuf_iterator<char>());
    assert(wav.size()>12);
    size_t offset=12;bool found=false;
    while(offset+8<=wav.size()) {
        uint32_t bytes=0;std::memcpy(&bytes,wav.data()+offset+4,4);
        assert(offset+8+bytes<=wav.size());
        if(std::memcmp(wav.data()+offset,"data",4)==0) {
            assert(bytes%32==0);
            std::vector<float> source(bytes/4),out(bytes/4);
            std::memcpy(source.data(),wav.data()+offset+8,bytes);
            for(int pair=0;pair<4;++pair) {
                InputPairDecoder decoder;decoder.reset(pair*2);uint32_t produced=0;
                for(size_t position=0;position<source.size();) {
                    const auto count=std::min<size_t>(864,source.size()-position);
                    produced+=decoder.decode(source.data()+position,count,out.data()+produced*2);
                    position+=count;
                }
                assert(produced==source.size()/8);
                for(uint32_t frame=0;frame<produced;++frame) {
                    assert(out[frame*2]==source[frame*8+pair*2]);
                    assert(out[frame*2+1]==source[frame*8+pair*2+1]);
                }
            }
            found=true;break;
        }
        offset+=8+bytes+(bytes%2);
    }
    assert(found);
    std::cout<<"PASS uploaded raw fixture: four recovered pairs preserve exact samples and 4:1 frame mapping\n";
}
int main(int argc, char **argv) {
    testRing(); testSession(266); testSession(0); testInputDiagnostic();
    testPairDecoder();
    for(int pair=0;pair<4;++pair) testRecoverySession(pair);
    if(argc>1) decodeUploadedFixture(argv[1]);
    oboe::nativeUsbRate=384000;
    testSession(266);
    std::cout<<"PASS native rate: USB 96 kHz and phone 48 kHz shared with input/engine; fallback to 48 kHz beyond engine range\n";
}
