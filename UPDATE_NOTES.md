# Update Notes - KanaePlayer

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
