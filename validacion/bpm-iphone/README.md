Pruebas de la adaptación al flujo de iPhone

Los archivos de este directorio son arneses JVM; no se copian al proyecto Android ni se mezclan con NativeAudio.kt o Appearance.kt reales.

Dependencias usadas: Kotlin compiler/stdlib 2.2.20, script-runtime 2.2.20, reflect 1.6.10, daemon 2.2.20, annotations 13.0, kotlinx-coroutines-core-jvm y kotlinx-coroutines-test-jvm 1.8.0. Se descargaron de Maven Central y se comprobaron sus SHA-1 publicados. FFT real compilada con validacion/JvmFFT.c, SBRealtime.c y cabeceras JNI de OpenJDK 21.

Preparación: ejecutar `python3 validacion/bpm-iphone/generate_fixtures.py /tmp/soinu-bpm-fixtures`. Esto extrae el cuerpo actual de poll(), sin reescribirlo, a un arnés que sustituye solo su contexto Android y extrae del historial el detector anterior como referencia.

Prueba de equivalencia: compilar AutoBpmRange.kt, SoinuState.kt, NativeAudio.kt, FFTAnalyzer.kt, BeatTracker.kt, TestConfiguration.kt, ReferenceBeatTracker.kt generado y TestDetectorEquivalence.kt. Ejecutar TestDetectorEquivalenceKt con la biblioteca soinubider JNI en java.library.path. Se comparan resultados completos (BPM, fase, confianza y timestamp) en todos los bloques de 256 muestras de dos señales de 35 segundos: rangos 115–135 y 85–175. La referencia usa la cadencia original de 0,5 s.

Prueba de integración: en una salida de clases SEPARADA compilar AutoBpmRange.kt, SoinuState.kt, FFTAnalyzer.kt, BeatTracker.kt, TestConfiguration.kt, FakeNativeAudio.kt, LogStub.kt, PollHarness.kt generado y TestPollIntegration.kt, con coroutines-test en el classpath. Ejecutar TestPollIntegrationKt. FakeNativeAudio simula FIFO de 63 paquetes, entrada a tiempo real 48 kHz/256 frames y mantiene la FFT C real por JNI. Dispatchers.Main usa un único hilo test-ui y se comprueba que solo ese hilo llama a JNI de sesión. Se añaden pausas UI de 60 ms. Comprueba detección de 120 BPM, ninguna desaparición después de detectarlo y cero pérdidas en FIFO simulada; imprime diagnóstico de la función de producción.

Resultados ejecutados en JVM: ambas pruebas pasan. Equivalencia exacta en 4965 publicaciones por rango. Tiempo del núcleo en esta ejecución: referencia 855/1301 ms frente a optimizado 454/729 ms para 35 s de audio (comparación orientativa en esta máquina, no un benchmark del Honor). Integración: retraso final ~11–21 ms, cero pérdidas de motor/cola y cero saltos; edad de detección ~0,02–0,53 s.

También pasa validacion/TestTempo.kt: TAP, 576 transiciones de rango, 11 ritmos/rangos sintéticos, pérdida de paquetes con conservación del tempo al estilo iPhone, recuperación y silencio.

No se ha construido el APK ni ejecutado el controlador Android completo. Falta comprobar rendimiento, entrada USB y pantalla en el Honor.
