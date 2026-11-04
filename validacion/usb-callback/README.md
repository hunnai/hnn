# Captura por callback: comprobación nativa aislada

El registro del Redmi / Audio 10 muestra AAudio en ambas direcciones, IDs coincidentes con los solicitados y `recibidas=0` durante más de treinta segundos. No son muestras de audio silenciosas: ninguna lectura no bloqueante de la app devuelve frames. Esto no identifica por sí solo un bug concreto de Oboe o del controlador de Android.

Cambio de prueba: instalar también un callback de datos en la entrada. Ese callback copia PCM float a `CaptureRing`; el callback de salida consume el anillo y sigue siendo el único que llama al motor C. No se usa `input->read()` ni se hacen esperas, logs o reservas en ninguno de los callbacks. Se conserva AAudio/Oboe con los IDs solicitados; no se recurre silenciosamente a un backend que ignore el dispositivo USB.

El anillo SPSC de 32768 frames estéreo usa índices uint32 con release/acquire. Duplica una entrada mono, acepta tamaños distintos de bloque, rellena con cero una falta de datos y descarta la parte nueva que no quepa cuando está lleno. Solo se resetea después de cerrar ambos streams en el arranque siguiente. Los contadores son de diagnóstico y no gobiernan la reproducción. Un desajuste sostenido entre relojes de entrada/salida podría acabar llenándolo o vaciándolo: no hay ASRC adaptativo nuevo; hay que comprobarlo en hardware.

Snapshot JNI: se conservan los campos 0–49. Ahora `recibidas` cuenta frames entregados al callback de entrada, `lecturasVacias` cuenta callbacks de salida sin datos de captura disponibles y `bloquesConSenal` cuenta bloques de entrada con amplitud detectada. Se añaden 50=frames descartados de captura, 51=frames en cola, 52=número de callbacks de entrada, 53/54=estados de entrada/salida reportados por Oboe. El controlador imprime esos cinco datos solo si hay 55 campos, manteniendo compatibilidad con versiones de diagnóstico anteriores.

`TestCapture.cpp` incluye sin reescribir el Session y CaptureRing de producción y usa un doble únicamente de la API de transporte Oboe. Su `read()` devuelve siempre cero, como en el fallo observado; se comprueba que la app no lo llama y consume los datos recibidos mediante callback. El doble NO representa un driver real ni demuestra que Android vaya a entregar callbacks en la Audio 10.

Pruebas ejecutadas:
- Mono/estéreo, relleno de falta de datos, exceso de capacidad, vuelta del índice del anillo y orden entre dos hilos con 200000 frames.
- Session USB estéreo: fragmentos de entrada de hasta 256 frames frente a salida de 1538, como la ráfaga observada; motor C real graba y reproduce, señal finita/acotada y sin pérdidas de captura ni de análisis en la simulación.
- Session de micrófono mono; selección de Generic solo en USB y Unprocessed en el teléfono; IDs conservados y captura limpia al reiniciar, manteniendo loops completados.
- Las mismas pruebas con AddressSanitizer y UndefinedBehaviorSanitizer sin errores.
- C++ comprobado contra cabeceras oficiales Oboe 1.9.0 y JNI; controlador Kotlin compilado contra bibliotecas Android/AndroidX y stub Android API35.

Repetición (JNI_INCLUDE apunta a la carpeta de jni.h de un JDK):

```sh
gcc -std=c11 -O2 -Wall -Wextra -Werror -c app/src/main/cpp/SBRealtime.c -o /tmp/SBRealtime.o
g++ -std=c++17 -O2 -Wall -Wextra -Werror -pthread -Ivalidacion/usb-callback/mock -I"$JNI_INCLUDE" -I"$JNI_INCLUDE/linux" validacion/usb-callback/TestCapture.cpp /tmp/SBRealtime.o -lm -latomic -o /tmp/test-capture
/tmp/test-capture
```

Para sanitizers, compilar ambos pasos también con `-fsanitize=address,undefined -fno-omit-frame-pointer`.

Pendiente: enlace NDK/APK y pruebas Audio 10, Behringer y micrófono reales. Los datos simulados y la compilación no confirman la compatibilidad de ninguna tarjeta concreta.

## Prueba posterior: captura USB en modo normal

El siguiente registro real del Redmi / Audio 10 conserva `callbacksEntrada=0` y `recibidas=0` durante unos 82 segundos. No demuestra un fallo del anillo, ya que nunca entra un bloque de captura. `estadoEntrada=4` es el estado reportado por Oboe: en 1.9.0 AudioStreamAAudio::getState puede convertir STARTING a STARTED como workaround; no confirma por sí solo que el hardware haya comenzado a producir datos.

Se cambia únicamente la solicitud de rendimiento de entrada USB a PerformanceMode::None; micrófono y salida conservan LowLatency. Continúan el mismo perfil Generic, formato float, sample rate, selección por ID y callback. Es una prueba de la ruta normal de captura, no una causa confirmada ni una garantía de corrección. La prueba Session con transporte simulado verifica estas tres selecciones de modo, además de los casos previos. Sintaxis verificada de nuevo con Oboe 1.9.0 real; pendiente hardware. Si sigue sin datos, hacen falta estados/configuración o registros del sistema de audio antes de atribuir el fallo al DSP o a los canales físicos.
