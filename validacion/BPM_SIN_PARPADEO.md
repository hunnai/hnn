Corrección de la publicación de BPM automático

La recogida JNI y el análisis compartían una corrutina: mientras la FFT/ajuste trabajaba, no se vaciaba el anillo de audio. BeatTracker borra evidencia y estimate ante una discontinuidad; publicar ese null borraba el valor de pantalla.

Ahora la recogida usa el hilo principal y el análisis una corrutina Default. Canal acotado de 16 lotes con DROP_OLDEST y marcas de generación para rechazar resultados de otro modo, rango o sesión. El detector conserva su confinamiento a un consumidor. Las llamadas JNI de sesión siguen en el hilo principal; la FFT continúa fuera del callback de audio.

AutomaticTempoHold conserva una estimación válida como máximo 5 segundos según el reloj de muestras. No modifica su timestamp ni su anchor. Valores inválidos, caducados o de una generación anterior no se aceptan. Reset y cambios de modo/rango siguen borrando el tempo. El límite evita grabar loops con una detección mantenida indefinidamente.

Validación ejecutada:
- Motor C + adaptador FFT JNI compilados con GCC C11, -Wall -Wextra -Werror.
- Núcleo Kotlin real compilado con Kotlin 2.2.20; configuración de colores y enum de apariencia sustituidos por tipos mínimos en el arnés JVM, sin dispositivo Android.
- TestAutoTempoHold: 500 resultados nulos sin parpadeo; expiración exacta tras 5 segundos; no resucita con resultado viejo; actualización inmediata y rechazo de NaN/fechas futuras.
- TestTempo: 576 transiciones del rango, TAP, ritmos sintéticos 85/90/118/120/132/170/175 BPM y silencio.
- Pérdida de 4096 muestras después de detectar 120 BPM: BeatTracker reinicia evidencia; la publicación mantiene temporalmente el valor y el detector vuelve a adquirir 120 BPM.
- Función poll de producción compilada sin modificar su cuerpo con kotlinx-coroutines real y un arnés de contexto, sin dependencias Android. No equivale a compilar el controlador Android completo.

Pendiente: Gradle/APK Android y comprobación con micrófono/tarjeta USB en el Honor. Estas pruebas no demuestran la precisión de detección con toda música real.
