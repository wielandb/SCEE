package de.westnordost.streetcomplete.util

import android.content.Context
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import de.westnordost.osmfeatures.FeatureDictionary
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.quests.tree.SearchAdapter
import de.westnordost.streetcomplete.util.ktx.dpToPx
import de.westnordost.streetcomplete.util.ktx.toast
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

// use displaySet and dataSet: displaySet is the sorted map.toList
// editing the map directly is not so simple, because the order may change if the key is changed (actually removed and re-added)
class EditTagsAdapter(
    private val displaySet: MutableList<Pair<String, String>>,
    private val dataSet: MutableMap<String, String>,
    private val featureDictionary: FeatureDictionary,
    context: Context,
    private val onDataChanged: () -> Unit
) :
    RecyclerView.Adapter<EditTagsAdapter.ViewHolder>() {
    val suggestionHeight = TypedValue().apply { context.theme.resolveAttribute(android.R.attr.listPreferredItemHeight, this, false) }
        .getDimension(context.resources.displayMetrics)
    val suggestionMaxHeight = context.resources.displayMetrics.heightPixels - context.dpToPx(100)

    init {
        if (keySuggestionsForFeatureId.isEmpty() && valueSuggestionsByKey.isEmpty()) {
            try {
                val keySuggestions = context.resources.assets.open("tag_editor/keySuggestionsForFeature.json").reader().readText()
                val valueSuggestions = context.resources.assets.open("tag_editor/valueSuggestionsByKey.json").reader().readText()
                keySuggestionsForFeatureId.putAll(Json.decodeFromString(keySuggestions))
                valueSuggestionsByKey.putAll(Json.decodeFromString(valueSuggestions))
            } catch (e: Exception) {
                Log.w("TagEditor", "failed to read and parse suggestions: ${e.message}")
            }
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val keyView: AutoCompleteTextView = view.findViewById<AutoCompleteTextView>(R.id.keyText).apply {
            setOnFocusChangeListener { _, focused -> if (focused)
                setText(text.toString()) // to get fresh suggestions and show dropdown; showDropDown() not helping here
            }
            onItemClickListener = AdapterView.OnItemClickListener { _, _, _, _ ->
                valueView.requestFocus() // move to value view if key is selected from suggestions
            }
            setAdapter(SearchAdapter(context, { search ->
                if (!isFocused) return@SearchAdapter emptyList() // don't search if the field is not focused
                val feature = featureDictionary.byTags(dataSet).isSuggestion(false).find().firstOrNull()
                    ?: return@SearchAdapter defaultKeyList
                val s = getSuggestions(feature.id, dataSet).filter { it.startsWith(search) }
                val h = TypedValue()
                context.theme.resolveAttribute(android.R.attr.listPreferredItemHeight, h, false)
                // limit the height of suggestions, because when all are shown the ones on top are hard to reach
                val minus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) rootWindowInsets.systemWindowInsetBottom
                else 0
                dropDownHeight = (suggestionMaxHeight - minus).coerceAtMost(suggestionHeight * s.size).toInt()
                s
            }, { it }))
            doAfterTextChanged {
                val position = absoluteAdapterPosition
                val newKey = it.toString()
                // do nothing if key is unchanged, happens when views are filled by EditTagsAdapter
                if (displaySet[position].first == newKey) return@doAfterTextChanged
                if (dataSet.containsKey(newKey)) {
                    // don't store duplicate keys, user should rename or delete them
                    context.toast(resources.getString(R.string.tag_editor_duplicate_key, newKey), Toast.LENGTH_LONG)
                    return@doAfterTextChanged
                }
                val oldEntry = displaySet[position]
                dataSet.remove(oldEntry.first)
                val newEntry = newKey to oldEntry.second
                dataSet[newEntry.first] = newEntry.second
                displaySet[position] = newEntry
                onDataChanged()
            }
        }

        val valueView: AutoCompleteTextView = view.findViewById<AutoCompleteTextView>(R.id.valueText).apply {
            setOnFocusChangeListener { _, focused -> if (focused)
                setText(text.toString()) // to get fresh suggestions and show dropdown; showDropDown() not helping here
            }
            setAdapter(SearchAdapter(context, { search ->
                if (!isFocused) return@SearchAdapter emptyList()
                val key = displaySet[absoluteAdapterPosition].first
                val s = valueSuggestionsByKey[key].orEmpty().filter { it.startsWith(search) }
                val minus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) rootWindowInsets.systemWindowInsetBottom
                else 0
                dropDownHeight = (suggestionMaxHeight - minus).coerceAtMost(suggestionHeight * s.size).toInt()
                s
            }, { it }))
            doAfterTextChanged {
                val position = absoluteAdapterPosition
                if (displaySet[position].second == it.toString()) return@doAfterTextChanged
                val oldEntry = displaySet[position]
                val newEntry = oldEntry.first to it.toString()
                dataSet[newEntry.first] = newEntry.second
                displaySet[position] = newEntry
                onDataChanged()
            }
        }

        val delete: ImageView = view.findViewById<ImageView>(R.id.deleteButton).apply {
            setOnClickListener {
                val position = absoluteAdapterPosition
                val oldEntry = displaySet.removeAt(position)
                dataSet.remove(oldEntry.first)
                onDataChanged()
//                notifyItemRemoved(position) // crash when editing an entry, and deleting another one right after
                notifyDataSetChanged()
            }
        }
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(viewGroup.context)
            .inflate(R.layout.row_edit_tag, viewGroup, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        viewHolder.keyView.setText(displaySet[position].first)
        viewHolder.valueView.setText(displaySet[position].second)
    }

    override fun getItemCount() = displaySet.size

    override fun getItemId(position: Int) = position.toLong()

    private fun getSuggestions(featureId: String, tags: Map<String, String>): Collection<String> {
        val fields = getMainSuggestions(featureId)
        val moreFields = getSecondarySuggestions(featureId)
        val suggestions = mutableSetOf<String>()
        fields.forEach {
            if (it.startsWith('{'))
                suggestions.addAll(getMainSuggestions(it.substringAfter('{').substringBefore('}')))
            else suggestions.add(it)
        }
        moreFields.forEach {
            if (it.startsWith('{'))
                suggestions.addAll(getSecondarySuggestions(it.substringAfter('{').substringBefore('}')))
            else suggestions.add(it)
        }
        suggestions.removeAll(tags.keys)
        // suggestions should not be cluttered with all those address tags
        val moveToEnd = suggestions.filter { it.startsWith("addr:") || it.startsWith("ref:") }
        suggestions.removeAll(moveToEnd)
        suggestions.addAll(moveToEnd)
        return suggestions
    }

    private fun getMainSuggestions(featureId: String): List<String> {
        val suggestions = keySuggestionsForFeatureId[featureId]?.first
        return suggestions ?: if (featureId.contains('/')) getMainSuggestions(featureId.substringBeforeLast('/')) else emptyList()
    }

    private fun getSecondarySuggestions(featureId: String): List<String> {
        val suggestions = keySuggestionsForFeatureId[featureId]?.second
        return suggestions ?: if (featureId.contains('/')) getSecondarySuggestions(featureId.substringBeforeLast('/')) else emptyList()
    }

    companion object {
        private val keySuggestionsForFeatureId = hashMapOf<String, Pair<List<String>?, List<String>?>>()
        private val valueSuggestionsByKey = hashMapOf<String, List<String>>()
    }
}

private val defaultKeyList = listOf("amenity", "shop", "name", "man_made", "emergency", "natural", "office", "leisure", "tourism", "historic", "attraction")
