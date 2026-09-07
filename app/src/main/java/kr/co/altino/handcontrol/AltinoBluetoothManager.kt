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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private val scope = CoroutineScope(Dispatchers.IO)
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
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        val altinos = devices.filter {
            val n = it.name.orEmpty().uppercase()
            n.startsWith("ALTINO-L") || n.contains("ALTINO")
        }
        return if (altinos.isNotEmpty()) altinos.sortedBy { it.name } else devices.sortedBy { it.name }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            onState("Bluetooth 권한 필요")
            return
        }
        disconnect()
        connectJob = scope.launch {
            try {
                onState("${device.name ?: device.address} 연결 중…")
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                output = s.outputStream
                onState("연결됨: ${device.name ?: device.address}")
                repeat(2) {
                    send(AltinoPacket.drive(0, 0, 0))
                    Thread.sleep(50)
                }
            } catch (t: Throwable) {
                closeInternal()
                onState("연결 실패: ${t.message ?: t.javaClass.simpleName}")
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

    fun isConnected(): Boolean = socket?.isConnected == true && output != null

    fun disconnect() {
        connectJob?.cancel()
        if (isConnected()) {
            runCatching { send(AltinoPacket.drive(0, 0, 0)) }
        }
        closeInternal()
        onState("연결 안 됨")
    }

    @Synchronized
    private fun closeInternal() {
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
    }
}
