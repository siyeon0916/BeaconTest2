package com.example.beaconscanner

import kotlin.math.pow

object LocationEstimator {
    // RSSI -> 거리 변환(path-loss 공식)
    fun rssiToDistance(rssi: Int, txPower: Int = BeaconConfig.DEFAULT_TX_POWER): Double {
        if (rssi == 0) return Double.MAX_VALUE
        val exponent = (txPower - rssi) / (10.0 * BeaconConfig.PATH_LOSS_N)
        return 10.0.pow(exponent).coerceIn(0.1, 30.0)   // 0.1m~30m 범위로 제한
    }
}