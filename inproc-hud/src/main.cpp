// Secreto Space — in-process HUD (Zygisk), milestone 1
//
// Inject ke proses game (daftar target: /data/adb/secreto_space/
// inproc_targets.list), hook eglSwapBuffers (OpenGLES) via Dobby, dan
// menggambar HUD ImGui di frame buffer game: FPS lokal, grafik frametime,
// preset aktif, dan statistik daemon (dibaca dari hud_stats.json).
//
// Toggle: buat/hapus file /data/adb/secreto_space/inproc_enable.
// Off by default — HUD hanya tergambar saat file enable ada.

#include <jni.h>

#include <android/log.h>
#include <chrono>
#include <EGL/egl.h>
#include <dlfcn.h>
#include <fstream>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <atomic>

#include <dobby.h>
#include "imgui.h"
#include "imgui_impl_opengl3.h"
#include "zygisk.hpp"

#define LOG_TAG "SecretoInproc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char* kTargets = "/data/adb/secreto_space/inproc_targets.list";
static const char* kEnable = "/data/adb/secreto_space/inproc_enable";
static const char* kStats = "/data/adb/secreto_space/hud_stats.json";
static const char* kDefaultTarget = "com.mobile.legends";

static std::atomic<bool> g_active{false};
static std::atomic<bool> g_hooked{false};

// Frametime ring (ms). Diisi di swap hook, dibaca di draw.
static float g_ms[240];
static int g_head = 0;
static bool g_has_last = false;
static std::chrono::steady_clock::time_point g_last;

static bool g_imgui_ready = false;
static bool g_draw_now = false;
static int g_frame = 0;
static std::string g_preset = "-";
static double g_daemon_fps = 0.0;
static double g_daemon_ms = 0.0;
static int g_w = 0;
static int g_h = 0;

static EGLBoolean (*orig_swap)(EGLDisplay, EGLSurface) = nullptr;

static bool file_exists(const char* p) {
    struct stat st;
    return stat(p, &st) == 0;
}

