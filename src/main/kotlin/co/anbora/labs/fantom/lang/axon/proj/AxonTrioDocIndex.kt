package co.anbora.labs.fantom.lang.axon.proj

import co.anbora.labs.fantom.lang.axon.copy.AxonFallbackFolder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/**
 * The `doc` of each function record in a `proj` folder: `func/<name>.trio` gives the key
 * `<name>` and its `doc` tag ("" when it has none). The platform builds it off the read
 * action, so quick doc and completion read the doc of a function without reading a file.
 */
class AxonTrioDocIndex : FileBasedIndexExtension<String, String>() {

    override fun getName(): ID<String, String> = NAME
    override fun getVersion(): Int = 1
    override fun dependsOnFileContent(): Boolean = true
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getValueExternalizer(): DataExternalizer<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> file.name.endsWith(TRIO) && file.parent?.name == AxonProjFolders.FUNC }

    override fun getIndexer(): DataIndexer<String, String, FileContent> = DataIndexer { content ->
        val name = content.fileName.removeSuffix(TRIO)
        val text = content.contentAsText
        if (name.isEmpty() || text.length > MAX_CHARS) return@DataIndexer emptyMap()
        val doc = runCatching { AxonFallbackFolder.trioTags(text.toString())["doc"] }.getOrNull().orEmpty()
        mapOf(name to doc.take(MAX_DOC))
    }

    companion object {
        val NAME: ID<String, String> = ID.create("co.anbora.labs.fantom.axonTrioDoc")
        private const val TRIO = ".trio"
        private const val MAX_CHARS = 4 * 1024 * 1024
        private const val MAX_DOC = 16 * 1024

        /**
         * The doc of the function [name] in the record [trio], or null. Index only: safe in a
         * read action. Null while the IDE indexes.
         */
        fun doc(project: Project, trio: VirtualFile, name: String): String? {
            if (DumbService.isDumb(project)) return null
            return FileBasedIndex.getInstance().getValues(NAME, name, GlobalSearchScope.fileScope(project, trio))
                .firstOrNull()?.takeIf { it.isNotBlank() }
        }
    }
}
