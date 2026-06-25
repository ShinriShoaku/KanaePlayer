# Update Notes - TikTok Gift Enhancement

Update ini fokus pada peningkatan fitur notifikasi TikTok Live, terutama pada sistem deteksi gift dan kustomisasi visual.

## Ringkasan Perubahan

### 1. Sistem Deteksi Gift (TikTokLiveManager)
- **Smart Data Extraction**: Aplikasi sekarang mampu mengekstrak nama gift dan URL ikon secara otomatis dari berbagai format data TikTok (termasuk objek `giftDetails` terbaru).
- **Describe-Text Parsing**: Menambahkan sistem cerdas untuk membedah teks deskripsi (contoh: *"ฅ ネクサス ฅ gifted the host 1 Rose"*) guna mendapatkan nama gift jika data teknis lainnya kosong.
- **Deduplication Engine**: Memperbaiki masalah notifikasi ganda dengan sistem pelacakan `msgId` yang unik untuk setiap event.

### 2. Peningkatan Visual & Overlay
- **TikTok Gift Icon Support**: Menambahkan kemampuan untuk menampilkan ikon asli gift langsung dari TikTok (seperti mawar, topi, dll) pada overlay notifikasi.
- **Priority Settings**: Pengguna dapat memilih antara menggunakan ikon asli TikTok atau gambar custom miliknya melalui menu pengaturan notifikasi.
- **Glide Integration**: Menggunakan library Glide untuk pemuatan gambar URL yang halus dan efisien di atas layar.

### 3. Pengaturan (Bottom Sheet Overlays)
- **Checkbox "Use TikTok Gift Icon"**: Kontrol penuh bagi pengguna untuk menentukan tampilan notifikasi gift.
- **Persistent Storage**: Pilihan pengaturan disimpan secara permanen di SharedPreferences.

---
*Update ini meningkatkan interaksi visual saat live streaming dengan memberikan umpan balik yang lebih akurat terhadap gift yang dikirim penonton.*
