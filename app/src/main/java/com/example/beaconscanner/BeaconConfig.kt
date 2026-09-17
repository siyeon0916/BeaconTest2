package com.example.beaconscanner

object BeaconConfig {

    data class BeaconInfo(
        val uuid: String,
        val major: Int,
        val minor: Int,
        val x: Double,      // 미터 단위
        val y: Double,      // 미터 단위
        val txPower: Int = -65
    ) {
        // 💡 MainActivity의 beaconKey 함수와 완벽 매칭되도록 하이픈을 제거하고 대문자로 통일하는 정제 로직 반영
        val key: String get() = "${uuid.replace("-", "").uppercase()}-$major-$minor"
    }

    // ── 📡 리액트 맵 픽셀 구조(1m = 45px)와 매칭된 비콘 실제 배치 ──
    val BEACONS = listOf(
        BeaconInfo("E2C56DB5DFFB48D2B060D0F5A71096E0", major = 40011, minor = 29429, x = 1.0,  y = 0.0 ),  // AEB5=A1
        BeaconInfo("E2C56DB5DFFB48D2B060D0F5A71096E0", major = 40011, minor = 29433, x = 0.0,  y = 3.5 ),  // AEBF=A2
        BeaconInfo("E2C56DB5DFFB48D2B060D0F5A71096E0", major = 40011, minor = 29439, x = 0.0,  y = 7.0 ),  // AEB6=A3
        BeaconInfo("E2C56DB5DFFB48D2B060D0F5A71096E0", major = 40011, minor = 29445, x = 5.0,  y = 10.0),  // AEB3=A4
        BeaconInfo("E2C56DB5DFFB48D2B060D0F5A71096E0", major = 40011, minor = 29430, x = 8.0,  y = 6.9),  // AEBE=A5
        BeaconInfo("E2C56DB5DFFB48D2B060D0F5A71096E0", major = 40011, minor = 29438, x = 8.0,  y =3.3),   // AE89=A6
        BeaconInfo("E2C56DB5DFFB48D2B060D0F5A71096E0", major = 40011, minor = 29427, x = 6.1,  y =0.0),   // AEB9=A7


    )

    const val PATH_LOSS_N = 2.5

    // ── 📏 리액트 CANVAS 크기를 미터(SCALE=45)로 정밀 변환한 값 ──
    const val ROOM_WIDTH  = 8.00  // 미터 (362px / 45)
    const val ROOM_HEIGHT = 10.00  // 미터 (767px / 45)

    // 픽셀 변환 스케일 상수 (1미터당 45픽셀)
    const val PIXEL_SCALE = 45.0

    var GRID_CELL_SIZE = 1.0

    // ── 🛠️ 주소 설정 구간 ──
    // ⚠️ 팀원 각자가 본인 PC의 사설 IP 주소를 조사한 뒤 '192.168.0.2' 대신 넣어주어야 로직이 동작합니다.
    private const val CURRENT_PC_IP = "172.18.41.69"

    const val SERVER_URL = "http://$CURRENT_PC_IP:4000/api/location"
    const val WEB_APP_URL = "http://$CURRENT_PC_IP:5173"
}