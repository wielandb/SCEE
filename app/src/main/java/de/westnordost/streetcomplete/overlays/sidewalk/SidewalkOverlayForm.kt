package de.westnordost.streetcomplete.overlays.sidewalk

import android.os.Bundle
import android.view.View
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.edits.update_tags.StringMapChangesBuilder
import de.westnordost.streetcomplete.data.osm.edits.update_tags.UpdateElementTagsAction
import de.westnordost.streetcomplete.osm.sidewalk.LeftAndRightSidewalk
import de.westnordost.streetcomplete.osm.sidewalk.Sidewalk
import de.westnordost.streetcomplete.osm.sidewalk.Sidewalk.NO
import de.westnordost.streetcomplete.osm.sidewalk.Sidewalk.SEPARATE
import de.westnordost.streetcomplete.osm.sidewalk.Sidewalk.YES
import de.westnordost.streetcomplete.osm.sidewalk.SidewalkSides
import de.westnordost.streetcomplete.osm.sidewalk.applyTo
import de.westnordost.streetcomplete.osm.sidewalk.asItem
import de.westnordost.streetcomplete.osm.sidewalk.asStreetSideItem
import de.westnordost.streetcomplete.osm.sidewalk.createSidewalkSides
import de.westnordost.streetcomplete.overlays.AStreetSideSelectOverlayForm
import de.westnordost.streetcomplete.view.controller.StreetSideDisplayItem
import de.westnordost.streetcomplete.view.image_select.ImageListPickerDialog

class SidewalkOverlayForm : AStreetSideSelectOverlayForm<Sidewalk>() {

    private var currentSidewalk: LeftAndRightSidewalk? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState == null) {
            initStateFromTags()
        }
    }

    private fun initStateFromTags() {
        val sidewalk = createSidewalkSides(element.tags)
        currentSidewalk = sidewalk
        streetSideSelect.setPuzzleSide(sidewalk?.left?.asStreetSideItem(), false)
        streetSideSelect.setPuzzleSide(sidewalk?.right?.asStreetSideItem(), true)
    }

    override fun onClickSide(isRight: Boolean) {
        val items = listOf(YES, NO, SEPARATE).mapNotNull { it.asItem() }
        ImageListPickerDialog(requireContext(), items, R.layout.cell_icon_select_with_label_below, 2) { item ->
            streetSideSelect.replacePuzzleSide(item.value!!.asStreetSideItem()!!, isRight)
        }.show()
    }

    override fun onClickOk() {
        streetSideSelect.saveLastSelection()
        applyEdit(UpdateElementTagsAction(StringMapChangesBuilder(element.tags).also {
            SidewalkSides(streetSideSelect.left!!.value, streetSideSelect.right!!.value).applyTo(it)
        }.create()))
    }

    override fun hasChanges(): Boolean =
        LeftAndRightSidewalk(streetSideSelect.left?.value, streetSideSelect.right?.value) != currentSidewalk

    override fun serialize(item: StreetSideDisplayItem<Sidewalk>, isRight: Boolean) =
        item.value.name

    override fun deserialize(str: String, isRight: Boolean) =
        Sidewalk.valueOf(str).asStreetSideItem()!!
}
