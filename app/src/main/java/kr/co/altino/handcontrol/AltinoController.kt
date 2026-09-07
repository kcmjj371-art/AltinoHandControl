package kr.co.altino.handcontrol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AltinoController(
    private val bluetooth: AltinoBluetoothManager,
    private val onCommand: (DriveCommand) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var command = DriveCommand.STOP
    @Volatile private var speed = 300
    private var txJob: Job? = null

    fun start() {
        if (txJob != null) return
        txJob = scope.launch {
            while (isActive) {
                if (bluetooth.isConnected()) {
                    bluetooth.send(packetFor(command))
                }
                delay(50)
            }
        }
    }

    fun setSpeed(value: Int) { speed = value.coerceIn(120, 500) }

    fun setCommand(newCommand: DriveCommand) {
        val safeCommand = if (newCommand == DriveCommand.UNKNOWN) DriveCommand.STOP else newCommand
        if (safeCommand != command) {
            command = safeCommand
            onCommand(safeCommand)
        }
    }

    fun emergencyStop() {
        command = DriveCommand.STOP
        onCommand(DriveCommand.STOP)
        if (bluetooth.isConnected()) {
            scope.launch {
                repeat(2) {
                    bluetooth.send(AltinoPacket.drive(0, 0, 0))
                    delay(50)
                }
            }
        }
    }

    fun stop() {
        emergencyStop()
        txJob?.cancel()
        txJob = null
    }

    private fun packetFor(cmd: DriveCommand): ByteArray {
        val s = speed
        return when (cmd) {
            DriveCommand.FORWARD -> AltinoPacket.drive(s, s, 0)
            DriveCommand.BACKWARD -> AltinoPacket.drive(-s, -s, 0)
            DriveCommand.LEFT -> AltinoPacket.drive((s * 0.80).toInt(), (s * 0.80).toInt(), -70)
            DriveCommand.RIGHT -> AltinoPacket.drive((s * 0.80).toInt(), (s * 0.80).toInt(), 70)
            else -> AltinoPacket.drive(0, 0, 0)
        }
    }
}
