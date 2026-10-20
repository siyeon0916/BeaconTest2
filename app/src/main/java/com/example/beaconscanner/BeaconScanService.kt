package com.example.beaconscanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.Region
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayDeque
import kotlin.math.roundToInt

/**
 * 🟢 백그라운드 BLE 비콘 스캔 & HTTP 서버 전송 + 🆕 딥러닝 학습용 CSV 데이터 수집(노트북으로 전송 -> 노트북에서 CSV 저장) 기능
 *
 * 📌 이번 수정 요약
 *  1) 거리 계산의 txPower/n 값을 BeaconConfig 한 곳으로 통일 (기존: 여기 -59/2.0, 화면 -65/2.5로 따로 놀았음)
 *  2) RSSI 처리: 최근 5개 중 최소·최대값을 버린 평균 (튀는 값 제거)
 *  3) 신호가 안 잡힌 비콘(-100/99m)은 서버로 보내지 않음 → 서버가 가짜 비콘으로 계산하는 문제 방지
 *  4) 비콘 목록을 BeaconConfig.BEACONS 하나만 사용 (중복 목록 제거)
 */
class BeaconScanService : LifecycleService() {

    private lateinit var beaconManager: BeaconManager
    private val beaconCache = mutableMapOf<String, CachedBeacon>()
    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 1000L
    private val beaconTimeoutMs = 5000L
    private val binder = LocalBinder()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private lateinit var scannerId: String
    private val mapId = "6a4e268e4b23f93d45141083"
    private val region = Region("all-beacons", null, null, null)

    // 🆕 [데이터 수집 모드] 상태
    private var isCollecting = false
    private var currentXM = 0.0
    private var currentYM = 0.0

    companion object {
        const val CHANNEL_ID = "beacon_scan_channel"
        const val NOTIFICATION_ID = 1001

        val beaconCacheLiveData = MutableLiveData<Map<String, CachedBeacon>>(emptyMap())
        val statusLiveData = MutableLiveData("대기중")

        // 🆕 데이터 수집 상태를 화면(MainActivity)에서 관찰할 수 있도록 LiveData로 노출
        val collectionStatusLiveData = MutableLiveData("미수집")
        val collectedRowCountLiveData = MutableLiveData(0)
    }

    inner class LocalBinder : Binder() {
        fun getService(): BeaconScanService = this@BeaconScanService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        getOrCreateScannerId()
        createNotificationChannel()

        beaconManager = BeaconManager.getInstanceForApplication(this)
        beaconManager.foregroundScanPeriod = 1100L
        beaconManager.foregroundBetweenScanPeriod = 0L
        beaconManager.backgroundScanPeriod = 1100L
        beaconManager.backgroundBetweenScanPeriod = 0L
        beaconManager.setEnableScheduledScanJobs(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                beaconManager.enableForegroundServiceScanning(
                    buildNotification("비콘 신호를 스캐닝하고 있습니다."),
                    NOTIFICATION_ID
                )
            } catch (e: Exception) {
                Log.e("BeaconScanService", "Foreground scanning 설정 실패: ${e.message}")
            }
        }

