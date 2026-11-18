package com.mo.ugreenforcoloros

import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

class UgreenT6Scanner(
    context: Context,
    private val onDeviceFound: (name: String, address: String, rssi: Int) -> Unit,
    private val onScanFinished: () -> Unit
) {

    private val context = context.applicationContext

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val bluetoothLeScanner: BluetoothLeScanner?
        get() = bluetoothManager.adapter?.bluetoothLeScanner

    private var scanning = false

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            val device = result.device

            val name = try {
                device.name ?: "(无名称)"
            } catch (_: SecurityException) {
                "(无法读取设备名称)"
            }

            onDeviceFound(
                name,
                device.address,
                result.rssi
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false

            onDeviceFound(
                "扫描失败",
                "errorCode=$errorCode",
                0
            )

            onScanFinished()
        }
    }

    fun startScan() {

        if (scanning) {
            return
        }

        if (!hasScanPermission()) {
            onDeviceFound(
                "缺少 BLUETOOTH_SCAN 权限",
                "",
                0
            )
            return
        }

        val scanner = bluetoothLeScanner

        if (scanner == null) {
            onDeviceFound(
                "BLE 扫描器不可用",
                "",
                0
            )
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(
                ScanSettings.SCAN_MODE_LOW_LATENCY
            )
            .build()

        try {

            scanner.startScan(
                null,
                settings,
                scanCallback
            )

            scanning = true

        } catch (e: SecurityException) {

            onDeviceFound(
                "启动扫描失败",
                e.message ?: "SecurityException",
                0
            )
        }
    }

    fun stopScan() {

        if (!scanning) {
            return
        }

        if (!hasScanPermission()) {
            scanning = false
            onScanFinished()
            return
        }

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }

        scanning = false
        onScanFinished()
    }

    private fun hasScanPermission(): Boolean {

        return if (android.os.Build.VERSION.SDK_INT >= 31) {

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

        } else {

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}