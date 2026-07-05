# Changelog - KanaePlayer

## [9.3.0] - 2025-02-13

### ✨ New Features
- **Follow Overlay**: Menambahkan fitur overlay baru untuk mendeteksi dan menampilkan notifikasi saat penonton mem-follow akun secara real-time.
- **Custom Style for Follow Overlay**: Dukungan kustomisasi gaya visual khusus untuk Follow Overlay.
- **Duration Settings**: Menambahkan pengaturan durasi tampilan untuk Join, Like, dan Follow Overlay agar pengguna dapat mengatur berapa lama notifikasi muncul di layar.
- **Seekbar Chat Command**: Penambahan perintah chat untuk mengaktifkan atau menonaktifkan seekbar secara dinamis (misal: `#seekbar on` / `#seekbar off`).

## [9.2.1] - 2025-11-12

### ✨ Improvements & Fixes
- **Sticker Support**: Menambahkan dukungan tampilan stiker pada Chat Overlay.
- **Overlay Refinement**: Perbaikan pada Join Overlay dan Like Overlay untuk sinkronisasi tampilan stiker dan animasi yang lebih baik.

## [9.0.1] - 2025-11-05

### ✨ New Features & Improvements
- **Canvas System**: Penambahan basis sistem Canvas untuk animasi dan interaksi visual yang lebih dinamis.
- **Like & Join Overlays**: Fitur overlay baru untuk mendeteksi dan merespon interaksi Like serta bergabungnya penonton (Join) secara real-time.
- **Visual Punch (Experimental)**: Implementasi awal efek Visual Punch untuk memberikan feedback visual yang lebih kuat.
- **Custom Style**: Dukungan kustomisasi gaya visual yang lebih luas untuk berbagai elemen antarmuka.

## [8.8.4] - 2025-10-30

### ⚙️ UI & UX Fixes
- **Layout Optimization (Custom Overlay Settings)**: Perbaikan masalah header yang tertutup pada perangkat tertentu atau Custom ROM dengan menerapkan `fitsSystemWindows` dan optimasi `ConstraintLayout`.
- **Enhanced Scrolling**: Mengganti `ScrollView` standar dengan `NestedScrollView` untuk integrasi yang lebih baik dengan komponen Material Design.
- **Title Bar Removal**: Menghapus Action Bar bawaan yang duplikat untuk memberikan ruang layar yang lebih luas dan tampilan yang lebih bersih, beralih sepenuhnya ke header kustom.

## [8.8.2] - 2025-10-27

