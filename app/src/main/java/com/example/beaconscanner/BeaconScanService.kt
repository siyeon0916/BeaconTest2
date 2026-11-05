package com.example.beaconscanner

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Binder
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
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.Region
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayDeque
import kotlin.math.roundToInt

/**
 * 🟢 백그라운드 BLE 비콘 스캔 & HTTP 서버 전송 + 🆕 딥러닝 학습용 CSV 데이터 수집(노트북으로 전송 -> 노트북에서 CSV 저장) 기능

 📌 이번 수정 요약
 1) 거리 계산의 txPower/n 값을 BeaconConfig 한 곳으로 통일
 2) 서버 전송용 RSSI: 비콘별 최근 RSSI_WINDOW_SIZE개 이동평균 (computeAvgRssiList, 설계 문서 기준)
 3) 학습 CSV용 RSSI: 비콘별 가장 최근 원본 값 (currentRssiList, 평균 없음)
 4) 신호가 안 잡힌 비콘(-100/99m)은 서버로 보내지 않음, MIN_BEACONS_TO_SEND 미만이면 전송 생략
 5) 전송은 onScanCycle 한 곳에서만 (스캔 결과마다, 약 1.1초). refreshRunnable은 스캔이 멈췄을 때만 대신 실행
 */
class BeaconScanService : LifecycleService() {

    private lateinit var beaconManager: BeaconManager
    private val beaconCache = mutableMapOf<String, CachedBeacon>()
    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 1000L
    private val beaconTimeoutMs = 3000L
    private var lastCycleMs = 0L
    private var warmupCycles = 0
    private val collectedRows = AtomicInteger(0)
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

    // 학습/위치 대상 비콘: BeaconConfig.BEACONS 하나만 사용 (A1~A7 순서, id = 서버 beaconId)
    private val targetBeacons = BeaconConfig.BEACONS

    // 비콘별 "가장 최근 값" 테이블 (A1~A7 한 칸씩)
    // 7개를 모두 한 번씩 받으면 위치 계산 시작(tableReady)
    // 이후 새 값이 오면 그 비콘 칸만 새 값으로 덮어쓰고 위치를 다시 계산
    private val latestRssi = arrayOfNulls<Int>(targetBeacons.size)
    private val latestTsMs = LongArray(targetBeacons.size)
    private val staleTimeOutMs = 10_000L // 이 시간 동안 새 값이 없으면 그 칸은 -100(미수신)으로 취급
    private var tableReady = false
    private var isScanning = false
    companion object {
        const val CHANNEL_ID = "beacon_scan_channel"
        const val NOTIFICATION_ID = 1001

        val beaconCacheLiveData = MutableLiveData<Map<String, CachedBeacon>>(emptyMap())
        val statusLiveData = MutableLiveData("대기중")

        // 🆕 데이터 수집 상태를 화면(MainActivity)에서 관찰할 수 있도록 LiveData로 노출
        val collectionStatusLiveData = MutableLiveData("미수집")
        val collectedRowCountLiveData = MutableLiveData(0)
        // 수집 중 여부 (상태 문구가 "⚠ 전송 실패"로 바뀌어도 수집은 계속되므로 별도 플래그로 노출)
        val isCollectingLiveData = MutableLiveData(false)
        // 비콘 7개를 모두 받아 서버로 위치 전송이 시작됐는지 (QR 진입 시 이때 웹앱으로 이동)
        val readyLiveData = MutableLiveData(false)

        // 기기 ID를 MainActivity에서도 쓰기 위해 companion으로 이동 (웹앱 ?sid= 연동용)
        fun getOrCreateScannerId(context: Context): String {
            val sharedPref = context.getSharedPreferences("BeaconScannerPrefs", Context.MODE_PRIVATE)
            var savedId = sharedPref.getString("scanner_id", null)
            if (savedId == null) {
                savedId = "android_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).lowercase()
                sharedPref.edit().putString("scanner_id", savedId).apply()
            }
            return savedId
        }
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

        // 스캔 주기·포그라운드 스캔·알림 채널 설정은 App.kt에서 한 번만 함
        beaconManager = BeaconManager.getInstanceForApplication(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification("스캔 준비 중..."))
        if (!isScanning) {
            isScanning = true
            startScanning()
        }
        return START_STICKY
    }

