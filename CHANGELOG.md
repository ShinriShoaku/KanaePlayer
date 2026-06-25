# Changelog - YTTikTokPlayer

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
