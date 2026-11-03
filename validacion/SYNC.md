SYNC de fase por banco

Uso: con un loop ya grabado, reproduciéndose y cuadrado manualmente, mantener pulsado su banco y pulsar AUTO. El gesto alterna el modo de ese banco; soltar no pausa el loop. Se pueden marcar varios bancos por separado. AUTO sin banco mantenido conserva su función anterior. SYNC… indica adquisición/espera y SYNC indica referencia suficientemente coherente, incluso cuando está dentro de la banda muerta y no necesita desplazarse.

Modelo: referencia relativa al instante de activación y al avance manual del banco. La fase de los pulsos se obtiene de TempoEstimate de la entrada, se proyecta al reloj actual y se compara con la fase de reproducción que corresponde al BPM fijo del loop. No se extraen ni correlacionan transitorios del PCM grabado. Se conserva la alineación manual como objetivo; es seguimiento de deriva de la rejilla rítmica y no reconocimiento de patrones ni de compás. No modifica el BPM, longitud, divisiones ni velocidad de lectura del banco.

Correcciones: exclusivamente NUDGE_BANK ya disponible en el motor. Máximo ±3 ms por corrección, no más de una cada 0,5 s de nuevas estimaciones; fundido existente de 25 ms. Banda muerta ±2 ms. Necesita al menos cuatro estimaciones y 1,5 segundos o tres pulsos coherentes. Ventana de hasta ocho observaciones, pendiente robusta por mediana de pendientes y residuos para rechazar picos aislados. Reexpresa las observaciones según el avance realmente confirmado por el snapshot; no asume que una orden rechazada se haya aplicado.

Límites conservadores: confianza >=0,75, estimación con antigüedad <=2 s, diferencia de tempo <=0,5 % respecto al BPM del banco (aprox. ±0,6 BPM a 120 BPM), error de fase <=min(50 ms, 10 % del período de un pulso), deriva estimada <=6 ms/s. Fuera de esos límites espera y no desplaza. No pretende seguir cambios grandes de tempo mediante saltos de fase.

Desactivación: mismo gesto; pausa/borrado/resize/ajuste manual de ese banco; ajuste manual global, RESET BPM, TAP, cambio de rango AUTO o interrupción/ruta de audio. El avance ya aplicado se conserva al desactivar para evitar un salto de vuelta. No se guarda el modo SYNC al cerrar la aplicación. Correcciones automáticas no desactivan su propio seguidor ni afectan a otros bancos. SoinuSYNC registra banco/corrección/BPM sin grabar audio.

Validación ejecutada:
- TestPhaseSync.kt: dos minutos simulados por caso, loops 119,8/120,2/119,5/120,5 BPM frente a entrada 120. Corrección en signo correcto y dentro de límites, sin reiniciar BPM. Error de fase máximo tras adquisición <=1,68 ms en esos casos sintéticos ideales.
- Tempo variable 120±0,3 BPM, 44,1 kHz y estimaciones con 200 ms de retardo: error máximo tras adquisición 2,34 ms.
- Conserva avance manual inicial, rechaza golpes de fase aislados, baja confianza, estimaciones caducadas, diferencias grandes y valores no finitos.
- TestSyncControls.kt + generate_sync_harness.py: métodos de producción extraídos sin reescribir a un arnés con transporte JNI simulado. Gesto on/off, supresión de click al soltar, dos bancos independientes, signo/límites, borrado de referencias por ajuste manual/RESET/TAP/pausa/fin de audio.
- test_sync_nudge.py: motor C real compilado con GCC C11 y -Wall -Wextra -Werror. Seno de 440 Hz grabado a 120 BPM; desplazamientos +3/-2/+1,25/-3 ms producen la fase esperada después del fundido; BPM queda en 120 y no cambia ningún otro banco.
- Todas las fuentes Kotlin, controlador y Compose compiladas con Kotlin 2.2.20, plugin Compose real, bibliotecas AndroidX y android-all API35; R de recursos sustituido por constante de prueba. No equivale a Gradle/NDK/instalación APK.

Fuentes de aplicación que cambian: SoinuState.kt, SoinuController.kt y MainActivity.kt. Ningún archivo nuevo de aplicación, dependencia nueva, recurso de icono, manifiesto ni motor C modificado.

Pendiente: prueba con música, tarjeta USB y Honor reales, escucha de los pequeños fundidos, estabilidad frente a patrones musicales ambiguos. Las métricas numéricas anteriores son pruebas sintéticas y no una garantía de percepción o precisión con música real.

Para repetir núcleo: compilar SoinuState.kt, AutoBpmRange.kt, validacion/bpm-iphone/TestConfiguration.kt y validacion/TestPhaseSync.kt con kotlin-stdlib; ejecutar TestPhaseSyncKt. Para gestos, generar arnés con `python3 validacion/generate_sync_harness.py /tmp/soinu-sync`, compilar además TapTempoEstimator.kt, TestSyncControls.kt, bpm-iphone/LogStub.kt y los dos archivos generados con coroutines-core. Ejecutar TestSyncControlsKt. Para motor: `gcc -shared -fPIC -O2 -std=c11 -Wall -Wextra -Werror app/src/main/cpp/SBRealtime.c -lm -o /tmp/libsoinu-core.so` y `python3 validacion/test_sync_nudge.py /tmp/libsoinu-core.so`.
