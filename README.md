# Kanae Player – Android (YTPlayer)

Aplikasi pemutar audio YouTube dengan integrasi TikTok Live chat untuk request lagu secara real-time.  
Aplikasi ini merupakan evolusi dari `main.py` (FastAPI + yt-dlp + mpv) yang sekarang diimplementasikan sepenuhnya secara native di Android menggunakan **NewPipeExtractor**.

---

## Fitur Utama

- **YouTube Audio Streaming**: Memutar audio dari YouTube secara langsung.
- **TikTok Live Integration**: Terhubung ke live chat TikTok menggunakan **EulerStream API**.
- **Chat Command System**: Penonton live bisa berinteraksi melalui perintah chat (`#req`, `#skip`, dll) yang dapat dikustomisasi.
- **Floating Overlay**: Menampilkan informasi lagu (Playing Overlay) dan daftar antrian (Queue Overlay) di atas aplikasi lain.
- **YouTube Anti-Bot Bypass**: Menggunakan `PoTokenGenerator` (WebView-based) dan `NewPipeDownloader` kustom untuk menghindari blokir YouTube (Error 403/Sign-in required) po_token berasal didapat
  ytdlnis.
- **Background Playback**: Berjalan sebagai Foreground Service agar musik tetap berputar meskipun layar mati atau saat membuka aplikasi lain.
- **Shuffle & Queue Management**: Mendukung mode acak dan pengelolaan antrian lagu secara real-time.

---

## Tech Stack

| Komponen | Library / Teknologi |
|---|---|
| **Audio Playback** | Media3 ExoPlayer |
| **YouTube Extraction** | NewPipeExtractor |
| **YouTube Bypass** | Custom Po-Token Generator (WebView + BotGuard VM) |
| **TikTok Live** | EulerStream API (WebSocket + HTTP Fallback) |
| **UI Framework** | Android XML Layouts + CardView + RecyclerView |
| **Floating UI** | WindowManager (TYPE_APPLICATION_OVERLAY) |
| **Networking** | OkHttp3 & Gson |
| **Image Loading** | Glide |

---

## Persiapan & Instalasi

### 1. Kebutuhan Sistem
- **Minimum SDK**: 26 (Android 8.0 Oreo)
- **Target SDK**: 35

### 2. Konfigurasi API Key
1. Dapatkan API Key dari [EulerStream](https://eulerstream.com).
2. Jalankan aplikasi, buka bagian **SETTINGS**.
3. Masukkan **EulerStream API Key** dan **TikTok Username** Anda.
4. Klik **Save & Connect**.

### 3. Izin Aplikasi (Permissions)
Aplikasi membutuhkan izin berikut untuk berfungsi:
- **Internet**: Untuk streaming audio dan koneksi chat.
- **System Alert Window (Overlay)**: Untuk menampilkan jendela melayang. Berikan izin melalui tombol "Grant Overlay Permission" di aplikasi atau secara manual di pengaturan Android.
- **Post Notifications**: Untuk kontrol media di bar notifikasi (khusus Android 13+).

---

## Perintah TikTok Chat (Commands)

| Perintah           | Deskripsi                                       | Contoh                                        |
|--------------------|-------------------------------------------------|-----------------------------------------------|
| `#req <judul/URL>` | Menambahkan lagu ke antrian via judul atau link | `#req Lathi` atau `#req https://youtu.be/...` |
| `#skip` / `#next`  | Melewati lagu yang sedang diputar               | `#skip`                                       |
| `#stop`            | Menghentikan pemutaran musik                    | `#stop`                                       |
| `#queue` / `#q`    | Melihat daftar antrian lagu                     | `#queue`                                      |
| `#clear` / `#cm`   | Menghapus antrian lagu                          | `#cm 1 atau #cm noah-menghapus jejakmu.`      |

*Prefix perintah dapat dikonfigurasi sesuka hati melalui menu Settings di dalam aplikasi (misal: ganti `#req` menjadi `#lagu`).*

---

## Arsitektur Internal

- **`PlayerForegroundService`**: Service utama yang mengelola lifecycle pemutar musik, koneksi chat, dan jendela overlay agar tidak dimatikan oleh sistem.
- **`NewPipeDownloader`**: Komponen kustom yang menyuntikkan `visitorData` dan `X-Goog-Po-Token` ke dalam request YouTube agar tidak terkena blokir bot.
- **`PoTokenGenerator`**: Mengotomatisasi pembuatan "Proof of Origin" (Po) Token menggunakan skrip BotGuard di dalam `WebView` tersembunyi.
- **`OverlayManager`**: Mengelola tampilan melayang yang interaktif dan mendukung fitur *drag-and-drop*.

---

## Troubleshooting

- **Error 403 / Sign-in Required**: Pastikan koneksi internet stabil. Aplikasi akan mencoba generate Po-Token secara otomatis. Cek log dengan tag `PoTokenGenerator`.
- **Chat Tidak Muncul**: Pastikan status akun TikTok sedang **LIVE** dan API Key sudah benar.
- **Overlay Tidak Muncul**: Pastikan izin "Display over other apps" sudah diizinkan di pengaturan sistem Android.
- **Musik Mati Sendiri**: Pada beberapa perangkat (Xiaomi, Oppo, Vivo), nonaktifkan optimasi baterai (*Battery Optimization*) untuk aplikasi ini agar service tidak dibunuh saat background.

---


## Disclaimer
Projek ini dibuat untuk keperluan edukasi. Pastikan Anda mematuhi kebijakan penggunaan (ToS) dari platform terkait (YouTube/TikTok).

---

## Donasi
Jika proyek ini membantu Anda, pertimbangkan untuk mendukung pengembang melalui Saweria:  
[**https://saweria.co/shinriMe**](https://saweria.co/shinriMe)
