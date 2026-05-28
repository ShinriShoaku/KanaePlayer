# YT TikTok Player – Android

YouTube audio player with TikTok Live chat integration.  
Port Android dari `main.py` (FastAPI + yt-dlp + mpv).

---

## Stack

| Komponen | Library |
|---|---|
| Audio playback | **ExoPlayer / Media3** |
| YouTube URL extraction | **yt-dlp** (ARM64 binary, auto-download) |
| TikTok Live | **EulerStream API** (WebSocket + HTTP fallback) |
| Floating overlay | **WindowManager** TYPE_APPLICATION_OVERLAY |
| Foreground Service | Android `Service` + `START_STICKY` |
| Networking | **OkHttp** |

---

## Setup

### 1. Buka di Android Studio

```
File → Open → pilih folder YTTikTokPlayer
```

Tunggu Gradle sync selesai.

### 2. SDK Requirements

- **minSdk**: 26 (Android 8.0)
- **targetSdk**: 35
- **Android Studio**: Hedgehog atau lebih baru

### 3. EulerStream API Key

Daftar di [eulerstream.com](https://eulerstream.com) → dapatkan API key.

Di app → Settings → masukkan API Key + TikTok username → **Save & Connect**.

### 4. Permissions yang dibutuhkan

| Permission | Fungsi |
|---|---|
| `INTERNET` | Stream YouTube + EulerStream |
| `FOREGROUND_SERVICE` | Service tidak dibunuh sistem |
| `SYSTEM_ALERT_WINDOW` | Floating overlay di atas semua app |
| `POST_NOTIFICATIONS` | Notifikasi player |
| `WAKE_LOCK` | Layar tetap aktif saat playback |

Permission `SYSTEM_ALERT_WINDOW` **harus diizinkan manual** di Settings:  
App → grant overlay permission (tombol otomatis tersedia di dalam app).

---

## Architecture

```
MainActivity
├── Bind ke PlayerForegroundService
├── UI: controls, queue, chat log, settings
└── Broadcast receiver ← state updates

PlayerForegroundService  (START_STICKY – tidak dibunuh)
├── AudioPlayer (ExoPlayer)
│     └── play(streamUrl) → pause/resume/stop
├── YtDlpHelper
│     ├── Download yt-dlp ARM64 binary on first run
│     └── extractAudioUrl(ytUrl) → direct stream URL
├── TikTokLiveManager
│     ├── WebSocket: wss://eulerstream.com/ws?api_key=…
│     ├── Fallback: HTTP polling setiap 2 detik
│     └── Parse commands: #req, #skip, #stop, #q
└── OverlayManager
      ├── WindowManager.addView() – TYPE_APPLICATION_OVERLAY
      ├── Drag & drop: OnTouchListener
      └── Update: song title, progress bar, queue count, live dot
```

---

## TikTok Live Commands

| Command | Fungsi |
|---|---|
| `#req <youtube url>` | Request lagu dari URL |
| `#req <judul lagu>` | Request lagu via search |
| `#lagu <judul>` | Alias request |
| `#skip` / `#next` / `#lewat` | Skip lagu |
| `#stop` | Stop player |
| `#queue` / `#antrian` / `#q` | Lihat queue |

---

## yt-dlp Binary

Pada pertama kali dijalankan, app akan **otomatis download** yt-dlp binary dari:
```
https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64
```

Binary disimpan di app private storage (`/data/data/com.ytplayer/files/yt-dlp`).  
Ukuran ~30-50MB. Butuh koneksi internet sekali saja.

Status download terlihat di status bar app: `🟡 yt-dlp downloading…` → `🟢 yt-dlp ready`.

---

## Build APK

Di Android Studio:
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

---

## Troubleshooting

**Overlay tidak muncul**  
→ Grant permission: *Settings → Apps → YT TikTok Player → Display over other apps → Allow*

**TikTok Live tidak connect**  
→ Pastikan API key EulerStream valid dan TikTok sedang LIVE

**yt-dlp gagal extract URL**  
→ Pastikan device terhubung internet saat download binary pertama kali  
→ Cek di logcat: tag `YtDlpHelper`

**Service dibunuh sistem**  
→ Di beberapa device (Xiaomi/Samsung), perlu tambahkan ke *Protected apps* / *Don't optimize battery*
