/* Adaptador de prueba JVM: usa la FFT real del motor, sin dispositivo de audio. */
#include <jni.h>
#include "../app/src/main/cpp/SBRealtime.h"
JNIEXPORT jboolean JNICALL Java_com_example_soinubiderxl_NativeAudio_fft(
    JNIEnv *env, jobject instance, jfloatArray input, jdoubleArray output) {
    (void)instance;
    jsize n = (*env)->GetArrayLength(env, input);
    jfloat *in = (*env)->GetFloatArrayElements(env, input, NULL);
    if (!in) return JNI_FALSE;
    jdouble *out = (*env)->GetDoubleArrayElements(env, output, NULL);
    if (!out) { (*env)->ReleaseFloatArrayElements(env, input, in, JNI_ABORT); return JNI_FALSE; }
    int ok = SBFFTRealMagnitudes(in, n, out);
    (*env)->ReleaseDoubleArrayElements(env, output, out, ok ? 0 : JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, input, in, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
