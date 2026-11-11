package co.anbora.labs.fantom.lang.index

import co.anbora.labs.fantom.lang.FantomFileType
import co.anbora.labs.fantom.lang.core.psi.FantomNamedElement
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import java.io.DataInput
import java.io.DataOutput

/**
 * File-based index of declaration names in `.fan` files.
 *
 * Key: the short name (`Str`, `toStr`, `make`). Value: the kinds and declaring
 * type names found under that name in one file. Go to Class, Go to Symbol and
 * later the reference resolver read it. Bodies (locals, params) are not indexed.
 *
 * The index is deliberately simple: no stubs, no PSI kept in the index. The
 * reader re-parses the file on demand (see [findDeclarations]), which is fine
 * for navigation and keeps the grammar free of stub plumbing.
 */
class FantomDeclarationIndex : FileBasedIndexExtension<String, List<FantomDeclarationIndex.Entry>>() {

    /** One declaration under a name: its kind and the enclosing type (null for types). */
    data class Entry(val kind: FantomDeclarationKind, val ownerType: String?)

    override fun getName(): ID<String, List<Entry>> = NAME
    override fun getVersion(): Int = VERSION
    override fun dependsOnFileContent(): Boolean = true
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(FantomFileType)
    override fun getValueExternalizer(): DataExternalizer<List<Entry>> = EntryListExternalizer

    override fun getIndexer(): DataIndexer<String, List<Entry>, FileContent> = DataIndexer { content ->
        val result = HashMap<String, MutableList<Entry>>()
        val psi = content.psiFile
        for (element in PsiTreeUtil.findChildrenOfType(psi, FantomNamedElement::class.java)) {
            val kind = FantomDeclarationKind.of(element) ?: continue
            val name = element.name?.takeIf { it.isNotEmpty() } ?: continue
            val owner = if (kind.isType) null else enclosingTypeName(element)
            result.getOrPut(name) { ArrayList(1) }.add(Entry(kind, owner))
        }
        result
    }

    private object EntryListExternalizer : DataExternalizer<List<Entry>> {
        override fun save(out: DataOutput, value: List<Entry>) {
            out.writeInt(value.size)
            for (e in value) {
                out.writeByte(e.kind.ordinal)
                out.writeBoolean(e.ownerType != null)
                if (e.ownerType != null) out.writeUTF(e.ownerType)
            }
        }

        override fun read(input: DataInput): List<Entry> {
            val n = input.readInt()
            val kinds = FantomDeclarationKind.entries
            return List(n) {
                val kind = kinds[input.readByte().toInt()]
                val owner = if (input.readBoolean()) input.readUTF() else null
                Entry(kind, owner)
            }
        }
    }

    companion object {
        @JvmField
        val NAME: ID<String, List<Entry>> = ID.create("co.anbora.labs.fantom.declarations")

        /** Bump when the indexer or the externalizer changes shape. */
        const val VERSION = 1

        /** Short name of the type that encloses a slot, by walking up to the nearest type declaration. */
        fun enclosingTypeName(element: FantomNamedElement): String? {
            var p = element.parent
            while (p != null) {
                if (p is FantomNamedElement && FantomDeclarationKind.of(p)?.isType == true) return p.name
                p = p.parent
            }
            return null
        }

        /**
         * All indexed names, limited to [scope] when given (project files only, say).
         * Without a scope this lists every key the index has seen, libraries included.
         */
        fun allNames(project: Project, scope: GlobalSearchScope? = null): Collection<String> {
            val index = FileBasedIndex.getInstance()
            if (scope == null) return index.getAllKeys(NAME, project)
            val out = LinkedHashSet<String>()
            index.processAllKeys(NAME, { key -> out.add(key); true }, scope, null)
            return out
        }

        /** Index entries for one name without opening any PSI. */
        fun entries(name: String, project: Project, scope: GlobalSearchScope): List<Entry> =
            FileBasedIndex.getInstance().getValues(NAME, name, scope).flatten()

        /**
         * The PSI declarations for a name. Re-parses only the files the index says
         * hold that name, then keeps the named elements that match [filter].
         */
        fun findDeclarations(
            name: String,
            project: Project,
            scope: GlobalSearchScope,
            filter: (FantomDeclarationKind) -> Boolean = { true },
        ): List<FantomNamedElement> {
            val files = FileBasedIndex.getInstance().getContainingFiles(NAME, name, scope)
            val manager = PsiManager.getInstance(project)
            val out = ArrayList<FantomNamedElement>()
            for (vf in files) {
                val psi = manager.findFile(vf) ?: continue
                for (element in PsiTreeUtil.findChildrenOfType(psi, FantomNamedElement::class.java)) {
                    val kind = FantomDeclarationKind.of(element) ?: continue
                    if (element.name == name && filter(kind)) out += element
                }
            }
            return out
        }
    }
}
