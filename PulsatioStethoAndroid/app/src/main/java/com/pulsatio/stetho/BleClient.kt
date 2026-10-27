package com.pulsatio.stetho

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * ESP32 steteskopuna BLE baglantisi.
 *
 * Windows'tan farki: baglanti ayarlarini burada uygulama ister
 *  - requestConnectionPriority(HIGH)  -> 7.5-15 ms baglanti araligi
 *  - requestMtu(517)                  -> 240 ornekli buyuk paketler
 *  - 2M PHY
 *
 * Android ayni anda tek GATT islemi kabul eder; bu yuzden yazma islemleri
 * basit bir kuyruktan sirayla gonderilir.
 */
@SuppressLint("MissingPermission")
class BleClient(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onLog(msg: String)
        fun onConnectionChanged(connected: Boolean)
        fun onAudioPacket(data: ByteArray)     // BLE thread'inden cagrilir
        fun onStatus(text: String)
        fun onMtu(mtu: Int)
    }

    companion object {
        const val DEVICE_NAME = "Pulsatio-Stetho-Test"
        val SERVICE_UUID: UUID = UUID.fromString("7b8a1000-1234-4cde-9000-111111111111")
        val STATUS_UUID: UUID = UUID.fromString("7b8a1001-1234-4cde-9000-111111111111")
        val CONTROL_UUID: UUID = UUID.fromString("7b8a1002-1234-4cde-9000-111111111111")
        val AUDIO_UUID: UUID = UUID.fromString("7b8a1003-1234-4cde-9000-111111111111")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var controlChar: BluetoothGattCharacteristic? = null

    // --- GATT islem kuyrugu (yalnizca ana thread'de kullanilir) ---
    private val ops = ArrayDeque<() -> Boolean>()
    private var opBusy = false
    private val opTimeout = Runnable { opBusy = false; nextOp() }

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    // ------------------------------------------------------------------
    // Tarama
    // ------------------------------------------------------------------

    private val stopScanRunnable = Runnable {
        if (scanning) {
            stopScan()
            listener.onLog("Cihaz bulunamadı. ESP açık mı, PC uygulaması kapalı mı?")
        }
    }

    fun startScan() {
        if (gatt != null) { listener.onLog("Zaten bağlı / bağlanıyor"); return }
        val sc = adapter?.bluetoothLeScanner
        if (sc == null) { listener.onLog("Bluetooth kapalı"); return }
        if (scanning) return
        scanning = true
        listener.onLog("Taranıyor: $DEVICE_NAME")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        sc.startScan(null, settings, scanCallback)
        main.postDelayed(stopScanRunnable, 10_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        main.removeCallbacks(stopScanRunnable)
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) { }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val name = result.scanRecord?.deviceName ?: result.device.name
            val uuids = result.scanRecord?.serviceUuids
            val match = name == DEVICE_NAME || (uuids?.contains(ParcelUuid(SERVICE_UUID)) == true)
            if (match) {
                stopScan()
                listener.onLog("Bulundu: ${name ?: "?"} [${result.device.address}]  RSSI ${result.rssi} dBm")
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener.onLog("Tarama hatası: $errorCode")
        }
    }

    // ------------------------------------------------------------------
    // Baglanti
    // ------------------------------------------------------------------

    private fun connect(device: BluetoothDevice) {
        listener.onLog("Bağlanılıyor...")
        gatt = device.connectGatt(
            context, false, gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK
        )
    }

    fun disconnect() {
        stopScan()
        val g = gatt
        if (g == null) { listener.onLog("Bağlı değil"); return }
        g.disconnect()
    }

    /** Uygulama kapanirken: kaynaklari hemen birak */
    fun close() {
        stopScan()
        gatt?.close()
        gatt = null
        controlChar = null
    }

    private fun cleanup() {
        gatt?.close()
        gatt = null
        controlChar = null
        ops.clear()
        opBusy = false
        main.removeCallbacks(opTimeout)
    }

    private fun post(block: () -> Unit) {
        main.post { block() }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                post { listener.onLog("Bağlandı – MTU ve hızlı bağlantı isteniyor") }
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(517)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                post {
                    cleanup()
                    listener.onConnectionChanged(false)
                    listener.onLog("Bağlantı kapandı (status $status)")
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            post {
                listener.onMtu(mtu)
                listener.onLog("MTU = $mtu" + if (mtu >= 495) " (240 örnekli paket)" else "")
            }
            g.setPreferredPhy(
                BluetoothDevice.PHY_LE_2M_MASK,
                BluetoothDevice.PHY_LE_2M_MASK,
                BluetoothDevice.PHY_OPTION_NO_PREFERRED
            )
            g.discoverServices()
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            post { listener.onLog("PHY: TX=$txPhy RX=$rxPhy (2 = 2M)") }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID)
            if (svc == null) {
                post { listener.onLog("Steteskop servisi bulunamadı") }
                g.disconnect()
                return
            }
            val ctrl = svc.getCharacteristic(CONTROL_UUID)
            val audio = svc.getCharacteristic(AUDIO_UUID)
            val st = svc.getCharacteristic(STATUS_UUID)
            if (ctrl == null || audio == null) {
                post { listener.onLog("Control/Audio karakteristiği eksik") }
                g.disconnect()
                return
            }
            controlChar = ctrl

            // Once ses, sonra durum bildirimleri; en sonda "hazir"
            enqueue { enableNotify(g, audio) }
            if (st != null) enqueue { enableNotify(g, st) }
            enqueue {
                listener.onConnectionChanged(true)
                false   // beklenecek GATT islemi yok
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            post { opDone() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            post { opDone() }
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotify(c.uuid, value)
        }

        // Android 10-12
        @Deprecated("Android 13 oncesi icin")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                val v = c.value ?: return
                handleNotify(c.uuid, v.copyOf())
            }
        }
    }

    private fun handleNotify(uuid: UUID, value: ByteArray) {
        when (uuid) {
            AUDIO_UUID -> listener.onAudioPacket(value)
            STATUS_UUID -> {
                val text = String(value, Charsets.UTF_8)
                post { listener.onStatus(text) }
            }
        }
    }

    // ------------------------------------------------------------------
    // GATT islem kuyrugu
    // ------------------------------------------------------------------

    private fun enqueue(op: () -> Boolean) {
        main.post {
            ops.addLast(op)
            nextOp()
        }
    }

    private fun nextOp() {
        if (opBusy) return
        while (ops.isNotEmpty()) {
            val op = ops.removeFirst()
            val started = try { op() } catch (e: Exception) { false }
            if (started) {
                opBusy = true
                main.postDelayed(opTimeout, 1000)   // geri cagri gelmezse kilitlenme
                return
            }
        }
    }

    private fun opDone() {
        main.removeCallbacks(opTimeout)
        opBusy = false
        nextOp()
    }

    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD_UUID) ?: return false
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = v
            g.writeDescriptor(d)
        }
    }

    /** ESP'ye kontrol komutu (0x01 BELL ... 0x10 START, 0x11 STOP ...) */
    @Suppress("DEPRECATION")
    fun sendCommand(bytes: ByteArray) {
        val ch = controlChar
        if (ch == null) { listener.onLog("Komut gönderilemedi: bağlı değil"); return }
        enqueue {
            val g = gatt ?: return@enqueue false
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(
                    ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
        }
    }
}
