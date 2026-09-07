package kr.co.altino.handcontrol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
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

class AltinoBluetoothManager(
    private val context: Context,
    private val onState: (String) -> Unit
) {
    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        closeInternal()
        onState("Bluetooth 오류: ${t.message ?: t.javaClass.simpleName}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    private var connectJob: Job? = null

    fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun pairedAltinoDevices(): List<BluetoothDevice> {
        if (!hasConnectPermission()) return emptyList()
        return try {
            val devices = adapter?.bondedDevices?.toList().orEmpty()
            val altinos = devices.filter {
                val n = runCatching { it.name.orEmpty().uppercase() }.getOrDefault("")
                n.contains("ALTINO") && !n.contains("BLE")
            }
            if (altinos.isNotEmpty()) altinos.sortedBy { runCatching { it.name }.getOrNull() }
            else devices.sortedBy { runCatching { it.name }.getOrNull() }
        } catch (t: Throwable) {
            onState("장치 목록 오류: ${t.message ?: t.javaClass.simpleName}")
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            onState("Bluetooth 권한 필요")
            return
        }

        // Do not call BluetoothAdapter.cancelDiscovery() here.
        // On Android 12+ it requires BLUETOOTH_SCAN and previously caused a SecurityException crash.
        disconnectInternal(notify = false)

        connectJob = scope.launch {
            try {
                val deviceLabel = runCatching { device.name ?: device.address }.getOrDefault("ALTINO")
                onState("$deviceLabel 연결 중…")

                var lastError: Throwable? = null
                val candidates: List<() -> BluetoothSocket> = listOf(
                    { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                    { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
                )

                for ((index, factory) in candidates.withIndex()) {
                    try {
                        val s = factory()
                        s.connect()
                        socket = s
                        output = s.outputStream
                        onState("연결됨: $deviceLabel / SPP${if (index == 0) "" else "(insecure)"}")

                        // Communication check: briefly blink the forward LED.
                        repeat(3) {
                            send(AltinoPacket.drive(0, 0, 0, led = 0x01))
                            delay(60)
                        }
                        repeat(3) {
                            send(AltinoPacket.drive(0, 0, 0, led = 0x00))
                            delay(60)
                        }
                        return@launch
                    } catch (t: Throwable) {
                        lastError = t
                        closeInternal()
                        delay(150)
                    }
                }

                onState("연결 실패: ${lastError?.message ?: "Classic SPP 장치를 선택하세요"}")
            } catch (t: Throwable) {
                closeInternal()
                onState("연결 오류: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    @Synchronized
    fun send(bytes: ByteArray): Boolean {
        return try {
            val out = output ?: return false
            out.write(bytes)
            out.flush()
            true
        } catch (t: Throwable) {
            onState("통신 오류: ${t.message ?: "Bluetooth 연결 확인"}")
            closeInternal()
            false
        }
    }

    fun isConnected(): Boolean = try {
        socket?.isConnected == true && output != null
    } catch (_: Throwable) {
        false
    }

    fun disconnect() {
        disconnectInternal(notify = true)
    }

    private fun disconnectInternal(notify: Boolean) {
        connectJob?.cancel()
        connectJob = null
        if (isConnected()) {
            runCatching { send(AltinoPacket.drive(0, 0, 0)) }
        }
        closeInternal()
        if (notify) onState("연결 안 됨")
    }

    @Synchronized
    private fun closeInternal() {
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
    }
}
