// YTMusic → MP3 · lógica del frontend
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => [...document.querySelectorAll(sel)];

let currentPlaylist = { title: "Playlist", items: [] };
let pollTimer = null;

const fmtDur = (s) => {
  if (s == null || isNaN(s)) return "--:--";
  s = Math.floor(s);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return h ? `${h}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}` : `${m}:${String(sec).padStart(2, "0")}`;
};
const esc = (str) => String(str ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;" }[c]));

// Cargar carpeta por defecto
fetch("/api/health").then(r => r.json()).then(d => {
  const saved = localStorage.getItem("ytmp3.out_dir");
  $("#out_dir").value = saved || d.default_dir || "";
}).catch(() => {});

$("#playlist-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const url = $("#url").value.trim();
  const errBox = $("#load-error");
  const btn = $("#btn-load");
  errBox.hidden = true;
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span>Leyendo playlist…';
  try {
    const r = await fetch("/api/playlist?url=" + encodeURIComponent(url));
    const data = await r.json();
    if (!r.ok) throw new Error(data.error || "No se pudo leer la playlist.");
    renderPlaylist(data);
    localStorage.setItem("ytmp3.out_dir", $("#out_dir").value.trim());
  } catch (err) {
    errBox.textContent = err.message;
    errBox.hidden = false;
    $("#step-tracks").hidden = true;
  } finally {
    btn.disabled = false;
    btn.textContent = "Cargar playlist";
  }
});

function renderPlaylist(data) {
  currentPlaylist = { title: data.title || "Playlist", items: data.items || [] };
  $("#playlist-title").textContent = currentPlaylist.title;
  const kindLabel = data.kind === "canal" ? "canal/perfil" : "playlist";
  $("#playlist-meta").textContent = [data.uploader, `${data.count} ${kindLabel === "canal/perfil" ? "videos" : "canciones"}`].filter(Boolean).join(" · ");

  // Filtro de Shorts: visible solo si hay pestaña de Shorts; por defecto, fuera.
  const hasShorts = currentPlaylist.items.some(t => /short/i.test(t.tab || ""));
  const filterSel = $("#tab-filter");
  $("#tab-filter-label").hidden = !hasShorts;
  filterSel.value = "no-shorts";

  renderTrackList();
}

function visibleItems() {
  const mode = $("#tab-filter").value;
  if ($("#tab-filter-label").hidden) return currentPlaylist.items;
  if (mode === "only-shorts") return currentPlaylist.items.filter(t => /short/i.test(t.tab || ""));
  if (mode === "no-shorts") return currentPlaylist.items.filter(t => !/short/i.test(t.tab || ""));
  return currentPlaylist.items;
}

function renderTrackList() {
  const shown = visibleItems();
  const ul = $("#track-list");
  ul.innerHTML = shown.map((t) => `
    <li class="track${t.unavailable ? " unavailable" : ""}">
      <input type="checkbox" class="track-check" data-id="${esc(t.id)}" ${t.unavailable ? "" : "checked"} ${t.unavailable ? "disabled" : ""} aria-label="Seleccionar ${esc(t.title)}">
      <img src="${esc(t.thumbnail)}" alt="" loading="lazy" onerror="this.style.visibility='hidden'">
      <div>
        <div class="t-title">${esc(t.title)}</div>
        <div class="t-sub">${[esc(t.uploader || ""), esc(t.tab || "")].filter(Boolean).join(" · ")}</div>
      </div>
      <div class="t-dur">${fmtDur(t.duration)}</div>
    </li>`).join("");

  $("#tot-count").textContent = shown.length;
  updateSelCount();
  $("#step-tracks").hidden = false;
  $("#step-tracks").scrollIntoView({ behavior: "smooth", block: "start" });
}

$("#tab-filter").addEventListener("change", renderTrackList);

function selectedItems() {
  const ids = new Set($$(".track-check:checked").map(cb => cb.dataset.id));
  return currentPlaylist.items.filter(t => ids.has(t.id));
}
function updateSelCount() {
  $("#sel-count").textContent = $$(".track-check:checked").length;
  $("#btn-download").disabled = $$(".track-check:checked").length === 0;
  const boxes = $$(".track-check:not(:disabled)");
  $("#check-all").checked = boxes.length > 0 && boxes.every(b => b.checked);
  $("#check-all").indeterminate = !$("#check-all").checked && boxes.some(b => b.checked);
}

$("#track-list").addEventListener("change", updateSelCount);
$("#check-all").addEventListener("change", (e) => {
  $$(".track-check:not(:disabled)").forEach(cb => { cb.checked = e.target.checked; });
  updateSelCount();
});