// Naive JSON reader untuk field angka + preset string. Daemon menulis
// {"fps":59.2,"frame_ms":16.8,"preset":"ESPORTS",...} — parse ringan tanpa
// dependensi supaya hot path HUD tetap murah.
static void refresh_stats() {
    std::ifstream f(kStats);
    if (!f.good()) return;
    std::string s((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    auto num = [&](const char* key) -> double {
        std::string k = std::string("\"") + key + "\":";
        auto p = s.find(k);
        if (p == std::string::npos) return 0.0;
        try {
            return std::stod(s.substr(p + k.size()));
        } catch (...) {
            return 0.0;
        }
    };
    g_daemon_fps = num("fps");
    g_daemon_ms = num("frame_ms");
    auto pk = s.find("\"preset\":\"");
    if (pk != std::string::npos) {
        auto e = s.find('"', pk + 10);
        if (e != std::string::npos) g_preset = s.substr(pk + 10, e - pk - 10);
    }
}

static void draw_hud(float dt) {
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2((float)g_w, (float)g_h);
    io.DeltaTime = dt > 0.0f && dt < 1.0f ? dt : 1.0f / 60.0f;

    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();

    double sum = 0.0;
    int n = 0;
    for (int i = 0; i < 240; i++) {
        if (g_ms[i] > 0.0) {
            sum += g_ms[i];
            n++;
        }
    }
    double fps = (n > 0 && sum > 0.0) ? (double)n / sum * 1000.0 : 0.0;

    ImGui::SetNextWindowPos(ImVec2(16.0f, 16.0f), ImGuiCond_Always);
    ImGui::SetNextWindowBgAlpha(0.55f);
    ImGui::Begin(
        "SecretoHUD", nullptr,
        ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoNav |
            ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_AlwaysAutoResize |
            ImGuiWindowFlags_NoBringToFrontOnFocus);
    ImGui::Text("FPS %.1f  (%.1f ms)", fps, n > 0 ? sum / n : 0.0);
    ImGui::Text("Preset: %s", g_preset.c_str());
    if (g_daemon_fps > 0.0)
        ImGui::Text("Daemon %.1f fps / %.1f ms", g_daemon_fps, g_daemon_ms);
    ImGui::PlotLines("frametime", g_ms, 240, 0, nullptr, 0.0f, 40.0f,
                     ImVec2(170.0f, 42.0f));
    ImGui::End();

    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

static EGLBoolean swap_hook(EGLDisplay dpy, EGLSurface surface) {
    static bool logged_first = false;
    if (!logged_first) {
        LOGI("swap called: GL present terdeteksi (frame pertama)");
        logged_first = true;
    }
    auto now = std::chrono::steady_clock::now();
    double ms = g_has_last
                    ? std::chrono::duration<double, std::milli>(now - g_last).count()
                    : 0.0;
    g_last = now;
    g_has_last = true;
    if (ms > 0.0 && ms < 2000.0) {
        g_ms[g_head] = (float)ms;
        g_head = (g_head + 1) % 240;
    }
    g_frame++;

    // I/O ringan hanya tiap 30 frame (~0.5 s di 60 fps).
    if (g_frame % 30 == 1) {
        g_draw_now = file_exists(kEnable);
        if (g_draw_now) refresh_stats();
    }

    if (!g_imgui_ready) {
        EGLint w = 0, h = 0;
        if (eglQuerySurface(dpy, surface, EGL_WIDTH, &w) &&
            eglQuerySurface(dpy, surface, EGL_HEIGHT, &h) && w > 0 && h > 0) {
            g_w = w;
            g_h = h;
            ImGui::CreateContext();
            ImGui::GetIO().IniFilename = nullptr;  // jangan tulis imgui.ini
            if (ImGui_ImplOpenGL3_Init("#version 300 es")) {
                g_imgui_ready = true;
                LOGI("imgui ready %dx%d", w, h);
            } else {
                LOGE("imgui opengl3 init failed");
            }
        }
    } else {
        EGLint w = 0, h = 0;
        if (eglQuerySurface(dpy, surface, EGL_WIDTH, &w) &&
            eglQuerySurface(dpy, surface, EGL_HEIGHT, &h) && w > 0 && h > 0) {
            g_w = w;
            g_h = h;
        }
        if (g_draw_now) draw_hud((float)(ms / 1000.0));
    }

    return orig_swap(dpy, surface);
}

static void wait_and_hook() {
    // Tunggu libEGL.so muncul di maps (Unity memuat GL belakangan).
    bool found = false;
    for (int i = 0; i < 120; i++) {
        std::ifstream f("/proc/self/maps");
        std::string line;
        while (std::getline(f, line)) {
            if (line.find("libEGL.so") != std::string::npos) {
                found = true;
                break;
            }
        }
        if (found) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
    if (!found) {
        LOGE("libEGL.so tidak muncul dalam 60s — kemungkinan game Vulkan");
        return;
    }
    void* h = dlopen("libEGL.so", RTLD_NOW);
    if (!h) {
        LOGE("dlopen libEGL.so gagal");
        return;
    }
    void* sym = dlsym(h, "eglSwapBuffers");
    if (!sym) {
        LOGE("dlsym eglSwapBuffers gagal");
        return;
    }
    if (DobbyHook(sym, (void*)swap_hook, (void**)&orig_swap) == 0) {
        g_hooked = true;
        LOGI("eglSwapBuffers hooked");
    } else {
        LOGE("DobbyHook gagal");
    }
}

class InprocHud : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        if (!args || !args->nice_name) return;
        const char* n = env->GetStringUTFChars(args->nice_name, nullptr);
        std::string pkg = n ? n : "";
        if (n) env->ReleaseStringUTFChars(args->nice_name, n);
        g_active = matches(pkg);
        if (g_active) LOGI("target match: %s", pkg.c_str());
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs*) override {
        if (g_active && !g_hooked) {
            std::thread(wait_and_hook).detach();
        }
    }

private:
    zygisk::Api* api = nullptr;
    JNIEnv* env = nullptr;

    static bool matches(const std::string& pkg) {
        std::ifstream f(kTargets);
        if (!f.good()) return pkg == kDefaultTarget;  // default bila tanpa config
        std::string line;
        while (std::getline(f, line)) {
            while (!line.empty() &&
                   (line.back() == '\r' || line.back() == ' ' ||
                    line.back() == '\t'))
                line.pop_back();
            if (!line.empty() && line[0] != '#' && line == pkg) return true;
        }
        return false;
    }
};

REGISTER_ZYGISK_MODULE(InprocHud)
