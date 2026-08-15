# Desktop picker and opener smoke

## Automated evidence

- `installDist` generated a self-contained distribution.
- The distribution was copied to a clean path containing spaces under `%TEMP%`.
- It launched from an unrelated working directory and remained alive after 8 seconds.
- Unit coverage proves startup/lock cleanup is confined to the dedicated opaque temp root and preserves sibling files.
- Picker and opener use `JFileChooser`, `DesktopImportSource`, and `Desktop.open`; imports do not call `readBytes()`.

## Manual interaction still required

1. Launch from the install distribution, not Gradle.
2. Complete first-run and confirm the application finishes locked.
3. Unlock, choose Import, cancel, and confirm no operation remains busy.
4. Import TXT, image, PDF, unreadable/removable source, and a file larger than 1 MiB.
5. Cancel a multi-chunk import and confirm no item appears after restart.
6. Open TXT/image/PDF with an installed associated application.
7. Test a type without an association; expect a generic in-app error, not a stack trace.
8. Lock and verify files under `%TEMP%/vs-open-4f16a9` are removed best-effort.
9. Close while unlocked, relaunch, and verify Locked state and intact data.
10. Resize down to 720x520 and expand; confirm controls and file rows remain usable.

External viewers are outside the trust boundary and may retain plaintext. Cleanup cannot revoke bytes already copied by another process, and Windows may temporarily prevent deletion while a viewer holds a file.
