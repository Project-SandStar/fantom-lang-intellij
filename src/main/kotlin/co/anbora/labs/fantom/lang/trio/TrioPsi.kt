package co.anbora.labs.fantom.lang.trio

import co.anbora.labs.fantom.ide.icons.FantomIcons
import co.anbora.labs.fantom.lang.trio.psi.TrioRawVal
import co.anbora.labs.fantom.lang.trio.psi.TrioRecord
import co.anbora.labs.fantom.lang.trio.psi.TrioRefVal
import co.anbora.labs.fantom.lang.trio.psi.TrioStrVal
import co.anbora.labs.fantom.lang.trio.psi.TrioTag
import co.anbora.labs.fantom.lang.trio.psi.TrioTypes
import co.anbora.labs.fantom.lang.trio.psi.TrioValue
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.util.TextRange
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon

/** A record: the tags between two `---` lines. */
abstract class TrioRecordMixin(node: ASTNode) : ASTWrapperPsiElement(node) {

    fun tags(): List<TrioTag> = PsiTreeUtil.getChildrenOfTypeAsList(this, TrioTag::class.java)

    fun findTag(name: String): TrioTag? = tags().firstOrNull { it.name == name }

    /** The text that names this record: its `name` tag, else `dis`, else `id`. */
    fun displayName(): String? {
        for (key in NAME_TAGS) {
            val text = findTag(key)?.let { valueText(it) }
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    override fun getName(): String? = displayName()

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = displayName() ?: tags().firstOrNull()?.name ?: "record"
        override fun getLocationString(): String? = null
        override fun getIcon(unused: Boolean): Icon = FantomIcons.FILE
    }

    companion object {
        val NAME_TAGS = listOf("name", "dis", "id")

        /** The value as a person reads it: no quotes, and for a ref its display name, else its id. */
        fun valueText(tag: TrioTag): String? = when (val value = (tag as? TrioTagMixin)?.tagValue()) {
            is TrioStrVal -> unquote(value.text)
            is TrioRawVal -> value.text
            is TrioRefVal -> value.node.findChildByType(TrioTypes.STRING)?.let { unquote(it.text) }?.takeIf { it.isNotEmpty() }
                ?: value.firstChild?.text
            null -> null
            else -> value.text.lineSequence().firstOrNull()
        }

        private fun unquote(text: String): String {
            val body = text.removePrefix("\"")
            return if (body.length < text.length && body.endsWith("\"")) body.dropLast(1) else body
        }
    }
}

/** A tag: `name` (a marker) or `name: value`. */
abstract class TrioTagMixin(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {

    override fun getNameIdentifier(): PsiElement? = node.findChildByType(TrioTypes.TAG_NAME)?.psi

    override fun getName(): String? = nameIdentifier?.text

    override fun setName(name: String): PsiElement {
        val file = PsiFileFactory.getInstance(project).createFileFromText("a.trio", TrioFileType, name)
        val newName = PsiTreeUtil.findChildOfType(file, TrioTag::class.java)?.nameIdentifier
        val old = nameIdentifier
        if (newName != null && old != null) old.replace(newName)
        return this
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    /** The value, or null for a marker tag. */
    fun tagValue(): TrioValue? = PsiTreeUtil.getChildOfType(this, TrioValue::class.java)

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String? = name
        override fun getLocationString(): String? = null
        override fun getIcon(unused: Boolean): Icon? = null
    }
}

/**
 * The value of a `src:` tag (node type `TrioTypes.SRC`). Axon is injected here by `AxonInjector`,
 * over the whole text of this element.
 */
abstract class TrioSrcValue(node: ASTNode) : ASTWrapperPsiElement(node), PsiLanguageInjectionHost {
    override fun isValidHost(): Boolean = true

    override fun updateText(text: String): PsiLanguageInjectionHost {
        (node.firstChildNode as? LeafElement)?.replaceWithText(text)
        return this
    }

    override fun createLiteralTextEscaper(): LiteralTextEscaper<out PsiLanguageInjectionHost> =
        object : LiteralTextEscaper<TrioSrcValue>(this) {
            override fun decode(rangeInsideHost: TextRange, outChars: StringBuilder): Boolean {
                outChars.append(rangeInsideHost.subSequence(myHost.text))
                return true
            }

            override fun getOffsetInHost(offsetInDecoded: Int, rangeInsideHost: TextRange): Int {
                val offset = rangeInsideHost.startOffset + offsetInDecoded
                return if (offset > rangeInsideHost.endOffset) -1 else offset
            }

            override fun isOneLine(): Boolean = false
        }
}

/** The records of a file, in order. */
fun TrioFile.records(): List<TrioRecord> = PsiTreeUtil.getChildrenOfTypeAsList(this, TrioRecord::class.java)
