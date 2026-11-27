// Secreto Space Console - Controller logic v0.1.0
(function () {
  "use strict";

  // Inject Native Bridge to window.ksu shim if running in Secreto Space APK
  if (window._nativeBridge) {
    window.ksu = {
      exec: async function (cmd) {
        try {
          const res = window._nativeBridge.exec(cmd);
          return { errno: 0, stdout: res || "", stderr: "" };
        } catch (e) {
          return { errno: -1, stdout: "", stderr: String(e) };
        }
      },
    };
  }

  // Elements: Hero & Telemetry
  const stateDot = document.getElementById("state-dot");
  const stateLabel = document.getElementById("state-label");
  const heroGame = document.getElementById("hero-game");
  const heroTemp = document.getElementById("hero-temp");
  const heroGpuClk = document.getElementById("hero-gpu-clk");
  const heroCpuClk = document.getElementById("hero-cpu-clk");
  const heroStutterGov = document.getElementById("hero-stutter-gov");
  const radialCpuFill = document.getElementById("radial-cpu-fill");
  const radialGpuFill = document.getElementById("radial-gpu-fill");
  const heroGpuLoadSub = document.getElementById("hero-gpu-load-sub");
  const heroPresetTag = document.getElementById("hero-preset-tag");
  const btnQuickBypass = document.getElementById("btn-quick-bypass");
  const btnQuickDnd = document.getElementById("btn-quick-dnd");
  const btnQuickGosWeb = document.getElementById("btn-quick-gos-web");
  const lblQuickBypass = document.getElementById("lbl-quick-bypass");
  const lblQuickDnd = document.getElementById("lbl-quick-dnd");
  const lblQuickGosWeb = document.getElementById("lbl-quick-gos-web");
  const btnQuickHud = document.getElementById("btn-quick-hud");
  const lblQuickHud = document.getElementById("lbl-quick-hud");
  const presetSegmentBtns = document.querySelectorAll(".preset-segment-btn[data-preset]");
  const gpuBusyFill = document.getElementById("gpu-busy-fill");
  const gpuBusyText = document.getElementById("gpu-busy-text");
  const gpuMinText = document.getElementById("gpu-min-text");
  const tempLittle = document.getElementById("temp-little");
  const tempGpu = document.getElementById("temp-gpu");
  const daemonUptime = document.getElementById("daemon-uptime");
  const snapshotStatus = document.getElementById("snapshot-status");
  const daemonStatus = document.getElementById("daemon-status");
  const frametimeCanvas = document.getElementById("frametime-canvas");
  const fpsLiveLabel = document.getElementById("fps-live-label");
  const fpsFtLabel = document.getElementById("fps-ft-label");

  // Elements: Session
  const sessionBadge = document.getElementById("session-badge");
  const sessionDuration = document.getElementById("session-duration");
  const sessionSlept = document.getElementById("session-slept");
  const sessionAvgFps = document.getElementById("session-avg-fps");
  const sessionP1 = document.getElementById("session-p1");
  const sessionJank = document.getElementById("session-jank");
  const sessionPeak = document.getElementById("session-peak");
  const sessionLast = document.getElementById("session-last");
  const btnSessionWake = document.getElementById("btn-session-wake");
  const btnSessionEnd = document.getElementById("btn-session-end");
  const sleepBadge = document.getElementById("sleep-badge");
  const sleepDesc = document.getElementById("sleep-desc");
  const btnSleepToggle = document.getElementById("btn-sleep-toggle");
  const sleepWhitelistInput = document.getElementById("sleep-whitelist-input");
  const btnSleepSave = document.getElementById("btn-sleep-save");

  // Elements: Network
  const netIface = document.getElementById("net-iface");
  const netCcBadge = document.getElementById("net-cc-text");

  // Elements: Game Library
  const gamesContainer = document.getElementById("games-container");
  const gamesCarouselView = document.getElementById("games-carousel-view");
  const carouselStage = document.getElementById("carousel-stage");
  const carouselDots = document.getElementById("carousel-dots");
  const carouselHero = document.getElementById("carousel-hero");
  const carouselHeroTitle = document.getElementById("carousel-hero-title");
  const carouselHeroSub = document.getElementById("carousel-hero-sub");
  const carouselHeroPreset = document.getElementById("carousel-hero-preset");
  const carouselHeroPlay = document.getElementById("carousel-hero-play");
  const carouselHeroTune = document.getElementById("carousel-hero-tune");
  const btnViewToggle = document.getElementById("btn-view-toggle");
  const btnScanGames = document.getElementById("btn-scan-games");
  const btnToggleAddDrawer = document.getElementById("btn-toggle-add-drawer");
  const addGameModal = document.getElementById("add-game-modal");
  const installedAppsSelect = document.getElementById("installed-apps-select");
  const customPkgInput = document.getElementById("custom-pkg-input");
  const btnSubmitAddGame = document.getElementById("btn-submit-add-game");
  const btnCancelAddGame = document.getElementById("btn-cancel-add-game");

  // Toast notification helper
  let toastTimer = null;
  function showToast(msg) {
    const toast = document.getElementById("toast");
    if (!toast) return;
    toast.textContent = msg;
    toast.classList.remove("hidden");
    // Force reflow then show
    void toast.offsetWidth;
    toast.classList.add("show");
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      toast.classList.remove("show");
      setTimeout(() => toast.classList.add("hidden"), 300);
    }, 3000);
  }

  // Local development preview only. It never runs in the APK/production WebUI.
  const previewAllowed = location.hostname === "localhost" || location.hostname === "127.0.0.1";
  const previewParams = new URLSearchParams(location.search);
  const previewMode = previewAllowed && previewParams.has("preview");
  const previewState = (previewParams.get("state") || "").toUpperCase();

  // State
  let allGames = [];
  let installedApps = [];
  let currentForegroundPkg = "";
  let gamesViewMode = "grid";        // "grid" | "carousel"
  let carouselIndex = 0;
  let carouselFiltered = [];
  const frametimeHistory = [];
  const MAX_FRAMETIME_POINTS = 45;

  function renderFrametimeGraph(fps) {
    if (!frametimeCanvas) return;
    const ctx = frametimeCanvas.getContext("2d");
    if (!ctx) return;

    const ft = fps > 0 ? 1000 / fps : 0;
    frametimeHistory.push(ft);
    if (frametimeHistory.length > MAX_FRAMETIME_POINTS) {
      frametimeHistory.shift();
    }

    if (fpsLiveLabel) {
      fpsLiveLabel.textContent = fps > 0 ? `${fps.toFixed(1)} FPS` : "0.0 FPS";
    }
    if (fpsFtLabel) {
      fpsFtLabel.textContent = fps > 0 ? `${ft.toFixed(1)} ms frametime` : "-- ms frametime";
    }

    const dpr = window.devicePixelRatio || 1;
    const rect = frametimeCanvas.getBoundingClientRect();
    const w = rect.width || 320;
    const h = 60;

    if (frametimeCanvas.width !== Math.floor(w * dpr) || frametimeCanvas.height !== Math.floor(h * dpr)) {
      frametimeCanvas.width = Math.floor(w * dpr);
      frametimeCanvas.height = Math.floor(h * dpr);
    }

    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w, h);

    // 16.6ms Target line (60 FPS)
    const maxMs = 50;
    const targetY = h - (16.66 / maxMs) * (h - 10);

    ctx.strokeStyle = "rgba(0, 230, 118, 0.4)";
    ctx.lineWidth = 1;
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(0, targetY);
    ctx.lineTo(w, targetY);
    ctx.stroke();
    ctx.setLineDash([]);

    if (frametimeHistory.length < 2) {
      ctx.restore();
      return;
    }

    const step = w / (MAX_FRAMETIME_POINTS - 1);
    const startX = w - (frametimeHistory.length - 1) * step;

    // Fill area under graph
    ctx.beginPath();
    frametimeHistory.forEach((val, idx) => {
      const clamped = Math.min(maxMs, Math.max(0, val));
      const y = h - (clamped / maxMs) * (h - 10);
      const x = startX + idx * step;
      if (idx === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.lineTo(w, h);
    ctx.lineTo(startX, h);
    ctx.closePath();
    const grad = ctx.createLinearGradient(0, 0, 0, h);
    grad.addColorStop(0, "rgba(0, 229, 255, 0.22)");
    grad.addColorStop(1, "rgba(0, 229, 255, 0.0)");
    ctx.fillStyle = grad;
    ctx.fill();

    // Line stroke
    ctx.beginPath();
    frametimeHistory.forEach((val, idx) => {
      const clamped = Math.min(maxMs, Math.max(0, val));
      const y = h - (clamped / maxMs) * (h - 10);
      const x = startX + idx * step;
      if (idx === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.strokeStyle = "#00e5ff";
    ctx.lineWidth = 1.5;
    ctx.stroke();

    ctx.restore();
  }

  // Tab switching
  const tabs = document.querySelectorAll(".nav-tab");
  const panels = document.querySelectorAll(".tab-panel");

  tabs.forEach((tab) => {
    tab.addEventListener("click", () => {
      tabs.forEach((t) => t.classList.remove("active"));
      panels.forEach((p) => p.classList.remove("active"));

      tab.classList.add("active");
      const target = document.getElementById(tab.dataset.tab);
      if (target) {
        target.classList.add("active");
        if (tab.dataset.tab === "tab-games") {
          loadGames();
        }
        if (tab.dataset.tab === "tab-policy") {
          refreshSleepSettings();
          if (!multiPingDone) {
            multiPingDone = true;
            runMultiPing();
          }
        }
        if (tab.dataset.tab === "tab-system") {
          loadProbe();
        }
      }
    });
  });

  // Shell command executor with KernelSU detection
  async function execCmd(cmd) {
    if (window.ksu && typeof window.ksu.exec === "function") {
      try {
        const res = await window.ksu.exec(cmd);
        if (res && typeof res === "object" && typeof res.stdout === "string") {
          return res.stdout.trim();
        }
        if (typeof res === "string") {
          return res.trim();
        }
        return res;
      } catch (e) {
        console.error("ksu.exec error:", e);
      }
    }
    // Fallback or demo mode: log
    console.log("[Demo Exec]", cmd);
    return null;
  }

  let ipcSeq = 0;
  function sendSocketCmd(cmd) {
    if (window._nativeBridge && typeof window._nativeBridge.sendSocketAsync === "function") {
      return new Promise((resolve) => {
        // Capture the slot number. Using the mutable counter inside the
        // callback deleted a newer request's slot and made its response fail
        // with "__secretoIpc[N] is not a function".
        const id = ++ipcSeq;
        const callback = `window.__secretoIpc[${id}]`;
        window.__secretoIpc = window.__secretoIpc || {};
        window.__secretoIpc[id] = (value) => {
          delete window.__secretoIpc[id];
          resolve(value || null);
        };
        window._nativeBridge.sendSocketAsync(cmd, callback);
      });
    }
    if (window._nativeBridge && typeof window._nativeBridge.sendSocket === "function") {
      return Promise.resolve(window._nativeBridge.sendSocket(cmd) || null);
    }
    const shell = `printf "${cmd}\\n" | nc -U /data/local/tmp/secreto.sock 2>/dev/null`;
    return execCmd(shell);
  }

  // Update UI with snapshot data
  function displayGameName(pkg) {
    if (!pkg) return "Desktop / Idle";
    const known = allGames.find((g) => g.pkg === pkg);
    return known && known.name ? known.name : pkg;
  }

  let lastSessionReport = null;
  let sleepEnabled = true;
  let sleepCandidateCount = 0;

  function formatDuration(totalSeconds) {
    const sec = Math.max(0, Math.round(totalSeconds || 0));
    if (sec < 60) return `${sec}s`;
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    if (m < 60) return `${m}m ${s}s`;
    return `${Math.floor(m / 60)}h ${m % 60}m`;
  }

  function renderSession(sess, sleeping) {
    const active = !!sess.active;
    const sessionStatusDot = document.getElementById("session-status-dot");
    const sessionMiniCover = document.getElementById("session-mini-cover");
    const sessionGameTitle = document.getElementById("session-title");

    if (sessionBadge) {
      sessionBadge.textContent = active ? "ACTIVE SESSION" : "STANDBY READY";
      sessionBadge.className = active ? "session-status-badge active" : "session-status-badge";
    }
    if (sessionStatusDot) {
      sessionStatusDot.className = active ? "session-status-dot active" : "session-status-dot";
    }
    if (sessionDuration) {
      sessionDuration.textContent = active ? "Elapsed: " + formatDuration(sess.elapsed_s) : "Elapsed: 00:00:00";
    }
    if (sessionGameTitle) {
      sessionGameTitle.textContent = active ? displayGameName(sess.pkg) : "No active session";
    }
    if (sessionMiniCover) {
      if (active && sess.pkg) {
        sessionMiniCover.style.setProperty("--tile-hue", charSumHue(sess.pkg));
        sessionMiniCover.classList.remove("idle");
      } else {
        sessionMiniCover.classList.add("idle");
      }
    }
    const sleptCount = active ? (sess.slept_apps || 0) : (sleeping ? sleeping.length : 0);
    if (sessionSlept) sessionSlept.value = String(sleptCount);
    if (sessionAvgFps) {
      sessionAvgFps.textContent = sess.avg_fps != null ? String(sess.avg_fps) : "--";
    }
    if (sessionP1) {
      sessionP1.textContent = sess.p1_low != null ? String(sess.p1_low) : "--";
    }
    if (sessionJank) {
      sessionJank.textContent = sess.jank_pct != null ? (sess.jank_pct + "%") : "--";
    }
    if (sessionPeak) {
      const peak = active ? sess.max_temp_c : (lastSessionReport?.max_temp_c ?? null);
      sessionPeak.textContent = peak != null ? (Math.round(peak) + "°C") : "--";
    }
    if (sessionLast) {
      if (active) {
        sessionLast.textContent = `Running ${displayGameName(sess.pkg)} on ${sess.preset || "preset"}. ${sleptCount} apps asleep.`;
      } else if (lastSessionReport) {
        const r = lastSessionReport;
        const avg = r.avg_fps != null ? `${r.avg_fps} FPS avg` : "no FPS data";
        const jank = r.jank_pct != null ? `${r.jank_pct}% jank` : "no frame data";
        const thermal = r.throttle_events ? ` · ${r.throttle_events} thermal drop${r.throttle_events > 1 ? "s" : ""}` : "";
        sessionLast.textContent = `Last: ${displayGameName(r.pkg)} · ${formatDuration(r.duration_s)} · ${avg} · ${jank} · peak ${r.max_temp_c || 0}°C${thermal}`;
      } else {
        sessionLast.textContent = "No session recorded yet.";
      }
    }
  }

  async function refreshSessionReport() {
    const raw = await sendSocketCmd("session_status");
    if (raw && typeof raw === "string") {
      try {
        const j = JSON.parse(raw);
        lastSessionReport = j.last || null;
        renderSession(j.session || {}, j.sleeping || []);
      } catch (e) {
        console.error("session_status parse:", e);
      }
    }
  }

  async function refreshSleepSettings() {
    const raw = await sendSocketCmd("get_sleep_settings");
    if (!raw || typeof raw !== "string") return;
    try {
      const j = JSON.parse(raw);
      sleepEnabled = j.enabled !== false;
      sleepCandidateCount = j.count || 0;
      if (sleepBadge) {
        sleepBadge.textContent = sleepEnabled ? "ON" : "OFF";
        sleepBadge.className = sleepEnabled ? "badge active" : "badge";
      }
      if (btnSleepToggle) {
        btnSleepToggle.textContent = sleepEnabled ? "ON" : "OFF";
        btnSleepToggle.classList.toggle("active", sleepEnabled);
      }
      if (sleepDesc) {
        const sleeping = (j.sleeping || []).length;
        sleepDesc.textContent = sleepEnabled
          ? `${j.count || 0} apps would sleep, ${sleeping} asleep now`
          : "Off. Nothing is slept when a session starts.";
      }
      if (sleepWhitelistInput && document.activeElement !== sleepWhitelistInput) {
        sleepWhitelistInput.value = (j.whitelist || []).join(", ");
      }
    } catch (e) {
      console.error("get_sleep_settings parse:", e);
    }
  }

  async function loadProbe() {
    const badge = document.getElementById("probe-badge");
    const raw = await sendSocketCmd("probe");
    if (!raw || typeof raw !== "string") return;
    try {
      const p = JSON.parse(raw).probe;
      if (!p) return;
      const set = (id, val) => {
        const el = document.getElementById(id);
        if (el) el.textContent = val;
      };
      set("probe-device", `${p.device || "--"} · ${p.model || "--"}`);
      set("probe-android", p.android ? `Android ${p.android}` : "--");
      set("probe-rom", p.rom || "--");
      set("probe-gos", p.gos ? "present" : "absent");
      set("probe-freeze", p.freeze || "--");
      set("probe-render", p.wm_size ? "wm size supported" : "not supported");
      set("probe-cpuset", p.cpuset ? p.cpuset_controllers || "present" : "absent");
      set("probe-root", p.root ? "root" : "no root");
      set("probe-selinux", p.selinux || "--");
      if (badge) {
        badge.textContent = "READY";
        badge.className = "badge active";
      }
    } catch (e) {
      console.error("probe parse:", e);
    }
  }

  function reportWhen(epochSeconds) {
    const d = new Date((epochSeconds || 0) * 1000);
    if (isNaN(d.getTime())) return "--";
    const date = d.toLocaleDateString([], { month: "short", day: "numeric" });
    const time = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    return `${date} ${time}`;
  }

  async function loadGameReports(pkg) {
    const listEl = document.getElementById("detail-reports");
    const adviceEl = document.getElementById("detail-advice");
    const countEl = document.getElementById("detail-report-count");
    if (!listEl) return;
    listEl.innerHTML = '<div class="report-empty">Loading reports...</div>';
    if (countEl) countEl.textContent = "";
    if (adviceEl) adviceEl.classList.add("hidden");
    const raw = await sendSocketCmd(`session_history ${pkg}`);
    if (!raw || typeof raw !== "string") {
      listEl.innerHTML = '<div class="report-empty">Reports unavailable.</div>';
      if (countEl) countEl.textContent = "UNAVAILABLE";
      return;
    }
    try {
      const j = JSON.parse(raw);
      const reports = j.reports || [];
      if (countEl) {
        countEl.textContent = reports.length
          ? `${reports.length} SESSION${reports.length === 1 ? "" : "S"}`
          : "NONE";
      }
      if (!reports.length) {
        listEl.innerHTML = '<div class="report-empty">No sessions recorded yet.</div>';
      } else {
        listEl.innerHTML = reports
          .map((r) => {
            const metrics = [
              formatDuration(r.duration_s),
              r.avg_fps != null ? `${r.avg_fps} avg` : "no fps",
              r.p1_low != null ? `${r.p1_low} 1%` : null,
              r.jank_pct != null ? `${r.jank_pct}% jank` : null,
              `${Math.round(r.max_temp_c || 0)}°C`,
              `${r.slept_apps || 0} asleep`,
              r.preset || "preset",
            ]
              .filter(Boolean)
              .join(" · ");
            const flag = r.throttle_events
              ? `${r.throttle_events} thermal`
              : r.reason === "idle"
                ? ""
                : r.reason || "";
            return `<div class="report-row"><span class="report-when">${reportWhen(r.started_at)}</span><span class="report-flag">${flag}</span><span class="report-metrics">${metrics}</span></div>`;
          })
          .join("");
      }
      if (adviceEl && j.suggestion) {
        adviceEl.textContent = j.suggestion;
        adviceEl.classList.remove("hidden");
      }
    } catch (e) {
      listEl.innerHTML = '<div class="report-empty">Reports unavailable.</div>';
      if (countEl) countEl.textContent = "UNAVAILABLE";
    }
  }

  let reportsExpanded = false;

  function setReportsExpanded(open) {
    reportsExpanded = !!open;
    const head = document.getElementById("btn-toggle-reports");
    const body = document.getElementById("detail-reports-body");
    if (head) head.setAttribute("aria-expanded", reportsExpanded ? "true" : "false");
    if (body) body.classList.toggle("open", reportsExpanded);
  }

  let renderStatus = null;

  function paintRenderScale(pkg) {
    const group = document.getElementById("detail-scale-group");
    const note = document.getElementById("detail-scale-note");
    if (!group) return;
    const supported = !!(renderStatus && renderStatus.supported);
    const mode = supported ? (renderStatus.scales || {})[pkg] || "native" : "native";
    group.querySelectorAll(".detail-scale").forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.scale === mode);
      btn.disabled = !supported;
    });
    if (note) {
      if (!supported) {
        note.textContent = "This ROM does not expose wm size. Render scale is unavailable.";
      } else {
        const active = renderStatus.override_size
          ? `Active now: ${renderStatus.override_size}`
          : `Native panel: ${renderStatus.native}`;
        note.textContent = `${active}. 720p and 900p apply only while this game runs, and are reset on exit or reboot.`;
      }
    }
  }

  async function loadRenderScale(pkg) {
    const raw = await sendSocketCmd("get_render_scale");
    if (!raw || typeof raw !== "string") return;
    try {
      renderStatus = JSON.parse(raw).render || null;
    } catch (e) {
      renderStatus = null;
    }
    paintRenderScale(pkg);
  }

  function renderSnapshot(data) {
    if (!data) return;

    // Quick toggles follow the daemon truth on every poll, so reopening the
    // app always shows the real last state (no hardcoded defaults).
    bypassActive = !!(data.battery && data.battery.bypass_charging);
    dndActive = !!data.heads_up_blocked;
    renderBypassState();
    renderDndState();

    if (daemonStatus) {
      daemonStatus.textContent = "ONLINE";
      daemonStatus.className = "badge active";
    }

    // State
    const st = data.daemon_state || "IDLE";
    stateLabel.textContent = st;
    stateDot.className = "state-dot";
    if (st === "GAME") stateDot.classList.add("active");
    else if (st === "WARN") stateDot.classList.add("warn");
    else if (st === "CRIT") stateDot.classList.add("crit");

    // Hero Meta
    currentForegroundPkg = data.game_pkg || "";
    heroGame.textContent = displayGameName(currentForegroundPkg);
    const tempC = Math.round((data.temp_big_mc || 0) / 1000);
    heroTemp.textContent = `${tempC}°`;

    // Clocks
    const gpuMhz = Math.round((data.gpu?.current_clock || data.gpu?.min_clock || 0) / 1000);
    heroGpuClk.textContent = `${gpuMhz}M`;

    const cpuMhz = Math.round((data.cpu?.big?.cur_freq || data.cpu?.big?.min_freq || 0) / 1000);
    heroCpuClk.textContent = `${cpuMhz}M`;

    // Radial Ring Gauges calculation
    if (radialCpuFill) {
      const maxTemp = 92;
      const tempRatio = Math.min(1, Math.max(0, tempC / maxTemp));
      const cpuOffset = 264 * (1 - tempRatio);
      radialCpuFill.style.strokeDashoffset = cpuOffset;
      if (tempC >= 80) {
        radialCpuFill.style.stroke = "#ff4d5e";
      } else if (tempC >= 68) {
        radialCpuFill.style.stroke = "#ffb020";
      } else {
        radialCpuFill.style.stroke = "#00e676";
      }
    }

    const busy = Math.round(data.gpu?.busy_pct || 0);
    if (radialGpuFill) {
      const gpuMax = 1053;
      const gpuRatio = Math.min(1, Math.max(0, gpuMhz / gpuMax));
      const gpuOffset = 264 * (1 - gpuRatio);
      radialGpuFill.style.strokeDashoffset = gpuOffset;
    }
    if (heroGpuLoadSub) {
      heroGpuLoadSub.textContent = `${busy}% LOAD`;
    }

    // Active Preset Tag & Selector
    const activePreset = (data.game_preset || "BALANCED").toUpperCase();
    if (heroPresetTag) {
      heroPresetTag.textContent = activePreset;
    }
    if (presetSegmentBtns && presetSegmentBtns.length) {
      presetSegmentBtns.forEach(btn => {
        const p = (btn.getAttribute("data-preset") || "").toUpperCase();
        if (p === activePreset) {
          btn.classList.add("active");
        } else {
          btn.classList.remove("active");
        }
        btn.disabled = !currentForegroundPkg;
      });
    }
    const presetIdleHint = document.getElementById("preset-idle-hint");
    if (presetIdleHint) presetIdleHint.classList.toggle("hidden", !!currentForegroundPkg);

    // Badges
    if (heroStutterGov) {
      if (data.is_game) {
        heroStutterGov.textContent = "Anti-Stutter";
        heroStutterGov.style.color = "#00e676";
      } else {
        heroStutterGov.textContent = "Stock";
        heroStutterGov.style.color = "";
      }
    }

    // Gauges
    gpuBusyFill.style.width = `${Math.min(100, Math.max(0, busy))}%`;
    gpuBusyText.textContent = `${busy}%`;
    gpuMinText.textContent = `Min: ${Math.round((data.gpu?.min_clock || 260000) / 1000)} MHz`;

    tempLittle.textContent = `${Math.round((data.temp_little_mc || 0) / 1000)}°`;
    tempGpu.textContent = `${Math.round((data.temp_gpu_mc || 0) / 1000)}°`;

    // Battery Telemetry
    if (data.battery) {
      const b = data.battery;
      const battCapEl = document.getElementById("batt-capacity");
      const battStatusEl = document.getElementById("batt-status-badge");
      const battCurrentEl = document.getElementById("batt-current");
      const battVoltageEl = document.getElementById("batt-voltage");
      const battTempEl = document.getElementById("batt-temp");
      const battPowerEl = document.getElementById("batt-power");

      if (battCapEl) battCapEl.textContent = `${b.capacity_pct || 0}%`;
      if (battStatusEl) {
        if (b.bypass_charging) {
          battStatusEl.textContent = "⚡ Bypass Passthrough";
          battStatusEl.className = "battery-status-badge bypass";
        } else {
          battStatusEl.textContent = b.status || "Discharging";
          battStatusEl.className = "battery-status-badge";
          if (b.status === "Charging") battStatusEl.classList.add("charging");
        }
      }
      if (btnQuickBypass && lblQuickBypass) {
        if (b.bypass_charging) {
          btnQuickBypass.classList.add("active");
          lblQuickBypass.textContent = "PASSTHROUGH";
        } else {
          btnQuickBypass.classList.remove("active");
          lblQuickBypass.textContent = "BYPASS CHG";
        }
      }
      if (battCurrentEl) {
        const sign = b.current_now_ma > 0 ? "+" : "";
        battCurrentEl.textContent = `${sign}${b.current_now_ma || 0} mA`;
      }
      if (battVoltageEl) {
        const volts = ((b.voltage_now_mv || 0) / 1000).toFixed(2);
        battVoltageEl.textContent = `${volts} V`;
      }
      if (battTempEl) battTempEl.textContent = `${(b.temp_c || 0).toFixed(1)}°C`;
      if (battPowerEl) battPowerEl.textContent = `${(b.power_w || 0).toFixed(2)} W`;
    }

    // GOS Status
    const heroGosVal = document.getElementById("hero-gos-val");
    const heroGosChip = document.getElementById("hero-gos-chip");
    const btnQuickGosWeb = document.getElementById("btn-quick-gos-web");
    const lblQuickGosWeb = document.getElementById("lbl-quick-gos-web");
    const gosBadge = document.getElementById("gos-badge");
    const gosStatus = document.getElementById("gos-status");

    const isGosDefeated = !!data.gos_disabled;
    const hasGosPkg = data.has_gos !== false;

    const gosPanel = document.getElementById("gos-title")?.closest(".panel");
    if (gosPanel) gosPanel.style.display = hasGosPkg ? "" : "none";

    if (heroGosVal) {
      if (!hasGosPkg) {
        heroGosVal.textContent = "AOSP";
        heroGosVal.style.color = "#00e676";
      } else {
        heroGosVal.textContent = isGosDefeated ? "BYPASS" : "ACTIVE";
        heroGosVal.style.color = isGosDefeated ? "#00e676" : "#ffb020";
      }
    }
    if (heroGosChip) {
      heroGosChip.style.color = (isGosDefeated || !hasGosPkg) ? "#00e676" : "#ffb020";
    }
    if (btnQuickGosWeb && lblQuickGosWeb) {
      if (!hasGosPkg) {
        btnQuickGosWeb.classList.remove("active");
        btnQuickGosWeb.disabled = true;
        btnQuickGosWeb.style.opacity = "0.6";
        lblQuickGosWeb.textContent = "NO GOS (AOSP)";
      } else {
        btnQuickGosWeb.disabled = false;
        btnQuickGosWeb.style.opacity = "1";
        btnQuickGosWeb.classList.toggle("active", isGosDefeated);
        lblQuickGosWeb.textContent = isGosDefeated ? "GOS KILLED" : "GOS BYPASS";
      }
    }
    if (gosBadge) {
      if (!hasGosPkg) {
        gosBadge.textContent = "NOT PRESENT (AOSP)";
        gosBadge.className = "badge active";
      } else {
        gosBadge.textContent = isGosDefeated ? "BYPASS ACTIVE" : "ENFORCED";
        gosBadge.className = isGosDefeated ? "badge active" : "badge warn";
      }
    }
    if (gosStatus) {
      if (!hasGosPkg) {
        gosStatus.textContent = "Status: Samsung GOS is not installed on this ROM (AOSP/GSI). No built-in GOS throttling.";
      } else {
        gosStatus.textContent = isGosDefeated
          ? "Status: GOS disabled (no downclocking, no throttling)"
          : "Status: GOS active (GameSDK caps GPU clock and FPS)";
      }
    }

    // Daemon Meta
    if (daemonUptime) daemonUptime.textContent = `${data.daemon_uptime_s || 0}s`;
    if (snapshotStatus) snapshotStatus.textContent = data.stock_saved ? "SAVED" : "NONE";

    renderSession(data.session || {}, data.sleeping_apps || []);

    // Net Meta
    if (data.net) {
      if (data.net.interface && netIface) netIface.textContent = data.net.interface;
      if (data.net.congestion_control && netCcBadge) netCcBadge.textContent = data.net.congestion_control;
    }

    // Highlight active game card if tab-games is open
    updateActiveGameCard();

    // Render live frametime graph
    renderFrametimeGraph(data.fps || 0);
  }

  function updateActiveGameCard() {
    if (!gamesContainer) return;
    const cards = gamesContainer.querySelectorAll(".game-item");
    cards.forEach((card) => {
      const pkg = card.dataset.pkg;
      if (currentForegroundPkg && pkg === currentForegroundPkg) {
        card.classList.add("active-profile");
      } else {
        card.classList.remove("active-profile");
      }
    });
  }

  // Telemetry Poller
  let telemetryVisible = true;
  document.addEventListener("visibilitychange", () => {
    telemetryVisible = document.visibilityState === "visible";
  });

  let daemonOnline = null;

  function setDaemonActions(enabled) {
    [
      "btn-emergency-restore",
      "btn-gos-disable",
      "btn-gos-restore",
      "btn-scan-games",
      "btn-quick-bypass",
      "btn-quick-dnd",
      "btn-quick-gos-web",
      "btn-quick-ping",
      "btn-session-end",
      "btn-session-wake",
      "btn-sleep-toggle",
      "btn-sleep-save",
    ].forEach((id) => {
      const el = document.getElementById(id);
      if (!el) return;
      el.disabled = !enabled;
      if (enabled) el.removeAttribute("title");
      else el.title = "Daemon offline";
    });
  }

  function setEngineBadge(online) {
    const cluster = document.querySelector(".cluster-state");
    const label = document.querySelector(".engine-badge-text");
    if (cluster) cluster.classList.toggle("is-offline", !online);
    if (label) label.textContent = online ? "ENGINE ARMED" : "ENGINE OFFLINE";
  }

  function setDaemonOffline() {
    if (stateLabel) stateLabel.textContent = "OFFLINE";
    if (stateDot) stateDot.className = "state-dot crit";
    if (heroGame) heroGame.textContent = "Daemon offline";
    if (heroTemp) heroTemp.textContent = "--°";
    if (heroGpuClk) heroGpuClk.textContent = "--M";
    if (heroCpuClk) heroCpuClk.textContent = "--M";
    if (daemonStatus) daemonStatus.textContent = "OFFLINE";
    if (daemonStatus) daemonStatus.className = "badge danger";
    if (daemonOnline !== false) {
      daemonOnline = false;
      setDaemonActions(false);
      setEngineBadge(false);
      const statusLine = document.getElementById("gos-status");
      if (statusLine) statusLine.textContent = "Status: waiting for the daemon";
      const badge = document.getElementById("gos-badge");
      if (badge) {
        badge.textContent = "UNKNOWN";
        badge.className = "badge";
      }
      const gosVal = document.getElementById("hero-gos-val");
      if (gosVal) {
        gosVal.textContent = "--";
        gosVal.style.color = "";
      }
    }
  }

  async function pollTelemetry() {
    if (!telemetryVisible) return;
    if (previewMode) {
      const state = ["IDLE", "GAME", "WARN", "CRIT"].includes(previewState) ? previewState : "GAME";
      const mock = {
        daemon_state: state,
        game_pkg: state === "IDLE" ? "" : "com.tencent.ig",
        is_game: state !== "IDLE",
        temp_big_mc: state === "CRIT" ? 91000 : state === "WARN" ? 86000 : 48000,
        temp_little_mc: 41000,
        temp_gpu_mc: 42000,
        fps: state === "IDLE" ? 0 : 58.4,
        p1_low_fps: state === "IDLE" ? 0 : 49.2,
        gpu: {
          governor: state === "IDLE" ? "Interactive" : "Booster",
          min_clock: state === "IDLE" ? 260000 : 455000,
          current_clock: state === "IDLE" ? 385000 : 572000,
          busy_pct: state === "IDLE" ? 18.0 : 64.0,
        },
        cpu: {
          little: { gov: "energy_adaptive", min_freq: 702000, cur_freq: 1222000 },
          big: { gov: state === "IDLE" ? "energy_adaptive" : "performance", min_freq: 1040000, cur_freq: 1508000 },
        },
        net: {
          interface: "wlan0",
          congestion_control: "bbr",
        },
        battery: {
          status: "Discharging",
          capacity_pct: 74,
          current_now_ma: -420,
          voltage_now_mv: 3820,
          temp_c: 31.2,
          power_w: 1.61,
        },
        daemon_uptime_s: 128,
        stock_saved: true,
      };
      const banner = document.getElementById("preview-banner");
      if (banner) banner.classList.remove("hidden");
      renderSnapshot(mock);
      const previewBadge = document.getElementById("net-health-badge");
      if (previewBadge) {
        previewBadge.textContent = "PREVIEW";
        previewBadge.className = "badge active";
      }
      return;
    }
    let raw = await sendSocketCmd("snapshot");
    if (!raw) {
      raw = await execCmd("cat /data/local/tmp/secreto-snapshot.json 2>/dev/null");
    }
    if (raw && typeof raw === "string") {
      try {
        const snap = JSON.parse(raw);
        if (daemonOnline !== true) {
          daemonOnline = true;
          setDaemonActions(true);
          setEngineBadge(true);
          refreshGosStatus();
          refreshHudInprocStatus();
        }
        renderSnapshot(snap);
        if (daemonStatus) {
          daemonStatus.textContent = "ONLINE";
          daemonStatus.className = "badge active";
        }
        return;
      } catch (e) {
        // partial read ignored
      }
    }
    setDaemonOffline();
  }

  setInterval(pollTelemetry, 1000);
  pollTelemetry();

  // ==========================================
  // GAME LIBRARY CONTROLLER (Auto + Manual)
  // ==========================================

  // Golden-angle hue spread: adjacent games never collide on the color wheel.
  function charSumHue(str) {
    const hash = String(str || "?").split("").reduce((a, c) => a + c.charCodeAt(0), 0);
    return Math.round((hash * 137.508) % 360);
  }

  // ---- Game art (real icon / banner pulled by the native bridge) ----
  // Store artwork is fetched once per package and cached in memory, so
  // re-rendering the grid or swiping the carousel never re-decodes it.
  const gameArtCache = Object.create(null);
  const gameArtQueued = Object.create(null);
  const gameArtFailedAt = Object.create(null);

  function hexToHue(hex) {
    if (!hex || hex.charAt(0) !== "#" || hex.length < 7) return null;
    const r = parseInt(hex.slice(1, 3), 16) / 255;
    const g = parseInt(hex.slice(3, 5), 16) / 255;
    const b = parseInt(hex.slice(5, 7), 16) / 255;
    if ([r, g, b].some((v) => Number.isNaN(v))) return null;
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    if (max === min) return 210;
    const d = max - min;
    let h;
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h *= 60;
    return Math.round((h + 360) % 360);
  }

  function requestGameArt(pkg) {
    if (!pkg || gameArtCache[pkg] || gameArtQueued[pkg]) return;
    const failedAt = gameArtFailedAt[pkg];
    if (failedAt && Date.now() - failedAt < 10 * 60 * 1000) return;
    if (!window._nativeBridge || typeof window._nativeBridge.getGameArt !== "function") return;
    gameArtQueued[pkg] = true;
    try {
      window._nativeBridge.getGameArt(pkg, "__secretoApplyGameArt");
    } catch (err) {
      gameArtQueued[pkg] = false;
    }
  }

  window.__secretoApplyGameArt = function (raw) {
    let art = null;
    try {
      art = typeof raw === "string" ? JSON.parse(raw) : raw;
    } catch (err) {
      return;
    }
    if (!art || !art.pkg) return;
    gameArtQueued[art.pkg] = false;
    if (!art.cover && !art.icon) {
      gameArtFailedAt[art.pkg] = Date.now();
      return;
    }
    gameArtCache[art.pkg] = art;
    paintGameArt(art.pkg);
  };

  function clearGameArt(card) {
    card.classList.remove("card-has-art", "card-has-icon");
    const layer = card.querySelector(".card-art");
    if (layer) layer.remove();
    const icon = card.querySelector(".game-tile-icon");
    if (icon) icon.remove();
  }

  function paintGameArt(pkg) {
    const art = gameArtCache[pkg];
    if (!art) return;
    const hue = hexToHue(art.color);

    document
      .querySelectorAll(`.game-item[data-pkg="${pkg}"], .cover-card[data-pkg="${pkg}"]`)
      .forEach((card) => {
        if (hue !== null) card.style.setProperty("--tile-hue", hue);

        if (art.cover) {
          let layer = card.querySelector(".card-art");
          if (!layer) {
            layer = document.createElement("div");
            layer.className = "card-art";
            layer.setAttribute("aria-hidden", "true");
            card.insertBefore(layer, card.firstChild);
          }
          if (layer.dataset.artPkg !== pkg) {
            layer.style.backgroundImage = `url("${art.cover}")`;
            layer.dataset.artPkg = pkg;
          }
          card.classList.add("card-has-art");
          requestAnimationFrame(() => layer.classList.add("is-loaded"));
        }

        if (art.icon) {
          let holder = card.querySelector(".game-tile-icon");
          if (!holder) {
            holder = document.createElement("div");
            holder.className = "game-tile-icon";
            holder.setAttribute("aria-hidden", "true");
            const anchor = card.querySelector(".game-tile-initial");
            if (anchor) card.insertBefore(holder, anchor.nextSibling);
            else card.appendChild(holder);
          }
          if (holder.dataset.artPkg !== pkg) {
            const img = document.createElement("img");
            img.alt = "";
            img.src = art.icon;
            holder.replaceChildren(img);
            holder.dataset.artPkg = pkg;
          }
          card.classList.add("card-has-icon");
          requestAnimationFrame(() => holder.classList.add("is-loaded"));
        }
      });

    const detailIcon = document.getElementById("detail-game-icon");
    if (detailIcon && selectedGamePkg === pkg && art.icon) {
      const img = document.createElement("img");
      img.alt = "";
      img.src = art.icon;
      detailIcon.replaceChildren(img);
      detailIcon.classList.add("has-art");
    }
  }

  function hydrateGameArt(card, pkg) {
    if (gameArtCache[pkg]) {
      paintGameArt(pkg);
      return;
    }
    clearGameArt(card);
    requestGameArt(pkg);
  }

  function buildGameCardData(game) {
    const sourceClass =
      game.source === "manual"
        ? "chip-source-manual"
        : game.source === "config"
        ? "chip-source-config"
        : game.source === "auto"
        ? "chip-source-auto"
        : "chip-source-builtin";

    const sourceLabel =
      game.source === "manual"
        ? "MANUAL"
        : game.source === "config"
        ? "CONFIG"
        : game.source === "auto"
        ? "AUTO"
        : "BUILTIN";

    const currentPreset = (game.preset || "BALANCED").toUpperCase();
    const rawProfile = game.profile || "";
    const presetPrefix = `${currentPreset} \u2022 `;
    const profileText = rawProfile.toUpperCase().startsWith(presetPrefix)
      ? rawProfile.slice(presetPrefix.length)
      : rawProfile;

    const initial = (game.name || "?").trim().charAt(0).toUpperCase();
    const hue = charSumHue(game.pkg);

    return { sourceClass, sourceLabel, currentPreset, profileText, initial, hue };
  }

  function gameCardInnerHTML(game, d) {
    return `
      <div class="game-tile-top">
        <span class="game-tile-source ${d.sourceClass}">${d.sourceLabel}</span>
        <span class="game-tile-preset preset-badge preset-badge-${d.currentPreset.toLowerCase()}">${d.currentPreset}</span>
      </div>
      <div class="game-tile-initial">${d.initial}</div>
      <div class="game-tile-bottom">
        <div class="game-tile-title">${game.name}</div>
        <div class="game-tile-sub">${game.pkg}</div>
        <button class="game-play-btn inline-launch" data-pkg="${game.pkg}" aria-label="Play ${game.name}" title="Play ${game.name}">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>
          <span>Play</span>
        </button>
      </div>
    `;
  }

  function bindGameCardEvents(scope) {
    scope.querySelectorAll(".game-item").forEach((item) => {
      item.addEventListener("click", (e) => {
        if (e.target.closest("button,input,select")) return;
        openGameDetail(item.dataset.pkg);
      });
    });
    scope.querySelectorAll(".inline-launch").forEach((btn) => {
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        if (window._nativeBridge && typeof window._nativeBridge.launchGame === "function") {
          window._nativeBridge.launchGame(btn.dataset.pkg);
        } else {
          showToast("Could not launch game. Make sure it is installed.");
        }
      });
    });
  }

  function renderGamesList(games) {
    if (!gamesContainer) return;

    // Empty state handling (shared by both modes)
    if (!games || games.length === 0) {
      if (gamesViewMode === "carousel" && gamesCarouselView) {
        gamesCarouselView.classList.add("hidden");
      }
      gamesContainer.classList.remove("hidden");
      gamesContainer.className = "games-grid";
      gamesContainer.innerHTML = `
        <div class="empty-state">
          <span class="empty-state-glyph" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
              <path d="M6.5 8.5h11a3.5 3.5 0 0 1 3.4 4.3l-.8 3.4a2.4 2.4 0 0 1-4.2 1L14.4 15H9.6l-1.5 2.2a2.4 2.4 0 0 1-4.2-1l-.8-3.4A3.5 3.5 0 0 1 6.5 8.5z"/>
              <path d="M8 11.4v2M7 12.4h2M15.6 12h.01M17.4 13.4h.01"/>
            </svg>
          </span>
          <p class="empty-state-title">No games matched</p>
          <p class="empty-state-desc">Only installed game apps are listed here.</p>
        </div>`;
      return;
    }

    // Always build the grid DOM so data stays fresh when toggling modes
    gamesContainer.innerHTML = "";
    gamesContainer.className = "games-grid";
    games.forEach((game) => {
      const item = document.createElement("div");
      item.className = "game-item";
      item.dataset.pkg = game.pkg;
      if (currentForegroundPkg && game.pkg === currentForegroundPkg) {
        item.classList.add("active-profile");
      }
      const d = buildGameCardData(game);
      item.style.setProperty("--tile-hue", d.hue);
      item.innerHTML = gameCardInnerHTML(game, d);
      gamesContainer.appendChild(item);
      hydrateGameArt(item, game.pkg);
    });
    bindGameCardEvents(gamesContainer);

    // Also (re)render the carousel data model for instant toggle
    carouselFiltered = games;
    if (carouselIndex >= games.length) carouselIndex = 0;
    if (gamesViewMode === "carousel") {
      // Hide grid, show carousel, render it
      gamesContainer.classList.add("hidden");
      if (gamesCarouselView) gamesCarouselView.classList.remove("hidden");
      renderCarousel(games);
    } else {
      // Grid mode: ensure carousel is hidden
      if (gamesCarouselView) gamesCarouselView.classList.add("hidden");
      gamesContainer.classList.remove("hidden");
    }
  }

  // Touch swipe state for carousel
  let swipeStartX = 0;
  let swipeStartY = 0;
  let swipeDeltaX = 0;
  let isSwiping = false;
  let swipeMoved = false;
  let swipeLastX = 0;
  let swipeLastT = 0;
  let swipeVelocity = 0;

  function bindCarouselSwipe() {
    if (!carouselStage) return;

    carouselStage.addEventListener("touchstart", (e) => {
      if (e.touches.length !== 1) return;
      swipeStartX = e.touches[0].clientX;
      swipeStartY = e.touches[0].clientY;
      swipeLastX = swipeStartX;
      swipeLastT = Date.now();
      swipeDeltaX = 0;
      swipeVelocity = 0;
      isSwiping = false;
      swipeMoved = false;
    }, { passive: true });

    carouselStage.addEventListener("touchmove", (e) => {
      if (e.touches.length !== 1) return;
      const cx = e.touches[0].clientX;
      const dx = cx - swipeStartX;
      const dy = e.touches[0].clientY - swipeStartY;
      // Determine direction on first significant move
      if (!swipeMoved && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
        isSwiping = Math.abs(dx) > Math.abs(dy);
        swipeMoved = true;
      }
      if (isSwiping) {
        swipeDeltaX = dx;
        // Track velocity for flick detection
        const now = Date.now();
        const dt = now - swipeLastT;
        if (dt > 0) {
          swipeVelocity = (cx - swipeLastX) / dt; // px/ms
        }
        swipeLastX = cx;
        swipeLastT = now;
        e.preventDefault();
        // Live drag: update card positions in real-time
        applyDragOffset(carouselFiltered, dx);
      }
    }, { passive: false });

    carouselStage.addEventListener("touchend", () => {
      if (!swipeMoved) return;
      const threshold = 40;
      // Velocity-based flick: fast swipe (>0.6 px/ms) nudges 1 extra slide
      const fastFlick = Math.abs(swipeVelocity) > 0.6;
      // Re-enable transition for spring snap-back
      carouselStage.querySelectorAll(".cover-card").forEach((c) => {
        c.style.transition = "";
      });
      if (swipeDeltaX < -threshold) {
        // Swipe left -> next
        if (carouselFiltered.length > 0) {
          let step = fastFlick ? 2 : 1;
          carouselIndex = Math.min(carouselIndex + step, carouselFiltered.length - 1);
          layoutCarousel(0, false);
          updateHeroAndDots(carouselFiltered);
        }
      } else if (swipeDeltaX > threshold) {
        // Swipe right -> prev
        if (carouselFiltered.length > 0) {
          let step = fastFlick ? 2 : 1;
          carouselIndex = Math.max(carouselIndex - step, 0);
          layoutCarousel(0, false);
          updateHeroAndDots(carouselFiltered);
        }
      } else {
        // Not enough drag: snap back to current
        layoutCarousel(0, false);
      }
      isSwiping = false;
      swipeMoved = false;
      swipeDeltaX = 0;
      swipeVelocity = 0;
    }, { passive: true });

    // Also support mouse drag for desktop preview
    let mouseDown = false;
    let mouseStartX = 0;
    carouselStage.addEventListener("mousedown", (e) => {
      if (e.target.closest("button,input,select")) return;
      mouseDown = true;
      mouseStartX = e.clientX;
    });
    carouselStage.addEventListener("mousemove", (e) => {
      if (!mouseDown) return;
      const dx = e.clientX - mouseStartX;
      applyDragOffset(carouselFiltered, dx);
    });
    carouselStage.addEventListener("mouseup", (e) => {
      if (!mouseDown) return;
      mouseDown = false;
      const dx = e.clientX - mouseStartX;
      // Re-enable transition for snap-back
      carouselStage.querySelectorAll(".cover-card").forEach((c) => {
        c.style.transition = "";
      });
      if (Math.abs(dx) > 40) {
        if (carouselFiltered.length > 0) {
          if (dx < 0) {
            carouselIndex = Math.min(carouselIndex + 1, carouselFiltered.length - 1);
          } else {
            carouselIndex = Math.max(carouselIndex - 1, 0);
          }
          layoutCarousel(0, false);
          updateHeroAndDots(carouselFiltered);
        }
      } else {
        // Snap back
        layoutCarousel(0, false);
      }
    });
    carouselStage.addEventListener("mouseleave", () => {
      if (mouseDown) {
        mouseDown = false;
        carouselStage.querySelectorAll(".cover-card").forEach((c) => {
          c.style.transition = "";
        });
        renderCarousel(carouselFiltered);
      }
    });
  }

  // Live drag: moves cards in real-time following the finger.
  // dragPx > 0 = dragging right (prev), dragPx < 0 = dragging left (next).
  function applyDragOffset(games, dragPx) {
    if (!games || games.length === 0) return;
    layoutCarousel(dragPx, true); // true = no transition during drag
  }

  // Compute and apply transform/opacity/zIndex for every card.
  // Based on "Anatomy of Cover Flow" (godui.design):
  // - Telescoping position: first unit full spacing, rest 55% spacing
  // - rotateY clamped to ±52deg max
  // - translateZ pushes far cards back for depth
  // - Only compositor-friendly props change (x, rotateY, z, scale, opacity)
  // - Infinite wrap: with N games, offsets wrap modulo N so cards appear
  //   on both sides even with only 2 games.
  function layoutCarousel(dragPx, noTransition) {
    const cards = carouselStage.querySelectorAll(".cover-card");
    if (!cards.length) return;
    const N = cards.length;
    const SPACING = 120;
    const dragInSlots = dragPx / SPACING;
    // Clamp drag so cards don't move past the first/last game.
    // At index 0, can't drag right (no prev). At index N-1, can't drag left.
    let clampedDrag = dragInSlots;
    if (carouselIndex === 0 && dragInSlots > 0) clampedDrag = dragInSlots * 0.3; // rubber band
    if (carouselIndex === N - 1 && dragInSlots < 0) clampedDrag = dragInSlots * 0.3;
    cards.forEach((card) => {
      card.style.transition = noTransition ? "none" : "";
      const idx = parseInt(card.dataset.idx, 10);
      const offset = idx - carouselIndex + clampedDrag;
      const abs = Math.abs(offset);
      const sign = offset > 0 ? 1 : (offset < 0 ? -1 : 0);
      // Telescoping X: first unit full spacing, rest compressed to 55%
      const near = Math.min(abs, 1);
      const far = Math.max(abs - 1, 0);
      const x = sign * (near * SPACING + far * SPACING * 0.55);
      // Clamped rotateY: center=0, sides capped at ±52deg
      const rotateY = -Math.max(-1, Math.min(1, offset)) * 52;
      // translateZ: push far cards back (max 3 units)
      const z = -Math.min(abs, 3) * 60;
      // Scale: center=1, sides shrink by 8% per unit (max 3)
      const scale = 1 - Math.min(abs, 3) * 0.08;
      // Opacity: center=1, sides fade
      const opacity = abs > 2.5 ? 0 : (1 - Math.min(abs, 2.5) * 0.25);
      const zIndex = 20 - Math.round(abs);
      card.style.transform = `translate3d(${x}px, 0, ${z}px) rotateY(${rotateY}deg) scale(${scale})`;
      card.style.opacity = String(opacity);
      card.style.zIndex = String(zIndex);
      if (abs < 0.5) {
        card.classList.add("cover-card-active");
      } else {
        card.classList.remove("cover-card-active");
      }
    });
  }

  function renderCarousel(games) {
    if (!carouselStage || !carouselDots) return;
    if (!games || games.length === 0) {
      carouselStage.innerHTML = "";
      carouselDots.innerHTML = "";
      return;
    }

    if (carouselIndex >= games.length) carouselIndex = 0;

    // Build DOM only if card count changed (avoids re-creating elements
    // so CSS transitions can animate between states).
    const existing = carouselStage.querySelectorAll(".cover-card");
    if (existing.length !== games.length) {
      carouselStage.innerHTML = games.map((game, idx) => {
        const d = buildGameCardData(game);
        return `
          <div class="cover-card"
               data-pkg="${game.pkg}"
               data-idx="${idx}"
               style="--tile-hue: ${d.hue};">
            <div class="game-tile-top">
              <span class="game-tile-source ${d.sourceClass}">${d.sourceLabel}</span>
              <span class="game-tile-preset preset-badge preset-badge-${d.currentPreset.toLowerCase()}">${d.currentPreset}</span>
            </div>
            <div class="game-tile-initial">${d.initial}</div>
            <div class="game-tile-bottom">
              <div class="game-tile-title">${game.name}</div>
              <div class="game-tile-sub">${game.pkg}</div>
            </div>
          </div>
        `;
      }).join("");

      // Bind click on cover cards once.
      carouselStage.querySelectorAll(".cover-card").forEach((card) => {
        card.addEventListener("click", (e) => {
          if (e.target.closest("button,input,select")) return;
          if (swipeMoved) return;
          const idx = parseInt(card.dataset.idx, 10);
          if (idx === carouselIndex) {
            openGameDetail(card.dataset.pkg);
          } else {
            carouselIndex = idx;
            layoutCarousel(0, false);
            updateHeroAndDots(games);
          }
        });
      });
      bindGameCardEvents(carouselStage);
      carouselStage.querySelectorAll(".cover-card").forEach((card) => {
        hydrateGameArt(card, card.dataset.pkg);
      });
    } else {
      // Update card content/data in-place (game list same length but
      // might have different packages after rescan).
      games.forEach((game, idx) => {
        const card = carouselStage.querySelector(`.cover-card[data-idx="${idx}"]`);
        if (!card) return;
        const d = buildGameCardData(game);
        card.dataset.pkg = game.pkg;
        card.style.setProperty("--tile-hue", d.hue);
        card.querySelector(".game-tile-source").className = "game-tile-source " + d.sourceClass;
        card.querySelector(".game-tile-source").textContent = d.sourceLabel;
        const preset = card.querySelector(".game-tile-preset");
        preset.textContent = d.currentPreset;
        preset.className = "game-tile-preset preset-badge preset-badge-" + d.currentPreset.toLowerCase();
        card.querySelector(".game-tile-initial").textContent = d.initial;
        card.querySelector(".game-tile-title").textContent = game.name;
        card.querySelector(".game-tile-sub").textContent = game.pkg;
        hydrateGameArt(card, game.pkg);
      });
    }

    // Apply transforms with transition (animated snap)
    layoutCarousel(0, false);
    updateHeroAndDots(games);
  }

  function updateHeroAndDots(games) {
    // Update hero showcase
    const activeGame = games[carouselIndex];
    if (activeGame) {
      const d = buildGameCardData(activeGame);
      if (carouselHero) carouselHero.style.setProperty("--tile-hue", d.hue);
      if (carouselHeroTitle) carouselHeroTitle.textContent = activeGame.name || activeGame.pkg;
      if (carouselHeroSub) carouselHeroSub.textContent = activeGame.pkg;
      if (carouselHeroPreset) {
        carouselHeroPreset.textContent = d.currentPreset;
        carouselHeroPreset.className = "carousel-hero-preset preset-badge preset-badge-" + d.currentPreset.toLowerCase();
      }
      if (carouselHeroPlay) carouselHeroPlay.dataset.pkg = activeGame.pkg;
    }

    carouselDots.innerHTML = games.map((_, idx) => `
      <button class="carousel-dot ${idx === carouselIndex ? "active" : ""}" data-idx="${idx}" aria-label="Go to game ${idx + 1}"></button>
    `).join("");

    carouselDots.querySelectorAll(".carousel-dot").forEach((dot) => {
      dot.addEventListener("click", () => {
        carouselIndex = parseInt(dot.dataset.idx, 10);
        layoutCarousel(0, false);
        updateHeroAndDots(games);
      });
    });

    // Hero Play button (rebind each time since games array may change)
    if (carouselHeroPlay) {
      carouselHeroPlay.addEventListener("click", () => {
        const pkg = carouselHeroPlay.dataset.pkg;
        if (!pkg) return;
        if (window._nativeBridge && typeof window._nativeBridge.launchGame === "function") {
          window._nativeBridge.launchGame(pkg);
        } else {
          showToast("Could not launch game. Make sure it is installed.");
        }
      });
    }

    // Hero Tune button opens detail
    if (carouselHeroTune) {
      carouselHeroTune.addEventListener("click", () => {
        const activeCard = carouselStage.querySelector(".cover-card-active");
        if (activeCard) openGameDetail(activeCard.dataset.pkg);
      });
    }
  }

  function setGamesViewMode(mode) {
    gamesViewMode = mode;
    if (mode === "carousel") {
      if (gamesCarouselView) gamesCarouselView.classList.remove("hidden");
      gamesContainer.classList.add("hidden");
      if (btnViewToggle) {
        btnViewToggle.innerHTML = viewToggleIcons.grid;
        btnViewToggle.dataset.mode = "carousel";
      }
      renderCarousel(carouselFiltered.length ? carouselFiltered : allGames);
    } else {
      if (gamesCarouselView) gamesCarouselView.classList.add("hidden");
      gamesContainer.classList.remove("hidden");
      if (btnViewToggle) {
        btnViewToggle.innerHTML = viewToggleIcons.carousel;
        btnViewToggle.dataset.mode = "grid";
      }
    }
  }

  const viewToggleIcons = {
    grid: '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>',
    carousel: '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="3" width="12" height="18" rx="2.5"/><path d="M2.5 8v8M21.5 8v8"/></svg>'
  };

  function applyFilter() {
    renderGamesList(allGames);
  }

  const gamesListView = document.getElementById("games-list-view");
  const gameDetailView = document.getElementById("game-detail-view");
  const gamesToolbar = document.getElementById("games-toolbar");
  let selectedGamePkg = "";

  function openGameDetail(pkg) {
    const game = (allGames || []).find((g) => g.pkg === pkg);
    if (!game) return;
    selectedGamePkg = pkg;
    document.documentElement.dataset.profile = (game.preset || "balanced").toLowerCase();
    const icon = document.getElementById("detail-game-icon");
    const name = document.getElementById("detail-game-name");
    const p = document.getElementById("detail-game-pkg");
    if (icon) icon.textContent = (game.name || "?").trim().charAt(0).toUpperCase();
    if (icon) icon.classList.remove("has-art");
    if (name) name.textContent = game.name || pkg;
    if (p) p.textContent = pkg;
    const bbrBtn = document.getElementById("btn-detail-bbr");
    const bbrDesc = document.getElementById("bbr-status-desc");
    const isBbr = !!game.auto_bbr;
    if (bbrBtn) {
      bbrBtn.textContent = isBbr ? "BBR ON" : "BBR OFF";
      bbrBtn.classList.toggle("on", isBbr);
      bbrBtn.classList.toggle("off", !isBbr);
    }
    if (bbrDesc) bbrDesc.textContent = isBbr ? "Applied on launch • Restored on exit" : "Kernel support verified • Off";
    document.querySelectorAll(".detail-preset").forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.preset === (game.preset || "").toLowerCase());
    });
    if (gamesListView) gamesListView.classList.add("hidden");
    if (gamesToolbar) gamesToolbar.classList.add("hidden");
    if (addGameModal) addGameModal.classList.add("hidden");
    if (gameDetailView) gameDetailView.classList.remove("hidden");
    setReportsExpanded(false);
    loadGameReports(pkg);
    loadRenderScale(pkg);
    requestGameArt(pkg);
    paintGameArt(pkg);
  }

  function closeGameDetail() {
    selectedGamePkg = "";
    if (gameDetailView) gameDetailView.classList.add("hidden");
    if (gamesListView) gamesListView.classList.remove("hidden");
    if (gamesToolbar) gamesToolbar.classList.remove("hidden");
  }

  document.getElementById("btn-close-game-detail")?.addEventListener("click", closeGameDetail);
  document.getElementById("btn-toggle-reports")?.addEventListener("click", () => {
    setReportsExpanded(!reportsExpanded);
  });

  document.querySelectorAll(".detail-preset").forEach((btn) => {
    btn.addEventListener("click", async () => {
      if (!selectedGamePkg) return;
      const preset = btn.dataset.preset.toUpperCase();
      document.documentElement.dataset.profile = preset.toLowerCase();
      const game = (allGames || []).find((g) => g.pkg === selectedGamePkg);
      const bbr = game?.auto_bbr ? "1" : "0";
      const res = await sendSocketCmd(`set_game_profile ${selectedGamePkg} ${preset} ${bbr}`);
      try {
        const data = JSON.parse(res);
        if (data.games) allGames = data.games;
      } catch (e) {}
      await loadGames();
      openGameDetail(selectedGamePkg);
    });
  });

  document.getElementById("btn-detail-bbr")?.addEventListener("click", async () => {
    if (!selectedGamePkg) return;
    const game = (allGames || []).find((g) => g.pkg === selectedGamePkg);
    if (!game) return;
    const preset = (game.preset || "BALANCED").toUpperCase();
    const newBbr = game.auto_bbr ? "0" : "1";
    const res = await sendSocketCmd(`set_game_profile ${selectedGamePkg} ${preset} ${newBbr}`);
    try {
      const data = JSON.parse(res);
      if (data.games) allGames = data.games;
    } catch (e) {}
    await loadGames();
    openGameDetail(selectedGamePkg);
  });

  document.querySelectorAll(".detail-scale").forEach((btn) => {
    btn.addEventListener("click", async () => {
      if (!selectedGamePkg || !renderStatus?.supported) return;
      const mode = btn.dataset.scale;
      btn.disabled = true;
      const raw = await sendSocketCmd(`set_render_scale ${selectedGamePkg} ${mode}`);
      btn.disabled = false;
      try {
        const j = JSON.parse(raw);
        if (j.status === "ok" && j.render) renderStatus = j.render;
      } catch (e) {}
      paintRenderScale(selectedGamePkg);
    });
  });

  document.getElementById("btn-detail-launch")?.addEventListener("click", async (e) => {
    if (!selectedGamePkg) return;
    const btn = e.currentTarget;
    const game = (allGames || []).find((g) => g.pkg === selectedGamePkg);
    const preset = (game?.preset || "BALANCED").toUpperCase();
    btn.disabled = true;
    btn.textContent = "APPLYING...";
    const res = await sendSocketCmd(`apply_preset ${preset}`);
    let state = "fail";
    try { const data = JSON.parse(res); state = data.status || "fail"; } catch (err) {}
    if (state === "ok") btn.textContent = "✓ PROFILE APPLIED";
    else if (state === "idle") btn.textContent = "✓ SAVED FOR NEXT LAUNCH";
    else btn.textContent = "✕ APPLY FAILED";
    setTimeout(() => {
      btn.disabled = false;
      btn.textContent = "Apply Profile";
    }, 2200);
  });

  document.getElementById("btn-detail-remove")?.addEventListener("click", async () => {
    if (!selectedGamePkg) return;
    const res = await sendSocketCmd(`remove_game ${selectedGamePkg}`);
    let ok = false;
    try {
      const data = JSON.parse(res);
      if (data.status === "ok" && data.games) {
        allGames = data.games;
        ok = true;
      }
    } catch (e) {}
    closeGameDetail();
    applyFilter();
    showToast(ok ? "Game removed from library" : "Failed to remove game");
  });

  async function loadGames() {
    if (previewMode) {
      allGames = [
        { pkg: "com.tencent.ig", name: "PUBG Mobile", source: "builtin", profile: "Booster 572M • CPU4 1.50G", auto_bbr: true, preset: "PERFORMANCE" },
        { pkg: "com.mobile.legends", name: "Mobile Legends: Bang Bang", source: "builtin", profile: "Booster 455M • CPU4 1.35G", auto_bbr: false, preset: "BALANCED" },
        { pkg: "com.dts.freefireth", name: "Free Fire", source: "auto", profile: "Dynamic 385M • CPU4 1.25G", auto_bbr: false, preset: "BATTERY" },
        { pkg: "com.miHoYo.Yuanshen", name: "Genshin Impact", source: "manual", profile: "Booster 683M • CPU4 1.50G", auto_bbr: true, preset: "ESPORTS" }
      ];
      applyFilter();
      return;
    }
    const res = await sendSocketCmd("list_games");
    if (res) {
      try {
        const data = JSON.parse(res);
        if (Array.isArray(data)) {
          allGames = data;
          applyFilter();
          return;
        }
      } catch (e) {
        console.error("parse games error:", e);
      }
    }
    // Daemon offline: do not show fake or uninstalled game entries.
    allGames = [];
    if (gamesContainer) {
      gamesContainer.innerHTML = `
      <div class="empty-state">
        <span class="empty-state-glyph" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 7v6M9 11h6"/>
            <path d="M6.5 4h11A2.5 2.5 0 0 1 20 6.5v11a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 17.5v-11A2.5 2.5 0 0 1 6.5 4z"/>
          </svg>
        </span>
        <p class="empty-state-title">Daemon offline</p>
        <p class="empty-state-desc">Start <code>secretod</code> on the device. This screen reconnects on its own.</p>
      </div>`;
    }
  }

  async function loadInstalledApps() {
    if (installedApps.length > 0) {
      populateAppSelect();
      return;
    }
    // Prefer native bridge (PackageManager) for real app labels
    if (window._nativeBridge && typeof window._nativeBridge.getInstalledApps === "function") {
      try {
        const raw = window._nativeBridge.getInstalledApps();
        if (raw) {
          const apps = JSON.parse(raw);
          if (Array.isArray(apps) && apps.length > 0) {
            installedApps = apps.map((a) => ({ pkg: a.pkg, label: a.label || a.pkg }));
            populateAppSelect();
            return;
          }
        }
      } catch (e) {
        console.error("native getInstalledApps error:", e);
      }
    }
    // Fallback: daemon list_installed_apps
    const res = await sendSocketCmd("list_installed_apps");
    if (res) {
      try {
        const apps = JSON.parse(res);
        if (Array.isArray(apps)) {
          installedApps = apps.map((a) => {
            if (typeof a === "string") return { pkg: a, label: a };
            return { pkg: a.pkg, label: a.label || a.pkg };
          });
          populateAppSelect();
        }
      } catch (e) {
        console.error("parse installed apps error:", e);
      }
    }
  }

  function populateAppSelect() {
    if (!installedAppsSelect) return;
    installedAppsSelect.innerHTML = '<option value="">-- Select app --</option>';
    installedApps.forEach((app) => {
      const opt = document.createElement("option");
      opt.value = app.pkg;
      opt.textContent = app.label && app.label !== app.pkg
        ? `${app.label} (${app.pkg})`
        : app.pkg;
      installedAppsSelect.appendChild(opt);
    });
  }

  // Scan device button
  btnScanGames?.addEventListener("click", async () => {
    btnScanGames.disabled = true;
    const res = await sendSocketCmd("scan_games");
    btnScanGames.disabled = false;
    if (res) {
      try {
        const data = JSON.parse(res);
        if (data.games) {
          allGames = data.games;
          applyFilter();
          showToast("Game library refreshed");
          return;
        }
      } catch (e) {
        console.error("scan parse error:", e);
      }
    }
    await loadGames();
    showToast("Game library refreshed");
  });

  // Toggle Add Modal
  btnToggleAddDrawer?.addEventListener("click", () => {
    addGameModal?.classList.toggle("hidden");
    if (!addGameModal?.classList.contains("hidden")) {
      loadInstalledApps();
    }
  });

  btnCancelAddGame?.addEventListener("click", () => {
    addGameModal?.classList.add("hidden");
  });

  // Tapping the backdrop closes the sheet.
  addGameModal?.addEventListener("click", (e) => {
    if (e.target === addGameModal) {
      addGameModal.classList.add("hidden");
    }
  });

  installedAppsSelect?.addEventListener("change", () => {
    if (installedAppsSelect.value) {
      customPkgInput.value = installedAppsSelect.value;
    }
  });

  btnSubmitAddGame?.addEventListener("click", async () => {
    const pkg = (customPkgInput?.value || "").trim();
    if (!pkg) {
      showToast("Enter a package name first.");
      return;
    }
    btnSubmitAddGame.disabled = true;
    btnSubmitAddGame.textContent = "Saving...";
    const res = await sendSocketCmd(`add_game ${pkg}`);
    btnSubmitAddGame.disabled = false;
    btnSubmitAddGame.textContent = "Add game";
    if (res) {
      try {
        const data = JSON.parse(res);
        if (data.status === "ok" && data.games) {
          allGames = data.games;
          applyFilter();
          customPkgInput.value = "";
          installedAppsSelect.value = "";
          addGameModal?.classList.add("hidden");
          showToast("Game added successfully.");
          return;
        } else if (data.status === "error") {
          showToast(`Failed: ${data.message}`);
          return;
        }
      } catch (e) {
        console.error("add game parse error:", e);
      }
    }
    await loadGames();
    addGameModal?.classList.add("hidden");
    showToast("Game added.");
  });

  // View mode toggle (grid <-> carousel)
  if (btnViewToggle) {
    btnViewToggle.innerHTML = viewToggleIcons.carousel;
    btnViewToggle.dataset.mode = "grid";
    btnViewToggle.addEventListener("click", () => {
      setGamesViewMode(btnViewToggle.dataset.mode === "grid" ? "carousel" : "grid");
    });
  }

  // Bind carousel swipe gestures (safe to call once; stage re-renders but
  // the touch listeners live on the parent element).
  bindCarouselSwipe();

  // Initial load of games
  loadGames();

  // ==========================================
  // ACTION BUTTONS (Boost, Network, Safety)
  // ==========================================

  // Action: Quick Gaming Dock
  let bypassActive = false;
  // Keep quick toggles in sync with the daemon snapshot, so a reopen
  // always reflects the real last state instead of a hardcoded default.
  function renderBypassState() {
    if (btnQuickBypass) btnQuickBypass.classList.toggle("active", bypassActive);
    if (lblQuickBypass) lblQuickBypass.textContent = bypassActive ? "PASSTHROUGH" : "BYPASS CHG";
  }
  function renderDndState() {
    if (btnQuickDnd) btnQuickDnd.classList.toggle("active", dndActive);
    if (lblQuickDnd) lblQuickDnd.textContent = dndActive ? "FOCUS DND" : "NOTIF ON";
  }
  if (btnQuickBypass) {
    btnQuickBypass.addEventListener("click", async () => {
      bypassActive = !bypassActive;
      renderBypassState();
      await sendSocketCmd(`set_bypass_charge ${bypassActive ? 1 : 0}`);
    });
  }

  let dndActive = true;
  if (btnQuickDnd) {
    btnQuickDnd.addEventListener("click", async () => {
      dndActive = !dndActive;
      renderDndState();
      await sendSocketCmd(`set_heads_up ${dndActive ? 0 : 1}`);
    });
  }

  if (btnQuickGosWeb) {
    btnQuickGosWeb.addEventListener("click", async () => {
      const targetDisable = !btnQuickGosWeb.classList.contains("active");
      btnQuickGosWeb.classList.toggle("active", targetDisable);
      if (lblQuickGosWeb) lblQuickGosWeb.textContent = targetDisable ? "GOS KILLED" : "GOS BYPASS";
      await sendSocketCmd(targetDisable ? "gos_disable" : "gos_restore");
      setTimeout(refreshGosStatus, 300);
    });
  }

  // In-process HUD (Zygisk) toggle: flips /data/adb/secreto_space/inproc_enable.
  function refreshHudInprocStatus(res) {
    const apply = (j) => {
      if (!j || j.status !== "ok") return;
      const on = !!j.enabled;
      if (btnQuickHud) btnQuickHud.classList.toggle("active", on);
      if (lblQuickHud) lblQuickHud.textContent = on ? "HUD-IN ON" : "HUD-IN OFF";
    };
    if (res && typeof res === "string") {
      try { apply(JSON.parse(res)); } catch (e) {}
    } else {
      sendSocketCmd("hud_inproc").then((r) => { try { apply(JSON.parse(r)); } catch (e) {} });
    }
  }

  if (btnQuickHud) {
    btnQuickHud.addEventListener("click", async () => {
      const res = await sendSocketCmd("hud_inproc toggle");
      refreshHudInprocStatus(res);
    });
  }

  if (presetSegmentBtns && presetSegmentBtns.length) {
    presetSegmentBtns.forEach(btn => {
      btn.addEventListener("click", async () => {
        const targetPreset = btn.getAttribute("data-preset");
        presetSegmentBtns.forEach(b => b.classList.remove("active"));
        btn.classList.add("active");
        if (heroPresetTag) heroPresetTag.textContent = targetPreset;
        if (currentForegroundPkg) {
          await sendSocketCmd(`set_game_profile ${currentForegroundPkg} ${targetPreset}`);
        } else {
          await sendSocketCmd(`apply_preset ${targetPreset}`);
        }
      });
    });
  }

  // Action: Network BBR & Stock
  const btnNetStock = document.getElementById("btn-net-stock");
  btnNetStock?.addEventListener("click", async () => {
    btnNetStock.textContent = "APPLYING...";
    await sendSocketCmd("net_restore");
    setTimeout(() => (btnNetStock.textContent = "Stock (Westwood)"), 1000);
  });

  const btnNetBbr = document.getElementById("btn-net-bbr");
  btnNetBbr?.addEventListener("click", async () => {
    btnNetBbr.textContent = "APPLYING...";
    await sendSocketCmd("net_apply_bbr");
    setTimeout(() => (btnNetBbr.textContent = "Enable BBR"), 1000);
  });

  // Action: Network Command Center (Ping Engine)
  let activeTarget = "1.1.1.1";
  const pingSamplesHistory = [];
  const MAX_PING_SAMPLES = 30;

  const netTargetDisplay = document.getElementById("net-target-display");
  const netHealthBadge = document.getElementById("net-health-badge");
  const netLiveJitter = document.getElementById("net-live-jitter");
  const netPingCanvas = document.getElementById("net-ping-canvas");
  const pingCircleEl = document.getElementById("ping-circle-el");

  const statMin = document.getElementById("stat-min");
  const statAvg = document.getElementById("stat-avg");
  const statMax = document.getElementById("stat-max");
  const statJitter = document.getElementById("stat-jitter");
  const statP50 = document.getElementById("stat-p50");
  const statP95 = document.getElementById("stat-p95");
  const statP99 = document.getElementById("stat-p99");
  const statLoss = document.getElementById("stat-loss");

  const chipGwVal = document.getElementById("chip-gw-val");
  const chipCfVal = document.getElementById("chip-cf-val");
  const chipGgVal = document.getElementById("chip-gg-val");
  const chipCustomVal = document.getElementById("chip-custom-val");

  const btnMultiPing = document.getElementById("btn-multi-ping");
  const multiPingRows = document.querySelectorAll(".multi-ping-row");
  const mpRefs = {
    gateway: {
      host: document.getElementById("mp-gw-host"),
      val: document.getElementById("mp-gw-val"),
      sub: document.getElementById("mp-gw-sub"),
    },
    cloudflare: {
      host: document.getElementById("mp-cf-host"),
      val: document.getElementById("mp-cf-val"),
      sub: document.getElementById("mp-cf-sub"),
    },
    google: {
      host: document.getElementById("mp-gg-host"),
      val: document.getElementById("mp-gg-val"),
      sub: document.getElementById("mp-gg-sub"),
    },
    custom: {
      host: document.getElementById("mp-custom-host"),
      val: document.getElementById("mp-custom-val"),
      sub: document.getElementById("mp-custom-sub"),
    },
  };
  let gatewayHost = "192.168.1.1";
  let lastMultiPing = null;
  let multiPingDone = false;
  let activeMultiKey = "gateway";

  const customTargetBox = document.getElementById("custom-target-box");
  const customTargetInput = document.getElementById("custom-target-input");
  const btnSetCustomTarget = document.getElementById("btn-set-custom-target");

  const targetChips = document.querySelectorAll(".target-chip");
  targetChips.forEach((chip) => {
    chip.addEventListener("click", () => {
      targetChips.forEach((c) => c.classList.remove("active"));
      chip.classList.add("active");
      const targetType = chip.dataset.target;
      if (targetType === "custom") {
        customTargetBox.classList.remove("hidden");
        if (customTargetInput.value.trim()) {
          activeTarget = customTargetInput.value.trim();
        }
      } else {
        customTargetBox.classList.add("hidden");
        if (targetType === "gw") {
          activeTarget = gatewayHost;
        } else {
          activeTarget = targetType;
        }
      }
      if (netTargetDisplay) netTargetDisplay.textContent = activeTarget;
      runProbe(activeTarget, 3);
    });
  });

  btnSetCustomTarget?.addEventListener("click", () => {
    const customHost = customTargetInput.value.trim();
    if (customHost) {
      activeTarget = customHost;
      if (netTargetDisplay) netTargetDisplay.textContent = activeTarget;
      runProbe(activeTarget, 3);
    }
  });

  function renderPingTimeline(samples) {
    if (!netPingCanvas) return;
    const ctx = netPingCanvas.getContext("2d");
    if (!ctx) return;

    const dpr = window.devicePixelRatio || 1;
    const rect = netPingCanvas.getBoundingClientRect();
    const w = rect.width || 280;
    const h = 60;

    if (netPingCanvas.width !== Math.floor(w * dpr) || netPingCanvas.height !== Math.floor(h * dpr)) {
      netPingCanvas.width = Math.floor(w * dpr);
      netPingCanvas.height = Math.floor(h * dpr);
    }

    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w, h);

    if (!samples || samples.length === 0) {
      ctx.fillStyle = "rgba(255, 255, 255, 0.2)";
      ctx.font = "10px monospace";
      ctx.textAlign = "center";
      ctx.fillText("No ping samples yet", w / 2, h / 2 + 3);
      ctx.restore();
      return;
    }

    const maxPing = Math.max(80, ...samples) * 1.15;
    const step = w / Math.max(samples.length - 1, 1);

    // 50ms reference line
    const ref50Y = h - (50 / maxPing) * (h - 10);
    ctx.strokeStyle = "rgba(255, 176, 32, 0.3)";
    ctx.lineWidth = 1;
    ctx.setLineDash([3, 3]);
    ctx.beginPath();
    ctx.moveTo(0, ref50Y);
    ctx.lineTo(w, ref50Y);
    ctx.stroke();
    ctx.setLineDash([]);

    // Path
    ctx.beginPath();
    samples.forEach((val, idx) => {
      const x = idx * step;
      const y = h - (val / maxPing) * (h - 10);
      if (idx === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.strokeStyle = "#00e5ff";
    ctx.lineWidth = 2;
    ctx.stroke();

    // Sample dots
    samples.forEach((val, idx) => {
      const x = idx * step;
      const y = h - (val / maxPing) * (h - 10);
      ctx.fillStyle = val < 35 ? "#00e676" : val < 75 ? "#ffb020" : "#ff4d5e";
      ctx.beginPath();
      ctx.arc(x, y, 3, 0, Math.PI * 2);
      ctx.fill();
    });

    ctx.restore();
  }

  const netPingValEl = document.getElementById("net-ping-val");

  function setActionState(button, busyText, busy) {
    if (!button) return;
    button.disabled = busy;
    button.classList.toggle("is-busy", busy);
    if (busy) {
      button.dataset.readyText = button.textContent;
      button.textContent = busyText;
    } else if (button.dataset.readyText) {
      button.textContent = button.dataset.readyText;
      delete button.dataset.readyText;
    }
  }

  let progressTimer = null;
  let progressStarted = 0;

  function showProgress(label, detail) {
    const node = document.getElementById("operation-status");
    if (!node) return;
    clearInterval(progressTimer);
    progressStarted = Date.now();
    node.classList.remove("hidden");
    node.querySelector("strong").textContent = label;
    node.querySelector(".operation-copy span").textContent = detail;
    node.querySelector(".operation-time").textContent = "0s";
    node.querySelector(".operation-progress i").style.width = "8%";
    progressTimer = setInterval(() => {
      const seconds = Math.floor((Date.now() - progressStarted) / 1000);
      node.querySelector(".operation-time").textContent = `${seconds}s`;
      node.querySelector(".operation-progress i").style.width = `${Math.min(92, 8 + seconds * 5)}%`;
    }, 250);
  }

  function hideProgress() {
    clearInterval(progressTimer);
    progressTimer = null;
    document.getElementById("operation-status")?.classList.add("hidden");
  }

  function withTimeout(promise, ms) {
    return Promise.race([
      promise,
      new Promise((resolve) => setTimeout(() => resolve(null), ms)),
    ]);
  }

  async function runProbe(host, count = 3) {
    setActionState(btnQuickPing, "Testing…", true);
    showProgress("Testing connection", `${count} probes • ${host}`);
    if (netPingValEl) netPingValEl.textContent = "…";
    try {
      const res = await withTimeout(sendSocketCmd(`net_ping ${host} ${count}`), 10000);
      if (res && typeof res === "string") {
        try {
          const p = JSON.parse(res);
          applyPingResult(p);
        } catch (e) {
          console.error("parse ping error:", e);
        }
      } else {
        if (netPingValEl) netPingValEl.textContent = "--";
        if (netHealthBadge) {
          netHealthBadge.textContent = "OFFLINE";
          netHealthBadge.className = "badge danger";
        }
      }
    } finally {
      setActionState(btnQuickPing, "Testing…", false);
      hideProgress();
    }
  }

  function applyPingResult(p) {
    if (netPingValEl) netPingValEl.textContent = Math.round(p.avg_ms || 0);
    if (statMin) statMin.textContent = `${(p.min_ms || 0).toFixed(1)} ms`;
    if (statAvg) statAvg.textContent = `${(p.avg_ms || 0).toFixed(1)} ms`;
    if (statMax) statMax.textContent = `${(p.max_ms || 0).toFixed(1)} ms`;
    if (statJitter) statJitter.textContent = `${(p.jitter_ms || 0).toFixed(1)} ms`;
    if (statP50) statP50.textContent = `${(p.p50_ms || 0).toFixed(1)} ms`;
    if (statP95) statP95.textContent = `${(p.p95_ms || 0).toFixed(1)} ms`;
    if (statP99) statP99.textContent = `${(p.p99_ms || 0).toFixed(1)} ms`;
    if (statLoss) statLoss.textContent = `${Math.round(p.loss_pct || 0)}%`;
    if (netLiveJitter) netLiveJitter.textContent = `Jitter: ${(p.jitter_ms || 0).toFixed(1)} ms`;

    // Health badge
    if (netHealthBadge) {
      if (p.loss_pct > 10.0) {
        netHealthBadge.textContent = "PACKET LOSS";
        netHealthBadge.className = "badge danger";
      } else if (p.jitter_ms > 20.0 || (p.spike_ms && p.spike_ms > 40.0)) {
        netHealthBadge.textContent = "JITTER SPIKE";
        netHealthBadge.className = "badge danger";
      } else if (p.avg_ms < 40.0) {
        netHealthBadge.textContent = "OPTIMAL";
        netHealthBadge.className = "badge active";
      } else {
        netHealthBadge.textContent = "STABLE";
        netHealthBadge.className = "badge";
      }
    }

    // Color code circle
    if (pingCircleEl) {
      if (p.avg_ms < 35) pingCircleEl.style.borderColor = "var(--accent-green)";
      else if (p.avg_ms < 75) pingCircleEl.style.borderColor = "var(--accent-cyan)";
      else pingCircleEl.style.borderColor = "var(--accent-red)";
    }

    if (p.samples && p.samples.length > 0) {
      p.samples.forEach((s) => {
        pingSamplesHistory.push(s);
        if (pingSamplesHistory.length > MAX_PING_SAMPLES) pingSamplesHistory.shift();
      });
      renderPingTimeline(pingSamplesHistory);
    }

    if (p.host) updateChipForHost(p.host, p.avg_ms);
  }

  function formatPingValue(v) {
    return Number.isFinite(v) && v > 0 ? `${Math.round(v)} ms` : "--";
  }

  function updateChipForHost(host, avgMs) {
    if (!host) return;
    const text = formatPingValue(avgMs);
    if (host === gatewayHost) {
      if (chipGwVal) chipGwVal.textContent = text;
    } else if (host === "1.1.1.1") {
      if (chipCfVal) chipCfVal.textContent = text;
    } else if (host === "8.8.8.8") {
      if (chipGgVal) chipGgVal.textContent = text;
    } else if (chipCustomVal) {
      chipCustomVal.textContent = text;
    }
  }

  function formatMultiSub(p) {
    if (!p || !p.host) return "";
    const parts = [];
    if (p.loss_pct != null) parts.push(`${Math.round(p.loss_pct)}% loss`);
    if (p.jitter_ms != null) parts.push(`${p.jitter_ms.toFixed(1)} jitter`);
    return parts.join(" · ");
  }

  function renderMultiPing(data) {
    const entries = [
      ["gateway", data.gateway],
      ["cloudflare", data.cloudflare],
      ["google", data.google],
      ["custom", data.custom],
    ];

    entries.forEach(([key, p]) => {
      const refs = mpRefs[key];
      if (!refs) return;
      if (!p || !p.host) {
        if (key === "custom") refs.host.textContent = "not set";
        refs.val.textContent = "--";
        refs.sub.textContent = "";
        return;
      }
      if (key === "gateway") gatewayHost = p.host;
      refs.host.textContent = p.host;
      refs.val.textContent = formatPingValue(p.avg_ms);
      refs.sub.textContent = formatMultiSub(p);
      updateChipForHost(p.host, p.avg_ms);
    });
  }

  async function runMultiPing() {
    if (!btnMultiPing) return;
    const customHost = customTargetInput ? customTargetInput.value.trim() : "";
    setActionState(btnMultiPing, "Testing…", true);
    showProgress("Multi-target ping", `Gateway, Cloudflare, Google${customHost ? ", " + customHost : ""}`);
    try {
      const cmd = customHost ? `net_multi_ping 3 ${customHost}` : "net_multi_ping 3";
      const res = await withTimeout(sendSocketCmd(cmd), 30000);
      if (res && typeof res === "string") {
        try {
          const data = JSON.parse(res);
          lastMultiPing = data;
          if (data.gateway && data.gateway.host) gatewayHost = data.gateway.host;
          renderMultiPing(data);

          const entry = multiEntryForRow(activeMultiKey);
          if (entry && entry.host) {
            activeTarget = entry.host;
            if (netTargetDisplay) netTargetDisplay.textContent = activeTarget;
            applyPingResult(entry);
          }
        } catch (e) {
          console.error("parse multi ping error:", e);
        }
      } else if (netHealthBadge) {
        netHealthBadge.textContent = "OFFLINE";
        netHealthBadge.className = "badge danger";
      }
    } finally {
      setActionState(btnMultiPing, "Testing…", false);
      hideProgress();
    }
  }

  function multiEntryForRow(key) {
    if (!lastMultiPing) return null;
    if (key === "gateway") return lastMultiPing.gateway;
    if (key === "cloudflare") return lastMultiPing.cloudflare;
    if (key === "google") return lastMultiPing.google;
    return lastMultiPing.custom;
  }

  multiPingRows.forEach((row) => {
    row.addEventListener("click", () => {
      const key = row.dataset.target;
      if (key === "custom") {
        const host = customTargetInput ? customTargetInput.value.trim() : "";
        if (!host) {
          customTargetBox?.classList.remove("hidden");
          customTargetInput?.focus();
          return;
        }
        activeTarget = host;
      } else if (key === "gateway") {
        activeTarget = gatewayHost;
      } else {
        activeTarget = key;
      }

      multiPingRows.forEach((r) => r.classList.toggle("active", r === row));
      activeMultiKey = key;
      const chipTarget = key === "gateway" ? "gw" : key;
      targetChips.forEach((c) => c.classList.toggle("active", c.dataset.target === chipTarget));

      if (netTargetDisplay) netTargetDisplay.textContent = activeTarget;
      const entry = multiEntryForRow(key);
      if (entry && entry.host) applyPingResult(entry);
    });
  });

  btnMultiPing?.addEventListener("click", () => {
    multiPingDone = true;
    runMultiPing();
  });

  // Quick Probe Button
  const btnQuickPing = document.getElementById("btn-quick-ping");
  btnQuickPing?.addEventListener("click", () => runProbe(activeTarget, 3));

  // Bufferbloat Test
  const btnRunBufferbloat = document.getElementById("btn-run-bufferbloat");
  const bbUnloadedVal = document.getElementById("bb-unloaded-val");
  const bbLoadedVal = document.getElementById("bb-loaded-val");
  const bbDeltaVal = document.getElementById("bb-delta-val");
  const bbGradeVal = document.getElementById("bb-grade-val");
  const bufferbloatBadge = document.getElementById("bufferbloat-badge");

  btnRunBufferbloat?.addEventListener("click", async () => {
    setActionState(btnRunBufferbloat, "Measuring…", true);
    showProgress("Measuring bufferbloat", "Idle latency, then loaded latency");
    if (bufferbloatBadge) {
      bufferbloatBadge.textContent = "TESTING";
      bufferbloatBadge.className = "badge danger";
    }

    try {
      const res = await withTimeout(sendSocketCmd(`net_bufferbloat ${activeTarget}`), 30000);
      if (res && typeof res === "string") {
      try {
        const bb = JSON.parse(res);
        if (bbUnloadedVal) bbUnloadedVal.textContent = `${bb.unloaded_avg_ms} ms`;
        if (bbLoadedVal) bbLoadedVal.textContent = `${bb.loaded_avg_ms} ms`;
        if (bbDeltaVal) bbDeltaVal.textContent = `+${bb.delta_ms} ms`;
        if (bbGradeVal) bbGradeVal.textContent = bb.grade;
        if (bufferbloatBadge) {
          bufferbloatBadge.textContent = bb.delta_ms < 35 ? "GRADE " + bb.grade.charAt(0) : "BUFFERBLOAT";
          bufferbloatBadge.className = bb.delta_ms < 35 ? "badge active" : "badge danger";
        }
      } catch (e) {
        console.error("parse bufferbloat error:", e);
      }
    } else {
      if (bbUnloadedVal) bbUnloadedVal.textContent = "-- ms";
      if (bbLoadedVal) bbLoadedVal.textContent = "-- ms";
      if (bbDeltaVal) bbDeltaVal.textContent = "-- ms";
      if (bbGradeVal) bbGradeVal.textContent = "--";
      if (bufferbloatBadge) {
        bufferbloatBadge.textContent = "OFFLINE";
        bufferbloatBadge.className = "badge danger";
      }
    }
    } finally {
      setActionState(btnRunBufferbloat, "Measuring…", false);
      hideProgress();
    }
  });

  // Action: Emergency Restore All
  const btnRestore = document.getElementById("btn-emergency-restore");
  btnRestore?.addEventListener("click", async () => {
    if (!confirm("Restore all GPU, CPU and network settings to stock?")) return;
    if (!confirm("This cancels every active tuning profile. Continue?")) return;
    btnRestore.textContent = "RESTORING...";
    await sendSocketCmd("restore_all");
    setTimeout(() => (btnRestore.textContent = "RESTORE ALL STOCK"), 1500);
  });

  // Action: Session controls
  btnSessionEnd?.addEventListener("click", async () => {
    if (!confirm("End this session, wake every app and restore stock clocks?")) return;
    btnSessionEnd.disabled = true;
    btnSessionEnd.textContent = "RESTORING...";
    await sendSocketCmd("session_end");
    await refreshSessionReport();
    await refreshSleepSettings();
    btnSessionEnd.textContent = "End session & restore";
    btnSessionEnd.disabled = false;
  });

  btnSessionWake?.addEventListener("click", async () => {
    btnSessionWake.disabled = true;
    await sendSocketCmd("wake_apps");
    await refreshSleepSettings();
    await refreshSessionReport();
    btnSessionWake.disabled = false;
  });

  btnSleepToggle?.addEventListener("click", async () => {
    const next = !sleepEnabled;
    btnSleepToggle.disabled = true;
    await sendSocketCmd(`set_sleep_enabled ${next ? 1 : 0}`);
    await refreshSleepSettings();
    btnSleepToggle.disabled = false;
  });

  btnSleepSave?.addEventListener("click", async () => {
    const value = (sleepWhitelistInput?.value || "").trim();
    btnSleepSave.disabled = true;
    await sendSocketCmd(`set_sleep_whitelist ${value}`);
    await refreshSleepSettings();
    btnSleepSave.textContent = "Saved";
    setTimeout(() => (btnSleepSave.textContent = "Save whitelist"), 1200);
    btnSleepSave.disabled = false;
  });

  refreshSleepSettings();
  refreshSessionReport();
  setInterval(refreshSessionReport, 30000);
  setInterval(refreshSleepSettings, 30000);

  // Action: Samsung GOS Disable/Restore
  const btnGosDisable = document.getElementById("btn-gos-disable");
  const btnGosRestore = document.getElementById("btn-gos-restore");
  const gosStatus = document.getElementById("gos-status");
  async function refreshGosStatus() {
    const res = await sendSocketCmd("gos_status");
    if (res && typeof res === "string") {
      try {
        const j = JSON.parse(res);
        const unavailable = j.has_gos === false;
        const panel = document.getElementById("gos-title")?.closest(".panel");
        if (panel) panel.style.display = unavailable ? "none" : "";
        if (unavailable) {
          if (gosStatus) gosStatus.textContent = "Status: no GOS package on this ROM (AOSP/GSI), nothing to disable.";
        } else {
          if (gosStatus) gosStatus.textContent = "Status: " + (j.disabled ? "disabled (bypass)" : "enabled (stock)");
        }
        [btnGosDisable, btnGosRestore].forEach((btn) => {
          if (!btn) return;
          btn.disabled = unavailable;
          if (unavailable) btn.title = "Game Optimizing Service is not installed on this device";
        });
      } catch (e) {}
    }
  }
  btnGosDisable?.addEventListener("click", async () => {
    if (!confirm("Disable Samsung Game Optimizing Service?")) return;
    btnGosDisable.textContent = "DISABLING...";
    await sendSocketCmd("gos_disable");
    btnGosDisable.textContent = "DISABLE GOS";
    refreshGosStatus();
  });
  btnGosRestore?.addEventListener("click", async () => {
    btnGosRestore.textContent = "RESTORING...";
    await sendSocketCmd("gos_restore");
    btnGosRestore.textContent = "RESTORE GOS";
    refreshGosStatus();
  });
  refreshGosStatus();

  // Floating HUD Controller
  function updateHudToggleState() {
    const btn = document.getElementById("btn-toggle-hud");
    const txt = document.getElementById("hud-status-text");
    if (!btn || !txt) return;

    if (window._nativeBridge && typeof window._nativeBridge.isHudRunning === "function") {
      const running = window._nativeBridge.isHudRunning();
      if (running) {
        btn.classList.remove("off");
        txt.textContent = "ON";
      } else {
        btn.classList.add("off");
        txt.textContent = "OFF";
      }
    }
  }
  window.updateHudToggleState = updateHudToggleState;

  const btnToggleHud = document.getElementById("btn-toggle-hud");
  btnToggleHud?.addEventListener("click", () => {
    if (window._nativeBridge && typeof window._nativeBridge.triggerHaptic === "function") {
      window._nativeBridge.triggerHaptic();
    }
    if (window._nativeBridge) {
      if (!window._nativeBridge.canDrawOverlays()) {
        showToast("Allow 'Display over other apps' to enable the floating HUD.");
        window._nativeBridge.requestOverlayPermission();
        return;
      }
      const running = window._nativeBridge.isHudRunning();
      window._nativeBridge.setHudEnabled(!running);
      setTimeout(updateHudToggleState, 250);
    } else {
      showToast("HUD Service only runs inside the Secreto Space app.");
    }
  });

  updateHudToggleState();


  // Open app on Games tab with carousel by default
  document.querySelector('[data-tab="tab-games"]')?.click();
  // Ensure carousel mode is active on first load
  if (gamesViewMode !== "carousel") {
    setGamesViewMode("carousel");
  }
})();

