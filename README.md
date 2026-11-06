# YTMusic → MP3

Descarga tus playlists de YouTube / YouTube Music como archivos MP3, en tu propia computadora, a través de una página web local.

> **Uso personal y local.** No expongas esta app a Internet. Respeta los derechos de autor y los Términos de Servicio de YouTube. No está afiliada a YouTube ni a Google.

## Requisitos

- Windows con Python 3.11+ (ya verificado en este equipo).
- Conexión a Internet.
- Todo lo demás (yt-dlp, Flask, ffmpeg vía `imageio-ffmpeg`) ya está instalado en el entorno virtual `.venv/`.

## Cómo usar

1. Haz doble clic en **`Iniciar.cmd`** (o ejecuta `.venv\Scripts\python.exe app.py`).
2. Se abre el navegador en `http://127.0.0.1:5088`.
3. Pega la URL de tu playlist, elige calidad y carpeta de destino.
4. Pulsa **Cargar playlist** → marca las canciones → **Descargar seleccionadas**.
5. Las canciones se guardan en una subcarpeta con el nombre de la playlist, numeradas: `01 - Título.mp3`.

## Playlists privadas o aviso de "inicia sesión para confirmar que no eres un bot"

Algunas listas requieren sesión. Exporta las cookies de tu navegador con una extensión tipo **“Get cookies.txt LOCALLY”**, guarda el archivo, e indica su ruta en el campo **Archivo cookies.txt** de la página. Se usa solo en tu equipo.

## Notas

- Solo escucha en `127.0.0.1`; deliberadamente no está expuesta a la red.
- Deno está instalado (runtime de JS que yt-dlp necesita para algunos formatos de YouTube). Si cambias de equipo, instálalo aparte: `winget install --id DenoLand.Deno -e --scope user`.
- Los videos eliminados o privados dentro de la playlist aparecen atenuados y con su casilla deshabilitada.
- El progreso se actualiza solo. Puedes cancelar una descarga en curso.
- Si una canción falla, el resto continúa y el error queda anotado en el registro del trabajo.
- Las descargas grandes pueden tardar; no cierres la ventana negra (la consola) mientras descarga.
