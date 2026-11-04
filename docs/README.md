# Guía rápida de SoinuBider XL

`SoinuBiderXL-guia-rapida.pdf`: dos páginas A4 para imprimir a doble cara o compartir con la aplicación. Instrucciones en español de entrada/micrófono/tarjeta, AUTO/TAP, grabación/pausa/borrado, desfase, Ajustes, división de loops y SYNC.

Las pantallas son esquemas vectoriales basados en las capturas facilitadas por el usuario, no capturas originales incrustadas. Los gestos se cotejaron con el código de MainActivity.kt y SoinuController.kt del commit 63b92ba, que incluye conservación de SYNC al ajustar un banco o dividirlo.

Generación: `python3 docs/generar_guia.py`, con ReportLab y fuentes DejaVu Sans. El pequeño ejemplo de las texturas usa el recurso ya existente en drawable-nodpi. El PDF incorpora las fuentes y tiene texto seleccionable. Se abrió y renderizó con PyMuPDF para inspección visual de ambas páginas y comprobación de límites del texto.

Revisión de la guía: anotaciones y acentos del documento en naranja con números blancos y borde blanco sobre las pantallas; la apariencia de la app permanece fiel a las capturas. Los tres botones de ejemplo de los gestos dicen «4 beats». Nota final: usar SYNC preferiblemente con auriculares y una entrada directa de la música original, evitando el retorno del loop reproducido a la señal analizada.

## Manual dentro de la app

El mismo PDF actualizado está en `app/src/main/assets/soinu_manual.pdf`. Se abre mediante «Manual de instrucciones» al final de Ajustes. La pantalla usa PdfRenderer (incluido en Android, sin dependencias ni conexión) y permite desplazamiento, pellizco, doble toque y botones −/+. Volver y Atrás devuelven a Ajustes, dentro de la misma actividad.

Las páginas se renderizan en IO con resolución acotada y se cierran PdfRenderer, sus páginas y el descriptor al terminar. Los bitmaps se liberan al cerrar la pantalla; si se cierra durante la carga, se cancela entre páginas y se limpian los recursos. No se abre otra aplicación ni se usa almacenamiento externo.

La guía mantiene las anotaciones naranjas, los botones de ejemplo «4 beats» y el consejo de SYNC con auriculares/entrada directa. Ahora menciona la entrada 1–2 y representa el botón del manual. Se cotejaron los gestos con la versión de audio que el usuario confirmó funcionando (5192680).

Actualización local: sustituir solo MainActivity.kt y copiar soinu_manual.pdf a app/src/main/assets (crear esa carpeta si no existe). No cambiar Gradle, manifiesto ni archivos de audio. Generar APK firmado e instalar sobre el existente.

Validación: PDF de dos páginas abierto y renderizado con PyMuPDF; inspección visual de ambas páginas y comprobación de texto seleccionable, primera entrada, gestos, SYNC y botón de manual. Copia de assets idéntica al PDF de docs. Kotlin/Compose completo compila con Android/AndroidX y stub API35. TestManualViewport comprueba anclaje del pellizco, límites de zoom/arrastre, documentos cortos y cambios de tamaño. Pendiente build APK y navegación/gestos/renderizado PdfRenderer reales en Redmi; no se modificó el motor de audio.

Revisión resumida: página 1 dedicada a controles y los tres gestos; página 2 solo Ajustes. LED reducido a una nota. Texto de SYNC sin recomendación de auriculares: entrada directa de música original para evitar el retorno del loop reproducido por la app. Se mantienen las combinaciones de dos dedos, tiempos de 2 s, mínimo de ocho TAP y corrección de 1 ms. La copia de assets se actualiza junto a la de docs; no requiere cambios de Kotlin.
