package kr.co.altino.handcontrol

/**
 * ALTINO LITE 22-byte output packet.
 * Layout follows the open saeonAltinoLite implementation:
 * 0:STX(0x02), 1:0x10, 2:checksum, 3:0x01, 4:0x01,
 * 5:steering, 6-7:right motor, 8-9:left motor,
 * 10:char, 11-18:dot matrix, 19:note, 20:LED, 21:ETX(0x03).
 */
object AltinoPacket {
    fun drive(left: Int, right: Int, steering: Int = 0): ByteArray {
        val packet = ByteArray(22)
        packet[0] = 0x02
        packet[1] = 0x10
        packet[3] = 0x01
        packet[4] = 0x01
        packet[5] = steering.coerceIn(-127, 127).toByte()

        putSigned16(packet, 6, right.coerceIn(-1000, 1000))
        putSigned16(packet, 8, left.coerceIn(-1000, 1000))

        packet[10] = 0x00
        for (i in 11..20) packet[i] = 0x00
        packet[21] = 0x03

        var sum = 0
        for (i in 3..20) sum += packet[i].toInt() and 0xFF
        packet[2] = (sum and 0xFF).toByte()
        return packet
    }

    private fun putSigned16(packet: ByteArray, index: Int, value: Int) {
        val v = value and 0xFFFF
        packet[index] = ((v ushr 8) and 0xFF).toByte()
        packet[index + 1] = (v and 0xFF).toByte()
    }
}
