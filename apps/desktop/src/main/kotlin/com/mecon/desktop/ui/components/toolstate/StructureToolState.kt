package com.mecon.desktop.ui.components.toolstate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mecon.api.primitive.BarlineType
import com.mecon.api.storage.NavigationMark

/** State owned by barline, volta and navigation-mark tools. */
class StructureToolState {
    var barlineSectionExpanded by mutableStateOf(true)
    var selectedBarlineType by mutableStateOf(BarlineType.SINGLE)
    var selectedRepeatCount by mutableStateOf(2)
    var selectedVoltaNumber by mutableStateOf<Int?>(null)
    var selectedNavigationMark by mutableStateOf<NavigationMark?>(null)

    fun reset() {
        barlineSectionExpanded = true
        selectedBarlineType = BarlineType.SINGLE
        selectedRepeatCount = 2
        selectedVoltaNumber = null
        selectedNavigationMark = null
    }
}
