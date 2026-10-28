# Pulsatio Stetho – Android uygulaması

ESP32-S3 steteskoptan BLE ile sesi alır, telefonda (hoparlör, kablolu veya
Bluetooth kulaklık) çalar, dalga formunu gösterir ve WAV kaydeder.

- Android 10 ve üzeri
- Harici kütüphane yok (sadece Android SDK + Kotlin)
- ESP tarafında değişiklik gerekmez (aynı GATT servisi ve paket formatı)

---

## 1. Yol: GitHub ile APK al (bilgisayara bir şey kurmadan)

1. github.com'da ücretsiz hesap açın (varsa giriş yapın).
2. Sağ üstte **+ → New repository**. Ad: `PulsatioStethoAndroid`. **Private** seçebilirsiniz. **Create repository**.
3. Açılan sayfada **uploading an existing file** bağlantısına tıklayın.
4. Bu klasörün **içindeki her şeyi** (app, gradle, .github, gradlew, build.gradle.kts …)
   sürükleyip bırakın. `.github` klasörü gizli olabilir: Windows Gezgini'nde
   *Görünüm → Gizli öğeler* açın. **Commit changes**.
5. Üstteki **Actions** sekmesine gidin. "APK derle" işi otomatik başlar
   (başlamazsa *APK derle → Run workflow*). 3–5 dakika sürer.
6. Yeşil tik çıkınca işe tıklayın, en altta **Artifacts → PulsatioStetho-apk**.
   İnen zip'in içinde `app-debug.apk` var.
7. APK'yı telefona atın (USB, Drive, e-posta…) ve **3. adım**a geçin.

## 2. Yol: Android Studio ile

1. https://developer.android.com/studio adresinden Android Studio'yu kurun.
2. **File → Open** → bu klasörü (`PulsatioStethoAndroid`) seçin.
   İlk açılışta Gradle eşitlemesi gerekli SDK'yı indirir (birkaç dakika).
3. İki seçenek:
   - **Doğrudan telefona yükle:** Telefonda *Geliştirici seçenekleri → USB hata ayıklama*
     açık olsun (Ayarlar → Telefon hakkında → *Yapı numarası*na 7 kez dokunun).
     USB ile bağlayın, üstte telefonunuzu seçip yeşil ▶ **Run**'a basın.
   - **APK üret:** *Build → Build App Bundle(s)/APK(s) → Build APK(s)*.
     Dosya: `app/build/outputs/apk/debug/app-debug.apk`

## 3. Telefona kurulum

1. APK dosyasına dokunun.
2. "Bilinmeyen kaynaklardan yükleme" uyarısı çıkarsa, açtığınız uygulamaya
   (Dosyalar / Chrome / Drive) izin verin.
3. Play Protect "tanınmayan uygulama" derse **Yine de yükle**. (İmzasız
   hata ayıklama sürümü olduğu için normaldir.)

## Arayüz

- **Telefon (dikey):** tek sütun – üstte bağlantı durumu, büyük *Dinlemeye başla* düğmesi,
  dalga formu + tahmini nabız, kayıt, ardından mod / profil / ses kartları.
- **Tablet veya yatay ekran:** iki sütun – solda kontroller, sağda büyük dalga formu.
- Ekranı döndürmek bağlantıyı ve sesi **kesmez**.
- Seçili mod/profil vurgulu görünür; bağlanınca uygulamadaki seçimler ESP'ye gönderilir.
- Bağlantı koparsa uygulama **kendiliğinden yeniden bağlanır** ve dinlemeye devam eder.
- Kayıt düğmesinde geçen süre görünür. Ayrıntılar bölümünde kayıt defteri ve ESP durumu var.
- Nabız tahmini ~6 saniye sinyalden sonra çıkar (sinyal güvenilir değilse "--").
  Sadece yaklaşık bir göstergedir.

## 4. Kullanım

1. **PC'deki Python uygulamasını kapatın.** ESP aynı anda tek bağlantı kabul eder.
2. Uygulamayı açın, **Yakındaki cihazlar** iznini verin.
   (Android 10–11'de konum izni istenir ve **Konum** açık olmalıdır.)
3. **Bağlan** → log'da `MTU = 517 (240 örnekli paket)` görünmeli.
4. **Başlat** → ses gelir. Durum satırında `kayıp %` 0'a yakın olmalı.
5. **Ses** kaydırıcısı ile seviye, kulaklıkta **Hoparlör modu** kapalı;
   telefon hoparlöründe açık (kalp sesi 30–150 Hz'de, sanal bas onu duyulur yapar).
6. **Kaydet** → `Download/Pulsatio/stetho_YYYYMMDD_HHMMSS.wav`
   Bu dosyalar PC'deki HSMM raporlayıcısıyla doğrudan analiz edilebilir.

## Sorun giderme

| Belirti | Çözüm |
|---|---|
| "Cihaz bulunamadı" | ESP açık mı? PC uygulaması bağlı kalmış olabilir, ESP'yi resetleyin |
| Bağlanıp hemen kopuyor (status 133) | Bluetooth'u kapatıp açın, tekrar deneyin |
| MTU 23 kalıyor | Log'da "MTU isteği gönderilemedi" var mı bakın; ESP seri monitöründe `MTU changed:` satırını kontrol edin. ESP'de `NimBLEDevice::setMTU(517)` olmalı |
| Kayıp yüksek | Telefonu ESP'ye yaklaştırın; ESP seri monitöründe `fail`/`fifoDrop` |
| Ses yok ama "giriş" dBFS değişiyor | Ses seviyesini artırın, Hoparlör modunu açın |

Bu uygulama prototip/mühendislik amaçlıdır; klinik tanı aracı değildir.
