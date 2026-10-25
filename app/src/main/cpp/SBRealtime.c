#include "SBRealtime.h"
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdatomic.h>
#include <limits.h>
#define SB_PI 3.14159265358979323846
#define CMD_N 64u
#define FREE_N 256u
#define ANALYSIS_N 64u
#define ANALYSIS_FRAMES 1024u
#define SNAP_N 16u
static const int beats[6] = {1,2,4,4,8,16};
static double clampd(double x,double lo,double hi){return x<lo?lo:(x>hi?hi:x);}
typedef struct {uint32_t capacity;float *samples;} SBBuffer;
typedef struct {int op,index;double a,b;SBBuffer *buffer;} Command;
typedef struct {uint64_t start;uint32_t count;float samples[ANALYSIS_FRAMES];} Analysis;
typedef struct {SBSnapshot header;SBBankSnapshot banks[6];} Snapshot;
typedef struct {
 SBBuffer *buffer; double length,position,bpm,advance,start; uint32_t recorded;
 int playing,erasing,divisions,previousDivisions;uint32_t resizeRemaining,resizeTotal;double level,normalization,dc[2],min[2],max[2],sum[2],energy;
 double pending,shift;uint32_t fadeRemaining;
} Bank;
struct SBEngine {
 double rate,baseAdvance,gain,targetGain;uint64_t clock;Bank banks[6];
 _Atomic uint64_t publishedClock;
 Command commands[CMD_N];_Atomic unsigned commandHead,commandTail;
 SBBuffer *garbage[FREE_N];_Atomic unsigned freeHead,freeTail;
 Analysis analysis[ANALYSIS_N];_Atomic unsigned analysisHead,analysisTail;
 Snapshot snapshots[SNAP_N];_Atomic unsigned snapshotHead,snapshotTail;
 Snapshot lastSnapshot;uint32_t drops;
};
static void releaseBuffer(SBBuffer *b){if(b){free(b->samples);free(b);}}
static void resetBank(SBEngine *e,Bank *b){memset(b,0,sizeof(*b));b->position=b->advance=round(e->baseAdvance*e->rate/1000.0);b->normalization=1;}
void SBEngineCollectGarbage(SBEngine *e){if(!e)return;unsigned t=atomic_load_explicit(&e->freeTail,memory_order_relaxed),h=atomic_load_explicit(&e->freeHead,memory_order_acquire);while(t!=h){releaseBuffer(e->garbage[t]);t=(t+1)%FREE_N;}atomic_store_explicit(&e->freeTail,t,memory_order_release);}
static void retire(SBEngine *e,SBBuffer *b){if(!b)return;unsigned h=atomic_load_explicit(&e->freeHead,memory_order_relaxed);unsigned next=(h+1)%FREE_N;
 /* At most 64 queued commands plus six banks can release buffers between
    producer calls; each producer call collects garbage. Capacity 256 suffices. */
 if(next!=atomic_load_explicit(&e->freeTail,memory_order_acquire)){e->garbage[h]=b;atomic_store_explicit(&e->freeHead,next,memory_order_release);}
}
SBEngine *SBEngineCreate(double rate,double advance){if(!isfinite(rate)||rate<8000||rate>192000||!isfinite(advance))return NULL;SBEngine *e=calloc(1,sizeof(*e));if(!e)return NULL;e->rate=rate;e->baseAdvance=advance;e->gain=e->targetGain=1;atomic_init(&e->publishedClock,0);atomic_init(&e->commandHead,0);atomic_init(&e->commandTail,0);atomic_init(&e->freeHead,0);atomic_init(&e->freeTail,0);atomic_init(&e->analysisHead,0);atomic_init(&e->analysisTail,0);atomic_init(&e->snapshotHead,0);atomic_init(&e->snapshotTail,0);for(int i=0;i<6;i++)resetBank(e,&e->banks[i]);return e;}
double SBEngineRate(const SBEngine *e){return e?e->rate:0;}
uint64_t SBEngineClock(SBEngine *e){return e?atomic_load_explicit(&e->publishedClock,memory_order_acquire):0;}
static int enqueue(SBEngine *e,Command c){if(!e)return 0;SBEngineCollectGarbage(e);unsigned h=atomic_load_explicit(&e->commandHead,memory_order_relaxed),n=(h+1)%CMD_N;if(n==atomic_load_explicit(&e->commandTail,memory_order_acquire))return 0;e->commands[h]=c;atomic_store_explicit(&e->commandHead,n,memory_order_release);return 1;}
int SBEngineArm(SBEngine *e,int bank,double bpm,double anchor){if(!e||bank<0||bank>=6||!isfinite(bpm)||bpm<=0||!isfinite(anchor))return 0;double frames=ceil(beats[bank]*60*e->rate/bpm)+1;if(!isfinite(frames)||frames>UINT32_MAX/2)return 0;SBBuffer *b=calloc(1,sizeof(*b));if(!b)return 0;b->capacity=(uint32_t)frames;b->samples=calloc((size_t)b->capacity*2,sizeof(float));if(!b->samples){free(b);return 0;}Command c={1,bank,bpm,anchor,b};if(!enqueue(e,c)){releaseBuffer(b);return 0;}return 1;}
int SBEngineToggle(SBEngine *e,int bank){if(bank<0||bank>=6)return 0;Command c={2,bank,0,0,NULL};return enqueue(e,c);}
int SBEngineErase(SBEngine *e){Command c={3,0,0,0,NULL};return enqueue(e,c);}
int SBEngineNudge(SBEngine *e,double ms){if(!isfinite(ms)||fabs(ms)>1000)return 0;Command c={4,0,ms,0,NULL};return enqueue(e,c);}
int SBEngineResizeBank(SBEngine *e,int bank,int steps){if(bank<0||bank>=6||steps==0||steps>16||steps< -16)return 0;Command c={9,bank,(double)steps,0,NULL};return enqueue(e,c);}
int SBEngineEraseBank(SBEngine *e,int bank){if(bank<0||bank>=6)return 0;Command c={8,bank,0,0,NULL};return enqueue(e,c);}
int SBEngineNudgeBank(SBEngine *e,int bank,double ms){if(bank<0||bank>=6||!isfinite(ms)||fabs(ms)>1000)return 0;Command c={7,bank,ms,0,NULL};return enqueue(e,c);}
int SBEngineSetInitialAdvance(SBEngine *e,double ms){if(!isfinite(ms)||fabs(ms)>2000)return 0;Command c={6,0,ms,0,NULL};return enqueue(e,c);}
int SBEngineSetGain(SBEngine *e,double gain){if(!isfinite(gain))return 0;Command c={5,0,clampd(gain,0,pow(10,.7/20)),0,NULL};return enqueue(e,c);}
static void commands(SBEngine *e){unsigned t=atomic_load_explicit(&e->commandTail,memory_order_relaxed),h=atomic_load_explicit(&e->commandHead,memory_order_acquire);while(t!=h){Command c=e->commands[t];t=(t+1)%CMD_N;
 if(c.op==1){Bank *b=&e->banks[c.index];if(b->buffer){retire(e,c.buffer);continue;}resetBank(e,b);b->buffer=c.buffer;b->bpm=c.a;b->length=beats[c.index]*60*e->rate/c.a;double period=60*e->rate/c.a;b->start=round(c.b+(floor(((double)e->clock-c.b)/period)+1)*period);for(int ch=0;ch<2;ch++){b->min[ch]=INFINITY;b->max[ch]=-INFINITY;}}
 else if(c.op==2){Bank *b=&e->banks[c.index];if(b->buffer&&b->recorded==b->buffer->capacity&&!b->erasing)b->playing=!b->playing;}
 else if(c.op==3){for(int i=0;i<6;i++){Bank *b=&e->banks[i];if(!b->buffer)continue;if(b->recorded<b->buffer->capacity||b->level<=0){retire(e,b->buffer);resetBank(e,b);}else{b->erasing=1;b->playing=0;}}}
 else if(c.op==4){for(int i=0;i<6;i++){Bank *b=&e->banks[i];if(b->buffer&&b->recorded==b->buffer->capacity&&!b->erasing){double delta=round(c.a*e->rate/1000);b->pending+=delta;b->advance+=delta;}}}
 else if(c.op==5)e->targetGain=c.a;
 else if(c.op==6)e->baseAdvance=c.a;
 else if(c.op==7){Bank *b=&e->banks[c.index];if(b->buffer&&b->recorded==b->buffer->capacity&&b->playing&&!b->erasing){double delta=round(c.a*e->rate/1000);b->pending+=delta;b->advance+=delta;}}
 else if(c.op==8){Bank *b=&e->banks[c.index];if(b->buffer&&b->recorded==b->buffer->capacity&&!b->playing&&!b->erasing){if(b->level<=0){retire(e,b->buffer);resetBank(e,b);}else b->erasing=1;}}

 else if(c.op==9){Bank *b=&e->banks[c.index];if(b->buffer&&b->recorded==b->buffer->capacity&&b->playing&&!b->erasing){int limit=0;for(int n=beats[c.index]*8;n>1;n>>=1)limit++;int next=b->divisions+(int)c.a;if(next<0)next=0;if(next>limit)next=limit;if(next!=b->divisions){if(!b->resizeRemaining)b->previousDivisions=b->divisions;b->divisions=next;b->resizeTotal=b->resizeRemaining=(uint32_t)fmax(2,round(e->rate*.002));}}}

 }atomic_store_explicit(&e->commandTail,t,memory_order_release);}
