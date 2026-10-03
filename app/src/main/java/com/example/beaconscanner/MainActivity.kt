package com.example.beaconscanner

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: BeaconAdapter

    private lateinit var btnScan: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvStrong: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvLocation: TextView

    // 🆕 [데이터 수집 모드] UI
    private lateinit var etGridRow: EditText
    private lateinit var etGridCol: EditText
    private lateinit var etXMeter: EditText
    private lateinit var etYMeter: EditText
    private lateinit var btnStartCollect: Button
    private lateinit var btnStopCollect: Button
    private lateinit var tvCollectStatus: TextView
    private lateinit var tvCollectCount: TextView

    private var hasOpenedWebApp = false

    // 🆕 서비스 바인딩 (startCollection/stopCollection 호출용)
    private var scanService: BeaconScanService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as BeaconScanService.LocalBinder
            scanService = localBinder.getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            scanService = null
            isBound = false
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODES = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan = findViewById(R.id.btnScan)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvCount = findViewById(R.id.tvCount)
        tvStrong = findViewById(R.id.tvStrong)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvLocation = findViewById(R.id.tvLocation)

        // 🆕 데이터 수집 모드 뷰 (activity_main.xml에 아래 id로 뷰를 추가해야 합니다 — 안내 참고)
        etGridRow = findViewById(R.id.etGridRow)
        etGridCol = findViewById(R.id.etGridCol)
        etXMeter = findViewById(R.id.etXMeter)
        etYMeter = findViewById(R.id.etYMeter)
        btnStartCollect = findViewById(R.id.btnStartCollect)
        btnStopCollect = findViewById(R.id.btnStopCollect)
        tvCollectStatus = findViewById(R.id.tvCollectStatus)
        tvCollectCount = findViewById(R.id.tvCollectCount)

        tvLocation.setOnClickListener { openWebApp() }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = BeaconAdapter(mutableListOf())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnScan.setOnClickListener { checkPermissionsAndScan() }
        btnStop.setOnClickListener { stopScan() }

        // 🆕 데이터 수집 시작 — 그리드 좌표 입력값 검증 후 서비스에 전달
        btnStartCollect.setOnClickListener {
            val row = etGridRow.text.toString().toIntOrNull()
            val col = etGridCol.text.toString().toIntOrNull()
            val xM = etXMeter.text.toString().toDoubleOrNull()
            val yM = etYMeter.text.toString().toDoubleOrNull()

            if (row == null || col == null || xM == null || yM == null) {
                Toast.makeText(this, "행/열/x(m)/y(m)을 모두 정확히 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isBound) {
                Toast.makeText(this, "먼저 '스캔 시작'을 눌러 스캔을 켜주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            scanService?.startCollection(row, col, xM, yM)
        }

        // 🆕 데이터 수집 중단
        btnStopCollect.setOnClickListener {
            scanService?.stopCollection()
        }

        // 🆕 수집 상태 관찰
        BeaconScanService.collectionStatusLiveData.observe(this) { status ->
            tvCollectStatus.text = status
        }
        BeaconScanService.collectedRowCountLiveData.observe(this) { count ->
            tvCollectCount.text = "수집된 행: ${count}개"
        }

        BeaconScanService.beaconCacheLiveData.observe(this) { cache -> updateUI(cache) }

        BeaconScanService.statusLiveData.observe(this) { status ->
            tvStatus.text = if (status == "스캔 중") "● 스캔 중" else "● 대기중"
            tvStatus.setTextColor(
                if (status == "스캔 중") Color.parseColor("#22C55E") else Color.parseColor("#6B7280")
            )
            btnScan.isEnabled = (status != "스캔 중")
            btnStop.isEnabled = (status == "스캔 중")
        }

        checkBluetooth()
    }

    // 🆕 서비스에 바인딩 — 스캔 서비스가 이미 떠 있으면 여기서 인스턴스를 가져옴
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

    private fun checkBluetooth() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (btManager.adapter == null || !btManager.adapter.isEnabled) {
            Toast.makeText(this, "블루투스를 활성화해 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODES)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODES &&
            grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) startScan()
        else Toast.makeText(this, "실내 위치 측정을 위해 필수 권한 승인이 필요합니다.", Toast.LENGTH_LONG).show()
    }

    private fun startScan() {
        val intent = Intent(this, BeaconScanService::class.java)
        ContextCompat.startForegroundService(this, intent)
        btnScan.isEnabled = false
        btnStop.isEnabled = true
        tvEmpty.text = "비콘 신호를 탐색하고 있습니다..."
        if (!hasOpenedWebApp) {
            hasOpenedWebApp = true
            openWebApp()
        }
    }

    private fun openWebApp() {
        try {
            val sharedPref = getSharedPreferences("BeaconScannerPrefs", Context.MODE_PRIVATE)
            val scannerId = sharedPref.getString("scanner_id", "") ?: ""
            val url = "${BeaconConfig.WEB_APP_URL}/?sid=$scannerId"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "웹앱을 열 수 없습니다: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopScan() {
        val intent = Intent(this, BeaconScanService::class.java)
        stopService(intent)
        btnScan.isEnabled = true
        btnStop.isEnabled = false
    }

    private fun updateUI(cache: Map<String, CachedBeacon>) {
        val sortedBeacons = cache.values.sortedByDescending { it.rssiHistory.average() }.map { it.beacon }
        adapter.updateBeacons(sortedBeacons, cache)
        val strong = cache.values.count { it.rssiHistory.average() >= -70 }
        tvCount.text = "${cache.size}"
        tvStrong.text = "$strong"
        tvEmpty.visibility = if (cache.isEmpty()) View.VISIBLE else View.GONE
    }
}