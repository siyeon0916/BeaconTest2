package com.example.beaconscanner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 🆕 데이터 수집(관리자) 전용 화면
 * - 기존 MainActivity 하단에 있던 "데이터 수집 모드"(좌표 입력 → 수집 시작/중단)를 그대로 옮긴 화면
 * - 수집 자체는 BeaconScanService가 하므로, 이 화면을 나가도 수집은 계속됨 (나갈 때 확인창으로 선택)
 * - 위치 계산(서버 전송) 로직은 건드리지 않음
 */
class CollectActivity : AppCompatActivity() {

    private lateinit var etXMeter: EditText
    private lateinit var etYMeter: EditText
    private lateinit var btnStartCollect: Button
    private lateinit var btnStopCollect: Button
    private lateinit var tvCollectStatus: TextView
    private lateinit var tvCollectCount: TextView
    private lateinit var tvScanState: TextView
    private lateinit var tvScanHint: TextView
    private lateinit var tvBeaconTable: TextView

    private var scanService: BeaconScanService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            scanService = (binder as BeaconScanService.LocalBinder).getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            scanService = null
            isBound = false
        }
    }

    private val isScanning: Boolean
        get() = BeaconScanService.statusLiveData.value == "스캔 중"

    private val isCollecting: Boolean
        get() = BeaconScanService.isCollectingLiveData.value == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collect)

        etXMeter = findViewById(R.id.etXMeter)
        etYMeter = findViewById(R.id.etYMeter)
        btnStartCollect = findViewById(R.id.btnStartCollect)
        btnStopCollect = findViewById(R.id.btnStopCollect)
        tvCollectStatus = findViewById(R.id.tvCollectStatus)
        tvCollectCount = findViewById(R.id.tvCollectCount)
        tvScanState = findViewById(R.id.tvScanState)
        tvScanHint = findViewById(R.id.tvScanHint)
        tvBeaconTable = findViewById(R.id.tvBeaconTable)

        findViewById<TextView>(R.id.tvCollectUrl).text = "전송 대상: ${BeaconConfig.COLLECT_URL}"
        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // 수집 시작 — 기존 MainActivity의 검증 로직 그대로 + "스캔 중인지" 확인을 정확하게 바꿈
        btnStartCollect.setOnClickListener {
            val xM = etXMeter.text.toString().toDoubleOrNull()
            val yM = etYMeter.text.toString().toDoubleOrNull()

            if (xM == null || yM == null) {
                Toast.makeText(this, "x(m)/y(m)을 정확히 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isScanning || !isBound) {
                Toast.makeText(this, "먼저 메인 화면에서 '스캔 시작'을 눌러 스캔을 켜주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            scanService?.startCollection(xM, yM)
        }

        btnStopCollect.setOnClickListener { scanService?.stopCollection() }

        // 상태 관찰
        BeaconScanService.collectionStatusLiveData.observe(this) { status ->
            tvCollectStatus.text = status
            tvCollectStatus.setTextColor(
                when {
                    status.startsWith("수집 중") -> Color.parseColor("#4ADE80")
                    status.startsWith("⚠") -> Color.parseColor("#FBBF24")
                    else -> Color.parseColor("#F0F2F8")
                }
            )
            refreshButtons()
        }
        BeaconScanService.isCollectingLiveData.observe(this) { refreshButtons() }
        BeaconScanService.collectedRowCountLiveData.observe(this) { count ->
            tvCollectCount.text = "수집된 행: ${count}개"
        }
        BeaconScanService.statusLiveData.observe(this) { status ->
            val scanning = status == "스캔 중"
            tvScanState.text = if (scanning) "● 스캔 중" else "● 대기중"
            tvScanState.setTextColor(Color.parseColor(if (scanning) "#22C55E" else "#6B7280"))
            tvScanHint.visibility = if (scanning) View.GONE else View.VISIBLE
            refreshButtons()
        }
        BeaconScanService.beaconCacheLiveData.observe(this) { cache -> updateBeaconTable(cache) }

        // 수집 중에 나가려고 하면 확인
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isCollecting) { finish(); return }
                AlertDialog.Builder(this@CollectActivity)
                    .setTitle("데이터 수집 중")
                    .setMessage("수집이 진행 중입니다. 어떻게 할까요?")
                    .setPositiveButton("중단하고 나가기") { _, _ ->
                        scanService?.stopCollection()
                        finish()
                    }
                    .setNeutralButton("계속 수집하며 나가기") { _, _ -> finish() }
                    .setNegativeButton("취소", null)
                    .show()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, BeaconScanService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun refreshButtons() {
        btnStartCollect.isEnabled = isScanning
        btnStopCollect.isEnabled = isCollecting
    }

    // A1~A7 각각 최근 RSSI 표시 (안 잡히면 "—")
    private fun updateBeaconTable(cache: Map<String, CachedBeacon>) {
        val rssiList = BeaconConfig.BEACONS.map { info ->
            cache.values.find {
                it.beacon.id2?.toInt() == info.major && it.beacon.id3?.toInt() == info.minor
            }?.rssiHistory?.lastOrNull()
        }
        val lines = BeaconConfig.BEACONS.mapIndexed { i, info ->
            val rssi = rssiList[i]
            if (rssi == null) "${info.id}   —        미수신"
            else "${info.id}   ${rssi.toString().padStart(4)} dBm  수신"
        }
        val received = rssiList.count { it != null }
        tvBeaconTable.text = lines.joinToString("\n") + "\n\n${received}/${BeaconConfig.BEACONS.size}개 수신 중"
    }
}