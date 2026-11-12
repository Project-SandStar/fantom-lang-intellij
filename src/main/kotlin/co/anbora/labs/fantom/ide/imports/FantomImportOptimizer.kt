package co.anbora.labs.fantom.ide.imports

import co.anbora.labs.fantom.lang.core.psi.FantomFile
import co.anbora.labs.fantom.lang.core.psi.FantomUsingDef
import co.anbora.labs.fantom.lang.resolve.FantomUsingScope
import com.intellij.lang.ImportOptimizer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Optimize Imports (`Ctrl+Alt+O`) for Fantom.
 *
 * Removes the `using` lines [FantomUsingUsage] reports as unused and sorts the
 * rest: `using sys` first, then pods alphabetically with the whole-pod import
 * before the type imports of the same pod, FFI usings (`[java]`) last. A line
 * with a trailing comment or an FFI spec is never removed and keeps its text.
 * Full-line comments inside the block stay at its top. One blank line follows
 * the block. The file is left alone when nothing would change.
 */
class FantomImportOptimizer : ImportOptimizer {

    override fun supports(file: PsiFile): Boolean = file is FantomFile

    override fun processFile(file: PsiFile): Runnable {
        val plan = plan(file) ?: return EMPTY
        return object : ImportOptimizer.CollectingInfoRunnable {
            override fun run() {
                val manager = PsiDocumentManager.getInstance(file.project)
                val document = manager.getDocument(file) ?: return
                manager.doPostponedOperationsAndUnblockDocument(document)
                if (document.text != plan.before) return
                document.replaceString(plan.start, plan.end, plan.replacement)
                manager.commitDocument(document)
            }

            override fun getUserNotificationInfo(): String? =
                if (plan.removed == 0) null
                else "Removed ${plan.removed} unused using" + (if (plan.removed == 1) "" else "s")
        }
    }

    private class Plan(val before: String, val start: Int, val end: Int, val replacement: String, val removed: Int)

    private class Item(val key: Key, val text: String)
    private data class Key(val group: Int, val pod: String, val hasType: Int, val type: String) : Comparable<Key> {
        override fun compareTo(other: Key): Int =
            compareValuesBy(this, other, { it.group }, { it.pod }, { it.hasType }, { it.type })
    }

    /** The text edit to make, or null when the file has no usings or is already in shape. */
    private fun plan(file: PsiFile): Plan? {
        val usings = FantomUsingScope.usingDefs(file)
        if (usings.isEmpty()) return null
        val text = file.text
        val unused = FantomUsingUsage.unused(file)

        val blockStart = lineStart(text, usings.first().textRange.startOffset)
        val blockEnd = lineEnd(text, usings.last().textRange.endOffset)
        val block = text.substring(blockStart, blockEnd)
        val lines = block.split('\n')
        val lineStarts = IntArray(lines.size).also { starts ->
            var off = blockStart
            lines.forEachIndexed { i, l -> starts[i] = off; off += l.length + 1 }
        }
        val usingsByLine = usings.groupBy { u ->
            lineStarts.indexOfLast { it <= u.textRange.startOffset }
        }

        val preserved = ArrayList<String>()
        val items = ArrayList<Item>()
        var removed = 0
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            val onLine = usingsByLine[i]
            when {
                onLine == null -> if (line.isNotEmpty()) preserved += line
                onLine.size > 1 -> items += Item(keyOf(onLine.first()), line)
                else -> {
                    val using = onLine.single()
                    if (using in unused) removed++ else items += Item(keyOf(using), line)
                }
            }
        }
        items.sortBy { it.key }

        val newBlock = (preserved + items.map { it.text }).joinToString("\n")
        val after = text.substring(blockEnd).trimStart('\n', '\r')
        val replacement = when {
            newBlock.isEmpty() -> ""
            after.isEmpty() -> newBlock + "\n"
            else -> newBlock + "\n\n"
        }
        val end = text.length - after.length
        if (text.substring(blockStart, end) == replacement) return null
        return Plan(text, blockStart, end, replacement, removed)
    }

    private fun keyOf(using: FantomUsingDef): Key {
        val pod = FantomUsingScope.podName(using)
        val type = FantomUsingScope.typeName(using)
        return when {
            pod == null -> Key(2, using.usingPod.podSpec?.text ?: "", if (type == null) 0 else 1, type ?: "")
            pod == FantomUsingScope.SYS -> Key(0, pod, if (type == null) 0 else 1, type ?: "")
            else -> Key(1, pod, if (type == null) 0 else 1, type ?: "")
        }
    }

    private fun lineStart(text: String, offset: Int): Int = text.lastIndexOf('\n', offset - 1) + 1

    private fun lineEnd(text: String, offset: Int): Int {
        val nl = text.indexOf('\n', offset)
        return if (nl < 0) text.length else nl
    }

    companion object {
        private val EMPTY = Runnable {}
    }
}
