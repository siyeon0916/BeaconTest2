package com.example.beaconscanner

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Region
import org.altbeacon.beacon.service.RunningAverageRssiFilter

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        val beaconManager = BeaconManager.getInstanceForApplication(this)

        // 1. 레이아웃 파서 등록 (iBeacon, Eddystone, AltBeacon)
        beaconManager.beaconParsers.clear()
        beaconManager.beaconParsers.add(
            BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24") // iBeacon
        )
        beaconManager.beaconParsers.add(
            BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_UID_LAYOUT)
        )
        beaconManager.beaconParsers.add(
            BeaconParser().setBeaconLayout(BeaconParser.EDDYSTONE_URL_LAYOUT)
        )
        beaconManager.beaconParsers.add(
            BeaconParser().setBeaconLayout(BeaconParser.ALTBEACON_LAYOUT)
        )

        // 2. 🔥 [핵심] Android OS 30초 5회 제한 회피: 연속 스캔(Continuous Scan) 모드 설정
        // foregroundScanPeriod를 길게 잡거나 BetweenScanPeriod를 0으로 설정하여 반복적인 Start/Stop을 방지합니다.
        beaconManager.foregroundScanPeriod = 1100L
        beaconManager.foregroundBetweenScanPeriod = 0L

        // 백그라운드 상태에서도 스캔을 중단하지 않고 지속하도록 동일 비율 설정
        beaconManager.backgroundScanPeriod = 1100L
        beaconManager.backgroundBetweenScanPeriod = 0L

        // 3. ⚠️ JobScheduler에 의해 10초마다 스캔이 재시작되는 현상 차단
        beaconManager.setEnableScheduledScanJobs(false)
        RunningAverageRssiFilter.setSampleExpirationMilliseconds(3000L) // 최근 3초 패킷으로 평균

        // 4. Android 8.0 이상 대응: OS가 스캔을 강제로 껐다 켜는 것을 막기 위한 Foreground Service 채널 설정
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "beacon_scan_channel"
            val channelName = "실시간 비콘 위치 스캔"
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)

            // 포그라운드 스캔 모드 활성화 (시스템에 의해 스캔이 중단/재시작되는 것 방지)
            try {
                beaconManager.enableForegroundServiceScanning(
                    android.app.Notification.Builder(this, channelId)
                        .setContentTitle("실내 위치 측정 중")
                        .setContentText("실시간 비콘 신호를 수신하고 있습니다.")
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .build(),
                    1001
                )
            } catch (e: Exception) {
                Log.e("BeaconApp", "Foreground service scanning 설정 실패: ${e.message}")
            }
        }

        // 5. 변경된 스캔 주기 반영
        try {
            beaconManager.updateScanPeriods()
            Log.d("BeaconApp", "✅ 비콘 스캔 주기 설정 완료 (연속 스캔 모드)")
        } catch (e: Exception) {
            Log.e("BeaconApp", "❌ ScanPeriod 업데이트 실패: ${e.message}")
        }
    }
}