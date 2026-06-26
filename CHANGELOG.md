# Changelog - YTTikTokPlayer

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