$("#btn-download").addEventListener("click", async () => {
  const items = selectedItems();
  if (!items.length) return;
  const btn = $("#btn-download");
  btn.disabled = true;
  const original = btn.textContent;
  btn.innerHTML = '<span class="spinner"></span>Iniciando…';
  try {
    const r = await fetch("/api/download", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        items,
        quality: $("#quality").value,
        out_dir: $("#out_dir").value.trim(),
        playlist_title: currentPlaylist.title,
        cookies_file: $("#cookies_file").value.trim(),
      }),
    });
    const data = await r.json();
    if (!r.ok) throw new Error(data.error || "No se pudo iniciar la descarga.");
    localStorage.setItem("ytmp3.out_dir", $("#out_dir").value.trim());
    $("#step-jobs").hidden = false;
    await refreshJobs();
    startPolling();
  } catch (err) {
    alert("Error: " + err.message);
  } finally {
    btn.disabled = false;
    btn.textContent = original;
    updateSelCount();
  }
});

$("#btn-open-folder").addEventListener("click", async () => {
  const r = await fetch("/api/open-folder", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path: $("#out_dir").value.trim() }),
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) alert(data.error || "No se pudo abrir la carpeta.");
});

$("#btn-clear-jobs").addEventListener("click", async (e) => {
  const btn = e.currentTarget;
  btn.disabled = true;
  try {
    await fetch("/api/jobs", { method: "DELETE" });
    await refreshJobs();
  } finally {
    btn.disabled = false;
  }
});

function startPolling() {
  clearInterval(pollTimer);
  pollTimer = setInterval(async () => {
    const anyActive = await refreshJobs();
    if (!anyActive) clearInterval(pollTimer);
  }, 1200);
}

async function refreshJobs() {
  try {
    const r = await fetch("/api/jobs");
    const { jobs } = await r.json();
    renderJobs(jobs || []);
    return (jobs || []).some(j => j.status === "descargando");
  } catch {
    return false;
  }
}

function statusClass(s) {
  return "st-" + String(s || "").toLowerCase().normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
}

function renderJobs(jobs) {
  const box = $("#jobs");
  if (!jobs.length) { box.innerHTML = '<p class="muted">Aún no hay descargas.</p>'; return; }
  box.innerHTML = jobs.map(j => {
    const isFinal = ["completado", "completado con errores", "cancelado", "error"].includes(String(j.status));
    const inTrack = isFinal ? 0 : Math.min(Math.max(j.percent || 0, 0), 100 * 3) / 100;
    const width = isFinal ? 100 : (j.total ? Math.min(100, Math.round(((j.done + inTrack) / j.total) * 1000) / 10) : 0);
    const log = (j.log || []).slice(-12).join("\n");
    const failed = (j.results || []).filter(r => r.status && r.status !== "ok");
    const errorsOnly = (j.results || []).filter(r => r.status === "error");
    const canRetry = isFinal && errorsOnly.length > 0;
    return `
    <div class="job" data-id="${esc(j.id)}">
      <div class="job-top">
        <div class="job-title">${esc(j.title)}</div>
        <span class="job-status ${statusClass(j.status)}">${esc(j.status)}</span>
      </div>
      <div class="progress"><div style="width:${width}%"></div></div>
      <div class="job-meta">
        <span>${j.done}/${j.total} canciones · ${j.failed} errores${j.current ? " · " + esc(j.current) : ""}</span>
        <span title="${esc(j.out_dir)}">${esc(j.out_dir)}</span>
      </div>
      ${failed.length ? `<details class="job-failed"><summary>Ver ${failed.length} con error</summary><ul>${failed.map(r => `<li>${esc(r.title || r.id)} <span class="muted">(${esc(r.status)})</span></li>`).join("")}</ul></details>` : ""}
      ${log ? `<pre class="job-log">${esc(log)}</pre>` : ""}
      ${j.status === "descargando"
        ? `<div class="job-actions"><button class="btn ghost small" data-cancel="${esc(j.id)}">Cancelar</button></div>`
        : canRetry
          ? `<div class="job-actions"><button class="btn primary small" data-retry="${esc(j.id)}">Reintentar ${errorsOnly.length} error(es)</button></div>`
          : ""}
    </div>`;
  }).join("");

  box.querySelectorAll("[data-cancel]").forEach(btn => {
    btn.addEventListener("click", async () => {
      btn.disabled = true;
      await fetch(`/api/jobs/${btn.dataset.cancel}/cancel`, { method: "POST" });
      refreshJobs();
    });
  });

  box.querySelectorAll("[data-retry]").forEach(btn => {
    btn.addEventListener("click", async () => {
      btn.disabled = true;
      btn.innerHTML = '<span class="spinner"></span>Reintentando…';
      try {
        const r = await fetch(`/api/jobs/${btn.dataset.retry}/retry`, { method: "POST" });
        const data = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(data.error || "No se pudo reintentar.");
        await refreshJobs();
        startPolling();
      } catch (err) {
        alert("Error: " + err.message);
        btn.disabled = false;
        refreshJobs();
      }
    });
  });
}

// Estado inicial por si la pestaña se recarga con jobs vivos
refreshJobs().then(active => { if (active) { $("#step-jobs").hidden = false; startPolling(); } });
