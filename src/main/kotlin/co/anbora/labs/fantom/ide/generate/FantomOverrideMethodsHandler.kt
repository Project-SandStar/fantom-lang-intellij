package co.anbora.labs.fantom.ide.generate

import co.anbora.labs.fantom.ide.generate.FantomOverrideCandidates.Candidate
import co.anbora.labs.fantom.ide.presentation.FantomPresentation
import co.anbora.labs.fantom.lang.core.psi.FantomClassDef
import co.anbora.labs.fantom.lang.core.psi.FantomEnumDef
import co.anbora.labs.fantom.lang.core.psi.FantomFacetDef
import co.anbora.labs.fantom.lang.core.psi.FantomFile
import co.anbora.labs.fantom.lang.core.psi.FantomMixinDef
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import co.anbora.labs.fantom.lang.core.psi.FantomTypes.RBRACE
import co.anbora.labs.fantom.lang.resolve.FantomScope
import com.intellij.codeInsight.generation.ClassMember
import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.codeInsight.generation.MemberChooserObjectBase
import com.intellij.codeInsight.hint.HintManager
import com.intellij.ide.util.MemberChooser
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.TestOnly

/**
 * Alt+Insert | Override Methods and Implement Methods for Fantom. Lists the inherited
 * slots the enclosing type may override (or must implement), lets the user pick in a
 * [MemberChooser], and writes the overrides before the type's closing brace.
 *
 * There is no formatter for Fantom, so the text is laid out here: each slot indented
 * like the previous slot (two spaces past the brace when there is none), one blank
 * line between slots.
 */
abstract class FantomOverrideBaseHandler(private val implementOnly: Boolean) : LanguageCodeInsightActionHandler {

    private val verb: String get() = if (implementOnly) "Implement" else "Override"

    override fun isValidFor(editor: Editor, file: PsiFile): Boolean =
        file is FantomFile && enclosingType(editor, file) != null

    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val type = enclosingType(editor, file) ?: return
        val all = FantomOverrideCandidates.candidates(type, implementOnly)
        if (all.isEmpty()) {
            if (!ApplicationManager.getApplication().isUnitTestMode) {
                HintManager.getInstance().showErrorHint(editor, "No slots to ${verb.lowercase()}")
            }
            return
        }
        val chosen = choose(project, all) ?: return
        if (chosen.isEmpty()) return
        WriteCommandAction.runWriteCommandAction(project, "$verb Slots", null, {
            insert(project, file, type, chosen)
        }, file)
    }

    private fun choose(project: Project, all: List<Candidate>): List<Candidate>? {
        if (ApplicationManager.getApplication().isUnitTestMode) {
            return testSelection?.invoke(all) ?: all
        }
        val members = all.map { FantomMember(it) }.toTypedArray()
        val chooser = MemberChooser(members, false, true, project)
        chooser.title = "Select Slots to $verb"
        chooser.show()
        if (chooser.exitCode != DialogWrapper.OK_EXIT_CODE) return null
        return chooser.selectedElements.orEmpty().map { it.candidate }
    }

    private fun insert(project: Project, file: PsiFile, type: FantomNamedElement, chosen: List<Candidate>) {
        val manager = PsiDocumentManager.getInstance(project)
        val document = manager.getDocument(file) ?: return
        manager.commitDocument(document)
        val block = blockOf(type) ?: return
        val rbrace = block.node.findChildByType(RBRACE) ?: return
        val braceOffset = rbrace.startOffset
        val text = document.charsSequence

        val braceLineStart = document.getLineStartOffset(document.getLineNumber(braceOffset))
        val braceIndent = leadingBlanks(text, braceLineStart, braceOffset)

        val lastSlot = FantomScope.ownSlots(type).maxByOrNull { it.textRange.startOffset }
        val indent = lastSlot?.let { slot ->
            val start = slot.textRange.startOffset
            val lineStart = document.getLineStartOffset(document.getLineNumber(start))
            leadingBlanks(text, lineStart, start).takeIf { lineStart + it.length == start }
        } ?: (braceIndent + "  ")

        var from = braceOffset
        while (from > 0 && text[from - 1].isWhitespace()) from--

        val body = chosen.joinToString("\n\n") { FantomOverrideCandidates.generate(it, indent) }
        val replacement = buildString {
            append('\n')
            if (lastSlot != null) append('\n')
            append(body).append('\n').append(braceIndent)
        }
        document.replaceString(from, braceOffset, replacement)
        manager.commitDocument(document)
    }

    private fun leadingBlanks(text: CharSequence, from: Int, to: Int): String {
        var i = from
        while (i < to && (text[i] == ' ' || text[i] == '\t')) i++
        return text.subSequence(from, i).toString()
    }

    private fun blockOf(type: FantomNamedElement): PsiElement? = when (type) {
        is FantomClassDef -> type.highOrderBlock
        is FantomMixinDef -> type.highOrderBlock
        is FantomFacetDef -> type.highOrderBlock
        is FantomEnumDef -> type.enumBlock
        else -> null
    }

    private fun enclosingType(editor: Editor, file: PsiFile): FantomNamedElement? {
        val offset = editor.caretModel.offset
        val at = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
        if (FantomScope.isTypeDef(at)) return at as FantomNamedElement
        return FantomScope.enclosingType(at)
    }

    /** One row of the chooser: `name(Params): Ret` under its declaring type. */
    private class FantomMember(val candidate: Candidate) :
        MemberChooserObjectBase(FantomPresentation.text(candidate.slot), FantomPresentation.icon(candidate.slot)), ClassMember {
        private val owner = MemberChooserObjectBase(candidate.owner.name ?: "?", FantomPresentation.icon(candidate.owner))
        override fun getParentNodeDelegate(): MemberChooserObject = owner
    }

    companion object {
        /** Test hook: picks the candidates to write instead of the dialog. Null selects all. */
        @TestOnly
        @JvmStatic
        var testSelection: ((List<Candidate>) -> List<Candidate>)? = null
    }
}

/** Alt+Insert | Override Methods: virtual and abstract slots. */
class FantomOverrideMethodsHandler : FantomOverrideBaseHandler(implementOnly = false)

/** Alt+Insert | Implement Methods: abstract slots only. */
class FantomImplementMethodsHandler : FantomOverrideBaseHandler(implementOnly = true)