### 🌐 EulerStream & TikTok Stability
- **Auto-Reconnection System**: Menambahkan mekanisme *Retry* otomatis hingga 5 kali dengan jeda waktu meningkat (*Exponential Backoff*) jika koneksi WebSocket terputus secara mendadak.
- **Enhanced Heartbeat**: Optimasi koneksi dengan `pingInterval` 30 detik untuk menjaga integritas socket dan mencegah *timeout* sepihak oleh ISP atau sistem Android.
- **5s Smart Delay**: Sistem secara cerdas mengabaikan perintah musik (#req, #skip, dll) selama 5 detik pertama setelah terhubung untuk mencegah "banjir" lagu lama, namun tetap menampilkan chat tersebut di log sebagai informasi.
- **System Live Log**: Pesan error koneksi, status penyambungan ulang, dan notifikasi sistem kini muncul langsung di Log Chat (Main UI) dengan ikon (ℹ️) untuk transparansi status koneksi.

### ⚙️ UI & UX Enhancements
- **Quick Access Settings**: Menambahkan fitur **Long-press** pada tombol toggle overlay di Bottom Sheet untuk masuk langsung ke pengaturan spesifik (Player, Queue, Chat, dll).
- **Improved Positioning Engine**: Sinkronisasi posisi (X, Y) yang lebih presisi saat menggunakan mode geser (*drag*) dan penyimpanan otomatis yang lebih reliabel.

### 🌐 Custom Overlay Improvements
- **Resource Optimization**: Peningkatan penanganan memori pada Custom WebView Overlay untuk menjamin notifikasi donasi (Saweria/Sociabuzz) tetap responsif 24/7.
- **Dynamic Scale Adjuster**: Perbaikan pada slider skala agar perubahan ukuran visual terasa lebih halus dan instan.

### 🐞 Bug Fixes
- Memperbaiki masalah *ghost connection* dimana koneksi lama tidak terputus sempurna saat berganti akun TikTok.
- Memperbaiki bug status UI yang macet di "Connecting" saat terjadi kegagalan otentikasi API Key.
- Perbaikan pada filter pesan duplikat agar tidak membuang pesan sistem yang penting.

## [8.7.0] - 2025-07-16

### 🌐 Peningkatan Custom Overlay
- **Hybrid Hiding Logic**: Mengganti sistem `View.GONE` menjadi `alpha = 0f` saat autohide. Hal ini mencegah sistem Android menangguhkan (*suspend*) proses JavaScript di WebView, memastikan notifikasi Saweria/Sociabuzz tetap terdeteksi 100% di background.
- **Instant WebView Wake-up**: Menambahkan pemanggilan `onResume()` saat overlay muncul kembali untuk memastikan performa rendering langsung maksimal tanpa delay.
- **Hot-Reload URL**: Perubahan URL di menu pengaturan kini langsung diterapkan secara real-time pada overlay yang sedang aktif tanpa perlu restart widget.
- **Stability Fix**: Memperbaiki konflik komunikasi JavaScript Bridge saat menggunakan lebih dari satu overlay dari domain yang sama.

### 🐞 Bug Fixes
- Memperbaiki masalah overlay yang sering "mogok" atau tidak mau muncul kembali setelah tersembunyi lama.
- Memperbaiki bug dimana video/audio di dalam WebView sering terhenti otomatis oleh sistem saat status GONE.

## [8.6.0] - 2025-07-15

### 🌐 Fitur Baru: Custom Overlay (Web-Based)
- **Multi-Slot Custom Overlay**: Sekarang kamu bisa menambahkan banyak overlay berbasis web (seperti Saweria, Sociabuzz, atau website eksternal lainnya) secara bebas.
- **Smart Auto-Hide Engine**:
    - **True Visibility State**: Overlay yang tidak aktif sekarang benar-benar diatur ke status `View.GONE`, menghemat resource GPU/CPU namun tetap "bangun" otomatis jika ada notifikasi baru.
    - **Advanced Wake-up**: Menggunakan *Mutation Observer* dan *AudioContext Hijacking* untuk mendeteksi notifikasi masuk atau media yang diputar di dalam WebView secara instan.
- **Precision Floating Adjuster**: Panel pengaturan terapung untuk mengatur **Ukuran (DP)**, **Skala**, dan **Opasitas Background** secara real-time langsung di atas overlay.
- **Simplified Workflow**: Alur penyimpanan yang lebih bersih—cukup "Simpan" konfigurasi tanpa mengganggu tampilan yang sedang aktif. Kontrol penuh melalui tombol **AKTIF/NONAKTIF**.

### ⚙️ Peningkatan Teknis & UI
- **Axis-Aligned Bounding Box**: Perbaikan pada sistem rotasi dan skala agar layout overlay tidak terpotong saat diputar.
- **Enhanced Settings UI**: Tampilan pengaturan yang lebih rapi dengan kategori Aksi Global (Nonaktifkan Semua).
- **Position Syncing**: Koordinat posisi (X, Y) sekarang tersinkronisasi otomatis setiap kali overlay digeser.

### 🐞 Bug Fixes
- Memperbaiki masalah overlay yang tetap menutupi layar meskipun transparan (Sekarang benar-benar `NOT_TOUCHABLE` saat tersembunyi).
- Memperbaiki bug timer autohide yang tidak ter-reset saat setelan diubah.

## [8.5.0] - 2025-06-25

### 🎁 Fitur Baru (TikTok Gift Enhancement)
- **TikTok Gift Icon Integration**: Notifikasi overlay sekarang dapat menampilkan ikon asli gift dari TikTok secara otomatis.
- **Smart Gift Detection**: Logika ekstraksi data gift diperbarui untuk mendukung format JSON terbaru dari TikTok/EulerStream, termasuk pengambilan nama gift dari teks deskripsi.
- **Gift Priority Setting**: Penambahan opsi di Bottom Sheet Settings untuk memilih antara menggunakan **Ikon Asli TikTok** (Prioritas) atau **Gambar Custom** pilihan sendiri.
- **Improved Duplicate Filter**: Sistem pelacakan ID pesan (`msgId`) yang lebih kuat untuk mencegah munculnya notifikasi ganda dari jalur WebSocket dan HTTP Polling.

### ⚙️ Peningkatan Teknis
- **JSON Chunk Logging**: Sistem logging sementara untuk membedah data JSON besar tanpa terpotong oleh batas Logcat (untuk kebutuhan debugging).
- **Glide Image Loading**: Optimalisasi pemuatan gambar dari URL eksternal untuk overlay yang lebih ringan.

### 🐞 Bug Fixes
- Memperbaiki masalah Nama Gift yang sering muncul sebagai "Gift" (default) menjadi nama asli (misal: "Rose").
- Memperbaiki masalah notifikasi gift/chat yang muncul dua kali (*double notification*).


## [8.3.0] - 2023-10-27

### 🚀 Fitur Baru
- **Independent Volume Control**: Penambahan slider volume terpisah untuk **Music**, **Chat TTS**, dan **Notification Overlay**.
- **Digital Volume Boost (LoudnessEnhancer)**: TTS sekarang mendukung penguatan suara hingga **200%** menggunakan amplifier digital agar suara asisten tetap terdengar kencang di atas musik.
- **Dynamic Audio Ducking**: Volume musik akan mengecil secara otomatis (Soft Ducking) sebesar 5-15% saat asisten atau notifikasi bersuara, dan kembali normal setelah selesai.

### 🛠️ Perbaikan & Peningkatan TTS (Text-to-Speech)
- **TTS-to-File Engine**: Mengubah sistem pembacaan teks menjadi file audio sementara (WAV) untuk mendapatkan kontrol volume yang lebih stabil dan presisi.
- **Indonesian Language Support**: Memaksa penggunaan locale `id-ID` agar suara asisten tetap berbahasa Indonesia meskipun sistem HP menggunakan bahasa lain.
- **Smart Filter Chat**: 
    - Mengabaikan pesan yang diawali dengan `@` (mention/tag).
    - Otomatis menyaring dan tidak membaca pesan yang merupakan *Command* (seperti `#req`, `#skip`, dll) berdasarkan pengaturan custom Anda.
    - Menghapus karakter `@` di tengah kalimat agar tidak dibaca "at".
- **Robust Fallback**: Menambahkan sistem cadangan jika mesin TTS error atau gagal membuat file, aplikasi akan otomatis beralih ke mode suara langsung tanpa crash.

### 🐞 Bug Fixes
- Memperbaiki masalah TTS Samsung yang volumenya sangat kecil.
- Memperbaiki interupsi audio yang menyebabkan musik berhenti (*pause*) saat asisten berbicara.
- Menambahkan pembersihan file sampah (`tts_cache.wav`) secara otomatis.

---
*Developed with ❤️ for the community.*