    private fun startScanning() {
        try {
            beaconManager.startRangingBeacons(region)
            val regionViewModel = beaconManager.getRegionViewModel(region)

            regionViewModel.rangedBeacons.observe(this, Observer { beaconsCollection ->
                val cycleMs = System.currentTimeMillis()
                val updatedIdx = mutableSetOf<Int>()
                beaconsCollection?.forEach { beacon ->
                    // 화면 표시용 비콘별 캐시 (기존 유지)
                    val key = BeaconUtils.beaconKey(beacon)
                    val history = beaconCache[key]?.rssiHistory ?: ArrayDeque()
                    val avg = beacon.runningAverageRssi
                    history.addLast(if (avg < 0) avg.roundToInt() else beacon.rssi)
                    while (history.size > BeaconConfig.RSSI_WINDOW_SIZE) history.removeFirst()
                    beaconCache[key] = CachedBeacon(beacon, cycleMs, history)
                    // 해당 비콘 칸만 새 값으로 덮어쓰기 (평균 없음)
                    val idx = targetBeacons.indexOfFirst {
                        it.major == beacon.id2?.toInt() && it.minor == beacon.id3?.toInt()
                    }
                    if (idx >= 0) {
                        latestRssi[idx] = beacon.rssi
                        latestTsMs[idx] = cycleMs
                        updatedIdx.add(idx)
                    }
                }
                if (!tableReady && latestRssi.all { it != null }) {
                    tableReady = true
                    readyLiveData.postValue(true)
                    Log.d("BeaconScanService", "✅ 비콘 7개 모두 수신 → 위치 계산 시작")
                }
                lastCycleMs = cycleMs
                onScanCycle(cycleMs, updatedIdx)
            })

            statusLiveData.postValue("스캔 중")
            handler.removeCallbacks(refreshRunnable)
            handler.post(refreshRunnable)
        } catch (e: Exception) {
            Log.e("BeaconScanService", "스캔 시작 중 오류 발생: ${e.message}")
        }
    }

    // 비콘별 최근 RSSI_WINDOW_SIZE개 평균 (BeaconConfig.BEACONS 순서, 샘플 2개 미만이면 -100)
    // 서버(위치 계산) 전송용
    private fun computeAvgRssiList(): List<Int> {
        return targetBeacons.map { info ->
            val cached = beaconCache.values.find {
                it.beacon.id2?.toInt() == info.major && it.beacon.id3?.toInt() == info.minor
            }
            val history = cached?.rssiHistory
            if (history == null || history.size < 2) {
                BeaconConfig.MISSING_RSSI
            } else {
                history.average().roundToInt()
            }
        }
    }

    // 현재 테이블 값 (한 번도 못 받았거나 staleTimeoutMs 넘게 새 값이 없으면 -100)
    // 👉 학습 CSV 전송용 (평균 없이 원본 값)
    private fun currentRssiList(now: Long): List<Int> {
        return targetBeacons.indices.map { i ->
            val v = latestRssi[i]
            if (v == null || now - latestTsMs[i] > staleTimeOutMs) BeaconConfig.MISSING_RSSI else v
        }
    }

    // 실제로 잡힌 비콘만 JSON에 포함. 잡힌 비콘이 MIN_BEACONS_TO_SEND 미만이면 null (전송 생략)
    private fun buildServerJson(rssiList: List<Int>): String? {
        val validBeaconJson = targetBeacons.mapIndexedNotNull { i, info ->
            val rssi = rssiList[i]
            if (rssi == BeaconConfig.MISSING_RSSI) {
                null
            } else {
                val distance = LocationEstimator.rssiToDistance(rssi, info.txPower)
                """{"beaconId":"${info.id}","rssi":$rssi,"distance":${String.format(Locale.US, "%.2f", distance)}}"""
            }
        }
        if (validBeaconJson.size < BeaconConfig.MIN_BEACONS_TO_SEND) return null
        return """
            {
                "scannerId": "$scannerId",
                "mapId": "$mapId",
                "beacons": ${validBeaconJson.joinToString(",", "[", "]")}
            }
        """.trimIndent()
    }

