# Guía rápida de SoinuBider XL

`SoinuBiderXL-guia-rapida.pdf`: dos páginas A4 para imprimir a doble cara o compartir con la aplicación. Instrucciones en español de entrada/micrófono/tarjeta, AUTO/TAP, grabación/pausa/borrado, desfase, Ajustes, división de loops y SYNC.

Las pantallas son esquemas vectoriales basados en las capturas facilitadas por el usuario, no capturas originales incrustadas. Los gestos se cotejaron con el código de MainActivity.kt y SoinuController.kt del commit 63b92ba, que incluye conservación de SYNC al ajustar un banco o dividirlo.

Generación: `python3 docs/generar_guia.py`, con ReportLab y fuentes DejaVu Sans. El pequeño ejemplo de las texturas usa el recurso ya existente en drawable-nodpi. El PDF incorpora las fuentes y tiene texto seleccionable. Se abrió y renderizó con PyMuPDF para inspección visual de ambas páginas y comprobación de límites del texto.
