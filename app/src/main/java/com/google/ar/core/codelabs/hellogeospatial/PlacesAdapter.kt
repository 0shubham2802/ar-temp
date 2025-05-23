package com.google.ar.core.codelabs.hellogeospatial

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for displaying search suggestions and recent places
 */
class PlacesAdapter(
    private val onItemClickListener: (SearchSuggestion) -> Unit,
    private val onClearRecentPlacesListener: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_RECENT_PLACE = 1
        private const val TYPE_DIVIDER = 2
        private const val TYPE_SUGGESTION = 3
    }

    private val items = mutableListOf<PlaceItem>()
    
    sealed class PlaceItem {
        object RecentPlacesHeader : PlaceItem()
        object Divider : PlaceItem()
        data class RecentPlace(val place: SearchSuggestion) : PlaceItem()
        data class Suggestion(val place: SearchSuggestion) : PlaceItem()
    }

    fun updateSuggestions(suggestions: List<SearchSuggestion>) {
        // Clear all suggestions but keep recent places
        items.removeAll { it is PlaceItem.Suggestion }
        
        // Add new suggestions
        if (suggestions.isNotEmpty()) {
            // Add a divider if we have recent places
            if (items.any { it is PlaceItem.RecentPlace }) {
                items.add(PlaceItem.Divider)
            }
            
            // Add all new suggestions
            items.addAll(suggestions.map { PlaceItem.Suggestion(it) })
        }
        
        notifyDataSetChanged()
    }

    fun setRecentPlaces(recentPlaces: List<SearchSuggestion>) {
        // Remove existing recent places
        items.removeAll { it is PlaceItem.RecentPlace || it is PlaceItem.RecentPlacesHeader || it is PlaceItem.Divider }
        
        // Re-add header and recent places only if we have any
        if (recentPlaces.isNotEmpty()) {
            // Add at the beginning of the list
            items.add(0, PlaceItem.RecentPlacesHeader)
            items.addAll(1, recentPlaces.map { PlaceItem.RecentPlace(it) })
            
            // Add divider if we have suggestions
            if (items.any { it is PlaceItem.Suggestion }) {
                items.add(recentPlaces.size + 1, PlaceItem.Divider)
            }
        }
        
        notifyDataSetChanged()
    }

    fun clearAllItems() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is PlaceItem.RecentPlacesHeader -> TYPE_HEADER
            is PlaceItem.Divider -> TYPE_DIVIDER
            is PlaceItem.RecentPlace -> TYPE_RECENT_PLACE
            is PlaceItem.Suggestion -> TYPE_SUGGESTION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_recent_places_header, parent, false)
                HeaderViewHolder(view, onClearRecentPlacesListener)
            }
            TYPE_DIVIDER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_divider, parent, false)
                DividerViewHolder(view)
            }
            TYPE_RECENT_PLACE -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_recent_place, parent, false)
                RecentPlaceViewHolder(view, onItemClickListener)
            }
            TYPE_SUGGESTION -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_search_suggestion, parent, false)
                SuggestionViewHolder(view, onItemClickListener)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is PlaceItem.RecentPlace -> (holder as RecentPlaceViewHolder).bind(item.place)
            is PlaceItem.Suggestion -> (holder as SuggestionViewHolder).bind(item.place)
            else -> { /* Nothing to bind for headers and dividers */ }
        }
    }

    override fun getItemCount(): Int = items.size

    // ViewHolders
    class HeaderViewHolder(itemView: View, private val onClearClickListener: () -> Unit) : RecyclerView.ViewHolder(itemView) {
        init {
            itemView.findViewById<TextView>(R.id.clearRecentPlaces).setOnClickListener {
                onClearClickListener()
            }
        }
    }

    class DividerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    class RecentPlaceViewHolder(itemView: View, private val onItemClickListener: (SearchSuggestion) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.placeTitle)
        private val addressText: TextView = itemView.findViewById(R.id.placeAddress)

        fun bind(place: SearchSuggestion) {
            titleText.text = place.title
            addressText.text = place.address

            itemView.setOnClickListener {
                onItemClickListener(place)
            }
        }
    }

    class SuggestionViewHolder(itemView: View, private val onItemClickListener: (SearchSuggestion) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.suggestionTitle)
        private val addressText: TextView = itemView.findViewById(R.id.suggestionAddress)

        fun bind(place: SearchSuggestion) {
            titleText.text = place.title
            addressText.text = place.address

            itemView.setOnClickListener {
                onItemClickListener(place)
            }
        }
    }
} 