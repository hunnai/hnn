"""Comprueba el signo y la duración de las correcciones SYNC en el motor C real.
Uso: python3 validacion/test_sync_nudge.py /ruta/libsoinu-core.so
"""
import ctypes as c
import math
import sys

lib = c.CDLL(sys.argv[1])
class Bank(c.Structure):
    _fields_ = [('state', c.c_int32), ('progress', c.c_float), ('bpm', c.c_double),
                ('advance', c.c_double), ('divisions', c.c_int32)]
class Snapshot(c.Structure):
    _fields_ = [('clock', c.c_uint64), ('inputPeak', c.c_float), ('outputPeak', c.c_float),
                ('reduction', c.c_float), ('drops', c.c_uint32)]
lib.SBEngineCreate.argtypes = [c.c_double, c.c_double]
lib.SBEngineCreate.restype = c.c_void_p
lib.SBEngineDestroy.argtypes = [c.c_void_p]
lib.SBEngineArm.argtypes = [c.c_void_p, c.c_int, c.c_double, c.c_double]
lib.SBEngineNudgeBank.argtypes = [c.c_void_p, c.c_int, c.c_double]
lib.SBEngineProcess.argtypes = [c.c_void_p] + [c.POINTER(c.c_float)] * 4 + [c.c_uint32]
lib.SBEngineReadSnapshot.argtypes = [c.c_void_p, c.POINTER(Snapshot), c.POINTER(Bank)]
lib.SBEngineReadAnalysis.argtypes = [c.c_void_p, c.POINTER(c.c_float), c.c_uint32,
                                   c.POINTER(c.c_uint64), c.POINTER(c.c_uint32)]
engine = lib.SBEngineCreate(48000, 0)
assert engine
clock = 0
header = Snapshot()
banks = (Bank * 6)()
audio = (c.c_float * 240)()
left = (c.c_float * 240)()
right = (c.c_float * 240)()
analysis = (c.c_float * 1024)()
start = c.c_uint64()
count = c.c_uint32()
def step():
    global clock
    begin = clock
    for i in range(240): audio[i] = .2 * math.sin(2 * math.pi * 440 * (begin + i) / 48000)
    lib.SBEngineProcess(engine, audio, audio, left, right, 240)
    clock += 240
    lib.SBEngineReadSnapshot(engine, c.byref(header), banks)
    while lib.SBEngineReadAnalysis(engine, analysis, 1024, c.byref(start), c.byref(count)): pass
    return begin, list(left)
def capture_phase():
    sine = cosine = 0.0
    for _ in range(20):
        begin, samples = step()
        for i, value in enumerate(samples):
            angle = 2 * math.pi * 440 * (begin + i) / 48000
            sine += value * math.sin(angle)
            cosine += value * math.cos(angle)
    return math.atan2(cosine, sine)
try:
    assert lib.SBEngineArm(engine, 0, 120, 0)
    for _ in range(240): step()
    assert banks[0].state == 3
    phase = capture_phase()
    for shift in [3.0, -2.0, 1.25, -3.0]:
        before_advance = banks[0].advance
        assert lib.SBEngineNudgeBank(engine, 0, shift)
        for _ in range(10): step()  # 50 ms, mayor que el fundido de 25 ms.
        measured = capture_phase()
        expected = 2 * math.pi * 440 * shift / 1000
        error = math.remainder(measured - phase - expected, 2 * math.pi)
        assert abs(error) < 1e-4, (shift, error)
        assert abs(banks[0].advance - before_advance - shift) < 1e-6
        assert banks[0].bpm == 120 and banks[0].divisions == 0
        assert all(bank.state == 0 for bank in banks[1:])
        phase = measured
    assert header.drops == 0
    print('PASS SYNC native: signed sub-3-ms shifts match playback phase; 120 BPM unchanged; other banks untouched')
finally:
    lib.SBEngineDestroy(engine)
