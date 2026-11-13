package co.anbora.labs.fantom.ide.settings

import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList

/** A list renderer that shows [label] of each item; a null item shows nothing. */
class FantomTextRenderer<T>(private val label: (T) -> String) : SimpleListCellRenderer<T>() {
    override fun customize(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
        text = value?.let(label) ?: ""
    }
}
