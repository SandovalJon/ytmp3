"""YTMusic-MP3 - Descarga playlists de YouTube / YouTube Music como MP3.

Uso personal y local. Escuchas este material bajo tu responsabilidad:
respeta los Términos de Servicio de YouTube y los derechos de autor.

Solo escucha en 127.0.0.1; no expongas esta app a Internet.
"""

import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid
from collections import deque
from pathlib import Path

from flask import Flask, jsonify, request, send_from_directory

try:
    import imageio_ffmpeg
    FFMPEG_PATH = imageio_ffmpeg.get_ffmpeg_exe()
except Exception:  # pragma: no cover
    FFMPEG_PATH = shutil.which("ffmpeg") or "ffmpeg"


def find_deno() -> str | None:
    """yt-dlp necesita un runtime JS para algunos formatos de YouTube."""
    found = shutil.which("deno")
    if found:
        return found
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidate = Path(local) / "Microsoft" / "WinGet" / "Packages" / \
            "DenoLand.Deno_Microsoft.Winget.Source_8wekyb3d8bbwe" / "deno.exe"
        if candidate.is_file():
            return str(candidate)
    return None


DENO_PATH = find_deno()
JS_RUNTIME_ARGS = ["--js-runtimes", f"deno:{DENO_PATH}"] if DENO_PATH else []
BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"
DEFAULT_DOWNLOAD_DIR = Path.home() / "Music" / "YouTube Music MP3"

VALID_URL_RE = re.compile(r"^(https?://)?(www\.|m\.|music\.)?youtube(-nocookie)?\.com/", re.I)
YOUTUBE_HOST_HINTS = ("youtube.com", "youtu.be", "music.youtube.com")

app = Flask(__name__, static_folder=str(STATIC_DIR), static_url_path="")

JOBS = {}
JOBS_LOCK = threading.Lock()


def sanitize_filename(name: str, fallback: str = "Playlist", max_len: int = 80) -> str:
    name = (name or "").strip()
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name)
    name = re.sub(r"\s+", " ", name).strip(" .")
    if not name:
        name = fallback
    return name[:max_len].rstrip(" .") or fallback


def is_youtube_url(url: str) -> bool:
    if not url:
        return False
    u = url.strip()
    return any(host in u.lower() for host in YOUTUBE_HOST_HINTS)


def playlist_info(url: str):
    # --flat-playlist: solo metadata, sin descargar nada.
    # Sirve para playlists, canales, perfiles (@handle) y tabs (/videos, /shorts).
    cmd = [sys.executable, "-m", "yt_dlp", "--flat-playlist", "-J", "--no-warnings", *JS_RUNTIME_ARGS, url]
    proc = subprocess.run(
        cmd, capture_output=True, text=True, encoding="utf-8",
        errors="replace", timeout=240,
    )
    if proc.returncode != 0:
        tail = (proc.stderr or proc.stdout or "").strip().splitlines()[-8:]
        raise RuntimeError("\n".join(tail) or "yt-dlp devolvió un error.")

    data = json.loads(proc.stdout)
    entries = data.get("entries")
    if entries is None:
        # URL de un video suelto: lo envolvemos como lista de 1
        entries = [data] if data.get("id") else []

    items = []

    def collect(node_list, tab_name: str | None = None):
        for e in node_list or []:
            if not e:
                continue
            nested = e.get("entries")
            if isinstance(nested, list):
                # Es una pestaña (Videos / Shorts) o sub-playlist: aplanar
                collect(nested, tab_name=e.get("title") or tab_name)
                continue
            vid = e.get("id") or (e.get("url") or "").split("v=")[-1].split("&")[0]
            if not vid:
                continue
            raw_title = e.get("title") or ""
            unavailable = (not raw_title.strip()) or raw_title.startswith(("[Private", "[Deleted"))
            items.append({
                "id": vid,
                "title": raw_title.strip() or "(sin título)",
                "uploader": e.get("uploader") or e.get("channel") or e.get("channel_id") or "",
                "duration": e.get("duration"),
                "thumbnail": f"https://i.ytimg.com/vi/{vid}/mqdefault.jpg",
                "unavailable": unavailable,
                "tab": tab_name or "",
            })

    collect(entries)

    # Reindexar tras aplanar (canales traen tabs Videos + Shorts)
    for i, it in enumerate(items, start=1):
        it["index"] = i

    lowered = (url or "").lower()
    if "list=" in lowered:
        kind = "playlist"
    elif any(k in lowered for k in ("/@", "/channel/", "/c/", "/user/", "music.youtube.com/@")):
        kind = "canal"
    else:
        kind = "playlist"

    return {
        "title": data.get("title") or data.get("channel") or "Playlist",
        "uploader": data.get("uploader") or data.get("channel") or "",
        "count": len(items),
        "kind": kind,
        "items": items,
    }


