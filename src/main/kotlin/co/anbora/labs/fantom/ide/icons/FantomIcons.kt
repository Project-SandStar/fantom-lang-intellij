package co.anbora.labs.fantom.ide.icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object FantomIcons {

    val FILE = getIcon("icon_file.svg")

    /** .axon files: a page with a lambda arrow (axon_file_dark.svg in dark themes). */
    val AXON_FILE = getIcon("axon_file.svg")

    /** .trio files: a page with record rows (trio_file_dark.svg in dark themes). */
    val TRIO_FILE = getIcon("trio_file.svg")

    val FUNCTION = AllIcons.Nodes.Function
    val PARAMETER = AllIcons.Nodes.Parameter

    val VARIABLE = AllIcons.Nodes.Variable

    private fun getIcon(path: String): Icon {
        return IconLoader.findIcon("/icons/$path", FantomIcons::class.java.classLoader) as Icon
    }
}
