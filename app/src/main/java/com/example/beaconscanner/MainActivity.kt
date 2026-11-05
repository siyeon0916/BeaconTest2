package com.example.beaconscanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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

    // 데이터 수집 화면(CollectActivity)으로 가는 버튼
    private lateinit var btnAdmin: TextView
    companion object {
        private const val PERMISSION_REQUEST_CODES = 1001
    }

    // QR(guidant://start)로 실행됐는지 → 스캔 시작 후 웹앱을 자동으로 열지 결정
    private var launchedFromQr = false

    // 블루투스가 꺼져 있으면 켜기 요청 → 켜지면 바로 스캔 시작
    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startScan()
            else {
                launchedFromQr = false
                Toast.makeText(this, "블루투스를 켜야 위치 측정이 가능합니다.", Toast.LENGTH_LONG).show()
            }
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

        btnAdmin = findViewById(R.id.btnAdmin)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        adapter = BeaconAdapter(mutableListOf())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnScan.setOnClickListener { checkPermissionsAndScan() }
        btnStop.setOnClickListener { stopScan() }

        // 데이터 수집은 별도 화면에서 (좌표 입력/수집 시작·중단)
        btnAdmin.setOnClickListener {
            startActivity(Intent(this, CollectActivity::class.java))
        }

        // 수집이 백그라운드에서 계속 돌고 있으면 메인 화면 버튼에 표시
        BeaconScanService.isCollectingLiveData.observe(this) { collecting ->
            btnAdmin.text = if (collecting) "● 수집 중" else "⚙ 수집"
            btnAdmin.setTextColor(Color.parseColor(if (collecting) "#4ADE80" else "#9CA3AF"))
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

        // QR로 들어온 경우 : 비콘 7개를 모두 받아 서버 전송이 시작된 뒤 웹앱으로 이동
        // ("스캔 중"만으로 넘어가면 아직 서버에 위치가 안 올라가서 지도가 비어 있음)
        BeaconScanService.readyLiveData.observe(this) { ready ->
            if (ready && launchedFromQr) openWebApp()
        }

        // 화면 회전 등으로 다시 만들어질 때는 안내창을 또 띄우지 않음
        if (savedInstanceState == null) handleQrLaunch(intent)
    }

    // 앱이 이미 켜져 있는 상태에서 QR로 다시 들어온 경우 (launchMode=singleTask)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleQrLaunch(intent)
    }

    // guidant://start 로 실행됐으면 "위치 허용" 안내창 표시
    private fun handleQrLaunch(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme != "guidant" || data.host != "start") return
        intent.data = null // 한 번 처리한 링크는 비움 (재진입 시 중복 방지)

        launchedFromQr = true
        when {
            BeaconScanService.readyLiveData.value == true -> openWebApp() // 이미 위치 전송 중이면 바로 웹앱으로
            BeaconScanService.statusLiveData.value == "스캔 중" -> Unit   // 스캔 중이면 7개 수신될 때까지 대기
            else -> showLocationConsentDialog()
        }
    }

    private fun showLocationConsentDialog() {
        AlertDialog.Builder(this)
            .setTitle("위치 허용")
            .setMessage("현재 위치, 혼잡도, 최단거리 안내를 위해\n주변 비콘 신호로 위치를 측정합니다.\n\n위치 허용 하시겠습니까?")
            .setCancelable(false)
            .setPositiveButton("허용") { _, _ -> checkPermissionsAndScan() } // = 스캔 시작 버튼과 동일
            .setNegativeButton("나중에") { _, _ -> launchedFromQr = false }
            .show()
    }

    // 스캔 시작 후 웹앱을 기기 ID(sid)와 함께 열기 → 웹앱이 이 기기의 위치를 표시
    private fun openWebApp() {
        launchedFromQr = false
        val sid = BeaconScanService.getOrCreateScannerId(this)
        val uri = Uri.parse(BeaconConfig.WEB_APP_URL).buildUpon()
            .appendQueryParameter("sid", sid)
            .build()
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    // 스캔에 꼭 필요한 권한 (알림 권한은 거부해도 스캔은 진행)
    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        return permissions
    }

    private fun checkPermissionsAndScan() {
        val permissions = requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) ensureBluetoothThenScan()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODES)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODES) return
        val allRequiredGranted = requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allRequiredGranted) ensureBluetoothThenScan()
        else {
            launchedFromQr = false
            Toast.makeText(this, "실내 위치 측정을 위해 필수 권한 승인이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    // 블루투스가 꺼져 있으면 켜기 요청, 켜져 있으면 바로 스캔
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT는 바로 위 단계에서 승인됨
    private fun ensureBluetoothThenScan() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            Toast.makeText(this, "이 기기는 블루투스를 지원하지 않습니다.", Toast.LENGTH_LONG).show()
            return
        }
        if (adapter.isEnabled) startScan()
        else enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun startScan() {
        val intent = Intent(this, BeaconScanService::class.java)
        ContextCompat.startForegroundService(this, intent)
        btnScan.isEnabled = false
        btnStop.isEnabled = true
        tvEmpty.text = "비콘 신호를 탐색하고 있습니다..."
        if (launchedFromQr) {
            Toast.makeText(this, "비콘 신호를 모두 받으면 웹 화면으로 이동합니다.", Toast.LENGTH_LONG).show()
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