def new_job(playlist_title: str, out_dir: str, total: int) -> dict:
    job = {
        "id": uuid.uuid4().hex[:10],
        "title": playlist_title,
        "out_dir": out_dir,
        "status": "descargando",
        "total": total,
        "done": 0,
        "failed": 0,
        "current": "",
        "percent": 0.0,
        "created": time.time(),
        "finished": None,
        "log": deque(maxlen=400),
        "cancel": threading.Event(),
        "proc": None,
        "results": [],
    }
    with JOBS_LOCK:
        JOBS[job["id"]] = job
    return job


def job_public(job: dict) -> dict:
    return {
        "id": job["id"],
        "title": job["title"],
        "out_dir": job["out_dir"],
        "status": job["status"],
        "total": job["total"],
        "done": job["done"],
        "failed": job["failed"],
        "current": job["current"],
        "percent": round(job["percent"], 1),
        "created": job["created"],
        "finished": job["finished"],
        "log": list(job["log"])[-120:],
        "results": job["results"],
    }


def log_job(job: dict, line: str):
    line = (line or "").rstrip()
    if line:
        job["log"].append(line)


def download_job(job: dict, items: list, quality: str, base_dir: str, cookies_file: str | None):
    try:
        base = Path(base_dir).expanduser() if base_dir else DEFAULT_DOWNLOAD_DIR
        final_dir = base / sanitize_filename(job["title"], "Playlist")
        final_dir.mkdir(parents=True, exist_ok=True)
        job["out_dir"] = str(final_dir)
        log_job(job, f"Carpeta de destino: {final_dir}")

        for i, item in enumerate(items, start=1):
            if job["cancel"].is_set():
                job["status"] = "cancelado"
                break

            vid = item["id"]
            title = item.get("title") or vid
            url = f"https://www.youtube.com/watch?v={vid}"
            job["current"] = title
            job["percent"] = 0.0
            log_job(job, f"[{i}/{len(items)}] Descargando: {title}")

            outtmpl = str(final_dir / f"{i:02d} - %(title)s.%(ext)s")
            cookies_args = []
            if cookies_file:
                cpath = Path(cookies_file).expanduser()
                if cpath.is_file():
                    cookies_args = ["--cookies", str(cpath)]
                    log_job(job, f"Usando cookies: {cpath}")
                else:
                    log_job(job, f"Aviso: no existe el archivo de cookies: {cpath}")
            cmd = [
                sys.executable, "-m", "yt_dlp", *JS_RUNTIME_ARGS, *cookies_args,
                "-x", "--audio-format", "mp3", "--audio-quality", quality,
                "--embed-metadata", "--embed-thumbnail",
                "--ffmpeg-location", FFMPEG_PATH,
                "--newline", "--no-colors", "--no-playlist", "--windows-filenames",
                "-o", outtmpl, url,
            ]
            proc = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace",
                stdin=subprocess.DEVNULL,
            )
            job["proc"] = proc
            for raw in proc.stdout:
                line = raw.rstrip()
                if not line:
                    continue
                m = re.search(r"(\d+(?:\.\d+)?)%", line)
                if m:
                    try:
                        job["percent"] = float(m.group(1))
                    except ValueError:
                        pass
                if ("[download]" in line or "[ExtractAudio]" in line or
                        "Destination" in line or "WARNING" in line or "ERROR" in line):
                    log_job(job, line)
            proc.wait()

            if job["cancel"].is_set():
                log_job(job, f"Cancelado por el usuario en: {title}")
                job["results"].append({"id": vid, "title": title, "status": "cancelado"})
                job["status"] = "cancelado"
                break

            if proc.returncode == 0:
                job["done"] += 1
                log_job(job, f"OK: {title}")
                job["results"].append({"id": vid, "title": title, "status": "ok"})
            else:
                job["failed"] += 1
                log_job(job, f"ERROR ({proc.returncode}): {title}")
                job["results"].append({"id": vid, "title": title, "status": "error"})
            job["percent"] = 100.0

        else:
            job["status"] = "completado" if job["failed"] == 0 else "completado con errores"
        job["current"] = ""
    except Exception as exc:  # nunca tumbar el servidor por un job
        job["status"] = "error"
        log_job(job, f"Error inesperado: {exc}")
    finally:
        job["finished"] = time.time()
        job["proc"] = None