static double readOriginal(const Bank *b,double position,int channel){double p=fmod(position,b->length);if(p<0)p+=b->length;uint32_t left=(uint32_t)floor(p);if(left>=b->buffer->capacity)left=0;uint32_t right=(left+1)%b->buffer->capacity;double f=p-floor(p);const float *x=b->buffer->samples+(size_t)channel*b->buffer->capacity;return x[left]*(1-f)+x[right]*f;}
static double readDivision(const Bank *b,double position,int channel,int divisions){
 if(divisions==0)return readOriginal(b,position,channel);
 double period=ldexp(b->length,-divisions),p=fmod(position,period);if(p<0)p+=period;
 double sample=readOriginal(b,p,channel);
 /* Fade only reduced-loop edges, max 2 ms each. No time or pitch change. */
 double edge=fmin(period*.05,fmax(1,(double)b->resizeTotal));
 double phase=fmin(1,fmin(p,period-p)/edge);
 if(phase>=1)return sample;
 double envelope=.5-.5*cos(SB_PI*phase);
 return (sample-b->dc[channel])*envelope+b->dc[channel];
}
static double readSample(const Bank *b,double position,int channel){
 double value=readDivision(b,position,channel,b->divisions);
 if(!b->resizeRemaining)return value;
 double progress=(double)(b->resizeTotal-b->resizeRemaining)/(b->resizeTotal-1);
 double blend=.5-.5*cos(SB_PI*progress);
 return readDivision(b,position,channel,b->previousDivisions)*(1-blend)+value*blend;
}
static void calibrate(SBEngine *e,Bank *b){double peak=0;for(int ch=0;ch<2;ch++){b->dc[ch]=b->sum[ch]/b->recorded;peak=fmax(peak,fmax(fabs(b->min[ch]-b->dc[ch]),fabs(b->max[ch]-b->dc[ch])));}double rms=sqrt(fmax(0,b->energy/(2*b->recorded)-(b->dc[0]*b->dc[0]+b->dc[1]*b->dc[1])/2));b->normalization=(peak>.0005&&rms>.0001)?fmin(1000,pow(10,-1.0/20)/peak):1;b->playing=1;b->position+=b->recorded-b->length;(void)e;}
static void publish(SBEngine *e,float inputPeak,float outputPeak,float reduction){unsigned h=atomic_load_explicit(&e->snapshotHead,memory_order_relaxed),n=(h+1)%SNAP_N;if(n==atomic_load_explicit(&e->snapshotTail,memory_order_acquire))return;Snapshot *s=&e->snapshots[h];memset(s,0,sizeof(*s));s->header.clock=e->clock;s->header.inputPeak=inputPeak;s->header.outputPeak=outputPeak;s->header.safetyReductionDB=reduction;s->header.analysisDrops=e->drops;for(int i=0;i<6;i++){Bank *b=&e->banks[i];s->banks[i].state=!b->buffer?0:(b->recorded<b->buffer->capacity?(e->clock<(uint64_t)b->start?1:2):(b->playing?3:4));s->banks[i].divisions=b->divisions;s->banks[i].bpm=b->bpm;s->banks[i].advanceMS=b->advance*1000/e->rate;s->banks[i].progress=b->buffer?(float)b->recorded/b->buffer->capacity:0;}atomic_store_explicit(&e->snapshotHead,n,memory_order_release);}
void SBEngineProcess(SBEngine *e,const float *inL,const float *inR,float *outL,float *outR,uint32_t frames){if(!e||!outL||!outR)return;commands(e);float inputPeak=0,outputPeak=0;double maxGain=pow(10,.7/20),ceiling=pow(10,-.3/20);uint32_t fade=(uint32_t)fmax(2,round(e->rate*.025));double step=1/fmax(1,round(e->rate*.005)),gainStep=1/fmax(1,round(e->rate*.01));
 for(uint32_t j=0;j<frames;j++){float l=inL?inL[j]:0,r=inR?inR[j]:l;if(!isfinite(l))l=0;if(!isfinite(r))r=0;inputPeak=fmaxf(inputPeak,fmaxf(fabsf(l),fabsf(r)));double sumL=0,sumR=0,weight=0;
  for(int k=0;k<6;k++){Bank *b=&e->banks[k];if(!b->buffer)continue;
   if(b->recorded<b->buffer->capacity){if((double)(e->clock+j)>=b->start){uint32_t pos=b->recorded;float values[2]={l,r};for(int ch=0;ch<2;ch++){float v=values[ch];b->buffer->samples[(size_t)ch*b->buffer->capacity+pos]=v;b->min[ch]=fmin(b->min[ch],v);b->max[ch]=fmax(b->max[ch],v);b->sum[ch]+=v;b->energy+=(double)v*v;}b->recorded++;if(b->recorded==b->buffer->capacity)calibrate(e,b);}continue;}
   if(!b->fadeRemaining&&b->pending!=0){b->shift=b->pending;b->pending=0;b->fadeRemaining=fade;}
   double a=readSample(b,b->position,0),c=readSample(b,b->position,1);
   if(b->fadeRemaining){double progress=(double)(fade-b->fadeRemaining)/(fade-1);double blend=.5-.5*cos(SB_PI*progress);a=a*(1-blend)+readSample(b,b->position+b->shift,0)*blend;c=c*(1-blend)+readSample(b,b->position+b->shift,1)*blend;b->fadeRemaining--;}
   if(b->resizeRemaining)b->resizeRemaining--;
   b->position=fmod(b->position+1,b->length);if(b->shift!=0&&!b->fadeRemaining){b->position=fmod(b->position+b->shift,b->length);if(b->position<0)b->position+=b->length;b->shift=0;}
   b->level=clampd(b->level+(b->playing?step:-step),0,1);sumL+=(a-b->dc[0])*b->normalization*b->level;sumR+=(c-b->dc[1])*b->normalization*b->level;weight+=b->level;
   if(b->erasing&&b->level==0){retire(e,b->buffer);resetBank(e,b);}
  }
  e->gain+=clampd(e->targetGain-e->gain,-gainStep,gainStep);e->gain=clampd(e->gain,0,maxGain);double gain=e->gain/fmax(1,weight);outL[j]=(float)(sumL*gain);outR[j]=(float)(sumR*gain);outputPeak=fmaxf(outputPeak,fmaxf(fabsf(outL[j]),fabsf(outR[j])));
 }
 double safety=outputPeak>ceiling?ceiling/outputPeak:1;if(safety<1){for(uint32_t j=0;j<frames;j++){outL[j]*=(float)safety;outR[j]*=(float)safety;}}
 for(uint32_t offset=0;offset<frames;offset+=ANALYSIS_FRAMES){unsigned h=atomic_load_explicit(&e->analysisHead,memory_order_relaxed),next=(h+1)%ANALYSIS_N;if(next==atomic_load_explicit(&e->analysisTail,memory_order_acquire)){e->drops++;continue;}Analysis *a=&e->analysis[h];a->start=e->clock+offset;a->count=frames-offset<ANALYSIS_FRAMES?frames-offset:ANALYSIS_FRAMES;for(uint32_t j=0;j<a->count;j++){float l=inL?inL[offset+j]:0,r=inR?inR[offset+j]:l;a->samples[j]=(isfinite(l)&&isfinite(r))?.5f*(l+r):0;}atomic_store_explicit(&e->analysisHead,next,memory_order_release);}
 e->clock+=frames;atomic_store_explicit(&e->publishedClock,e->clock,memory_order_release);publish(e,inputPeak,(float)(outputPeak*safety),(float)(-20*log10(safety)));
}
int SBEngineReadAnalysis(SBEngine *e,float *dest,uint32_t capacity,uint64_t *start,uint32_t *count){if(!e||!dest||!start||!count)return 0;unsigned t=atomic_load_explicit(&e->analysisTail,memory_order_relaxed);if(t==atomic_load_explicit(&e->analysisHead,memory_order_acquire))return 0;Analysis *a=&e->analysis[t];if(capacity<a->count)return 0;memcpy(dest,a->samples,a->count*sizeof(float));*start=a->start;*count=a->count;atomic_store_explicit(&e->analysisTail,(t+1)%ANALYSIS_N,memory_order_release);return 1;}
void SBEngineReadSnapshot(SBEngine *e,SBSnapshot *header,SBBankSnapshot *banks){if(!e||!header||!banks)return;unsigned t=atomic_load_explicit(&e->snapshotTail,memory_order_relaxed),h=atomic_load_explicit(&e->snapshotHead,memory_order_acquire);float peak=0,output=0;while(t!=h){e->lastSnapshot=e->snapshots[t];peak=fmaxf(peak,e->lastSnapshot.header.inputPeak);output=fmaxf(output,e->lastSnapshot.header.outputPeak);t=(t+1)%SNAP_N;}atomic_store_explicit(&e->snapshotTail,t,memory_order_release);*header=e->lastSnapshot.header;header->inputPeak=peak;header->outputPeak=output;memcpy(banks,e->lastSnapshot.banks,sizeof(e->lastSnapshot.banks));}
int SBEngineReconfigureRate(SBEngine *e,double rate){if(!e||!isfinite(rate)||rate<8000||rate>192000)return 0;commands(e);SBEngineCollectGarbage(e);double ratio=rate/e->rate;SBBuffer *replacement[6]={0};for(int i=0;i<6;i++){Bank *b=&e->banks[i];if(!b->buffer||b->recorded<b->buffer->capacity)continue;SBBuffer *n=calloc(1,sizeof(*n));if(!n)goto fail;n->capacity=(uint32_t)ceil(b->length*ratio)+1;n->samples=calloc((size_t)n->capacity*2,sizeof(float));if(!n->samples){free(n);goto fail;}replacement[i]=n;for(int ch=0;ch<2;ch++)for(uint32_t j=0;j<n->capacity;j++)n->samples[(size_t)ch*n->capacity+j]=(float)readOriginal(b,j/ratio,ch);}
 for(int i=0;i<6;i++){Bank *b=&e->banks[i];if(!b->buffer)continue;if(b->recorded<b->buffer->capacity){releaseBuffer(b->buffer);resetBank(e,b);continue;}releaseBuffer(b->buffer);b->buffer=replacement[i];b->length*=ratio;b->position*=ratio;b->advance*=ratio;b->recorded=b->buffer->capacity;b->pending=0;b->shift=0;b->fadeRemaining=0;b->resizeRemaining=0;b->previousDivisions=b->divisions;b->resizeTotal=(uint32_t)fmax(2,round(rate*.002));b->level=0;}
 e->clock=(uint64_t)llround(e->clock*ratio);e->rate=rate;atomic_store(&e->publishedClock,e->clock);atomic_store(&e->analysisHead,0);atomic_store(&e->analysisTail,0);atomic_store(&e->snapshotHead,0);atomic_store(&e->snapshotTail,0);memset(&e->lastSnapshot,0,sizeof(e->lastSnapshot));for(int i=0;i<6;i++)if(!e->banks[i].buffer)resetBank(e,&e->banks[i]);publish(e,0,0,0);return 1;
fail:for(int i=0;i<6;i++)releaseBuffer(replacement[i]);return 0;}
void SBEngineDestroy(SBEngine *e){if(!e)return;SBEngineCollectGarbage(e);unsigned t=atomic_load(&e->commandTail),h=atomic_load(&e->commandHead);while(t!=h){if(e->commands[t].op==1)releaseBuffer(e->commands[t].buffer);t=(t+1)%CMD_N;}for(int i=0;i<6;i++)releaseBuffer(e->banks[i].buffer);free(e);}
int SBFFTRealMagnitudes(const float *input,uint32_t n,double *out){if(!input||!out||n<2||(n&(n-1)))return 0;double *re=malloc(n*sizeof(double)),*im=calloc(n,sizeof(double));if(!re||!im){free(re);free(im);return 0;}for(uint32_t i=0,j=0;i<n;i++){re[j]=input[i];uint32_t bit=n>>1;while(j&bit){j^=bit;bit>>=1;}j^=bit;}for(uint32_t length=2;length<=n;length<<=1){double angle=-2*SB_PI/length,stepC=cos(angle),stepS=sin(angle);for(uint32_t start=0;start<n;start+=length){double c=1,s=0;for(uint32_t j=0;j<length/2;j++){uint32_t a=start+j,b=a+length/2;double tr=re[b]*c-im[b]*s,ti=re[b]*s+im[b]*c;re[b]=re[a]-tr;im[b]=im[a]-ti;re[a]+=tr;im[a]+=ti;double nextC=c*stepC-s*stepS;s=c*stepS+s*stepC;c=nextC;}}if(length==n)break;}for(uint32_t k=0;k<=n/2;k++)out[k]=hypot(re[k],im[k]);free(re);free(im);return 1;}