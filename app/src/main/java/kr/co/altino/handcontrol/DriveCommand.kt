package kr.co.altino.handcontrol

enum class DriveCommand(val label: String) {
    STOP("정지"),
    FORWARD("전진"),
    BACKWARD("후진"),
    LEFT("좌회전"),
    RIGHT("우회전"),
    UNKNOWN("인식 중")
}