@app.get("/")
def index():
    return send_from_directory(STATIC_DIR, "index.html")


@app.get("/api/health")
def health():
    return jsonify({"ok": True, "ffmpeg": FFMPEG_PATH, "deno": DENO_PATH, "default_dir": str(DEFAULT_DOWNLOAD_DIR)})


@app.get("/api/playlist")
def api_playlist():
    url = (request.args.get("url") or "").strip()
    if not is_youtube_url(url):
        return jsonify({"error": "Introduce una URL válida de YouTube o YouTube Music."}), 400
    try:
        info = playlist_info(url)
    except subprocess.TimeoutExpired:
        return jsonify({"error": "Tiempo de espera agotado al leer la playlist."}), 504
    except json.JSONDecodeError:
        return jsonify({"error": "No se pudo interpretar la respuesta de yt-dlp."}), 502
    except RuntimeError as exc:
        msg = str(exc)
        if "Sign in" in msg or "cookies" in msg.lower():
            msg += "\nLa playlist puede requerir inicio de sesión: exporta cookies.txt de tu navegador y usa el campo de cookies."
        return jsonify({"error": msg}), 502
    if not info["items"]:
        return jsonify({"error": "La playlist está vacía o no es accesible (¿privada?)."}), 404
    return jsonify(info)


@app.post("/api/download")
def api_download():
    payload = request.get_json(silent=True) or {}
    items = payload.get("items") or []
    quality = str(payload.get("quality") or "192K")
    if quality not in {"128K", "192K", "256K", "320K"}:
        quality = "192K"
    playlist_title = str(payload.get("playlist_title") or "Playlist")
    base_dir = str(payload.get("out_dir") or "").strip()
    cookies_file = str(payload.get("cookies_file") or "").strip() or None

    clean_items = []
    for it in items:
        vid = str((it or {}).get("id") or "").strip()
        # IDs de video: 11 caracteres, alfabeto seguro
        if re.fullmatch(r"[A-Za-z0-9_-]{6,20}", vid):
            clean_items.append({"id": vid, "title": str(it.get("title") or vid)})
    if not clean_items:
        return jsonify({"error": "No hay canciones seleccionadas."}), 400

    job = new_job(sanitize_filename(playlist_title), base_dir or str(DEFAULT_DOWNLOAD_DIR), len(clean_items))
    threading.Thread(
        target=download_job,
        args=(job, clean_items, quality, base_dir, cookies_file),
        daemon=True,
    ).start()
    return jsonify({"job_id": job["id"]})


@app.get("/api/jobs")
def api_jobs():
    with JOBS_LOCK:
        jobs = sorted(JOBS.values(), key=lambda j: j["created"], reverse=True)
    return jsonify({"jobs": [job_public(j) for j in jobs]})


@app.get("/api/jobs/<jid>")
def api_job(jid):
    with JOBS_LOCK:
        job = JOBS.get(jid)
    if not job:
        return jsonify({"error": "Job no encontrado."}), 404
    return jsonify(job_public(job))


@app.post("/api/jobs/<jid>/cancel")
def api_cancel(jid):
    with JOBS_LOCK:
        job = JOBS.get(jid)
    if not job:
        return jsonify({"error": "Job no encontrado."}), 404
    job["cancel"].set()
    proc = job.get("proc")
    if proc and proc.poll() is None:
        try:
            proc.terminate()
        except Exception:
            pass
    return jsonify({"ok": True})


@app.post("/api/open-folder")
def api_open_folder():
    data = request.get_json(silent=True) or {}
    path = str(data.get("path") or "").strip()
    p = Path(path).expanduser() if path else DEFAULT_DOWNLOAD_DIR
    if p.is_dir():
        if sys.platform.startswith("win"):
            os.startfile(str(p))  # noqa: S606 - app personal local
            return jsonify({"ok": True})
        return jsonify({"ok": True, "note": "Abre la carpeta manualmente: %s" % p})
    return jsonify({"error": "La carpeta no existe todavía."}), 404


if __name__ == "__main__":
    # Solo local. No cambiar a 0.0.0.0.
    app.run(host="127.0.0.1", port=int(os.environ.get("PORT", "5088")), debug=False, threaded=True)
