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
    session->configureInput(0);
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
    session->configureInput(0);
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
static void testAutomaticRouting() {
    for (int rate : {48000,96000}) {
        for(int declared : {1,2}) {
            AutomaticInputRouter router;router.configure(rate,declared,true,-1);
            std::vector<float> recovered,source;
            std::array<float,512> out{};
            for(int block=0;block<rate*2/256;++block) {
                std::array<float,512> data{};
                for(int i=0;i<256*declared;++i) data[i]=float((block*256*declared+i)%8)*.1f;
                source.insert(source.end(),data.begin(),data.begin()+256*declared);
                const auto frames=router.process(data.data(),256,block*256,double(block*256)/rate,out.data());
                recovered.insert(recovered.end(),out.begin(),out.begin()+frames*2);
            }
            assert(router.ready() && router.channels()==declared && !recovered.empty());
            const size_t originalFrames=source.size()/declared;
            const size_t start=originalFrames-recovered.size()/2;
            for(size_t i=0;i<recovered.size()/2;++i) {
                assert(recovered[i*2]==source[(start+i)*declared]);
                assert(recovered[i*2+1]==source[(start+i)*declared+declared-1]);
            }
        }
    }
    // Cadencia 4x sin estructura de ocho canales: no reinterpretar un estéreo.
    AutomaticInputRouter ambiguous;ambiguous.configure(48000,2,true,-1);
    std::array<float,2048> source{},out{};
    for(int block=0;block<400;++block) {
        for(int frame=0;frame<1024;++frame) {
            const float value=.2f*std::sin(float((block*1024+frame)*.001));
            source[frame*2]=source[frame*2+1]=value;
        }
        assert(ambiguous.process(source.data(),1024,block*256,double(block*256)/48000,out.data())==0);
    }
    assert(!ambiguous.ready());
    // Ruta validada anteriormente: empezar directamente, incluso en silencio.
    AutomaticInputRouter cached;cached.configure(96000,2,true,8);
    source.fill(0);
    assert(cached.ready() && cached.process(source.data(),1024,0,0,out.data())==256);
    std::cout<<"PASS automatic layout: mono/stereo at 48/96 kHz, no false 8-lane detection from periodic stereo or 4x cadence alone, cached recovery\n";
}
static void testRecoverySession() {
    constexpr int pair=0;
    oboe::opened.clear(); oboe::nativeUsbRate=96000;
    auto session=std::make_unique<Session>();
    session->configureInput(8);
    session->start(266,261,0);
    auto output=oboe::opened[0], input=oboe::opened[1];
    bool rejected=false;
    try { session->configureInput(0); } catch(const std::runtime_error &) { rejected=true; }
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
    session->stop();session->configureInput(0);session->start(266,261,0);
    assert(session->snapshot()[56]==0 && session->snapshot()[55]==0);
    std::cout<<"PASS recovery Session pair "<<pair+1<<": reconstructed rate, no FIFO drops, selected audio and real C loop playback\n";
}
static void testFreshAutomaticSession(bool packed) {
    oboe::opened.clear(); oboe::nativeUsbRate=packed?96000:48000;
    const int rate=oboe::nativeUsbRate;
    auto session=std::make_unique<Session>();session->configureInput(-1);
    session->start(266,261,0);
    auto output=oboe::opened[0];
    assert(!session->arm(0,120,0,-1)); // No grabar mientras se clasifica la ruta.
    std::array<float,256*8> source{};std::array<float,256*2> out{};
    bool armed=false,audible=false;
    const int stride=packed?8:2;
    for(int block=0;block<1200;++block) {
        for(int frame=0;frame<256;++frame) for(int lane=0;lane<stride;++lane)
            source[frame*stride+lane]=float(.2*std::sin((block*256+frame)*2*3.141592653589793*(440+lane*70)/rate));
        session->receiveInput(source.data(),packed?1024:256,double(block*256)/rate);
        auto snapshot=session->snapshot();
        if(snapshot[58]==1 && !armed) { assert(session->arm(0,120,0,-1)); armed=true; }
        output->callback->onAudioReady(output.get(),out.data(),256);
        snapshot=session->snapshot();
        assert(snapshot[50]==0); // No desbordamiento durante ni después de calibrar.
        if(snapshot[58]==1) assert(snapshot[56]==(packed?8:0) && snapshot[57]==0);
        for(float value:out) { assert(std::isfinite(value)); audible|=std::fabs(value)>.1f; }
        std::array<float,1024> analysis{};uint64_t start=0;
        while(session->read(analysis.data(),analysis.size(),&start)>0) {}
    }
    assert(armed && audible && session->snapshot()[7]==3);
    std::cout<<"PASS fresh automatic Session "<<(packed?"packed eight":"normal stereo")<<": layout, no FIFO drops and first-pair C loop record/play\n";
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
            std::vector<float> source(bytes/4),out(bytes/4),recovered;
            std::memcpy(source.data(),wav.data()+offset+8,bytes);
            for(int hint : {-1,8}) {
                AutomaticInputRouter router;router.configure(96000,2,true,hint);
                recovered.clear(); uint32_t rawFrames=0;
                for(int repeat=0;repeat<2;++repeat) for(size_t position=0;position<source.size();) {
                    const auto count=std::min<size_t>(862,source.size()-position);
                    const auto produced=router.process(source.data()+position,count/2,
                        rawFrames/4,double(rawFrames)/4/96000,out.data());
                    recovered.insert(recovered.end(),out.begin(),out.begin()+produced*2);
                    rawFrames+=count/2;position+=count;
                }
                assert(router.ready() && router.channels()==8 && !recovered.empty());
                const size_t totalFrames=source.size()/8*2;
                const size_t start=totalFrames-recovered.size()/2;
                for(size_t frame=0;frame<recovered.size()/2;++frame) {
                    const size_t original=(start+frame)%(source.size()/8);
                    assert(recovered[frame*2]==source[original*8]);
                    assert(recovered[frame*2+1]==source[original*8+1]);
                }
            }
            found=true;break;
        }
        offset+=8+bytes+(bytes%2);
    }
    assert(found);
    std::cout<<"PASS uploaded raw fixture: automatic detection and cached recovery match exact validated first-pair samples, including fragmented groups\n";
}
int main(int argc, char **argv) {
    testRing(); testSession(266); testSession(0); testInputDiagnostic();
    testAutomaticRouting();
    testRecoverySession();
    testFreshAutomaticSession(false); testFreshAutomaticSession(true);
    if(argc>1) decodeUploadedFixture(argv[1]);
    oboe::nativeUsbRate=384000;
    testSession(266);
    std::cout<<"PASS native rate: USB 96 kHz and phone 48 kHz shared with input/engine; fallback to 48 kHz beyond engine range\n";
}
