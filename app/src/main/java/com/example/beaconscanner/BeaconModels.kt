package com.example.beaconscanner

import org.altbeacon.beacon.Beacon
import kotlin.collections.ArrayDeque
/**
 * 🟢 비콘 캐시 및 타깃 데이터 모델 분리
 */
data class CachedBeacon(
    val beacon: Beacon,
    val lastSeenMs: Long,
    val rssiHistory: ArrayDeque<Int> = ArrayDeque()
)