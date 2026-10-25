"""Prueba funcional del motor original con audio sintético; no requiere Android.
Uso: python test_motor.py /ruta/a/libsoinu-core.so
"""
import ctypes as c
import math
import sys

lib = c.CDLL(sys.argv[1])
class Bank(c.Structure):
    _fields_ = [('state', c.c_int32), ('progress', c.c_float), ('bpm', c.c_double), ('advance', c.c_double), ('divisions', c.c_int32)]
class Snapshot(c.Structure):
    _fields_ = [('clock', c.c_uint64), ('inputPeak', c.c_float), ('outputPeak', c.c_float), ('reduction', c.c_float), ('drops', c.c_uint32)]
lib.SBEngineCreate.argtypes = [c.c_double, c.c_double]; lib.SBEngineCreate.restype = c.c_void_p
for name in ['SBEngineDestroy', 'SBEngineCollectGarbage']:
    getattr(lib, name).argtypes = [c.c_void_p]
lib.SBEngineArm.argtypes = [c.c_void_p, c.c_int, c.c_double, c.c_double]
lib.SBEngineProcess.argtypes = [c.c_void_p, c.POINTER(c.c_float), c.POINTER(c.c_float), c.POINTER(c.c_float), c.POINTER(c.c_float), c.c_uint32]
lib.SBEngineReadSnapshot.argtypes = [c.c_void_p, c.POINTER(Snapshot), c.POINTER(Bank)]
lib.SBEngineReadAnalysis.argtypes = [c.c_void_p, c.POINTER(c.c_float), c.c_uint32, c.POINTER(c.c_uint64), c.POINTER(c.c_uint32)]
for name in ['SBEngineToggle', 'SBEngineEraseBank']:
    getattr(lib, name).argtypes = [c.c_void_p, c.c_int]
lib.SBEngineResizeBank.argtypes = [c.c_void_p, c.c_int, c.c_int]
lib.SBEngineNudge.argtypes = [c.c_void_p, c.c_double]
lib.SBEngineReconfigureRate.argtypes = [c.c_void_p, c.c_double]
lib.SBEngineErase.argtypes = [c.c_void_p]
lib.SBFFTRealMagnitudes.argtypes = [c.POINTER(c.c_float), c.c_uint32, c.POINTER(c.c_double)]

engine = lib.SBEngineCreate(48000, 0)
assert engine
header = Snapshot(); banks = (Bank * 6)()
audio = (c.c_float * 256)(); left = (c.c_float * 256)(); right = (c.c_float * 256)()
analysis = (c.c_float * 1024)(); position = c.c_uint64(); count = c.c_uint32()
clock = 0; audible = False
def step(blocks=1):
    global clock, audible
    for _ in range(blocks):
        for i in range(256): audio[i] = .2 * math.sin(2 * math.pi * 440 * (clock + i) / 48000)
        lib.SBEngineProcess(engine, audio, None, left, right, 256)
        clock += 256
        lib.SBEngineReadSnapshot(engine, c.byref(header), banks)
        assert all(math.isfinite(x) and abs(x) <= .967 for x in left)
        audible = audible or max(abs(x) for x in left) > .1
        while lib.SBEngineReadAnalysis(engine, analysis, 1024, c.byref(position), c.byref(count)):
            assert 0 < count.value <= 1024
        lib.SBEngineCollectGarbage(engine)
try:
    assert not lib.SBEngineArm(engine, -1, 120, 0)
    for i in range(6): assert lib.SBEngineArm(engine, i, 120, 0)
    step(1650)
    assert all(b.state == 3 for b in banks), [b.state for b in banks]
    assert audible and header.drops == 0
    print('PASS six banks record, mix and play; analysis contiguous; output bounded')
    assert lib.SBEngineToggle(engine, 0); step(3); assert banks[0].state == 4
    assert lib.SBEngineEraseBank(engine, 0); step(3); assert banks[0].state == 0
    assert lib.SBEngineToggle(engine, 1); step(3); assert banks[1].state == 4
    assert lib.SBEngineToggle(engine, 1); step(3); assert banks[1].state == 3
    print('PASS pause, resume and selective erase')
    assert lib.SBEngineResizeBank(engine, 2, 1); step(3); assert banks[2].divisions == 1
    assert lib.SBEngineResizeBank(engine, 2, -1); step(3); assert banks[2].divisions == 0
    assert lib.SBEngineNudge(engine, 1.0); step(6)
    assert all(abs(banks[i].advance - 1) < .001 for i in range(1, 6))
    print('PASS resize both directions and phase adjustment')
    assert lib.SBEngineReconfigureRate(engine, 96000)
    lib.SBEngineReadSnapshot(engine, c.byref(header), banks)
    assert all(banks[i].state == 3 for i in range(1, 6))
    assert all(abs(banks[i].advance - 1) < .001 for i in range(1, 6))
    print('PASS completed loops and phase survive sample-rate reconfiguration')
    assert lib.SBEngineErase(engine); step(10); assert all(b.state == 0 for b in banks)
    print('PASS erase all')
finally:
    lib.SBEngineDestroy(engine)

n = 1024; signal = (c.c_float * n)(*[math.sin(2 * math.pi * 9 * i / n) for i in range(n)])
spectrum = (c.c_double * (n // 2 + 1))()
assert lib.SBFFTRealMagnitudes(signal, n, spectrum)
assert max(range(len(spectrum)), key=lambda i: spectrum[i]) == 9
assert abs(spectrum[9] - n / 2) < .001
assert not lib.SBFFTRealMagnitudes(signal, 1000, spectrum)
print('PASS real FFT magnitude and invalid-size rejection')
