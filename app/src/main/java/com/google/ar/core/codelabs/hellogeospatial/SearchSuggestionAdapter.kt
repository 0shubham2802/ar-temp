package com.google.ar.core.codelabs.hellogeospatial

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for displaying search suggestions in a RecyclerView
 */
class SearchSuggestionAdapter(
    private val onSuggestionClickListener: (SearchSuggestion) -> Unit
) : RecyclerView.Adapter<SearchSuggestionAdapter.SuggestionViewHolder>() {

    private val suggestions = mutableListOf<SearchSuggestion>()

    fun updateSuggestions(newSuggestions: List<SearchSuggestion>) {
        suggestions.clear()
        suggestions.addAll(newSuggestions)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_suggestion, parent, false)
        return SuggestionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        val suggestion = suggestions[position]
        holder.bind(suggestion)
    }

    override fun getItemCount(): Int = suggestions.size

    inner class SuggestionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.suggestionTitle)
        private val addressText: TextView = itemView.findViewById(R.id.suggestionAddress)
        private val iconView: ImageView = itemView.findViewById(R.id.suggestionIcon)

        fun bind(suggestion: SearchSuggestion) {
            titleText.text = suggestion.title
            addressText.text = suggestion.address

            // Set click listener
            itemView.setOnClickListener {
                onSuggestionClickListener(suggestion)
            }
        }
    }
} 