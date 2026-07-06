# Update Notes - KanaePlayer

## Update 10.0.0: Style Customization & Landscape Support

Update besar ini membawa fleksibilitas desain yang lebih luas, dukungan orientasi layar baru, dan perbaikan pada sistem penyimpanan posisi overlay.

### 1. New Custom Styles (Lyric & Queue)
- **Visual Variety**: Kini tersedia berbagai pilihan gaya layout untuk Lyric dan Queue overlay:
    - **Card Style**: Tampilan modern berbasis kartu dengan bayangan halus.
    - **Neon Style**: Efek cahaya neon yang mencolok untuk tema gaming.
    - **Glass Style**: Efek transparansi kaca (Frosted Glass) yang elegan.
    - **Minimal Style**: Desain ringkas tanpa banyak gangguan visual.

### 2. Full Landscape Support
- **Adaptive UI**: Menu Pengaturan (Settings) dan area interaksi Canvas kini mendukung mode Landscape secara penuh, memudahkan penggunaan saat perangkat diletakkan secara horizontal.

### 3. Overlay Stability & UI Cleanup
- **Save Position Fix**: Masalah posisi overlay yang sering kembali ke default setelah restart aplikasi kini telah diperbaiki sepenuhnya.
- **Streamlined Settings**: Menghapus fungsi pengatur lebar dan tinggi manual pada menu Bottom Sheet. Sistem kini secara cerdas mengatur ukuran optimal berdasarkan gaya layout yang dipilih untuk menjaga estetika dan integritas visual.

---

## Update 9.3.0: Follow Overlay & Duration Settings

Update ini memperkenalkan fitur Follow Overlay, kustomisasi durasi, dan perintah chat baru untuk kontrol seekbar.

### 1. Follow Overlay & Customization
- **New Follow Overlay**: Sekarang aplikasi dapat merespon aksi Follow dari penonton dengan notifikasi visual yang menarik.
- **Follow Style**: Kamu dapat mengatur tampilan Follow Overlay agar sesuai dengan tema streaming kamu melalui menu pengaturan.

### 2. Duration Settings
- **Display Duration**: Kontrol penuh atas durasi kemunculan overlay. Kamu bisa mengatur waktu tampil untuk Join, Like, dan Follow overlay secara terpisah.

### 3. Seekbar Control Command
- **Chat Command**: Admin atau moderator dapat mengaktifkan atau menyembunyikan seekbar player menggunakan perintah chat, memberikan kontrol lebih tanpa harus membuka aplikasi.

---

## Update 9.2.1: Sticker Support & Overlay Fixes

Update ini membawa perbaikan pada sistem overlay untuk mendukung tampilan stiker yang lebih baik.

### 1. Sticker Support in Chat
- **Chat Sticker**: Chat overlay kini dapat menampilkan stiker TikTok secara langsung, membuat interaksi chat terasa lebih hidup.

### 2. Join & Like Overlay Improvements
- **Visual Fixes**: Perbaikan pada Join dan Like overlay agar elemen visual dan stiker muncul dengan posisi dan animasi yang lebih tepat.

---

## Update 9.0.1: Visual Enhancement & Overlays

Update ini menghadirkan fitur visual baru dan peningkatan sistem overlay untuk pengalaman streaming yang lebih interaktif.

### 1. Canvas & Visual Punch (Experimental)
- **Canvas System**: Dasar baru untuk pergerakan elemen visual yang lebih halus.
- **Visual Punch**: Efek getaran atau dorongan visual saat terjadi aksi tertentu di dalam aplikasi.

### 2. Like & Join Overlays
- **Like Interaction**: Overlay khusus yang memberikan respon visual saat penonton memberikan Like.
- **Join Animation**: Menampilkan notifikasi yang lebih menarik ketika penonton baru bergabung dalam Live.

### 3. Custom Style
- **Flexible Styling**: Peningkatan kemampuan untuk mengatur gaya visual sesuai keinginan pengguna, memberikan sentuhan personal yang lebih mendalam pada antarmuka aplikasi.

