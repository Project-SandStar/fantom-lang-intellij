package co.anbora.labs.fantom.ide.imports

import co.anbora.labs.fantom.lang.core.psi.FantomUsingDef
import co.anbora.labs.fantom.lang.resolve.FantomUsingScope
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Adds a `using pod::Name` line to a file: after the last `using` line, or at the
 * top of the file (after a `#!` shebang line). Shared by the "Add using" quick fix
 * and the completion insert handler.
 */
object FantomUsingInserter {

    fun statement(pod: String, typeName: String?): String =
        if (typeName == null) "using $pod" else "using $pod::$typeName"

    /** Inserts the line and commits the document. Does nothing without a document. */
    fun insert(file: PsiFile, pod: String, typeName: String?) {
        val manager = PsiDocumentManager.getInstance(file.project)
        val document = manager.getDocument(file) ?: return
        insert(file, document, pod, typeName)
        manager.commitDocument(document)
    }

    /** Inserts the line into [document]; the caller commits. */
    fun insert(file: PsiFile, document: Document, pod: String, typeName: String?) {
        val lastUsing = file.children.filterIsInstance<FantomUsingDef>().lastOrNull()
        val statement = statement(pod, typeName)
        if (lastUsing != null) {
            document.insertString(lastUsing.textRange.endOffset, "\n$statement")
        } else {
            document.insertString(topInsertOffset(document.text), "$statement\n")
        }
    }

    /** Adds `using pod::typeName` unless the type is visible already. */
    fun insertIfNeeded(file: PsiFile, pod: String, typeName: String) {
        if (FantomUsingScope.usingsOf(file).isVisible(typeName, pod)) return
        insert(file, pod, typeName)
    }

    // A `#!` shebang line must stay first.
    private fun topInsertOffset(text: CharSequence): Int {
        if (!text.startsWith("#!")) return 0
        val nl = text.indexOf('\n')
        return if (nl < 0) text.length else nl + 1
    }

    /** Completion insert handler: adds the `using` line for a type that is not visible yet. */
    class AddUsingInsertHandler(private val pod: String, private val typeName: String) : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            context.commitDocument()
            insertIfNeeded(context.file, pod, typeName)
        }
    }
}
