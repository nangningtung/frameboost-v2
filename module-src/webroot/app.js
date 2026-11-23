// FrameBoost Game Space Console - Controller logic v2.0.0
(function () {
  "use strict";

  // Elements: Hero & Telemetry
  const stateDot = document.getElementById("state-dot");
  const stateLabel = document.getElementById("state-label");
  const heroGame = document.getElementById("hero-game");
  const heroTemp = document.getElementById("hero-temp");
  const heroGpuClk = document.getElementById("hero-gpu-clk");
  const heroCpuClk = document.getElementById("hero-cpu-clk");
  const heroGpuGov = document.getElementById("hero-gpu-gov");
  const heroCpuGov = document.getElementById("hero-cpu-gov");
  const gpuBusyFill = document.getElementById("gpu-busy-fill");
  const gpuBusyText = document.getElementById("gpu-busy-text");
  const gpuMinText = document.getElementById("gpu-min-text");
  const tempLittle = document.getElementById("temp-little");
  const tempGpu = document.getElementById("temp-gpu");
  const daemonUptime = document.getElementById("daemon-uptime");
  const snapshotStatus = document.getElementById("snapshot-status");
  const frametimeCanvas = document.getElementById("frametime-canvas");
  const fpsLiveLabel = document.getElementById("fps-live-label");

  // Elements: Network
  const netIface = document.getElementById("net-iface");
  const netGw = document.getElementById("net-gw");
  const netPingVal = document.getElementById("net-ping-val");
  const netJitter = document.getElementById("net-jitter");
  const netLoss = document.getElementById("net-loss");
  const netCcBadge = document.getElementById("net-cc-badge");
  const benchBox = document.getElementById("bench-results");
  const benchStockVal = document.getElementById("bench-stock-val");
  const benchBbrVal = document.getElementById("bench-bbr-val");

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
    grad.addColorStop(0, "rgba(0, 240, 255, 0.25)");
    grad.addColorStop(1, "rgba(0, 240, 255, 0.0)");
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
    ctx.strokeStyle = "#00f0ff";
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

  async function sendSocketCmd(cmd) {
    const shell = `printf "${cmd}\\n" | nc -U /data/local/tmp/frameboost.sock 2>/dev/null`;
    const res = await execCmd(shell);
    return res;
  }

  // Update UI with snapshot data
  function renderSnapshot(data) {
    if (!data) return;

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

    // Gauges
    const busy = Math.round(data.gpu?.busy_pct || 0);
    gpuBusyFill.style.width = `${Math.min(100, Math.max(0, busy))}%`;
    gpuBusyText.textContent = `${busy}%`;
    gpuMinText.textContent = `Min: ${Math.round((data.gpu?.min_clock || 260000) / 1000)} MHz`;

    tempLittle.textContent = `${Math.round((data.temp_little_mc || 0) / 1000)}°`;
    tempGpu.textContent = `${Math.round((data.temp_gpu_mc || 0) / 1000)}°`;

    // Daemon Meta
    daemonUptime.textContent = `${data.daemon_uptime_s || 0}s`;
    snapshotStatus.textContent = data.stock_saved ? "SAVED" : "NONE";

    // Net Meta
    if (data.net) {
      if (data.net.interface) netIface.textContent = data.net.interface;
      if (data.net.congestion_control) netCcBadge.textContent = data.net.congestion_control;
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
  async function pollTelemetry() {
    let raw = await execCmd("cat /data/local/tmp/frameboost-snapshot.json 2>/dev/null");
    if (raw && typeof raw === "string") {
      try {
        const snap = JSON.parse(raw);
        renderSnapshot(snap);
      } catch (e) {
        // partial read ignored
      }
    } else {
      // Mock data for preview in browser without KernelSU
      const mock = {
        daemon_state: "IDLE",
        game_pkg: "com.mobile.legends",
        is_game: true,
        temp_big_mc: 48000,
        temp_little_mc: 41000,
        temp_gpu_mc: 42000,
        gpu: {
          governor: "Interactive",
          min_clock: 260000,
          current_clock: 385000,
          busy_pct: 18.0,
        },
        cpu: {
          little: { gov: "energy_adaptive", min_freq: 702000, cur_freq: 1222000 },
          big: { gov: "energy_adaptive", min_freq: 1040000, cur_freq: 1508000 },
        },
        net: {
          interface: "wlan0",
          congestion_control: "westwood",
        },
        daemon_uptime_s: 128,
        stock_saved: true,
      };
      renderSnapshot(mock);
    }
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
          <span style="font-size:10px; color:var(--text-sub);">Gunakan tombol 'Scan Device' atau '+ Add Custom' di atas.</span>
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

      item.innerHTML = `
        <div class="game-item-top">
          <div class="game-info" style="flex:1; min-width:0;">
            <div class="game-title" style="white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${game.name}</div>
            <div class="game-pkg" style="white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${game.pkg}</div>
            <div class="game-chips">
              <span class="chip-sm ${sourceClass}">${sourceLabel}</span>
              <span class="chip-sm">${game.profile || "Balanced 455M"}</span>
            </div>
          </div>
          <button class="btn-remove-game" data-pkg="${game.pkg}" title="Hapus dari daftar">✕</button>
        </div>
        <div class="game-tune-row">
          <select class="select-preset" data-pkg="${game.pkg}">
            <option value="BATTERY" ${currentPreset === "BATTERY" ? "selected" : ""}>🔋 Battery (385M)</option>
            <option value="BALANCED" ${currentPreset === "BALANCED" ? "selected" : ""}>⚡ Balanced (455M)</option>
            <option value="PERFORMANCE" ${currentPreset === "PERFORMANCE" ? "selected" : ""}>🚀 Performance (572M)</option>
            <option value="ESPORTS" ${currentPreset === "ESPORTS" ? "selected" : ""}>🔥 Esports (683M)</option>
          </select>
          <button class="btn-toggle-bbr ${isBbr ? "active" : ""}" data-pkg="${game.pkg}" data-bbr="${isBbr ? "1" : "0"}">
            ${isBbr ? "🌐 BBR: ON" : "🌐 BBR: OFF"}
          </button>
        </div>
      `;

      gamesContainer.appendChild(item);
    });

    // Wire preset dropdowns
    gamesContainer.querySelectorAll(".select-preset").forEach((sel) => {
      sel.addEventListener("change", async (e) => {
        const pkg = sel.dataset.pkg;
        const preset = sel.value;
        const bbrBtn = sel.closest(".game-item")?.querySelector(".btn-toggle-bbr");
        const bbr = bbrBtn ? bbrBtn.dataset.bbr : "0";
        const res = await sendSocketCmd(`set_game_profile ${pkg} ${preset} ${bbr}`);
        if (res) {
          try {
            const data = JSON.parse(res);
            if (data.games) {
              allGames = data.games;
              applyFilter();
              return;
            }
          } catch (_) {}
        }
        await loadGames();
      });
    });

    // Wire BBR toggle buttons
    gamesContainer.querySelectorAll(".btn-toggle-bbr").forEach((btn) => {
      btn.addEventListener("click", async (e) => {
        const pkg = btn.dataset.pkg;
        const currentBbr = btn.dataset.bbr === "1";
        const newBbr = !currentBbr ? "1" : "0";
        const sel = btn.closest(".game-item")?.querySelector(".select-preset");
        const preset = sel ? sel.value : "BALANCED";
        btn.textContent = "...";
        const res = await sendSocketCmd(`set_game_profile ${pkg} ${preset} ${newBbr}`);
        if (res) {
          try {
            const data = JSON.parse(res);
            if (data.games) {
              allGames = data.games;
              applyFilter();
              return;
            }
          } catch (_) {}
        }
        await loadGames();
      });
    });

    // Wire remove buttons
    gamesContainer.querySelectorAll(".btn-remove-game").forEach((btn) => {
      btn.addEventListener("click", async (e) => {
        e.stopPropagation();
        const pkg = btn.dataset.pkg;
        if (!confirm(`Hapus '${pkg}' dari daftar game FrameBoost?`)) return;
        btn.textContent = "...";
        const res = await sendSocketCmd(`remove_game ${pkg}`);
        if (res) {
          try {
            const data = JSON.parse(res);
            if (data.games) {
              allGames = data.games;
              applyFilter();
              return;
            }
          } catch (_) {}
        }
        await loadGames();
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

  async function loadGames() {
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
    // Fallback if daemon not responding
    allGames = [
      { pkg: "com.mobile.legends", name: "Mobile Legends: Bang Bang", source: "builtin", profile: "Booster 455M • CPU4 1.35G" },
      { pkg: "com.mobilechess.gp", name: "Auto Chess", source: "auto", profile: "Booster 455M • CPU4 1.35G" },
      { pkg: "com.tencent.ig", name: "PUBG Mobile", source: "builtin", profile: "Booster 572M • CPU4 1.50G" },
      { pkg: "com.dts.freefireth", name: "Free Fire", source: "builtin", profile: "Dynamic 385M • CPU4 1.25G" },
      { pkg: "com.miHoYo.Yuanshen", name: "Genshin Impact", source: "builtin", profile: "Booster 683M • CPU4 1.50G" }
    ];
    applyFilter();
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
    btnScanGames.innerHTML = '<span>⟳</span> Scanning...';
    const res = await sendSocketCmd("scan_games");
    btnScanGames.disabled = false;
    btnScanGames.innerHTML = '<span>⟳</span> Scan Device';
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

  // Action: Network Stock (Westwood)
  const btnNetStock = document.getElementById("btn-net-stock");
  btnNetStock?.addEventListener("click", async () => {
    btnNetStock.textContent = "APPLYING...";
    await sendSocketCmd("net_restore");
    setTimeout(() => (btnNetStock.textContent = "Stock (Westwood)"), 1000);
  });

  // Action: Network BBR
  const btnNetBbr = document.getElementById("btn-net-bbr");
  btnNetBbr?.addEventListener("click", async () => {
    btnNetBbr.textContent = "APPLYING...";
    await sendSocketCmd("net_apply_bbr");
    setTimeout(() => (btnNetBbr.textContent = "Enable BBR"), 1000);
  });

  // Action: A/B Benchmark
  const btnBenchmark = document.getElementById("btn-benchmark");
  btnBenchmark?.addEventListener("click", async () => {
    btnBenchmark.textContent = "Testing...";
    btnBenchmark.disabled = true;

    const res = await sendSocketCmd("net_ab_test");
    btnBenchmark.textContent = "Run 5-packet A/B Test";
    btnBenchmark.disabled = false;

    if (res && typeof res === "string") {
      try {
        const data = JSON.parse(res);
        if (data.stock && data.bbr) {
          benchStockVal.textContent = `${data.stock.avg_ms.toFixed(1)} ms`;
          benchBbrVal.textContent = `${data.bbr.avg_ms.toFixed(1)} ms`;
          benchBox.classList.remove("hidden");
        }
      } catch (e) {
        console.error("parse bench error:", e);
      }
    } else {
      // Mock demo preview
      benchStockVal.textContent = "86.3 ms";
      benchBbrVal.textContent = "78.5 ms (-9%)";
      benchBox.classList.remove("hidden");
    }
  });

  // Action: Emergency Restore All
  const btnRestore = document.getElementById("btn-emergency-restore");
  btnRestore?.addEventListener("click", async () => {
    if (!confirm("Kembalikan seluruh setting GPU, CPU, dan Network ke stock?")) return;
    btnRestore.textContent = "RESTORING...";
    await sendSocketCmd("restore_all");
    setTimeout(() => (btnRestore.textContent = "RESTORE ALL STOCK"), 1500);
  });
})();
