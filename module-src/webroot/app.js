// FrameBoost Game Space Console - Controller logic v2.0.0
(function () {
  "use strict";

  // Inject Native Bridge to window.ksu shim if running in FrameBoost APK
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
  const heroGpuGov = document.getElementById("hero-gpu-gov");
  const heroCpuGov = document.getElementById("hero-cpu-gov");
  const heroStutterGov = document.getElementById("hero-stutter-gov");
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

  // Elements: Network
  const netIface = document.getElementById("net-iface");
  const netCcBadge = document.getElementById("net-health-badge");

  // Elements: Game Library
  const gamesContainer = document.getElementById("games-container");
  const gameSearchInput = document.getElementById("game-search-input");
  const btnScanGames = document.getElementById("btn-scan-games");
  const btnToggleAddDrawer = document.getElementById("btn-toggle-add-drawer");
  const addGameDrawer = document.getElementById("add-game-drawer");
  const installedAppsSelect = document.getElementById("installed-apps-select");
  const customPkgInput = document.getElementById("custom-pkg-input");
  const btnSubmitAddGame = document.getElementById("btn-submit-add-game");
  const btnCancelAddGame = document.getElementById("btn-cancel-add-game");

  // Local development preview only. It never runs in the APK/production WebUI.
  const previewAllowed = location.hostname === "localhost" || location.hostname === "127.0.0.1";
  const previewParams = new URLSearchParams(location.search);
  const previewMode = previewAllowed && previewParams.has("preview");
  const previewState = (previewParams.get("state") || "").toUpperCase();

  // State
  let allGames = [];
  let installedApps = [];
  let currentForegroundPkg = "";
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
      if (fps > 0) {
        fpsLiveLabel.textContent = `${fps.toFixed(1)} FPS • ${ft.toFixed(1)} ms`;
      } else {
        fpsLiveLabel.textContent = `0.0 FPS • -- ms`;
      }
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

    ctx.strokeStyle = "rgba(0, 255, 157, 0.4)";
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
    grad.addColorStop(0, "rgba(208, 188, 255, 0.25)");
    grad.addColorStop(1, "rgba(208, 188, 255, 0.0)");
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
    ctx.strokeStyle = "#d0bcff";
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
        if (tab.dataset.tab === "tab-network" && !multiPingDone) {
          multiPingDone = true;
          runMultiPing();
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
        const callback = `window.__frameboostIpc[${++ipcSeq}]`;
        window.__frameboostIpc = window.__frameboostIpc || {};
        window.__frameboostIpc[ipcSeq] = (value) => {
          delete window.__frameboostIpc[ipcSeq];
          resolve(value || null);
        };
        window._nativeBridge.sendSocketAsync(cmd, callback);
      });
    }
    if (window._nativeBridge && typeof window._nativeBridge.sendSocket === "function") {
      return Promise.resolve(window._nativeBridge.sendSocket(cmd) || null);
    }
    const shell = `printf "${cmd}\\n" | nc -U /data/local/tmp/frameboost.sock 2>/dev/null`;
    return execCmd(shell);
  }

  // Update UI with snapshot data
  function renderSnapshot(data) {
    if (!data) return;

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
    heroGame.textContent = currentForegroundPkg || "Desktop / Idle";
    const tempC = Math.round((data.temp_big_mc || 0) / 1000);
    heroTemp.textContent = `${tempC}°`;

    // Clocks
    const gpuMhz = Math.round((data.gpu?.current_clock || data.gpu?.min_clock || 0) / 1000);
    heroGpuClk.textContent = `${gpuMhz}M`;

    const cpuMhz = Math.round((data.cpu?.big?.cur_freq || data.cpu?.big?.min_freq || 0) / 1000);
    heroCpuClk.textContent = `${cpuMhz}M`;

    // Badges
    heroGpuGov.textContent = `GPU: ${data.gpu?.governor || "Interactive"}`;
    heroCpuGov.textContent = `CPU: ${data.cpu?.big?.gov || "energy_adaptive"}`;
    if (heroStutterGov) {
      if (data.is_game) {
        heroStutterGov.textContent = `RAM: Anti-Stutter (ZRAM 10) • DND ON`;
        heroStutterGov.style.color = "#7ddca5";
        heroStutterGov.style.borderColor = "rgba(0, 255, 157, 0.4)";
      } else {
        heroStutterGov.textContent = `RAM: Stock (ZRAM 130)`;
        heroStutterGov.style.color = "";
        heroStutterGov.style.borderColor = "";
      }
    }

    // Gauges
    const busy = Math.round(data.gpu?.busy_pct || 0);
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
        battStatusEl.textContent = b.status || "Discharging";
        battStatusEl.className = "battery-status-badge";
        if (b.status === "Charging") battStatusEl.classList.add("charging");
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

    // Daemon Meta
    if (daemonUptime) daemonUptime.textContent = `${data.daemon_uptime_s || 0}s`;
    if (snapshotStatus) snapshotStatus.textContent = data.stock_saved ? "SAVED" : "NONE";

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

  function setDaemonOffline() {
    if (stateLabel) stateLabel.textContent = "OFFLINE";
    if (stateDot) stateDot.className = "state-dot crit";
    if (heroGame) heroGame.textContent = "Daemon offline";
    if (heroTemp) heroTemp.textContent = "--°";
    if (heroGpuClk) heroGpuClk.textContent = "--M";
    if (heroCpuClk) heroCpuClk.textContent = "--M";
    if (daemonStatus) daemonStatus.textContent = "OFFLINE";
    if (daemonStatus) daemonStatus.className = "badge danger";
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
      return;
    }
    let raw = await sendSocketCmd("snapshot");
    if (!raw) {
      raw = await execCmd("cat /data/local/tmp/frameboost-snapshot.json 2>/dev/null");
    }
    if (raw && typeof raw === "string") {
      try {
        const snap = JSON.parse(raw);
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

  function renderGamesList(games) {
    if (!gamesContainer) return;
    if (!games || games.length === 0) {
      gamesContainer.innerHTML = `
        <div style="text-align:center; padding:30px 10px; color:var(--text-dim); font-family:var(--font-mono); font-size:12px;">
          Tidak ada game yang cocok.<br>
          <span style="font-size:10px; color:var(--text-sub);">Hanya aplikasi game yang terpasang di device ditampilkan.</span>
        </div>`;
      return;
    }

    gamesContainer.innerHTML = "";
    games.forEach((game) => {
      const item = document.createElement("div");
      item.className = "game-item";
      item.dataset.pkg = game.pkg;
      if (currentForegroundPkg && game.pkg === currentForegroundPkg) {
        item.classList.add("active-profile");
      }

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

      const isBbr = !!game.auto_bbr;
      const currentPreset = (game.preset || "BALANCED").toUpperCase();

      const initial = (game.name || "?").trim().charAt(0).toUpperCase();
      const hue = (game.pkg.split("").reduce((a, c) => a + c.charCodeAt(0), 0) % 360);
      item.innerHTML = `
        <div class="game-tile-cover" style="background: linear-gradient(135deg, hsl(${hue}, 60%, 25%), hsl(${(hue+60)%360}, 50%, 15%));">
          <div class="game-tile-initial">${initial}</div>
          <div class="game-tile-source ${sourceClass}">${sourceLabel}</div>
        </div>
        <div class="game-tile-body">
          <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
            <div style="min-width:0;">
              <div class="game-title" style="white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${game.name}</div>
              <div class="game-pkg" style="white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${game.pkg}</div>
            </div>
            <span class="preset-badge-${currentPreset.toLowerCase()}" style="font-family:var(--font-mono);font-size:10px;padding:2px 6px;border-radius:6px;background:var(--surface-2);">${currentPreset}</span>
          </div>
          <button class="btn btn-primary inline-launch" data-pkg="${game.pkg}" style="margin-top:10px; width:100%; font-size:11px;">▶ PLAY</button>
        </div>
      `;

      gamesContainer.appendChild(item);
    });

    // Tap card (outside buttons) opens the separate detail page.
  gamesContainer.querySelectorAll(".game-item").forEach((item) => {
    item.addEventListener("click", (e) => {
      if (e.target.closest("button,input,select")) return;
      openGameDetail(item.dataset.pkg);
    });
  });

  gamesContainer.querySelectorAll(".inline-launch").forEach((btn) => {
    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      if (window._nativeBridge && typeof window._nativeBridge.launchGame === "function") {
        window._nativeBridge.launchGame(btn.dataset.pkg);
      } else {
        alert("Gagal meluncurkan game. Pastikan package terinstall.");
      }
    });
  });

  }

  function applyFilter() {
    const q = (gameSearchInput?.value || "").toLowerCase().trim();
    if (!q) {
      renderGamesList(allGames);
      return;
    }
    const filtered = allGames.filter(
      (g) =>
        (g.name && g.name.toLowerCase().includes(q)) ||
        (g.pkg && g.pkg.toLowerCase().includes(q)) ||
        (g.source && g.source.toLowerCase().includes(q))
    );
    renderGamesList(filtered);
  }

  const gamesListView = document.getElementById("games-list-view");
  const gameDetailView = document.getElementById("game-detail-view");
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
    if (gameDetailView) gameDetailView.classList.remove("hidden");
  }

  function closeGameDetail() {
    selectedGamePkg = "";
    if (gameDetailView) gameDetailView.classList.add("hidden");
    if (gamesListView) gamesListView.classList.remove("hidden");
  }

  document.getElementById("btn-close-game-detail")?.addEventListener("click", closeGameDetail);

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

  document.getElementById("btn-detail-launch")?.addEventListener("click", async (e) => {
    if (!selectedGamePkg) return;
    const btn = e.currentTarget;
    btn.disabled = true;
    btn.textContent = "APPLYING...";
    const res = await sendSocketCmd("apply_game");
    let ok = false;
    try { const data = JSON.parse(res); ok = data.status === "ok"; } catch (err) {}
    btn.textContent = ok ? "✓ PROFILE APPLIED" : "✕ APPLY FAILED";
    setTimeout(() => {
      btn.disabled = false;
      btn.textContent = "Apply Profile";
    }, 1800);
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
      gamesContainer.innerHTML = '<div style="text-align:center; padding:30px 10px; color:var(--text-dim); font-family:var(--font-mono); font-size:12px;">Daemon offline.<br><span style="font-size:10px; color:var(--text-sub);">Start frameboostd and reload.</span></div>';
    }
  }

  async function loadInstalledApps() {
    if (installedApps.length > 0) return;
    const res = await sendSocketCmd("list_installed_apps");
    if (res) {
      try {
        const apps = JSON.parse(res);
        if (Array.isArray(apps)) {
          installedApps = apps;
          installedAppsSelect.innerHTML = '<option value="">-- Pilih Aplikasi Terinstall --</option>';
          apps.forEach((app) => {
            const opt = document.createElement("option");
            opt.value = app;
            opt.textContent = app;
            installedAppsSelect.appendChild(opt);
          });
        }
      } catch (e) {
        console.error("parse installed apps error:", e);
      }
    }
  }

  // Scan device button
  btnScanGames?.addEventListener("click", async () => {
    btnScanGames.disabled = true;
    btnScanGames.innerHTML = '<span>⟳</span> Refreshing installed games...';
    const res = await sendSocketCmd("scan_games");
    btnScanGames.disabled = false;
    btnScanGames.innerHTML = '<span>⟳</span> Refresh installed games';
    if (res) {
      try {
        const data = JSON.parse(res);
        if (data.games) {
          allGames = data.games;
          applyFilter();
          alert(`Scan selesai! Ditemukan ${data.found || 0} game baru dari sistem.`);
          return;
        }
      } catch (e) {
        console.error("scan parse error:", e);
      }
    }
    await loadGames();
  });

  // Toggle Add Drawer
  btnToggleAddDrawer?.addEventListener("click", () => {
    addGameDrawer?.classList.toggle("hidden");
    if (!addGameDrawer?.classList.contains("hidden")) {
      loadInstalledApps();
      customPkgInput?.focus();
    }
  });

  btnCancelAddGame?.addEventListener("click", () => {
    addGameDrawer?.classList.add("hidden");
  });

  installedAppsSelect?.addEventListener("change", () => {
    if (installedAppsSelect.value) {
      customPkgInput.value = installedAppsSelect.value;
    }
  });

  btnSubmitAddGame?.addEventListener("click", async () => {
    const pkg = (customPkgInput?.value || "").trim();
    if (!pkg) {
      alert("Masukkan nama package terlebih dahulu.");
      return;
    }
    btnSubmitAddGame.disabled = true;
    btnSubmitAddGame.textContent = "Menyimpan...";
    const res = await sendSocketCmd(`add_game ${pkg}`);
    btnSubmitAddGame.disabled = false;
    btnSubmitAddGame.textContent = "Tambah Game";
    if (res) {
      try {
        const data = JSON.parse(res);
        if (data.status === "ok" && data.games) {
          allGames = data.games;
          applyFilter();
          customPkgInput.value = "";
          installedAppsSelect.value = "";
          addGameDrawer?.classList.add("hidden");
          return;
        } else if (data.status === "error") {
          alert(`Gagal: ${data.message}`);
          return;
        }
      } catch (e) {
        console.error("add game parse error:", e);
      }
    }
    await loadGames();
    addGameDrawer?.classList.add("hidden");
  });

  gameSearchInput?.addEventListener("input", applyFilter);

  // Initial load of games
  loadGames();

  // ==========================================
  // ACTION BUTTONS (Boost, Network, Safety)
  // ==========================================

  // Action: Manual Boost Override
  const btnToggleBoost = document.getElementById("btn-toggle-boost");
  btnToggleBoost?.addEventListener("click", async () => {
    btnToggleBoost.textContent = "APPLYING...";
    await sendSocketCmd("apply_game");
    setTimeout(() => {
      btnToggleBoost.textContent = "BOOST APPLIED";
      setTimeout(() => (btnToggleBoost.textContent = "APPLY BOOST"), 2000);
    }, 500);
  });


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
    ctx.strokeStyle = "rgba(255, 208, 97, 0.3)";
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
    ctx.strokeStyle = "#d0bcff";
    ctx.lineWidth = 2;
    ctx.stroke();

    // Sample dots
    samples.forEach((val, idx) => {
      const x = idx * step;
      const y = h - (val / maxPing) * (h - 10);
      ctx.fillStyle = val < 35 ? "#7ddca5" : val < 75 ? "#f4b77a" : "#ffb4ab";
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
    if (!p || !p.host) return "--";
    return `${Math.round(p.loss_pct || 0)}% loss • ${(p.jitter_ms || 0).toFixed(1)} j`;
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
        if (key === "custom") {
          refs.host.textContent = "not set";
          refs.val.textContent = "--";
          refs.sub.textContent = "--";
        }
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
    if (!confirm("Kembalikan seluruh setting GPU, CPU, dan Network ke stock?")) return;
    if (!confirm("Konfirmasi terakhir: restore semua setting sekarang?")) return;
    btnRestore.textContent = "RESTORING...";
    await sendSocketCmd("restore_all");
    setTimeout(() => (btnRestore.textContent = "RESTORE ALL STOCK"), 1500);
  });

  // Action: Samsung GOS Disable/Restore
  const btnGosDisable = document.getElementById("btn-gos-disable");
  const btnGosRestore = document.getElementById("btn-gos-restore");
  const gosStatus = document.getElementById("gos-status");
  async function refreshGosStatus() {
    const res = await sendSocketCmd("gos_status");
    if (res && typeof res === "string") {
      try {
        const j = JSON.parse(res);
        if (gosStatus) gosStatus.textContent = "Status: " + (j.disabled ? "DISABLED" : "ENABLED");
      } catch (e) {}
    }
  }
  btnGosDisable?.addEventListener("click", async () => {
    if (!confirm("Nonaktifkan Samsung Game Optimizing Service?")) return;
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
        alert("Mohon izinkan izin 'Display over other apps' untuk mengaktifkan HUD melayang.");
        window._nativeBridge.requestOverlayPermission();
        return;
      }
      const running = window._nativeBridge.isHudRunning();
      window._nativeBridge.setHudEnabled(!running);
      setTimeout(updateHudToggleState, 250);
    } else {
      alert("HUD Service hanya aktif di aplikasi Android FrameBoost Game Space.");
    }
  });

  updateHudToggleState();
})();

