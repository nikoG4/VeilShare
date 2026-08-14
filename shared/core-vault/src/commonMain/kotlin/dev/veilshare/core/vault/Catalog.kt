package dev.veilshare.core.vault

interface VaultCatalog { suspend fun items(parent: VaultDirectoryId?): List<VaultItem>; suspend fun find(id: VaultItemId): VaultItem?; suspend fun createDirectory(parent: VaultDirectoryId?, id: VaultItemId, name: String): VaultItem.Directory; suspend fun move(id: VaultItemId, parent: VaultDirectoryId?); suspend fun rename(id: VaultItemId, name: String); suspend fun deleteDirectory(id: VaultItemId) }
class InMemoryVaultCatalog : VaultCatalog {
    private val entries = mutableMapOf<VaultItemId, VaultItem>()
    override suspend fun items(parent: VaultDirectoryId?) = entries.values.filter { it.parentId == parent }.sortedBy { it.displayName }
    override suspend fun find(id: VaultItemId) = entries[id]
    override suspend fun createDirectory(parent: VaultDirectoryId?, id: VaultItemId, name: String): VaultItem.Directory { validateName(name); require(entries[id] == null); require(entries.values.none { it.parentId == parent && it.displayName == name }); return VaultItem.Directory(id, parent, name).also { entries[id] = it } }
    override suspend fun rename(id: VaultItemId, name: String) { validateName(name); val item = requireNotNull(entries[id]); require(entries.values.none { it.id != id && it.parentId == item.parentId && it.displayName == name }); entries[id] = when(item) { is VaultItem.Directory -> item.copy(displayName = name); is VaultItem.File -> item.copy(displayName = name) } }
    override suspend fun move(id: VaultItemId, parent: VaultDirectoryId?) { val item = requireNotNull(entries[id]); require(parent?.value != id.value); if (item is VaultItem.Directory && parent != null) require(!isDescendant(parent, item.id)); entries[id] = when(item) { is VaultItem.Directory -> item.copy(parentId = parent); is VaultItem.File -> item.copy(parentId = parent) } }
    override suspend fun deleteDirectory(id: VaultItemId) { require(entries[id] is VaultItem.Directory); require(entries.values.none { it.parentId?.value == id.value }) { "Non-empty directory deletion is rejected" }; entries.remove(id) }
    private fun isDescendant(candidate: VaultDirectoryId, ancestor: VaultItemId): Boolean { var cursor: VaultItem? = entries[VaultItemId(candidate.value)]; while (cursor?.parentId != null) { if (cursor.parentId?.value == ancestor.value) return true; cursor = entries[VaultItemId(cursor.parentId!!.value)] }; return false }
    private fun validateName(name: String) { require(name.isNotBlank() && name.none { it == '/' || it == '\\' || it.code < 32 }) }
}