    // 스캔 사이클마다 1번: 위치 서버 전송 → (수집 중이면) 새로 들어온 비콘의 행만 CSV 전송
    private fun onScanCycle(cycleMs: Long, updatedIdx: Set<Int>) {
        val now = System.currentTimeMillis()
        beaconCache.entries.removeAll { now - it.value.lastSeenMs > beaconTimeoutMs }

        beaconCacheLiveData.postValue(beaconCache.toMap())
        updateNotification()

        // 실시간 위치용 : 이동평균 값, 잡힌 비콘이 MIN_BEACONS_TO_SEND 이상이면 바로 전송
        buildServerJson(computeAvgRssiList())?.let { sendToServer(it) }

        if (isCollecting && updatedIdx.isNotEmpty()) {  // 학습 CSV용: 새로 들어온 원본 값의 행만
            if (warmupCycles > 0) warmupCycles-- else sendCollectToLaptop(currentRssiList(now), updatedIdx, cycleMs)
        }
    }

    // 스캔 콜백이 한동안 없을 때만 동작하는 하트비트 (위치 서버에 현재 테이블 재전송 / 만료 정리용, CSV에는 영향 없음)
    private val refreshRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - lastCycleMs > 1500L) onScanCycle(now, emptySet())
            handler.postDelayed(this, refreshInterval)
        }
    }

    // 🆕 그리드 포인트 지정하고 수집 시작 (MainActivity에서 bindService 후 호출)
    fun startCollection(xM: Double, yM: Double) {
        // 좌표를 바꾼 직후, 이전 지점에서 받던 스캔 사이클이 새 좌표 라벨로 저장되지 않도록 첫 2사이클은 버림
        warmupCycles = 2
        currentXM = xM
        currentYM = yM
        isCollecting = true

        isCollectingLiveData.postValue(true)

        collectionStatusLiveData.postValue("수집 중: ($xM m, $yM m)")
        Log.d("DataCollector", "▶️ 수집 시작: x=$xM y=$yM")
    }

    // 🆕 수집 중단
    fun stopCollection() {
        isCollecting = false

        isCollectingLiveData.postValue(false)

        collectionStatusLiveData.postValue("미수집")
        Log.d("DataCollector", "⏹ 수집 중단")
    }

    // 수집 데이터를 노트북 서버로 전송 (새 값이 온 비콘만 값 ,나머지는 null → 서버가 그 행은 건너뜀)
    private fun sendCollectToLaptop(rssiList: List<Int>, rowsIdx: Set<Int>, cycleMs: Long) {
            val rssiJson = rssiList.mapIndexed { i, r -> if (i in rowsIdx) "$r" else "null" }
                .joinToString(",")
            val json = """{"ts":$cycleMs,"x_m":$currentXM,"y_m":$currentYM,""" +
                    """"rssi":[$rssiJson],"scannerId":"$scannerId"}"""
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
                        collectedRowCountLiveData.postValue(collectedRows.addAndGet(rowsIdx.size))
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
       scannerId = getOrCreateScannerId(this)
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
        val initInfo = if (tableReady) "" else " · 초기화 ${latestRssi.count { it != null }}/${targetBeacons.size}"
        manager?.notify(NOTIFICATION_ID, buildNotification("감지된 비콘: ${beaconCache.size}개$initInfo$suffix"))
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        try {
            beaconManager.stopRangingBeacons(region) // 라이브러리 스캔도 확실히 정지
        } catch (e: Exception) {
            Log.e("BeaconScanService", "스캔 중지 오류: ${e.message}")
        }
        statusLiveData.postValue("대기중")
        readyLiveData.postValue(false)
        super.onDestroy()
    }
}