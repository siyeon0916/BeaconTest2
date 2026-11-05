package com.example.beaconscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.altbeacon.beacon.Beacon

class BeaconAdapter(private val beacons: MutableList<Beacon>) :
    RecyclerView.Adapter<BeaconAdapter.ViewHolder>() {

    // 독립 분리된 CachedBeacon 참조
    private var cache: Map<String, CachedBeacon> = emptyMap()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvId: TextView = view.findViewById(R.id.tvId)
        val tvRssi: TextView = view.findViewById(R.id.tvRssi)
        val tvSignalLabel: TextView = view.findViewById(R.id.tvSignalLabel)
        val tvDistance: TextView = view.findViewById(R.id.tvDistance)
        val tvTxPower: TextView = view.findViewById(R.id.tvTxPower)
        val tvMac: TextView = view.findViewById(R.id.tvMac)
        val tvType: TextView = view.findViewById(R.id.tvType)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_beacon, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val beacon = beacons[position]

        // UUID-major-minor 키로 캐시에서 조회
        val key = BeaconUtils.beaconKey(beacon)
        val cached = cache[key]

        // 최근 RSSI 히스토리 평균값 계산
        val avgRssi = cached?.rssiHistory?.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: beacon.rssi
        val rawRssi = beacon.rssi

        // 비콘 이름
        val name = beacon.bluetoothName?.takeIf { it.isNotBlank() } ?: "(이름 없음)"
        holder.tvName.text = name

        // 비콘 ID
        val id1 = beacon.id1?.toString()
        val id2 = beacon.id2?.toString()
        val id3 = beacon.id3?.toString()
        val idText = when {
            id1 != null && id2 != null && id3 != null -> "UUID: $id1\nMajor: $id2  Minor: $id3"
            id1 != null -> "ID: $id1"
            else -> "MAC: ${beacon.bluetoothAddress}"
        }
        holder.tvId.text = idText

        // RSSI 및 신호 세기 라벨 표시
        holder.tvRssi.text = "$avgRssi dBm"
        holder.tvRssi.setTextColor(BeaconUtils.rssiColor(avgRssi))
        holder.tvSignalLabel.text = "${BeaconUtils.rssiLabel(avgRssi)} (현재: $rawRssi)"
        holder.tvSignalLabel.setTextColor(BeaconUtils.rssiColor(avgRssi))

        // 거리 계산 (Path Loss Model 적용)
        val configTxPower = BeaconConfig.BEACONS.find { it.key == key }?.txPower ?: beacon.txPower
        val dist = LocationEstimator.rssiToDistance(avgRssi, configTxPower)

        holder.tvDistance.text = "거리: ${"%.2f".format(dist)} m"
        holder.tvTxPower.text = "TX: ${beacon.txPower} dBm"
        holder.tvMac.text = "MAC: ${beacon.bluetoothAddress}"

        // 비콘 타입 분류
        val type = when {
            beacon.serviceUuid == 0xFEAA -> "Eddystone"
            id1 != null && id2 != null && id3 != null -> "iBeacon"
            id1 != null -> "AltBeacon"
            else -> "BLE Device"
        }
        holder.tvType.text = type
    }

    override fun getItemCount() = beacons.size

    fun updateBeacons(newBeacons: List<Beacon>, newCache: Map<String, CachedBeacon>) {
        cache = newCache
        beacons.clear()
        beacons.addAll(newBeacons)
        notifyDataSetChanged()
    }
}