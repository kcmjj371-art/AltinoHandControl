package kr.co.altino.handcontrol

class GestureStabilizer(
    private val holdMs: Long = 350,
    private val lostHandStopMs: Long = 300
) {
    private var candidate = DriveCommand.UNKNOWN
    private var candidateSince = 0L
    private var stable = DriveCommand.STOP
    private var lastHandSeen = 0L

    fun update(raw: DriveCommand, nowMs: Long): DriveCommand? {
        if (raw == DriveCommand.UNKNOWN) {
            if (lastHandSeen != 0L && nowMs - lastHandSeen >= lostHandStopMs && stable != DriveCommand.STOP) {
                stable = DriveCommand.STOP
                candidate = DriveCommand.UNKNOWN
                return stable
            }
            return null
        }

        lastHandSeen = nowMs

        if (raw == DriveCommand.STOP) {
            if (stable != DriveCommand.STOP) {
                stable = DriveCommand.STOP
                candidate = DriveCommand.STOP
                candidateSince = nowMs
                return stable
            }
            return null
        }

        if (raw != candidate) {
            candidate = raw
            candidateSince = nowMs
            return null
        }

        if (raw != stable && nowMs - candidateSince >= holdMs) {
            stable = raw
            return stable
        }
        return null
    }

    fun forceStop(): DriveCommand {
        stable = DriveCommand.STOP
        candidate = DriveCommand.UNKNOWN
        return stable
    }
}
