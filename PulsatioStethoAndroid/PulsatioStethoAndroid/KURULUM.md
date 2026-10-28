# Pulsatio Stetho 0.2 – Android uygulaması

ESP32-S3 dijital steteskop için muayene uygulaması: hasta kaydı → odak noktalarında
kayıt → otomatik analiz → sonuç, dinleme ve dışa aktarma.

- Android 10 ve üzeri, telefon ve tablet (dikey/yatay)
- Harici kütüphane yok (sadece Android SDK + Kotlin)
- ESP tarafında değişiklik gerekmez (v2 firmware, aynı GATT servisi)

> Prototip / mühendislik sürümüdür; tanı aracı değildir.

---

## Akış

1. **Ana sayfa** – cihaz durumu, *Yeni muayene*, son muayeneler, günlük özet
2. **Hasta** – Hasta ID (zorunlu), ad, yaş, cinsiyet, not; son hastalardan hızlı seçim
3. **Kayıt** – gövde haritasında **Aort, Pulmoner, Erb, Triküspit, Mitral** noktaları.
   Noktayı seçin, kayıt düğmesine basın; 10/15/30 sn sonra kayıt kendiliğinden durur,
   analiz edilir, nokta ✓ ile işaretlenir ve sıradaki noktaya geçilir.
   Canlı dalga formu ve nabızla atan kalp göstergesi vardır.
4. **Sonuç** – genel nabız, ritim, sinyal kalitesi; bulgu göstergeleri; her nokta için
   S1/S2 işaretli dalga formu (parmakla kaydırılır), sistol/diyastol süreleri,
   kaydı **dinleme** ve **paylaşma** (WAV + rapor.json + rapor.txt).

Alt menü: **Ana sayfa · Kayıtlar** (ID/ad ile arama) **· Cihaz** (debug).

**Cihaz sayfası:** bağlantı ve MTU, canlı paket istatistikleri, test dinleme,
ESP ayarları (mod, Comfort/Diagnostic, BLE kaynağı Ham/İşlenmiş/Odaklı, kulaklık odağı
S1…Diyastol), telefon ses kazancı, hoparlör modu, sensör kanalları (PCG aktif,
EKG/SpO₂ ileride), kayıt defteri ve ESP durum satırı.

## Analiz neleri ölçer?

| Ölçüm | Yöntem |
|---|---|
| Nabız | 25–400 Hz zarfının otokorelasyonu + atım takibi |
| Ritim | Ardışık atım aralıklarının değişim katsayısı (<%8 düzenli) |
| S1 / S2, sistol, diyastol | Nabza göre beklenen sistol süresi (≈ 500 − 2,1×nabız ms) civarında ikinci ses |
| Sinyal kalitesi (0–100) | Periyodiklik, kapsanan döngü oranı, kırpılma, BLE kaybı |
| Yüksek frekans göstergesi | Sistol/diyastolde 150–400 Hz enerjisi (gürültü tabanı çıkarılmış) / S1 enerjisi |

Test: 11 Ekim kaydında nabız 95, ritim düzenli, sistol 270 ms, diyastol 360 ms, uyarı yok.
Sentetik sistolik/diyastolik üfürüm eklenen kayıtlarda gösterge doğru bölümde tetiklendi.

## Veriler nerede?

- Uygulama içi: muayeneler uygulamanın kendi klasöründe (`session.json` + WAV).
  Uygulama kaldırılırsa silinir.
- **Paylaş** düğmesi: `Download/Pulsatio/<HastaID>_<tarih>/` klasörüne
  WAV dosyaları, `rapor.json` ve `rapor.txt` kopyalanır ve paylaşma menüsü açılır.
  WAV'lar PC'deki HSMM raporlayıcısıyla doğrudan analiz edilebilir.
- `session.json` biçimi kanal tabanlıdır (`"channels": ["PCG"]`); EKG eklendiğinde
  aynı muayeneye `ECG` kanalı olarak girer.

---

## APK alma

### 1. Yol: GitHub (bilgisayara bir şey kurmadan)

1. GitHub'da yeni repo (Private olabilir) → **uploading an existing file**.
2. Bu klasörün **içindeki her şeyi** yükleyin (`.github` gizli olabilir: Windows Gezgini →
   *Görünüm → Gizli öğeler*). Eski sürümü yüklediyseniz üzerine yazın.
   **Not:** Eski `WaveformView.kt` dosyası artık yok; repoda kaldıysa silin.
3. **Actions** sekmesi → "APK derle" (3–5 dk) → **Artifacts → PulsatioStetho-apk**.

### 2. Yol: Android Studio

**File → Open** → klasör → telefonda USB hata ayıklama açık → ▶ **Run**.

## Telefona kurulum

APK'ya dokunun → bilinmeyen kaynak iznini verin → Play Protect uyarısında **Yine de yükle**.
Eski sürümün üzerine kurulabilir.

## İlk kullanım

1. PC'deki Python uygulamasını kapatın (ESP tek bağlantı kabul eder).
2. **Yakındaki cihazlar** iznini verin (Android 10–11: konum izni ve Konum açık).
3. Ana sayfada **Bağlan** → "Bağlı · MTU 517" görünmeli.
4. **Yeni muayene** → Hasta ID → **Kayda başla**.

## Sorun giderme

| Belirti | Çözüm |
|---|---|
| Bağlanıyor… kalıyor | ESP açık mı, PC uygulaması bağlı mı? ESP'yi resetleyin |
| MTU 23 / "(düşük)" | Cihaz sayfası → kayıt defterinde "MTU" satırları; ESP'de `setMTU(517)` |
| Kalite "Zayıf" | Göğüs başlığını sabit ve tam temaslı tutun, konuşmayın, tekrar kaydedin |
| Kırpılma uyarısı | Cihaz sayfasında Diagnostic profil / ESP kazancını azaltın |
| Telefon hoparlöründe ses zayıf | Cihaz → Hoparlör modu açık, kazancı artırın; en iyisi kulaklık |
| Derleme hatası | Actions log'unu (kırmızı adım) kopyalayıp gönderin |