---

## Update 8.8.4: Perbaikan Layout & Tampilan Bersih

Update ini fokus pada perbaikan visual dan kenyamanan antarmuka pengguna.

### 1. Perbaikan Header Settings
- **Safe Area Support**: Menambahkan dukungan `fitsSystemWindows` pada menu Custom Overlay Settings agar bagian atas layar tidak tertutup oleh status bar atau notch, terutama pada Custom ROM.
- **Modern Layout**: Menggunakan `ConstraintLayout` untuk header agar elemen teks dan tombol tetap presisi di berbagai ukuran layar.

### 2. Tampilan Full Custom
- **Remove Default Action Bar**: Menghapus bar judul bawaan sistem yang duplikat. Kini aplikasi menggunakan tema `NoActionBar` sehingga seluruh area layar digunakan secara maksimal oleh desain kustom aplikasi.

---

## Update 8.8.3: Koneksi Stabil & Navigasi Cepat

Update ini berfokus pada stabilitas koneksi TikTok Live melalui EulerStream dan kemudahan akses pengaturan overlay.

### 1. Stabilitas Koneksi (EulerStream)
- **Self-Healing Connection**: Jika koneksi internet terganggu, aplikasi kini secara otomatis mencoba menyambung kembali (*Auto-Retry*) hingga 5 kali sebelum beralih ke mode cadangan (HTTP Polling).
- **Anti-Spam Delay**: Delay 5 detik saat awal koneksi untuk menyaring perintah lama yang menumpuk, memastikan antrian lagu tetap bersih saat Live baru dimulai.
- **Live Debugging**: Semua kendala koneksi kini diinformasikan langsung melalui chat log di layar utama, sehingga kamu tahu persis jika ada masalah pada API Key atau Username.

### 2. Navigasi Pengaturan "Long-Press"
- Membuka pengaturan overlay kini jauh lebih cepat. Cukup **tekan lama** tombol overlay manapun di menu Bottom Sheet untuk langsung mengatur posisi, skala, dan fitur spesifik overlay tersebut tanpa navigasi yang rumit.

---

## Update 8.7.0: Custom Overlay System

## Fitur Utama

### 1. Multi-Web Overlay
- Mendukung berbagai platform alert (Saweria, Sociabuzz, Streamlabs, dll).
- Jumlah slot overlay yang tidak terbatas (sesuai kebutuhan).
- Pengaturan URL, Nama, dan visual yang terpisah untuk tiap slot.

### 2. Logika "Smart Hide" (Autohide)
Fitur ini didesain khusus agar layar tetap bersih saat tidak ada aktivitas:
- **Persistent Background Process**: Menggunakan teknik transparansi (`alpha = 0f`) alih-alih menyembunyikan total (`GONE`). Ini menjamin WebView tetap aktif menjalankan skrip deteksi notifikasi di background.
- **Instant Wake-up**: Mengawasi perubahan DOM dan audio di dalam WebView menggunakan *Mutation Observer*. Begitu ada aktivitas (seperti notifikasi Saweria baru), overlay akan langsung tampil kembali secara instan.
- **Auto Media Check**: Sistem akan terus memantau apakah ada media (audio/video) yang sedang diputar sebelum memutuskan untuk bersembunyi kembali.

### 3. Real-time Visual Adjuster
Kamu bisa mengatur tampilan overlay tanpa harus bolak-balik ke menu pengaturan:
- **Drag & Scale**: Geser posisi dan cubit (*pinch*) untuk mengatur ukuran.
- **Opacity Slider**: Mengatur tingkat transparansi latar belakang overlay agar tidak menutupi konten utama.
- **Persistent State**: Semua perubahan posisi dan skala disimpan secara otomatis ke memori internal.

---
*Fitur ini memberikan fleksibilitas penuh bagi streamer atau pengguna yang ingin memantau notifikasi eksternal sambil tetap menjalankan aplikasi dengan lancar.*
