package br.com.centralcasa.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter
import br.com.centralcasa.books.BookMetadata

class BookMetadataAdapter(context: Context, private val vocabulary: List<String>) :
    ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, BookMetadata.suggestions(vocabulary).toMutableList()) {
    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val matches = BookMetadata.suggestions(vocabulary, constraint?.toString().orEmpty())
            return FilterResults().apply { values = matches; count = matches.size }
        }
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            val matches = (results?.values as? List<*>)?.filterIsInstance<String>().orEmpty()
            setNotifyOnChange(false); clear(); addAll(matches); notifyDataSetChanged()
        }
    }
}
