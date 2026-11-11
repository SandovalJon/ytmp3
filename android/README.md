# YTMusic → MP3 (Android)

App Android nativa en Kotlin que replica la app web original
[`SandovalJon/ytmp3`](https://github.com/SandovalJon/ytmp3)
(Flask + yt-dlp en el equipo): pega la URL de una playlist de YouTube /
YouTube Music, elige la calidad MP3 y descarga las canciones
seleccionadas como `NN - Título.mp3` en
`Music/YouTube Music MP3/<nombre de la playlist>/`.

> **Uso personal.** Respeta los derechos de autor y los Términos de
> Servicio de YouTube. No está afiliada a YouTube ni a Google.

## Funciones

- Cargar playlist con `yt-dlp --flat-playlist -J` (solo metadata):
  título, autor, duración y miniatura por canción. Las privadas o
  eliminadas aparecen atenuadas con la casilla deshabilitada.
- Seleccionar todo o canciones individuales.
- Calidad MP3: 128K, 192K, 256K, 320K (por defecto 320K).
- Descarga con `--extract-audio --audio-format mp3 --embed-metadata
  --embed-thumbnail`, con progreso por canción y general, registro
  (log), cancelación y servicio en primer plano con notificación.
- Archivo `cookies.txt` opcional (selector de archivos del sistema)
  para playlists privadas o el aviso de "inicia sesión para confirmar
  que no eres un bot".
- Botón "Actualizar motor yt-dlp" (canal estable de GitHub).
- UI oscura igual que la web original: fondo `#0f1117`, tarjetas
  `#1a1d27`, degradado `#6c8cff → #b06cff`, textos en español.

## Motor de descarga

`io.github.junkfood02.youtubedl-android:library:ffmpeg:0.18.1`
(Maven Central): fork mantenido de `yausername/youtubedl-android`
(abandonado). Incluye yt-dlp **2025.11.12**, Python 3.8, ffmpeg y un
runtime **QuickJS**, necesario porque yt-dlp dejó de usar su
intérprete JS interno para YouTube. Es la librería que usan en
producción Seal y YTDLnis, y permite actualizar yt-dlp en tiempo de
ejecución.

Se descartó `ffmpegkit-maintained/yt-dlp-android` 2.x: está basado en
Chaquopy (Python 3.13 embebido), exige un plugin Gradle propio, es
más pesado de compilar y no permite actualizar yt-dlp dentro de la app.

## Almacenamiento (Android 10+)

yt-dlp no puede escribir directamente en MediaStore, así que cada
canción se descarga primero a la caché privada de la app y al
terminar se publica en la biblioteca de música con
`MediaStore.Audio` (`RELATIVE_PATH =
Music/YouTube Music MP3/<playlist>/`). Los archivos quedan visibles
para cualquier reproductor de música. En Android 9 o anterior se
escriben directamente en esa ruta pública.

## Versión

**1.0.8** (versionCode 9, `com.sandovaljon.ytmp3`).

- 1.0.8: botón “Reintentar N errores” (paridad con la web) y numeración estable `NN` de playlist en reintentos.

- 1.0.7: buscador por título/artista, duración total de la selección y hasta 3 descargas en paralelo con progreso agregado; paridad con la web.

- 1.0.0: primera versión.
- 1.0.1: corrige el cierre al abrir (se llamaba a `getString()` antes de `onCreate()`).
- 1.0.2: si YouTube bloquea la descarga, la app actualiza yt-dlp sola y reintenta una vez; si falla al incrustar la portada, reintenta sin incrustarla; usa `-f bestaudio/best` y muestra el error completo de yt-dlp en el registro.
- 1.0.3: acepta canales y perfiles (`@handle`, `/channel/`, YouTube Music): aplana las pestañas Videos/Shorts; filtro "Solo canciones (sin Shorts)" por defecto, con opciones Todo / Solo Shorts; la pestaña de origen se muestra en cada fila.
- 1.0.5: calidad por defecto 320 kbps.
- 1.0.4: la tarjeta de descarga lista las canciones con error al terminar (antes solo se marcaban en la lista).

El APK no se guarda en el repo (pesa unos 99 MB). Publícalo como asset de una Release de GitHub.

## Actualización remota (sin descargar APKs a mano)

La app se auto-actualiza sola desde GitHub Releases:

- Al abrir, comprueba `https://api.github.com/repos/SandovalJon/ytmp3/releases/latest`
  y compara el `tag_name` (p. ej. `v1.0.6`) con la versión instalada.
- Si hay versión nueva, el botón **Buscar actualización de la app** cambia a
  **Descargar e instalar 1.0.6**: descarga el primer asset `.apk` y lo instala
  con `FileProvider` (pide permiso de “orígenes desconocidos” la primera vez).
- El motor yt-dlp se sigue actualizando aparte sin APK nuevo
  (botón **Actualizar motor yt-dlp**).

Para publicar una actualización:

1. Sube `versionCode` y `versionName` en `app/build.gradle.kts`
   (p. ej. `versionCode = 9`, `versionName = "1.0.8"`).
2. Compila: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
3. En GitHub → Releases → **New release** → tag `v1.0.8`, título `1.0.8`,
   adjunta el APK renombrado (p. ej. `ytmp3-1.0.8.apk`) y publica.
4. Los teléfonos con la 1.0.7 verán el aviso y se actualizarán solos.

## Compilar

Requisitos: JDK 17 y Android SDK (platform 34, build-tools 34.0.0).

```bash
./gradlew assembleDebug --max-workers=2
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

El proyecto usa el Gradle Wrapper (Gradle 8.7, AGP 8.5.2,
Kotlin 1.9.24) y `abiFilters arm64-v8a, armeabi-v7a`.

Nota: `gradle/wrapper/gradle-wrapper.jar` no se incluye en el repo.
Si `./gradlew` te pide ese archivo, genera el wrapper con
`gradle wrapper --gradle-version 8.7` o abre el proyecto en
Android Studio, que lo genera solo.

## Instalar en el teléfono

1. Copia `app-debug.apk` al teléfono (USB, Drive, etc.).
2. En el teléfono, toca el archivo para instalarlo. Si es la primera
   vez, Android pedirá permitir "Instalar apps desconocidas" para la
   app desde la que lo abras (Archivos, Chrome, etc.).
3. Abre **YTMusic → MP3**, pega la URL de la playlist y pulsa
   **Cargar playlist**. La primera vez el motor yt-dlp tarda unos
   segundos en prepararse.
4. Marca las canciones y pulsa **Descargar seleccionadas**. Los MP3
   aparecen en `Música/YouTube Music MP3/<playlist>/`.

## Limitaciones conocidas

- YouTube cambia sus protecciones con frecuencia. Si una descarga
  falla con errores de "Sign in to confirm you're not a bot" o
  HTTP 403, usa el botón **Actualizar motor yt-dlp** y/o carga un
  `cookies.txt` exportado de tu navegador (extensión
  "Get cookies.txt LOCALLY"). Las playlists privadas siempre
  necesitan cookies.
- La app no usa cuentas de Google ni servidores externos: todo el
  trabajo ocurre en el teléfono.
- El APK incluye el motor yt-dlp + Python + ffmpeg, por lo que pesa
  decenas de MB (un APK por ABI; este build incluye arm64-v8a y
  armeabi-v7a).
- Si cierras la app a la fuerza durante una descarga, el servicio en
  primer plano se detiene; vuelve a abrirla y reintenta las canciones
  que falten.
