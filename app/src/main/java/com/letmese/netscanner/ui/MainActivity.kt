package com.letmese.netscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.letmese.netscanner.data.NetworkDevice
import com.letmese.netscanner.data.NetworkScanner
import com.letmese.netscanner.databinding.ActivityMainBinding
import com.letmese.netscanner.databinding.BottomSheetNetworkInfoBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bottomSheetBinding: BottomSheetNetworkInfoBinding
    private lateinit var networkScanner: NetworkScanner
    private lateinit var deviceAdapter: DeviceAdapter
    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startScan()
        } else {
            Toast.makeText(this, "Permissions required for network scanning", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        bottomSheetBinding = BottomSheetNetworkInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        networkScanner = NetworkScanner(this)
        setupUI()
        checkPermissionsAndScan()
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)

        // RecyclerView
        deviceAdapter = DeviceAdapter { device ->
            device.isExpanded = !device.isExpanded
            deviceAdapter.notifyItemChanged(deviceAdapter.devices.indexOf(device))
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = deviceAdapter

        // Swipe refresh
        binding.swipeRefresh.setOnRefreshListener { startScan() }
        binding.swipeRefresh.setColorSchemeResources(
            com.letmese.netscanner.R.color.primary,
            com.letmese.netscanner.R.color.secondary,
            com.letmese.netscanner.R.color.tertiary
        )

        // FAB
        binding.fabScan.setOnClickListener { startScan() }

        // Bottom sheet
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheetNetworkInfo)
        bottomSheetBinding.btnCloseBottomSheet.setOnClickListener {
            bottomSheetBehavior?.state = BottomSheetBehavior.STATE_HIDDEN
        }
    }

    private fun checkPermissionsAndScan() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.INTERNET
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val ungranted = requiredPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (ungranted.isNotEmpty()) {
            requestPermissions.launch(ungranted.toTypedArray())
        } else {
            startScan()
        }
    }

    private fun startScan() {
        binding.swipeRefresh.isRefreshing = true
        binding.emptyState.visibility = View.GONE
        binding.fabScan.isEnabled = false
        binding.fabScan.animate().rotationBy(360f).duration = 1000

        lifecycleScope.launch {
            networkScanner.scanNetwork(object : NetworkScanner.ScanCallback {
                override fun onDeviceFound(device: NetworkDevice) {
                    runOnUiThread {
                        deviceAdapter.submitList(deviceAdapter.devices + device)
                        binding.emptyState.visibility = View.GONE
                    }
                }

                override fun onScanComplete(devices: List<NetworkDevice>) {
                    runOnUiThread {
                        binding.swipeRefresh.isRefreshing = false
                        binding.fabScan.isEnabled = true
                        binding.fabScan.animate().cancel()
                        if (devices.isEmpty()) {
                            binding.emptyState.visibility = View.VISIBLE
                        }
                        Toast.makeText(this@MainActivity, "${devices.size} ${getString(com.letmese.netscanner.R.string.devices_found)}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(error: String) {
                    runOnUiThread {
                        binding.swipeRefresh.isRefreshing = false
                        binding.fabScan.isEnabled = true
                        binding.fabScan.animate().cancel()
                        Toast.makeText(this@MainActivity, "Error: $error", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
    }

    private fun showNetworkInfo() {
        val info = networkScanner.getNetworkInfo()
        bottomSheetBinding.tvSsid.text = info.ssid
        bottomSheetBinding.tvBssid.text = info.bssid
        bottomSheetBinding.tvLocalIp.text = info.localIp
        bottomSheetBinding.tvGateway.text = info.gateway
        bottomSheetBinding.tvSubnet.text = info.subnetMask
        bottomSheetBinding.tvDns.text = info.dns
        bottomSheetBinding.tvSignal.text = "${info.signalStrength}/5"
        bottomSheetBinding.tvFrequency.text = "${info.frequency} MHz"
        bottomSheetBinding.tvLinkSpeed.text = info.linkSpeed

        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            com.letmese.netscanner.R.id.action_network_info -> {
                showNetworkInfo()
                true
            }
            com.letmese.netscanner.R.id.action_port_scanner -> {
                Toast.makeText(this, "Port Scanner coming soon!", Toast.LENGTH_SHORT).show()
                true
            }
            com.letmese.netscanner.R.id.action_settings -> {
                Toast.makeText(this, "Settings coming soon!", Toast.LENGTH_SHORT).show()
                true
            }
            com.letmese.netscanner.R.id.action_about -> {
                Toast.makeText(this, "Net Scanner v1.0\nGlassy Material 3 Design", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}