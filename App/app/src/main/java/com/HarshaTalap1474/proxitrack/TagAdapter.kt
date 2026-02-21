package com.HarshaTalap1474.proxitrack

import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HarshaTalap1474.proxitrack.data.TrackingNode

class TagAdapter : ListAdapter<TrackingNode, TagAdapter.TagViewHolder>(TagComparator()) {

    // 1. Inflate the XML layout for each individual card
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tag_card, parent, false)
        return TagViewHolder(view)
    }

    // 2. Bind the Database data to the specific UI elements on the card
    override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
        val currentTag = getItem(position)
        holder.bind(currentTag)

        // NEW: Click to open Details Page
        holder.itemView.setOnClickListener {
            val intent = Intent(holder.itemView.context, TagDetailsActivity::class.java)
            intent.putExtra("MAC_ADDRESS", currentTag.macAddress)
            holder.itemView.context.startActivity(intent)
        }
    }

    // 3. The ViewHolder holds the specific UI elements in memory to avoid lag
    class TagViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconImage: ImageView = itemView.findViewById(R.id.iconImage)
        private val tvCustomName: TextView = itemView.findViewById(R.id.tvCustomName)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvRssi: TextView = itemView.findViewById(R.id.tvRssi)

        fun bind(node: TrackingNode) {
            // Set the Name and Icon
            tvCustomName.text = node.customName

            // If iconId is 0 (not set), fallback to a default lock/secure icon
            if (node.iconId != 0) {
                iconImage.setImageResource(node.iconId)
            } else {
                iconImage.setImageResource(android.R.drawable.ic_secure)
            }

            // Set the real-time RSSI signal strength
            tvRssi.text = "${node.lastRssi} dBm"

            // Handle the UI Colors based on the "Status" integer from our Service
            when (node.status) {
                0 -> {
                    tvStatus.text = "🟢 NEAR"
                    tvStatus.setTextColor(Color.parseColor("#4CAF50")) // Green
                }
                1 -> {
                    tvStatus.text = "🟡 SEARCHING"
                    tvStatus.setTextColor(Color.parseColor("#FF9800")) // Orange/Yellow
                }
                2 -> {
                    tvStatus.text = "🔴 LOST"
                    tvStatus.setTextColor(Color.parseColor("#F44336")) // Red
                }
            }
        }
    }

    // 4. DiffUtil: The magic algorithm that prevents the screen from flickering
    class TagComparator : DiffUtil.ItemCallback<TrackingNode>() {
        // Checks if it's the same physical ESP32 tag (comparing Primary Keys)
        override fun areItemsTheSame(oldItem: TrackingNode, newItem: TrackingNode): Boolean {
            return oldItem.macAddress == newItem.macAddress
        }

        // Checks if any actual data (like the RSSI or Status) changed
        override fun areContentsTheSame(oldItem: TrackingNode, newItem: TrackingNode): Boolean {
            return oldItem == newItem
        }
    }
}