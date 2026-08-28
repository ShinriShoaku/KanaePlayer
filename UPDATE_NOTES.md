# Update Notes - KanaePlayer

## Update 12.3.6: Bug Fixes, Library Updates & New Design

Update ini membawa perbaikan stabilitas dan penyegaran visual pada bagian media player.

### 1. Perbaikan Bug (Device Compatibility)
- Mengatasi beberapa kendala teknis dan crash yang dilaporkan pada model smartphone tertentu.
- Optimasi performa untuk memastikan aplikasi berjalan lebih lancar di berbagai versi Android.

### 2. Update Library
- Memperbarui beberapa library internal dan dependensi pihak ketiga ke versi terbaru untuk meningkatkan keamanan dan efisiensi sistem.

### 3. Media Player Style Design
- Penambahan pilihan gaya desain baru untuk Media Player yang lebih modern, memberikan pengalaman visual yang lebih segar saat mendengarkan musik.

---

# Update Notes - KanaePlayer

## Update 12.3.4: Authorized User & NL Studio Sync Fix

Update ini memperkenalkan fitur keamanan baru untuk kontrol perintah chat dan perbaikan integrasi dengan ekosistem NL Studio.

### 1. Authorized User (Mode Skip/Command)
- **Role-Based Access**: Kamu sekarang bisa mengatur siapa yang berhak melewati lagu (#skip) atau menggunakan perintah kontrol lainnya.
- **Followers Mode**: Memberikan akses kepada semua penonton yang sudah mengikuti (follow) akun kamu.
- **Specific User List**: Daftar putih (whitelist) untuk user tertentu yang kamu percayai. Cukup masukkan username TikTok mereka tanpa tanda @.
- **Enhanced Privacy**: Mencegah penonton yang tidak dikenal atau bot untuk mengganggu antrian lagu kamu.

### 2. Perbaikan AIDL NL Studio
- **Communication Bridge**: Mengoptimalkan interface AIDL untuk memastikan data lagu dan status player terkirim dengan lancar ke aplikasi NL Studio.
- **Stability Fix**: Mengatasi masalah "Service Disconnected" yang kadang muncul pada beberapa perangkat saat sinkronisasi aktif.

---

# Update Notes - KanaePlayer

## Update 12.3.0: Background Stability & Battery Optimization

Update ini difokuskan pada penguatan pondasi aplikasi agar tetap stabil berjalan di background, terutama pada perangkat dengan manajemen baterai yang agresif.

### 1. Pengecualian Optimasi Baterai
- **Manual Exemption Dialog**: Aplikasi kini secara cerdas mendeteksi jika sistem Android membatasi aktivitas background-nya. Jika terdeteksi, dialog informatif akan muncul untuk memandu pengguna memberikan izin "Don't Optimize", yang sangat krusial agar musik tidak tiba-tiba mati.
- **Persistent Permission**: Izin ini membantu aplikasi melewati pembatasan standar sistem Android demi kelancaran fitur Foreground Service.

### 2. Peningkatan Prioritas Layanan (Service Priority)
- **Thread Boosting**: Melalui fitur `boostServicePriority()`, thread utama yang menangani audio dan chat kini berjalan dengan prioritas foreground (`THREAD_PRIORITY_FOREGROUND`), mengurangi risiko "freeze" saat CPU sedang sibuk.
- **Notification Importance**: Level Notification Channel ditingkatkan menjadi `IMPORTANCE_DEFAULT`. Hal ini memberikan sinyal kepada sistem operasi bahwa layanan ini memiliki kepentingan yang lebih tinggi dibandingkan proses background biasa.

### 3. Mekanisme Anti-Sleep (Auto-Renewing Wake Lock)
- **CPU Keep-Alive**: Menambahkan sistem *Partial Wake Lock* yang diperbarui secara otomatis setiap 8 menit. Ini memastikan CPU tetap aktif untuk memproses data streaming meskipun layar perangkat dalam keadaan mati.
- **Battery-Friendly Cleanup**: Sistem dirancang agar Wake Lock segera dilepas saat aplikasi ditutup secara normal, mencegah pengurasan baterai yang tidak perlu (battery leak).

> [!NOTE]
> Perubahan ini adalah pendekatan paling realistis untuk menjaga stabilitas aplikasi. Namun, perlu diingat bahwa beberapa fitur vendor seperti **Game Turbo** atau **Game Space** yang sangat agresif mungkin masih bisa membatasi aplikasi karena kebijakan scheduler CPU internal vendor yang tidak dapat di-override secara paksa oleh aplikasi pihak ketiga.

---

# Update Notes - KanaePlayer

## Update 12.2.5

- **Chrome OS & Emulator Support**: Menambahkan dukungan untuk menjalankan aplikasi di Chrome OS dan emulator PC.
- **Web Overlay Unified**: Web Overlay kini menjadi sistem tunggal untuk mengatur semua dukungan overlay berbasis web seperti Sociabuzz, Saweria, dll.
- **Web Overlay Fixes**: Memperbaiki berbagai masalah stabilitas dan bug pada sistem Web Overlay.
- **Removal of Saweria Overlay**: Menghapus fitur Saweria Overlay khusus karena fungsionalitasnya sudah terintegrasi sepenuhnya ke dalam Web Overlay yang lebih fleksibel.

---

# Update Notes - KanaePlayer

## Update 12.2.4

- **NL Studio App (AIDL) Link**: Integrasi link baru ke aplikasi NL Studio menggunakan protokol AIDL untuk sinkronisasi yang lebih baik.
- **Android 15 Foreground Services Fix**: Perbaikan kritis untuk kepatuhan sistem Android 15 guna mencegah penghentian paksa layanan di background.
- **Queue UI Improvements**: Pembaruan desain pada antrian lagu untuk visibilitas dan kemudahan penggunaan yang lebih baik.
- **Minor Fixes**: Perbaikan bug kecil dan optimalisasi performa umum.

---

# Update Notes - KanaePlayer

## Update 11.2.1

- **NL Studio App Integration**: Kini terdapat shortcut langsung untuk membuka aplikasi NL Studio dari splash screen jika aplikasi tersebut terinstal.
- Perbaikan minor dan peningkatan stabilitas.

---

# Update Notes - KanaePlayer

## Update 11.2.0

- Added **Always Show** and **Chat History** options.
- Optimized cache using JSON.
- Added new splash screen for initialization and update detection.
- UI refinements.

---

# Update Notes - KanaePlayer

## Update 10.1.7: Quick Overlay Scale & Landscape Fixes

Update ini membawa peningkatan pada fitur Quick Custom Overlay serta perbaikan stabilitas pada sistem Gift.

### 1. Scale Feature for Quick Overlay
- **Adjustable Size**: Kini kamu bisa mengatur skala (ukuran) dari Quick Custom Overlay secara real-time, memungkinkan penyesuaian yang lebih presisi dengan elemen layar lainnya.

### 2. Gift System Enhancement
- **Gift Sound Fix**: Memperbaiki masalah dimana beberapa suara gift tidak terdeteksi atau gagal diputar.
- **More Gift Items**: Penambahan aset visual dan suara untuk item gift baru agar notifikasi terasa lebih variatif.

### 3. Landscape Mode Optimization
- **Better Layout**: Perbaikan pada antarmuka Quick Custom Overlay saat berada dalam mode landscape, memastikan semua kontrol tetap mudah diakses dan tidak terpotong.

---

## Update 10.1.6: Visual Punch & Edit Text Effect

Update ini menambahkan fitur visual baru pada sistem overlay untuk memberikan pengalaman yang lebih interaktif.

### 1. Visual Punch in Web Browser Overlay
- **Dynamic Interaction**: Menambahkan fungsi efek visual punch pada web browser overlay, memungkinkan feedback visual yang lebih kuat saat berinteraksi dengan konten web.

### 2. Edit Text Effect in Custom Overlay
- **Enhanced Customization**: Kini kamu dapat edit text secara langsung pada custom overlay, memberikan fleksibilitas lebih dalam menampilkan informasi teks yang dinamis.

---

## Update 10.1.4: UI Flexibility & Custom Soundboard

Update ini menghadirkan kembali kontrol dimensi overlay yang fleksibel serta fitur audio kustom untuk interaksi yang lebih dinamis.

### 1. Overlay Width Control
- **Manual Dimensioning**: Menjawab masukan pengguna, kini fungsi pengaturan lebar (width) telah dikembalikan untuk Player, Queue, Lyrics, dan Chat overlay. Kamu bisa menyesuaikan ukuran overlay agar pas dengan layout streaming kamu.

### 2. Quick Custom Overlay & Soundboard
- **All-in-One Customization**: Menambahkan fitur Quick Custom Overlay yang mencakup:
    - **Soundboard**: Putar efek suara secara instan melalui overlay.
    - **Animations**: Pilihan animasi baru untuk tampilan overlay yang lebih hidup.
    - **Overlay Layout**: Kustomisasi tata letak overlay dengan lebih cepat dan mudah.

### 3. Custom Gift Sound
- **Personalized Alerts**: Sekarang kamu bisa mengunggah atau memilih file suara sendiri untuk diputar saat mendapatkan Gift di TikTok Live, memberikan ciri khas unik pada stream kamu.

### 4. Keyboard Mapping (Experimental)
- **Keyboard Control**: Memungkinkan penggunaan keyboard eksternal untuk mengontrol berbagai fungsi aplikasi (masih dalam tahap eksperimental).

---

## Update 10.0.5: Stability & Android 11 Fixes

Update ini berfokus pada stabilitas aplikasi melalui integrasi Firebase dan perbaikan bug fatal yang terjadi pada perangkat Android 11.

### 1. Firebase Crashlytics
- **Real-time Monitoring**: Kini pengembang dapat mendeteksi dan memperbaiki crash lebih cepat berkat integrasi sistem pelaporan error otomatis.

### 2. Android 11 Compatibility (The "Oppo Fix")
- **URLDecoder Patch**: Memperbaiki error `NoSuchMethodError` yang terjadi saat memproses link YouTube pada perangkat dengan sistem operasi Android 11.
- **Modern Java Support**: Mengaktifkan sistem *Desugaring* sehingga HP lama bisa menjalankan instruksi Java terbaru tanpa kendala.
- **Background WebView Fix**: Menjamin proses pengambilan token YouTube (PoToken) berjalan lancar meskipun di background service.

### 3. Engine Stability
- **Concurrent Processing**: Peningkatan manajemen memori saat menerima ribuan chat TikTok sekaligus guna mencegah aplikasi tertutup tiba-tiba.

---

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
