# 🎵 KanaePlayer – Ultimate YouTube x TikTok Live Integration

[![Android Version](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-35-blue.svg)](https://developer.android.com)
[![Platform](https://img.shields.io/badge/Platform-Android-orange.svg)](https://android.com)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**KanaePlayer** adalah aplikasi pemutar audio YouTube revolusioner yang dirancang khusus untuk streamer TikTok. Aplikasi ini memungkinkan penonton Anda melakukan request lagu secara real-time melalui chat TikTok Live, lengkap dengan sistem overlay yang canggih dan stabil.

Evolusi dari `main.py` (FastAPI + yt-dlp + mpv) menjadi aplikasi Android native yang ditenagai oleh **NewPipeExtractor**.

---

## ✨ Fitur Unggulan

- **🚀 YouTube Audio Streaming**: Pemutaran audio super ringan langsung dari YouTube.
- **💬 TikTok Live Chat Sync**: Integrasi mulus dengan TikTok Live menggunakan **EulerStream API**.
- **🖼️ Advanced Floating Overlays**:
  - **Playing Overlay**: Menampilkan informasi lagu saat ini.
  - **Queue Overlay**: Daftar antrian lagu yang transparan.
  - **Chat & Event Overlays**: Menampilkan chat, stiker, gift, join, dan follow secara real-time.
- **🛡️ Anti-Bot Bypass**: Sistem **PoTokenGenerator** (WebView-based) unik untuk menghindari error 403 & blokir YouTube.
- **📻 Background Playback**: Berjalan sebagai Foreground Service agar musik tetap mengalir meskipun layar mati.
- **🎮 Chat Command System**: Kendali penuh via chat (`#req`, `#skip`, `#seekbar`, dll) yang bisa dikustomisasi.
- **🎨 Custom Styling**: Atur gaya visual, durasi, dan posisi overlay sesuai keinginan Anda.

---

## 🆕 Pembaruan Terkini (v9.3.0)

- **Follow Overlay**: Notifikasi real-time saat penonton mem-follow akun Anda.
- **Seekbar Chat Command**: Kontrol tampilan seekbar via chat (`#seekbar on`/`off`).
- **Duration Settings**: Atur berapa lama notifikasi Join, Like, dan Follow muncul.
- **Sticker Support**: Tampilan stiker TikTok kini didukung di Chat Overlay.

---

## 🛠️ Tech Stack

| Kategori | Teknologi |
|---|---|
| **Audio Engine** | Media3 ExoPlayer |
| **Extraction** | NewPipeExtractor |
| **Bypass Logic** | Custom Po-Token Generator (WebView + BotGuard VM) |
| **Live Sync** | EulerStream API (WebSocket + HTTP Fallback) |
| **UI/UX** | XML Layouts + Material Components + WindowManager |
| **Image Engine** | Glide |

---

## 🚀 Persiapan & Instalasi

### 1. Prasyarat
- Android 8.0 (Oreo) atau lebih tinggi.
- API Key dari [EulerStream](https://eulerstream.com).

### 2. Langkah Setup
1. **Instal APK**: Download dan instal versi terbaru.
2. **Konfigurasi API**: Masuk ke menu **SETTINGS**, masukkan **API Key** dan **Username TikTok** Anda.
3. **Izinkan Overlay**: Klik "Grant Overlay Permission" agar aplikasi bisa menampilkan jendela melayang.
4. **Optimasi Baterai**: Nonaktifkan pembatasan baterai agar service tidak terhenti di background.

---

## ⌨️ Perintah Chat (Commands)

| Perintah | Fungsi | Contoh |
|:---:|---|---|
| `#req` | Menambah lagu ke antrian | `#req Lathi` atau `#req [URL]` |
| `#skip` | Melewati lagu saat ini | `#skip` |
| `#q` | Melihat daftar antrian | `#q` |
| `#stop` | Menghentikan musik | `#stop` |
| `#seekbar` | Toggle tampilan progress | `#seekbar on` |
| `#cm` | Menghapus antrian spesifik | `#cm 1` |

> [!TIP]
> Semua prefix dan nama perintah dapat diubah melalui menu **Settings** di dalam aplikasi!

---


## ⚠️ Troubleshooting

- **Error 403 / Sign-in Required**: Cek tab `PoTokenGenerator` di log, pastikan generator berjalan.
- **Chat Terputus**: Gunakan fitur *Auto-Reconnection* yang akan mencoba menyambung kembali hingga 5 kali.
- **Overlay Hilang**: Pastikan izin "Display over other apps" sudah aktif.

---

## 🤝 Kontribusi & Dukungan

Proyek ini dikembangkan dengan ❤️ untuk komunitas streamer.

- **Donasi**: Dukung pengembangan lebih lanjut melalui [Saweria](https://saweria.co/shinriMe).
- **Disclaimer**: Gunakan aplikasi ini dengan bijak sesuai kebijakan platform YouTube dan TikTok.

---
Copyright © 2026 **KanaePlayer Team**.
