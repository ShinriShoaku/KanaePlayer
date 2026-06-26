# Update Notes - Custom Overlay System

Update 8.6.0 memperkenalkan sistem **Custom Overlay** yang fleksibel, memungkinkan pengguna untuk menambahkan widget pihak ketiga (seperti alert donasi) langsung ke dalam tampilan overlay.

## Fitur Utama

### 1. Multi-Web Overlay
- Mendukung berbagai platform alert (Saweria, Sociabuzz, Streamlabs, dll).
- Jumlah slot overlay yang tidak terbatas (sesuai kebutuhan).
- Pengaturan URL, Nama, dan visual yang terpisah untuk tiap slot.

### 2. Logika "Smart Hide" (Autohide)
Fitur ini didesain khusus agar layar tetap bersih saat tidak ada aktivitas:
- **Automatic Sleep**: Menyembunyikan overlay (`View.GONE`) jika tidak ada aktivitas media atau perubahan visual dalam durasi tertentu.
- **Instant Wake-up**: Mengawasi perubahan DOM dan audio di dalam WebView. Begitu notifikasi muncul di web, overlay akan langsung tampil kembali secara otomatis.
- **Resource Efficient**: Mengurangi beban render sistem saat overlay dalam kondisi tersembunyi.

### 3. Real-time Visual Adjuster
Kamu bisa mengatur tampilan overlay tanpa harus bolak-balik ke menu pengaturan:
- **Drag & Scale**: Geser posisi dan cubit (*pinch*) untuk mengatur ukuran.
- **Opacity Slider**: Mengatur tingkat transparansi latar belakang overlay agar tidak menutupi konten utama.
- **Persistent State**: Semua perubahan posisi dan skala disimpan secara otomatis ke memori internal.

---
*Fitur ini memberikan fleksibilitas penuh bagi streamer atau pengguna yang ingin memantau notifikasi eksternal sambil tetap menjalankan aplikasi dengan lancar.*
