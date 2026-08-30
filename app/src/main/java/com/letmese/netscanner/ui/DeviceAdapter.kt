package com.letmese.netscanner.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.letmese.netscanner.data.NetworkDevice
import com.letmese.netscanner.databinding.ItemDeviceBinding

class DeviceAdapter(
    private val onDeviceClick: (NetworkDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private var devices: List<NetworkDevice> = emptyList()

    inner class DeviceViewHolder(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(device: NetworkDevice) {
            binding.device = device
            binding.onClick = { onDeviceClick(device) }
            
            // Status indicator
            val statusColor = if (device.isOnline) binding.root.context.getColor(com.letmese.netscanner.R.color.online)
            else binding.root.context.getColor(com.letmese.netscanner.R.color.offline)
            binding.statusIndicator.setBackgroundColor(statusColor)
            
            // Expand/collapse
            binding.root.setOnClickListener {
                device.isExpanded = !device.isExpanded
                binding.detailsLayout.visibility = if (device.isExpanded) View.VISIBLE else View.GONE
                binding.chevron.animate().rotation(if (device.isExpanded) 90f else 0f).duration = 200
            }
            
            binding.executePendingBindings()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    fun submitList(newDevices: List<NetworkDevice>) {
        devices = newDevices
        notifyDataSetChanged()
    }
}