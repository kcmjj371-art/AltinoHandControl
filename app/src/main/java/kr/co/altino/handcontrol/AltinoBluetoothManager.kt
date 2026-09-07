package kr.co.altino.handcontrol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AltinoBluetoothManager(
    private val context: Context,
    private val onState: (String) -> Unit
) {
    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Microchip Transparent UART used by ALTINO LITE BLE module.
        private val MCHP_SERVICE_UUID: UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
        private val MCHP_TX_UUID: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
        private val MCHP_RX_UUID: UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        closeInternal()
        onState("Bluetooth 오류: ${t.message ?: t.javaClass.simpleName}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val discovered = ConcurrentHashMap<String, BluetoothDevice>()
    private var scanCallback: ScanCallback? = null
    private var connectJob: Job? = null

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var txCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var rxCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var negotiatedMtu = 23
    @Volatile private var bleReady = false
    @Volatile private var receiveCount = 0L
    @Volatile private var useTxPathNext = true

    fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverAltinoDevices(onUpdate: (List<BluetoothDevice>) -> Unit) {
        if (!hasConnectPermission()) {
            onState("Bluetooth 연결 권한 필요")
            return
        }

        discovered.clear()
        runCatching {
            adapter?.bondedDevices?.forEach { d ->
                val name = runCatching { d.name.orEmpty() }.getOrDefault("")
                if (name.contains("ALTINO", ignoreCase = true)) discovered[d.address] = d
            }
        }
        onUpdate(sortedDevices())

        if (!hasScanPermission()) {
            onState("BLE 검색 권한 필요")
            return
        }

        stopScan()
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            onState("BLE 검색을 지원하지 않는 기기입니다")
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val advertisedName = result.scanRecord?.deviceName.orEmpty()
                val deviceName = runCatching { d.name.orEmpty() }.getOrDefault("")
                if (advertisedName.contains("ALTINO", true) || deviceName.contains("ALTINO", true)) {
                    discovered[d.address] = d
                    onUpdate(sortedDevices())
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                onState("BLE 검색 실패: $errorCode")
            }
        }
        scanCallback = callback
        onState("알티노 라이트 BLE 검색 중…")
        scanner.startScan(callback)
        scope.launch {
            delay(6000)
            stopScan()
            onState(if (discovered.isNotEmpty()) "기기 검색 완료: ${discovered.size}대" else "알티노 라이트를 찾지 못했습니다")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sortedDevices(): List<BluetoothDevice> {
        return discovered.values.sortedWith(
            compareByDescending<BluetoothDevice> {
                val n = runCatching { it.name.orEmpty() }.getOrDefault("")
                n.contains("BLE", true) || it.type == BluetoothDevice.DEVICE_TYPE_LE || it.type == BluetoothDevice.DEVICE_TYPE_DUAL
            }.thenBy { runCatching { it.name }.getOrNull() }
        )
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val callback = scanCallback ?: return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            onState("Bluetooth 연결 권한 필요")
            return
        }
        disconnectInternal(notify = false)
        stopScan()

        val name = runCatching { device.name.orEmpty() }.getOrDefault("")
        val preferBle = name.contains("BLE", true) ||
            device.type == BluetoothDevice.DEVICE_TYPE_LE ||
            device.type == BluetoothDevice.DEVICE_TYPE_DUAL

        if (preferBle && hasScanPermission()) connectBle(device) else connectClassic(device)
    }

    @SuppressLint("MissingPermission")
    private fun connectBle(device: BluetoothDevice) {
        onState("알티노 라이트 BLE 연결 중…")
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (t: Throwable) {
            onState("BLE 연결 오류: ${t.message ?: t.javaClass.simpleName}")
            connectClassic(device)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onState("BLE 연결 실패(status=$status)")
                runCatching { g.close() }
                if (gatt === g) gatt = null
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onState("BLE 연결됨 · 통신 설정 중…")
                negotiatedMtu = 23
                val requested = runCatching { g.requestMtu(160) }.getOrDefault(false)
                if (!requested) runCatching { g.discoverServices() }
                scope.launch {
                    delay(700)
                    if (!bleReady && gatt === g) runCatching { g.discoverServices() }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bleReady = false
                txCharacteristic = null
                rxCharacteristic = null
                onState("BLE 연결 해제됨")
                runCatching { g.close() }
                if (gatt === g) gatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
            runCatching { g.discoverServices() }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onState("BLE 서비스 검색 실패: $status")
                return
            }

            val service = g.getService(MCHP_SERVICE_UUID)
            val tx = service?.getCharacteristic(MCHP_TX_UUID)
            val rx = service?.getCharacteristic(MCHP_RX_UUID)

            if (tx == null && rx == null) {
                onState("Microchip UART 서비스를 찾지 못했습니다")
                return
            }

            txCharacteristic = tx
            rxCharacteristic = rx
            bleReady = true
            useTxPathNext = true

            if (tx != null && (tx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)) {
                enableNotifications(g, tx)
            }

            val paths = buildList {
                if (tx != null) add("TX")
                if (rx != null) add("RX")
            }.joinToString("+")
            onState("ALTINO LITE 통신 준비 완료 · MTU $negotiatedMtu · v6 $paths · 20+2")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            receiveCount += characteristic.value?.size ?: 0
            if (receiveCount % 220L < 22L) onState("ALTINO LITE 수신 ${receiveCount}B · v6")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receiveCount += value.size
            if (receiveCount % 220L < 22L) onState("ALTINO LITE 수신 ${receiveCount}B · v6")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        runCatching {
            g.setCharacteristicNotification(c, true)
            c.getDescriptor(CCCD_UUID)?.let { d ->
                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(d, value)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        d.value = value
                        g.writeDescriptor(d)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectClassic(device: BluetoothDevice) {
        connectJob = scope.launch {
            try {
                val label = runCatching { device.name ?: device.address }.getOrDefault("ALTINO LITE")
                onState("Classic 연결 중…")
                val candidates: List<() -> BluetoothSocket> = listOf(
                    { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                    { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
                )
                var lastError: Throwable? = null
                for (factory in candidates) {
                    try {
                        val s = factory()
                        s.connect()
                        socket = s
                        output = s.outputStream
                        onState("Classic SPP 연결됨: $label")
                        return@launch
                    } catch (t: Throwable) {
                        lastError = t
                        closeClassic()
                        delay(150)
                    }
                }
                onState("Classic 연결 실패: ${lastError?.message ?: "연결 확인"}")
            } catch (t: Throwable) {
                closeClassic()
                onState("Classic 연결 오류: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    @Synchronized
    fun send(bytes: ByteArray): Boolean {
        if (bleReady) return sendBleCompat(bytes)
        return try {
            val out = output ?: return false
            out.write(bytes)
            out.flush()
            true
        } catch (t: Throwable) {
            onState("Classic 통신 오류: ${t.message ?: "연결 확인"}")
            closeClassic()
            false
        }
    }

    /**
     * Compatibility transport for Orchestra-era ALTINO LITE BLE.
     * - Always split 22-byte ALTINO packet into 20 + 2 bytes (ATT MTU 23 era behavior)
     * - Alternate Microchip Transparent UART TX and RX writable paths each packet
     * - Use WRITE WITHOUT RESPONSE when available, otherwise normal WRITE
     */
    @SuppressLint("MissingPermission")
    private fun sendBleCompat(bytes: ByteArray): Boolean {
        val g = gatt ?: return false

        val tx = txCharacteristic
        val rx = rxCharacteristic
        val first = if (useTxPathNext) tx ?: rx else rx ?: tx
        useTxPathNext = !useTxPathNext
        val c = first ?: return false

        val chunks = if (bytes.size <= 20) listOf(bytes) else listOf(
            bytes.copyOfRange(0, 20),
            bytes.copyOfRange(20, bytes.size)
        )

        for ((index, chunk) in chunks.withIndex()) {
            val noResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            val writeType = if (noResponse)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            val ok = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(c, chunk, writeType) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        c.writeType = writeType
                        c.value = chunk
                        g.writeCharacteristic(c)
                    }
                }
            } catch (t: Throwable) {
                onState("BLE 전송 오류: ${t.message ?: t.javaClass.simpleName}")
                false
            }
            if (!ok) return false
            if (index == 0 && chunks.size > 1) Thread.sleep(8)
        }
        return true
    }

    fun isConnected(): Boolean {
        if (bleReady && gatt != null && (txCharacteristic != null || rxCharacteristic != null)) return true
        return try { socket?.isConnected == true && output != null } catch (_: Throwable) { false }
    }

    fun disconnect() {
        disconnectInternal(notify = true)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(notify: Boolean) {
        connectJob?.cancel()
        connectJob = null
        stopScan()
        if (isConnected()) runCatching { send(AltinoPacket.drive(0, 0, 0)) }
        closeInternal()
        if (notify) onState("연결 안 됨")
    }

    @SuppressLint("MissingPermission")
    private fun closeInternal() {
        closeClassic()
        bleReady = false
        txCharacteristic = null
        rxCharacteristic = null
        val oldGatt = gatt
        gatt = null
        runCatching { oldGatt?.disconnect() }
        runCatching { oldGatt?.close() }
    }

    private fun closeClassic() {
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
    }
}
