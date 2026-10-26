# SoinuBider XL para Android

Paquete integrado de código para el proyecto Android Studio `SoinuBiderXL` existente de Unai. Incluye motor C, conexión Oboe, seis bancos de loops, TAP, AUTO, controles de desfase, gestos y ajustes.

## Apariencia

Pantalla principal adaptada a la referencia de iPhone: fondo oscuro, título centrado y engranaje, medidor de puntos, marco de BPM, AUTO/TAP, acciones, seis bancos grandes y flechas. La distribución se ajusta a la altura disponible.

El audio intenta iniciarse al abrir la aplicación. Todos los textos son blancos. **Ajustes** contiene el desfase actual, el guardado, sus botones de guardar/resetear y **Rango BPM Auto**. Se han retirado los controles de inicio/parada y las explicaciones. La barra de estado y la navegación son las propias de Android.

Los dos desplegables muestran solo números, en pasos de 10: mínimo 85–155 y máximo 105–175. Están ligados con una diferencia **exacta de 20 BPM**: cambiar uno ajusta el otro. El rango se guarda y se aplica al detector AUTO; el valor inicial sigue siendo 115–135.

## Descargar e instalar en Windows

1. En esta página pulsa **Code → Download ZIP** y extrae todo el archivo.
2. Cierra Android Studio.
3. Dentro de la carpeta extraída `hnn-main`, ejecuta **APLICAR_EN_WINDOWS.cmd**.
4. Cuando lo pida, introduce la carpeta del proyecto que ya tienes, por ejemplo:
   `C:\Users\unai_\AndroidStudioProjects\SoinuBiderXL`.
5. El instalador guarda y comprueba una copia de los archivos anteriores antes de sustituirlos.
6. Abre tu proyecto habitual en Android Studio, sincroniza Gradle y ejecuta **Build** y **Run**.

También puedes descargar el [paquete ZIP original](https://github.com/hunnai/hnn/raw/refs/heads/main/SoinuBiderXL-archivos-completos.zip).

**Este repositorio contiene archivos para aplicar al proyecto existente. No se abre como un proyecto Android independiente y no contiene un APK.** Conserva el Gradle Wrapper, el catálogo de versiones, los iconos y las herramientas de tu proyecto actual.

Consulta [LEEME.txt](LEEME.txt) para la instalación manual, los gestos, las pruebas y cómo restaurar la copia de seguridad.

## Estado de las comprobaciones

- Compilación de los archivos Kotlin con el compilador de Compose: correcta.
- Motor C: grabación y mezcla de seis loops, pausa, reanudación, borrado, cambio de duración, desfase y conservación al cambiar frecuencia comprobados con audio sintético.
- Puente C++: sintaxis y tipos comprobados con cabeceras oficiales de Oboe 1.9.0 y JNI.
- TAP y AUTO: pruebas sintéticas correctas, incluidos 118, 120 y 132 BPM.
- **Pendiente:** compilación completa de Gradle y enlace NDK en el proyecto Windows; instalador CMD; pruebas de interfaz, audio real, USB, latencia e interrupciones en Android. AUTO con el micrófono del usuario sigue pendiente de validar.
- Firma del APK y distribución por enlace: pendientes.

Detalle: [validacion/RESULTADOS.txt](validacion/RESULTADOS.txt).
