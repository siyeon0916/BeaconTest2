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
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayDeque

/**
 * 🟢 백그라운드 BLE 비콘 스캔 & HTTP 서버 전송 + 🆕 딥러닝 학습용 CSV 데이터 수집 기능
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
    private var currentGridRow = -1
    private var currentGridCol = -1
    private var currentXM = 0.0
    private var currentYM = 0.0
    private lateinit var csvFile: File
    private val csvDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // 🆕 학습 대상 비콘 (기존 refreshRunnable과 동일한 6개, 한 곳으로 통일)
    private val targetBeacons = listOf(
        TargetBeaconInfo("A1", 40011, 29429),
        TargetBeaconInfo("A2", 40011, 29433),
        TargetBeaconInfo("A3", 40011, 29439),
        TargetBeaconInfo("A4", 40011, 29445),
        TargetBeaconInfo("A5", 40011, 29430),
        TargetBeaconInfo("A6", 40011, 29438),
        TargetBeaconInfo("A7", 40011, 29427)
    )

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
        initCsvFile() // 🆕

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
                    if (history.size >= 15) history.removeFirst()
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

    // 🆕 6개 타깃 비콘의 트림 평균 RSSI를 한 번에 계산 (기존 서버 전송용 로직과 CSV 수집용 로직 공유)
    private fun computeTrimmedAvgRssiList(): List<Int> {
        return targetBeacons.map { target ->
            val cached = beaconCache.values.find {
                it.beacon.id2?.toInt() == target.major && it.beacon.id3?.toInt() == target.minor
            }
            if (cached != null && cached.rssiHistory.isNotEmpty()) {
                val sortedRssi = cached.rssiHistory.sorted()
                val trimCount = (sortedRssi.size * 0.2).toInt()
                val trimmedList = if (sortedRssi.size >= 5)
                    sortedRssi.subList(trimCount, sortedRssi.size - trimCount)
                else sortedRssi
                if (trimmedList.isNotEmpty()) trimmedList.average().toInt() else -100
            } else -100
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val expiredKeys = beaconCache.entries.filter { now - it.value.lastSeenMs > beaconTimeoutMs }.map { it.key }
            expiredKeys.forEach { beaconCache.remove(it) }

            beaconCacheLiveData.postValue(beaconCache.toMap())
            updateNotification()

            val avgRssiList = computeTrimmedAvgRssiList() // 🆕 공용 계산

            // ── 기존: 서버로 실시간 전송 ──
            val beaconJsonList = targetBeacons.mapIndexed { i, target ->
                val avgRssi = avgRssiList[i]
                if (avgRssi != -100) {
                    val distance = Math.pow(10.0, (-59 - avgRssi) / (10.0 * 2.0))
                    """{"beaconId":"${target.beaconId}","rssi":$avgRssi,"distance":${String.format("%.2f", distance)}}"""
                } else {
                    """{"beaconId":"${target.beaconId}","rssi":-100,"distance":99.0}"""
                }
            }
            val beaconsArrayJson = beaconJsonList.joinToString(",", "[", "]")
            val finalJsonBody = """
                {
                  "scannerId": "$scannerId",
                  "mapId": "$mapId",
                  "beacons": $beaconsArrayJson
                }
            """.trimIndent()
            sendToServer(finalJsonBody)

            // ── 🆕 데이터 수집 모드: CSV 한 줄 기록 ──
            if (isCollecting) {
                writeCsvRow(avgRssiList)
            }

            handler.postDelayed(this, refreshInterval)
        }
    }

    // 🆕 CSV 파일 초기화 (헤더 작성)
    private fun initCsvFile() {
        csvFile = File(getExternalFilesDir(null), "beacon_training_data.csv")
        if (!csvFile.exists()) {
            csvFile.writeText("timestamp,gridRow,gridCol,x_m,y_m,rssi_A1,rssi_A2,rssi_A3,rssi_A4,rssi_A5,rssi_A6,scannerId\n")
        }
    }

    // 🆕 그리드 포인트 지정하고 수집 시작 (MainActivity에서 bindService 후 호출)
    fun startCollection(row: Int, col: Int, xM: Double, yM: Double) {
        currentGridRow = row
        currentGridCol = col
        currentXM = xM
        currentYM = yM
        isCollecting = true
        collectionStatusLiveData.postValue("수집 중: R$row C$col ($xM m, $yM m)")
        Log.d("DataCollector", "▶️ 수집 시작: row=$row col=$col x=$xM y=$yM")
    }

    // 🆕 수집 중단
    fun stopCollection() {
        isCollecting = false
        collectionStatusLiveData.postValue("미수집")
        Log.d("DataCollector", "⏹ 수집 중단")
    }

    private fun writeCsvRow(avgRssiList: List<Int>) {
        try {
            val ts = csvDateFormat.format(Date())
            val row = "$ts,$currentGridRow,$currentGridCol,$currentXM,$currentYM," +
                    "${avgRssiList.joinToString(",")},$scannerId\n"
            csvFile.appendText(row)

            val count = (collectedRowCountLiveData.value ?: 0) + 1
            collectedRowCountLiveData.postValue(count)
        } catch (e: Exception) {
            Log.e("DataCollector", "❌ CSV 쓰기 실패: ${e.message}")
        }
    }

    // 🆕 저장된 CSV 파일 경로 반환 (공유/내보내기용)
    fun getCsvFile(): File = csvFile

    private fun sendToServer(jsonBody: String) {
        val targetUrl = BeaconConfig.SERVER_URL
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonBody.toRequestBody(mediaType)
        val request = Request.Builder().url(targetUrl).post(body)
            .addHeader("Connection", "keep-alive").build()

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
        val suffix = if (isCollecting) " · 데이터수집중(R$currentGridRow C$currentGridCol)" else ""
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