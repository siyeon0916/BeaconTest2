package com.example.beaconscanner

object BeaconConfig {

    // ══════════════════════════════════════════════════════════════
    // 📏 거리 계산 공통 상수 — 서버 전송용 / 화면 표시용 모두 이 값 하나만 사용
    // ⚠️ 캘리브레이션(1m·2m·4m·6m RSSI 실측) 후 아래 두 값만 바꾸면 앱 전체에 반영됩니다.
    // ══════════════════════════════════════════════════════════════
    const val DEFAULT_TX_POWER = -59   // 비콘 1m 거리에서의 RSSI(dBm)
    const val PATH_LOSS_N = 2.5        // 경로 손실 지수 (실내 보통 2.0 ~ 3.0)

    // ══════════════════════════════════════════════════════════════
    // 📶 RSSI 처리 / 전송 기준
    // ══════════════════════════════════════════════════════════════
    const val MISSING_RSSI = -100        // 신호가 안 잡힌 비콘 표시값
    const val RSSI_WINDOW_SIZE = 5       // 비콘별 최근 RSSI 보관 개수 (스캔 1.1초 기준 약 5초)
    const val MIN_BEACONS_TO_SEND = 2    // 이 개수 미만으로 잡히면 서버 전송 생략 (대역폭 절약)

    private const val BEACON_UUID = "E2C56DB5DFFB48D2B060D0F5A71096E0"

    data class BeaconInfo(
        val id: String,     // 서버 DB의 beaconId와 동일 ("A1" ~ "A7")
        val uuid: String,
        val major: Int,
        val minor: Int,
        val x: Double,      // 미터 단위
        val y: Double,      // 미터 단위
        val txPower: Int = DEFAULT_TX_POWER   // 비콘마다 다르게 측정했다면 여기 개별 입력
    ) {
        // 하이픈 제거 + 대문자로 통일한 비교용 키
        val key: String get() = "${uuid.replace("-", "").uppercase()}-$major-$minor"
    }

    // ── 📡 비콘 배치 (앱 화면 표시용. 서버 위치 계산은 DB에 등록된 좌표를 사용합니다) ──
    val BEACONS = listOf(
        BeaconInfo("A1", BEACON_UUID, major = 40011, minor = 29429, x = 1.0, y = 0.0),   // AEB5
        BeaconInfo("A2", BEACON_UUID, major = 40011, minor = 29433, x = 0.0, y = 3.5),   // AEBF
        BeaconInfo("A3", BEACON_UUID, major = 40011, minor = 29439, x = 0.0, y = 7.0),   // AEB6
        BeaconInfo("A4", BEACON_UUID, major = 40011, minor = 29445, x = 5.0, y = 10.0),  // AEB3
        BeaconInfo("A5", BEACON_UUID, major = 40011, minor = 29430, x = 8.0, y = 6.9),   // AEBE
        BeaconInfo("A6", BEACON_UUID, major = 40011, minor = 29438, x = 8.0, y = 3.3),   // AE89
        BeaconInfo("A7", BEACON_UUID, major = 40011, minor = 29427, x = 6.1, y = 0.0),   // AEB9
    )

    // ── 📐 공간 크기 (웹앱 MapSketch.jsx와 동일: 가로 8m × 세로 10m, 1m = 75px) ──
    const val ROOM_WIDTH = 8.00
    const val ROOM_HEIGHT = 10.00
    const val PIXEL_SCALE = 75.0

    var GRID_CELL_SIZE = 1.0

    // ── 🛠️ 서버 주소 ──
    // 배포 서버 (Render): https로 시작, 공백 없이, 맨 끝에 / 붙이지 않기
    private const val BASE = "https://guidant-rsyf.onrender.com"

    const val SERVER_URL = "$BASE/api/location"
    const val WEB_APP_URL = BASE

    // 학습 데이터 수집 전용 (노트북에서 csv_server.js를 켰을 때만 사용, 폰과 노트북이 같은 Wi-Fi여야 함)
    private const val LAPTOP_IP = "172.18.41.69"
    const val COLLECT_URL = "http://$LAPTOP_IP:4001/api/collect"
}