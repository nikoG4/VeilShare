from pathlib import Path

root = Path(__file__).resolve().parents[1]
source_path = root / "tools" / "apply_android_ux_overhaul.py"
source = source_path.read_text(encoding="utf-8")

start = source.index('import_coordinator = "shared/core-vault/src/commonMain/kotlin/dev/veilshare/core/vault/ImportCoordinator.kt"')
end = source.index('# ---------------------------------------------------------------------------\n# UI/runtime contract extensions:', start)
fixed_import_section = r"""import_coordinator = "shared/core-vault/src/commonMain/kotlin/dev/veilshare/core/vault/ImportCoordinator.kt"
replace_once(
    import_coordinator,
    "interface ImportSource { val displayName:String; val mimeHint:String?; val sizeHint:Long?; suspend fun openRead():ImportReadHandle }",
    '''interface ImportSource {
    val displayName:String
    val mimeHint:String?
    val sizeHint:Long?
    /** Explicit one-shot request. Deletion is attempted only after the encrypted commit is durable. */
    val deleteOriginalRequested:Boolean get() = false
    val originalDeleted:Boolean get() = false
    suspend fun openRead():ImportReadHandle
    suspend fun deleteOriginalAfterCommit():Boolean = false
}''',
)
replace_once(
    import_coordinator,
    "journal.remove(tx); progress(ImportProgress.Complete(entry)); return next to entry",
    '''journal.remove(tx)
            // Original deletion is deliberately after encrypted catalog + committed journal durability.
            // Provider refusal/failure cannot invalidate the completed encrypted import.
            if (source.deleteOriginalRequested) runCatching { source.deleteOriginalAfterCommit() }
            progress(ImportProgress.Complete(entry)); return next to entry''',
)

"""
source = source[:start] + fixed_import_section + source[end:]
compiled = compile(source, str(source_path), "exec")
exec(compiled, {"__file__": str(source_path), "__name__": "__main__"})