        try {
            beaconManager.updateScanPeriods()
        } catch (e: Exception) {
            Log.e("BeaconScanService", "ScanPeriod 반영 실패: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification("스캔 준비 중..."))
        startScanning()
        return START_STICKY
    }

    private fun startScanning() {
        try {
            beaconManager.startRangingBeacons(region)
            val regionViewModel = beaconManager.getRegionViewModel(region)

            regionViewModel.rangedBeacons.observe(this, Observer { beaconsCollection ->
                val now = System.currentTimeMillis()
                beaconsCollection?.forEach { beacon ->
                    val key = beaconKey(beacon)
                    val existing = beaconCache[key]
                    val history = existing?.rssiHistory ?: ArrayDeque()
                    if (history.size >= BeaconConfig.RSSI_WINDOW_SIZE) history.removeFirst()
                    history.addLast(beacon.rssi)
                    beaconCache[key] = CachedBeacon(beacon, now, history)
                }
            })

            statusLiveData.postValue("스캔 중")
            handler.removeCallbacks(refreshRunnable)
            handler.post(refreshRunnable)
        } catch (e: Exception) {
            Log.e("BeaconScanService", "스캔 시작 중 오류 발생: ${e.message}")
        }
    }

    /**
     * 비콘별 대표 RSSI 계산 (BeaconConfig.BEACONS 순서와 동일한 리스트 반환)
     *  - 샘플이 2개 미만이면 아직 믿을 수 없으므로 MISSING_RSSI
     *  - 샘플이 5개 이상이면 최소·최대값을 버리고 평균 (순간적으로 튄 값 제거)
     *  - 소수점은 버리지 않고 반올림
     */
    private fun computeAvgRssiList(): List<Int> {
        return BeaconConfig.BEACONS.map { info ->
            val cached = beaconCache.values.find {
                it.beacon.id2?.toInt() == info.major && it.beacon.id3?.toInt() == info.minor
            }
            val history = cached?.rssiHistory
            if (history == null || history.size < 2) {
                BeaconConfig.MISSING_RSSI
            } else {
                val sorted = history.sorted()
                val trimmed = if (sorted.size >= 5) sorted.subList(1, sorted.size - 1) else sorted
                trimmed.average().roundToInt()
            }
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val expiredKeys = beaconCache.entries.filter { now - it.value.lastSeenMs > beaconTimeoutMs }.map { it.key }
            expiredKeys.forEach { beaconCache.remove(it) }

            beaconCacheLiveData.postValue(beaconCache.toMap())
            updateNotification()

            val avgRssiList = computeAvgRssiList()

            // ── 서버로 실시간 전송: 실제로 잡힌 비콘만 포함 ──
            val validBeaconJson = BeaconConfig.BEACONS.mapIndexedNotNull { i, info ->
                val rssi = avgRssiList[i]
                if (rssi == BeaconConfig.MISSING_RSSI) {
                    null
                } else {
                    val distance = LocationEstimator.rssiToDistance(rssi, info.txPower)
                    """{"beaconId":"${info.id}","rssi":$rssi,"distance":${String.format(Locale.US, "%.2f", distance)}}"""
                }
            }

            if (validBeaconJson.size >= BeaconConfig.MIN_BEACONS_TO_SEND) {
                val finalJsonBody = """
                    {
                      "scannerId": "$scannerId",
                      "mapId": "$mapId",
                      "beacons": ${validBeaconJson.joinToString(",", "[", "]")}
                    }
                """.trimIndent()
                sendToServer(finalJsonBody)
            }

            // ── 🆕 데이터 수집 모드: 노트북 서버로 전송 (학습 데이터는 미검출도 -100으로 그대로 기록) ──
            if (isCollecting) {
                sendCollectToLaptop(avgRssiList)
            }

            handler.postDelayed(this, refreshInterval)
        }
    }

    // 🆕 그리드 포인트 지정하고 수집 시작 (MainActivity에서 bindService 후 호출)
    fun startCollection(xM: Double, yM: Double) {
        currentXM = xM
        currentYM = yM
        isCollecting = true
        collectionStatusLiveData.postValue("수집 중: ($xM m, $yM m)")
        Log.d("DataCollector", "▶️ 수집 시작: x=$xM y=$yM")
    }

    // 🆕 수집 중단
    fun stopCollection() {
        isCollecting = false
        collectionStatusLiveData.postValue("미수집")
        Log.d("DataCollector", "⏹ 수집 중단")
    }

    // 수집 데이터를 노트북 서버로 전송 (노트북이 CSV 한 줄로 저장)
    private fun sendCollectToLaptop(avgRssiList: List<Int>) {
        val json = """{"x_m":$currentXM,"y_m":$currentYM,""" +
                """"rssi":[${avgRssiList.joinToString(",")}], "scannerId":"$scannerId"}"""
        val request = Request.Builder()
            .url(BeaconConfig.COLLECT_URL)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("DataCollector", "❌ 노트북 전송 실패: ${e.localizedMessage}")
                collectionStatusLiveData.postValue("⚠ 노트북 전송 실패 (IP/서버 확인)")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        val count = (collectedRowCountLiveData.value ?: 0) + 1
                        collectedRowCountLiveData.postValue(count)
                    } else {
                        Log.e("DataCollector", "❌ 노트북 응답 코드: ${it.code}")
                    }
                }
            }
        })
    }

    private fun sendToServer(jsonBody: String) {
        val request = try {
            Request.Builder()
                .url(BeaconConfig.SERVER_URL.trim())
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Connection", "keep-alive")
                .build()
        } catch (e: IllegalArgumentException) {
            Log.e("BeaconNetwork", "❌ 잘못된 서버 URL: ${BeaconConfig.SERVER_URL} (${e.message})")
            return
        }

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("BeaconNetwork", "❌ [서버 전송 실패]: ${e.localizedMessage}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) Log.e("BeaconNetwork", "❌ HTTP 코드: ${it.code}")
                }
            }
        })
    }

    private fun getOrCreateScannerId() {
        val sharedPref = getSharedPreferences("BeaconScannerPrefs", Context.MODE_PRIVATE)
        var savedId = sharedPref.getString("scanner_id", null)
        if (savedId == null) {
            savedId = "android_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).lowercase()
            sharedPref.edit().putString("scanner_id", savedId).apply()
        }
        scannerId = savedId
    }

    private fun beaconKey(beacon: Beacon): String {
        val rawUuid = beacon.id1?.toString()?.replace("-", "")?.uppercase() ?: "UNKNOWN"
        val major = beacon.id2?.toInt() ?: 0
        val minor = beacon.id3?.toInt() ?: 0
        return "$rawUuid-$major-$minor"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "비콘 위치 스캔", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "백그라운드 실시간 실내 위치 측정이 구동 중입니다." }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Guidant 비콘 스캐너")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val suffix = if (isCollecting) " · 데이터수집중($currentXM m $currentYM m)" else ""
        manager?.notify(NOTIFICATION_ID, buildNotification("감지된 비콘: ${beaconCache.size}개$suffix"))
    }

    fun stopScanning() {
        handler.removeCallbacks(refreshRunnable)
        try {
            beaconManager.stopRangingBeacons(region)
            beaconManager.disableForegroundServiceScanning()
        } catch (e: Exception) {
            Log.e("BeaconScanService", "스캔 중지 오류: ${e.message}")
        }
        statusLiveData.postValue("대기중")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
    }
}