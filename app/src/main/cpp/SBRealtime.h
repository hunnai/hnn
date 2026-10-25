#ifndef SB_REALTIME_H
#define SB_REALTIME_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
#define SB_BANK_COUNT 6
typedef struct SBEngine SBEngine;
typedef struct { int32_t state; float progress; double bpm; double advanceMS; int32_t divisions; } SBBankSnapshot;
typedef struct { uint64_t clock; float inputPeak; float outputPeak; float safetyReductionDB; uint32_t analysisDrops; } SBSnapshot;
SBEngine *SBEngineCreate(double sampleRate, double advanceMS);
void SBEngineDestroy(SBEngine *engine);
double SBEngineRate(const SBEngine *engine);
uint64_t SBEngineClock(SBEngine *engine);
int SBEngineArm(SBEngine *engine, int bank, double bpm, double anchor);
int SBEngineToggle(SBEngine *engine, int bank);
int SBEngineErase(SBEngine *engine);
int SBEngineEraseBank(SBEngine *engine, int bank);
int SBEngineResizeBank(SBEngine *engine, int bank, int steps);
int SBEngineNudgeBank(SBEngine *engine, int bank, double milliseconds);
int SBEngineNudge(SBEngine *engine, double milliseconds);
int SBEngineSetInitialAdvance(SBEngine *engine, double milliseconds);
int SBEngineSetGain(SBEngine *engine, double gain);
int SBEngineReconfigureRate(SBEngine *engine, double sampleRate);
void SBEngineCollectGarbage(SBEngine *engine);
void SBEngineProcess(SBEngine *engine, const float *inL, const float *inR, float *outL, float *outR, uint32_t frames);
int SBEngineReadAnalysis(SBEngine *engine, float *destination, uint32_t capacity, uint64_t *start, uint32_t *count);
void SBEngineReadSnapshot(SBEngine *engine, SBSnapshot *snapshot, SBBankSnapshot *banks);
int SBFFTRealMagnitudes(const float *input, uint32_t size, double *magnitudes);
#ifdef __cplusplus
}
#endif
#endif
