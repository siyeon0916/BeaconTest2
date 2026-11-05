package com.example.beaconscanner

import android.graphics.Color
import org.altbeacon.beacon.Beacon

object BeaconUtils {
    fun rssiColor(rssi: Int): Int = when {
        rssi >= -60 -> Color.parseColor("#4ADE80")
        rssi >= -70 -> Color.parseColor("#A3E635")
        rssi >= -80 -> Color.parseColor("#FBBF24")
        rssi >= -90 -> Color.parseColor("#FB923C")
        else        -> Color.parseColor("#F87171")
    }

    fun rssiLabel(rssi: Int): String = when {
        rssi >= -60 -> "매우 강함"
        rssi >= -70 -> "강함"
        rssi >= -80 -> "보통"
        rssi >= -90 -> "약함"
        else        -> "매우 약함"
    }

    // 비콘 비교용 키 (UUID 하이픈 제거 + 대문자 + major + minor) - BeaconConfig.BeaconInfo.key와 같은 형식
    fun beaconKey(beacon: Beacon): String {
        val rawUuid = beacon.id1?.toString()?.replace("-","")?.uppercase() ?: "UNKNOWN"
        val major = beacon.id2?.toInt() ?: 0
        val minor = beacon.id3?.toInt() ?: 0
        return "$rawUuid-$major-$minor"
    }
}