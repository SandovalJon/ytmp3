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
import tempfile
import threading
import time
import uuid
from collections import deque
from concurrent.futures import ThreadPoolExecutor
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

# Descargas simultáneas por trabajo (1-4). Ajustable con YTMP3_PARALLEL=2.
try:
    MAX_PARALLEL = max(1, min(4, int(os.environ.get("YTMP3_PARALLEL", "3"))))
except (TypeError, ValueError):
    MAX_PARALLEL = 3
# Historial: se purgan trabajos terminados de más de 1 día y se topa en 30.
JOB_TTL_SECS = 24 * 3600
MAX_JOBS = 30


def kill_proc_tree(proc) -> None:
    """Termina un yt-dlp y su ffmpeg hijo (evita huérfanos al cancelar)."""
    try:
        if proc is None or proc.poll() is not None:
            return
        if sys.platform.startswith("win"):
            try:
                subprocess.run(
                    ["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10,
                )
                return
            except Exception:
                pass
        try:
            proc.terminate()
        except Exception:
            pass
        try:
            proc.wait(timeout=5)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass
    except Exception:
        pass


def purge_jobs() -> None:
    now = time.time()
    with JOBS_LOCK:
        for jid in [k for k, j in JOBS.items()
                    if j.get("status") != "descargando"
                    and j.get("finished") and now - j["finished"] > JOB_TTL_SECS]:
            JOBS.pop(jid, None)
        if len(JOBS) > MAX_JOBS:
            ordered = sorted(JOBS.values(), key=lambda j: j.get("created", 0))
            excess = len(JOBS) - MAX_JOBS
            dropped = 0
            for j in ordered:
                if dropped >= excess:
                    break
                if j.get("status") != "descargando" and JOBS.pop(j["id"], None) is not None:
                    dropped += 1


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
        "procs": [],
        "active": {},
        "lock": threading.Lock(),
        "results": [],
        "items": [],
        "quality": "320K",
        "base_dir": out_dir,
        "cookies_file": None,
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


MAX_ATTEMPTS = 3
RETRY_DELAY_SECS = 3


def download_job(job: dict, items: list, quality: str, base_dir: str, cookies_file: str | None):
    lock = job["lock"]
    total = len(items)
    titles = {it["id"]: (it.get("title") or it["id"]) for it in items}
    try:
        base = Path(base_dir).expanduser() if base_dir else DEFAULT_DOWNLOAD_DIR
        final_dir = base / sanitize_filename(job["title"], "Playlist")
        final_dir.mkdir(parents=True, exist_ok=True)
        job["out_dir"] = str(final_dir)
        log_job(job, f"Carpeta de destino: {final_dir} ({MAX_PARALLEL} en paralelo)")

        cookies_args = []
        if cookies_file:
            cpath = Path(cookies_file).expanduser()
            if cpath.is_file():
                cookies_args = ["--cookies", str(cpath)]
                log_job(job, f"Usando cookies: {cpath}")
            else:
                log_job(job, f"Aviso: no existe el archivo de cookies: {cpath}")

        def refresh_live_locked():
            act = job["active"]
            job["percent"] = round(sum(act.values()), 1)
            names = [titles.get(v, v) for v in act]
            txt = " · ".join(names)
            job["current"] = txt[:140] + ("…" if len(txt) > 140 else "")

        def one(item, pos):
            vid = item["id"]
            title = item.get("title") or vid
            if job["cancel"].is_set():
                return
            try:
                track_no = int(item.get("num") or pos)
            except (TypeError, ValueError):
                track_no = pos
            outtmpl = str(final_dir / f"{track_no:02d} - %(title)s.%(ext)s")
            cmd = [
                sys.executable, "-m", "yt_dlp", *JS_RUNTIME_ARGS, *cookies_args,
                "-x", "--audio-format", "mp3", "--audio-quality", quality,
                "--embed-metadata", "--embed-thumbnail",
                "--ffmpeg-location", FFMPEG_PATH,
                "--newline", "--no-colors", "--no-playlist", "--windows-filenames",
                "-o", outtmpl, f"https://www.youtube.com/watch?v={vid}",
            ]
            try:
                with lock:
                    job["active"][vid] = 0.0
                    refresh_live_locked()
                log_job(job, f"[{pos}/{total}] Descargando: {title}")
                success = False
                last_code = None
                for attempt in range(1, MAX_ATTEMPTS + 1):
                    if job["cancel"].is_set():
                        break
                    if attempt > 1:
                        log_job(job, f"Reintento {attempt}/{MAX_ATTEMPTS}: {title}")
                    try:
                        proc = subprocess.Popen(
                            cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace",
                            stdin=subprocess.DEVNULL,
                        )
                    except OSError as exc:
                        last_code = -1
                        log_job(job, f"No se pudo lanzar yt-dlp: {exc}")
                        break
                    with lock:
                        job["procs"].append(proc)
                    for raw in proc.stdout:
                        line = raw.rstrip()
                        if not line:
                            continue
                        m = re.search(r"(\d+(?:\.\d+)?)%", line)
                        if m:
                            try:
                                with lock:
                                    if vid in job["active"]:
                                        job["active"][vid] = float(m.group(1))
                                        refresh_live_locked()
                            except ValueError:
                                pass
                        if ("[download]" in line or "[ExtractAudio]" in line or
                                "Destination" in line or "WARNING" in line or "ERROR" in line):
                            log_job(job, line)
                    proc.wait()
                    last_code = proc.returncode
                    with lock:
                        job["procs"] = [p for p in job["procs"] if p is not proc]
                    if job["cancel"].is_set():
                        break
                    if last_code == 0:
                        success = True
                        break
                    if attempt < MAX_ATTEMPTS:
                        log_job(job, f"Fallo intento {attempt}/{MAX_ATTEMPTS} ({last_code}): {title}. Reintentando en {RETRY_DELAY_SECS}s…")
                        if job["cancel"].wait(RETRY_DELAY_SECS):
                            break
            except Exception as exc:
                log_job(job, f"Error inesperado en {title}: {exc}")
                success = False
                last_code = -1
            finally:
                with lock:
                    job["active"].pop(vid, None)
                    refresh_live_locked()
            if job["cancel"].is_set():
                log_job(job, f"Cancelado por el usuario en: {title}")
                with lock:
                    job["results"].append({"id": vid, "title": title, "status": "cancelado"})
                    if job.get("status") == "descargando":
                        job["status"] = "cancelado"
                return
            with lock:
                if success:
                    job["done"] += 1
                    job["results"].append({"id": vid, "title": title, "status": "ok"})
                else:
                    job["failed"] += 1
                    job["results"].append({"id": vid, "title": title, "status": "error"})
            log_job(job, f"{'OK' if success else f'ERROR ({last_code}) tras {MAX_ATTEMPTS} intentos'}: {title}")

        with ThreadPoolExecutor(max_workers=min(MAX_PARALLEL, max(1, total))) as ex:
            list(ex.map(one, items, range(1, total + 1)))

        with lock:
            if job.get("status") == "descargando":
                if job["cancel"].is_set():
                    job["status"] = "cancelado"
                else:
                    job["status"] = "completado" if job["failed"] == 0 else "completado con errores"
        # Limpieza: si yt-dlp no pudo incrustar la portada, la deja suelta (.webp/.jpg).
        # Se borran esas imágenes; los .mp3 no se tocan.
        try:
            out = job.get("out_dir")
            if out:
                leftovers = [p for ext in ("*.webp", "*.jpg", "*.jpeg", "*.png")
                             for p in Path(out).glob(ext) if p.is_file()]
                for p in leftovers:
                    try:
                        p.unlink()
                    except OSError:
                        pass
                if leftovers:
                    log_job(job, f"Limpieza: {len(leftovers)} portada(s) suelta(s) eliminada(s)")
        except Exception as exc:
            log_job(job, f"Aviso: no se pudo limpiar portadas sueltas: {exc}")
        job["current"] = ""
    except Exception as exc:  # nunca tumbar el servidor por un job
        job["status"] = "error"
        log_job(job, f"Error inesperado: {exc}")
    finally:
        job["finished"] = time.time()
        job["proc"] = None
        try:
            with job["lock"]:
                job["procs"] = []
                job["active"] = {}
                job["percent"] = 100.0 if job.get("status") != "descargando" else job.get("percent", 0.0)
                job["current"] = ""
        except Exception:
            pass
        try:
            tmp = job.get("temp_cookies")
            if tmp:
                try:
                    os.unlink(tmp)
                except OSError:
                    pass
        except Exception:
            pass
        purge_jobs()


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
    quality = str(payload.get("quality") or "320K")
    if quality not in {"128K", "192K", "256K", "320K"}:
        quality = "320K"
    playlist_title = str(payload.get("playlist_title") or "Playlist")
    base_dir = str(payload.get("out_dir") or "").strip()
    cookies_file = str(payload.get("cookies_file") or "").strip() or None
    cookies_content = str(payload.get("cookies_content") or "")
    temp_cookies = None
    if cookies_content.strip():
        if len(cookies_content) > 2_000_000:
            return jsonify({"error": "cookies.txt demasiado grande (máx 2 MB)."}), 400
        try:
            fd, tmppath = tempfile.mkstemp(prefix="ytmp3_cookies_", suffix=".txt")
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(cookies_content)
        except OSError:
            return jsonify({"error": "No se pudo guardar cookies.txt."}), 500
        temp_cookies = tmppath
        cookies_file = tmppath

    clean_items = []
    for it in items:
        vid = str((it or {}).get("id") or "").strip()
        # IDs de video: 11 caracteres, alfabeto seguro
        if re.fullmatch(r"[A-Za-z0-9_-]{6,20}", vid):
            num = (it or {}).get("index", (it or {}).get("num"))
            try:
                num = int(num) if num is not None else None
            except (TypeError, ValueError):
                num = None
            clean_items.append({"id": vid, "title": str(it.get("title") or vid), "num": num})
    if not clean_items:
        return jsonify({"error": "No hay canciones seleccionadas."}), 400
    # Sin número original (p. ej. reintento antiguo): numerar en orden
    for pos, it in enumerate(clean_items, start=1):
        if not isinstance(it.get("num"), int):
            it["num"] = pos

    job = new_job(sanitize_filename(playlist_title), base_dir or str(DEFAULT_DOWNLOAD_DIR), len(clean_items))
    job["items"] = [dict(it) for it in clean_items]
    job["quality"] = quality
    job["base_dir"] = base_dir or str(DEFAULT_DOWNLOAD_DIR)
    job["cookies_file"] = cookies_file
    job["temp_cookies"] = temp_cookies
    threading.Thread(
        target=download_job,
        args=(job, clean_items, quality, base_dir, cookies_file),
        daemon=True,
    ).start()
    return jsonify({"job_id": job["id"]})


@app.post("/api/jobs/<jid>/retry")
def api_retry(jid):
    with JOBS_LOCK:
        job = JOBS.get(jid)
    if not job:
        return jsonify({"error": "Job no encontrado."}), 404
    if job.get("status") == "descargando":
        return jsonify({"error": "El trabajo aún está en curso; cancélalo antes de reintentar."}), 409
    failed_ids = {str(r.get("id")) for r in (job.get("results") or []) if r.get("status") == "error"}
    if not failed_ids:
        return jsonify({"error": "No hay errores para reintentar."}), 400
    orig_items = job.get("items") or []
    by_id = {str(it.get("id")): it for it in orig_items if it.get("id")}
    if by_id:
        retry_items = [dict(by_id[fid]) for fid in failed_ids if fid in by_id]
        # Mantener orden original de la playlist
        retry_items.sort(key=lambda it: (it.get("num") if isinstance(it.get("num"), int) else 9999))
    else:
        # Jobs antiguos sin items guardados: reconstruir desde results
        retry_items = [
            {"id": str(r.get("id")), "title": str(r.get("title") or r.get("id")), "num": None}
            for r in (job.get("results") or []) if r.get("status") == "error"
        ]
        for pos, it in enumerate(retry_items, start=1):
            it["num"] = pos
    if not retry_items:
        return jsonify({"error": "No se pudieron reconstruir los errores."}), 500
    base_dir = job.get("base_dir") or job.get("out_dir") or str(DEFAULT_DOWNLOAD_DIR)
    # base_dir puede ser la carpeta final ya resuelta; subir un nivel si parece serlo
    quality = job.get("quality") or "320K"
    cookies_file = job.get("cookies_file")
    # Si base_dir apunta a la subcarpeta final, usar su padre como base
    try:
        if Path(str(base_dir)).name == sanitize_filename(job.get("title", ""), "Playlist"):
            base_dir = str(Path(str(base_dir)).parent)
    except Exception:
        pass
    new = new_job(job.get("title") or "Playlist", base_dir, len(retry_items))
    new["items"] = [dict(it) for it in retry_items]
    new["quality"] = quality
    new["base_dir"] = base_dir
    new["cookies_file"] = cookies_file
    log_job(new, f"Reintento manual de {len(retry_items)} error(es) del trabajo {job.get('id')}")
    threading.Thread(
        target=download_job,
        args=(new, retry_items, quality, base_dir, cookies_file),
        daemon=True,
    ).start()
    return jsonify({"job_id": new["id"], "count": len(retry_items)})


@app.get("/api/jobs")
def api_jobs():
    purge_jobs()
    with JOBS_LOCK:
        jobs = sorted(JOBS.values(), key=lambda j: j["created"], reverse=True)
    return jsonify({"jobs": [job_public(j) for j in jobs]})


@app.delete("/api/jobs")
def api_jobs_clear():
    with JOBS_LOCK:
        finished = [jid for jid, j in JOBS.items() if j.get("status") != "descargando"]
        for jid in finished:
            JOBS.pop(jid, None)
    return jsonify({"ok": True, "removed": len(finished)})


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
    try:
        with job["lock"]:
            procs = list(job.get("procs") or [])
            if job.get("proc") is not None:
                procs.append(job["proc"])
    except Exception:
        procs = []
    for proc in procs:
        kill_proc_tree(proc)
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


def clean_stale_cookies() -> None:
    """Borra cookies temporales de ejecuciones anteriores (p. ej. si se mató el proceso)."""
    try:
        tmp = Path(tempfile.gettempdir())
        for p in tmp.glob("ytmp3_cookies_*.txt"):
            try:
                if time.time() - p.stat().st_mtime > 3600:
                    p.unlink()
            except OSError:
                pass
    except Exception:
        pass


if __name__ == "__main__":
    clean_stale_cookies()
    # Solo local. No cambiar a 0.0.0.0.
    app.run(host="127.0.0.1", port=int(os.environ.get("PORT", "5088")), debug=False, threaded=True)
