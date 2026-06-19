# Changelog - YTTikTokPlayer

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
