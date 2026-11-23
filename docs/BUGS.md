# BUGS.md — Open Bugs

> Only open, unresolved issues. Fixed bugs are not tracked here;
> they are recorded in the commit messages and the corresponding regression tests.
> Last updated: 2026-10-25 after static code review (not
> executed/reproduced — line references checked, behavior not).
> Full list (≈ 125 bugs): [`review/`](review/); processing:
> [REVIEW-PLAN.md](REVIEW-PLAN.md). Bugs #11–#16 come from the live test
> of 2026-10-03.

---

## New from the 2026-10-25 review — High priority

References `ED:E-01` etc. point to the reports in `docs/review/`
(ED=edit layer, RD=read tools, RW=rewrite, SN=snapshot/util, RT=root/config,
BT=build package and tests).

### Data loss / inconsistent state

| No. | Finding | Reference | Plan |
|-----|---------|-----------|------|
| N1 | `edit_line` never reads buffered transaction contents (snapshot keys relative, query absolute): a second `edit_line` or `edit_line` after `add_method` overwrites the first change | ED:E-01 | AP-1.1 |
| N2 | Rollback, `begin_transaction`, `reboot_spoon` do not reset the snapshot overlay; the next edit writes rolled-back changes to disk | ED:E-02 | AP-1.2 |
| N3 | Multi-file edits (`rename_symbol`, `move_class`, `rewrite_signature`) without rollback; a fingerprint error in file n leaves a half-renamed project without a dirty marker | ED:E-03 | AP-1.7 |
| N4 | `move_class` and class rename overwrite an existing target file without checking; `moveFile` is not transaction-aware (commit re-creates the old file) | ED:E-04, E-05 | AP-1.5, 1.6 |
| N5 | Deleting and re-creating the same path within one transaction: the deletion wins, the new content is lost | ED:E-08 | AP-1.4 |
| N6 | Commit does not re-check fingerprints; an external change between buffering and commit gets overwritten. After the first own write, the file is excluded from the fingerprint lock | ED:E-09, E-48 | AP-1.8, 1.9 |
| N7 | Edit tools run in parallel with `load`/`reload`/`unload`; an edit can overwrite a freshly loaded entry with a stale model or resurrect an unloaded project | ED:E-26, RT:P-13 | AP-1.16 |

### Wrong results / non-compiling code

| No. | Finding | Reference | Plan |
|-----|---------|-----------|------|
| N8 | `rewrite_signature` without `newParameters` deletes **all** parameters (the tool sends an empty string; the test masks this) | RW:W-01 | AP-4.1 |
| N9 | Field rename leaves unqualified accesses (`return x;`) untouched → the class no longer compiles | RW:W-02 | AP-4.2 |
| N10 | `add_annotation` (FQN) and `rewrite_signature` (new types) do not add an import | RW:W-06, W-07 | AP-4.5 |
| N11 | `isSourceFile` checks substrings on the absolute path: projects under `…\\build…`/`…\\target…` and packages named `build` are missing from the snapshot (affects this project itself: `de/jare/rewisp/build/`). Consequence: empty snapshot, `NO_CHANGES`, „Project is clean“ | SN:S-01, ED:E-06, RD:R-19a | AP-2.1 |
| N12 | `find_references` only scans method bodies of top-level types (no constructors, initializers, fields, nested); `remove_member` safe mode and `rename_symbol` rely on this | RD:R-11, ED:E-14 | AP-3.7, 3.8 |
| N13 | `inspect_class/method/field`, `get_type_hierarchy`: `findEntry` before `reloadIfSpoonStale` → the triggering call still answers with the old model | RD:R-01 | AP-3.2 |
| N14 | `check_project_dirty` never detects `pom.xml` changes (snapshot contains only `.java`); reports „clean“ when the baseline is missing | RD:R-03, SN:S-14 | AP-2.9, 2.10 |
| N15 | Recipe/parse errors become `NO_CHANGES`: no-op `onError`, `ParseError` not recognized, `diagnostics` read by no tool | RW:W-10, W-11 | AP-4.8 |
| N16 | The stale set is global instead of per project: reloading A marks B as in sync | RD:R-09, RW:W-15 | AP-3.1 |

### Watcher, loading, operation

| No. | Finding | Reference | Plan |
|-----|---------|-----------|------|
| N17 | `WorkspaceWatcher`: event paths checked against the project root instead of the watched directory; `OVERFLOW` → NPE silently ends the loop; a deleted directory ends the whole watcher; busy-spin in the “debounce”; no tests | SN:S-02–S-08, S-39 | AP-2.2–2.7 |
| N18 | `System.out.println` before transport start writes into the JSON-RPC channel | RT:P-01 | AP-0.5 |
| N19 | `-D` properties are overwritten by YAML (sandbox `workspace.allowedRoots` via command line ineffective; default empty = everything allowed) | RT:P-03, C-04 | AP-5.3, 5.2 |
| N20 | JVM does not exit after stdin EOF (one orphaned process per client session) | RT:P-02 | AP-5.4 |
| N21 | Delombok: output is read until EOF before `waitFor(timeout)` runs — the timeout never fires; a hung `mvn` blocks `load_java_project` forever | RT:P-18 | AP-5.7 |
| N22 | ZIP/archive projects with a Maven layout are loaded as RAW (without pom) (`findSingleRoot` only checks depth 3) | RT:P-04 | AP-5.1 |
| N23 | `LombokDetector`: `@Value`/`@Log`/`@With` without an import check → Spring projects count as Lombok projects (download, delombok); for delombok Maven projects the dependency classpath is missing (no pom in the delombok directory) | RT:P-24, P-15 | AP-5.11, 5.10 |
| N24 | `pom.xml` compiles for Java 21, the README promises Java 17+; `rewrite-java-17` does not parse Java 21 syntax | RT:C-01 | AP-0.2 |
| N25 | Source files in ISO-8859-1/Cp1252 → `MalformedInputException`; the file is silently missing from the snapshot or gets re-encoded as UTF-8 | ED:E-07, SN:S-13 | AP-1.15 |
| N26 | `unload_java_project` does not resolve aliases; `reload expired=true` makes a read-only project editable again | RD:R-07, R-08 | AP-3.14, 3.15 |
| N27 | **Gradle support effectively dead:** the task allowlist rejects `compileClasspath` (value of `--configuration`) → `GOAL_NOT_ALLOWED` on every resolve; the edit tools swallow the exception; the tests accept any error. In addition, the Gradle “classpath” consists of Maven coordinates instead of jar paths | BT:B-01, B-02, T-05 | AP-5.21, 5.22 |
| N28 | Build processes: Gradle reads output until EOF before `waitFor(timeout)` (timeout never fires); under Windows `destroyForcibly` only kills `cmd.exe`, the build JVM remains as a zombie | BT:B-04, B-05 | AP-5.23 |
| N29 | The Maven resolver writes `.effective-pom.xml`/`.maven-classpath.txt` into the project root and is not synchronized: parallel calls → silently empty classpath in the cache; multi-module delivers only one module, language level hardcoded to `17` | BT:B-06, B-07 | AP-5.24, 5.25 |
| N30 | **Observed 2026-10-25 (test run, once):** `EditManager.writeFile` sporadically throws `AccessDeniedException` on Windows during `Files.move(.mcp-tmp → target, ATOMIC_MOVE)` (`EditLineToolTest.trailingNewlineInNewContentStripped` under parallel load; green again on repeat). Suspected cause: target file briefly opened by another thread/scanner; no retry, temp file may be left behind | Test run | **Fixed 2026-10-25** via retry with backoff (`EditManager.moveReplacing`, 6 attempts); occurred in two different tests, effectiveness only provable through repeated runs |
| N32 ✅ | **FIXED 2026-10-26:** Spoon now reads only from Rewrite. `ProjectManager.ensureSpoonModel` rebuilds the model from snapshot + dirty files + transaction overlay (VirtualFile) as soon as the rewrite state has changed; called from `findEntry` of all read and edit tools. Limitations: delomboked projects keep the on-disk model; generated source directories are still read from disk; full rebuild per change (performance: AP open) | Test `spoonSeesTransactionOverlay_andRollbackRestoresIt`, `moveClassThenEditInTransaction_commitsConsistently` | done |
| N31 | **Found and fixed 2026-10-25 (during AP-1.7):** fingerprint, dirty and edited keys existed in short-name (RENTEN~1) or long-name form depending on the location; the tools write via PathUtils.resolveReal (long name). The lookup returned null and **the fingerprint check against external changes was silently skipped**. Fix: PathUtils.canonical for keys and lookups (`buildFingerprints`, `validateFingerprint` in both base classes, DirtyChecker, ProjectEntry.isFileDirty/isFileEdited, EditManager.writeFile) | Test MultiFileWriteTest | done |
| N33 | **Review finding, fixed 2026-10-25:** `IgnoreRules` ignored the packages `build`/`target`/`out` when the project root itself lies in the source tree (`…/src/main/java`) | independent review H1 | done (test `rootInsideSourceTree_packageNamedBuildIsSource`) |
| N34 | **Review findings, fixed 2026-10-25:** rollback did not restore buffered deletions/moves in the snapshot (M1); `moveFile` to a target marked for deletion lost the content (M2); self-deleted/moved paths later failed on a stale fingerprint (M3); the pending overlay contained files of other projects (M5); the target directory was missing at commit after `moveFile` (N1) | independent review | done (tests in `TransactionTest`, `TransactionRollbackSnapshotTest`) |
| N35 | **Fixed 2026-11-18** (restore resets the original mtime, `TransactionCommitRestoreTest`; before:** after a failed commit, the restore recovered files via `writeString` (new mtime) → fingerprint stale, the next edit reports „modified since project load“ | Review M4 | AP-1.x (re-set the fingerprint after restore) |
| N36 | **Fixed 2026-11-18** (`isFileEdited` deleted; `editedFiles` stays: `check_project_dirty` reads it for its own changes, it is not dead code; before:** `ProjectEntry.isFileEdited`/`editedFiles` are no longer read anywhere after AP-1.9 → dead code | Review N2 | AP-6.x |
| N37 | **Fixed 2026-11-18** (`target`/`build` were already fully excluded, `buildFingerprints` uses `IgnoreRules`, `checkDirtyFullScan` removed, comparison now case-insensitive; before:** `IgnoreRules` ignores Maven `target/generated-sources` and Gradle `build/generated`, which Spoon can see as source roots; the comparison is case-sensitive; `buildFingerprints`/`DirtyChecker.checkDirtyFullScan` do not use `IgnoreRules` yet (after `mvn compile`, `check_project_dirty` then reports `target/**.java` as new) | Review N3 | AP-2.1 remainder |
| N38 | **Fixed 2026-11-18** (`CheckProjectDirtyTool` now counts only `editedFiles`; tests `CheckProjectDirtyAfterTransactionTest`; before:** `check_project_dirty` reported "Edited through rewisp since load: 7 file(s)" after `move_class` when 5 files had been written. Cause: `markDirty` after `move_class`/`add_package` marks every source file dirty, and the display also counted `dirtyFiles`; the check result ("No external changes") was correct, only the number was misleading. Found in the tictactoe roundtrip. |
| N38 ✅ | **FIXED 2026-10-26:** the watcher did not register new directories, terminated on every exception and discarded events within the debounce window. Now: new directories are registered and reported afterwards, errors per event are logged, debounce coalesces instead of discarding | `WorkspaceWatcherTest` (6), `ChangeTrackerTest` (9) | done. Open: directories end up as “dirty” in `sourceDirty` (noise) |
| N39 ✅ | **FIXED 2026-10-26:** the OpenRewrite engine passed jar paths to JavaParser.Builder.classpath(String...), which expects runtime artifact names — every project with a non-empty classpath ended in IllegalArgumentException: Unable to find runtime dependencies beginning with ...; moreover, only the first module was considered. Now classpath(Collection<Path>) over the union of all modules, missing entries are skipped | OpenRewriteEngineDiagnosticsTest (foreign jar type is attributed) | AP-4.9 done |
| N40 ✅ | **FIXED 2026-11-20 (found in the live test after the driver refactoring):** `add_class` rejects a body containing a field like `public static final char[] ALL = {...}` with `UNRESOLVED_TYPES`: the `AutoImportResolver` treats the uppercase field name `ALL` as a type. This already existed before the refactoring (same logic in `AddClassTool`), not a regression. Fix: `AutoImportResolver.isDeclaredVariableName` skips identifiers after a type that are followed by `=`, `;` or `,` | `DefaultRewispDriverTest.addClassAcceptsAnUppercaseFieldName` | done |
| N41 ✅ | **FIXED 2026-11-20 (found in the live test):** `remove_annotation` leaves a blank line with indentation at the annotation's position (whitespace only, compiles, but untidy). Fix: `RemoveAnnotationRecipe` resets the prefix of the next element or passes it on to the remaining first annotation | `DefaultRewispDriverTest.removeAnnotationLeavesNoWhitespaceLine` | done |
| N42 ✅ | **FIXED 2026-11-20 (found in the tictactoe live test):** `find_references` shows the owner as `?` for occurrences in the type header (`class A implements Stone`, `extends`, annotations on the type) (`ReferenceFinder.ownerOf` finds no enclosing member). Fix: `ownerOf` remembers the first enclosing type while ascending and reports `declaration of A`; `?` only if no type is found either | `DefaultRewispDriverTest.referenceInTheTypeHeaderNamesTheDeclaringType` | done |

Bug #14 has new facets (RT:P-10, RD:R-09): a fix must also defer watcher, registry and backup cleanup, and a harmless read-only call can eject the project from the manager via a failed auto-reload. Bug #16 additionally concerns the duplicate check (ED:E-16). Bug #11 has additions (ED:E-19: type parameters like `T` and nested types are reported as unresolved).

---

## Bug #14 — Failed `reload_java_project` ejects the project from the manager

**Status:** Open · **Priority:** High · **Found:** 2026-10-03, live test

**Observation:** A `reload_java_project` that fails on unparseable source code
removes the project completely from the manager. The second
call reports „No project found with name or alias '...'“. All loaded
project data (model, fingerprints, dirty tracking, snapshot) is gone.

**Cause:** `ProjectManager.load()` removes the old entry at the very beginning
(`entries.remove(name)`), **before** parsing. If parsing fails with
`ProjectLoadException`, the old entry is already deleted and is not restored.

**Reproduction:**
1. Load the project
2. Modify a source file so that it is semantically broken
   (e.g. remove an import, the simple name remains)
3. `reload_java_project` → „Reload failed: Failed to parse project: ...“
4. `reload_java_project` again → „No project found“

**Fix idea:** Replace the old entry only after a successful load
(remove only immediately before registering the new entry), or restore the
old entry on `ProjectLoadException`.

**Regression test:** When fixing, add a test that triggers a failed
reload and then runs `find()`/edit on the project.

---

## Bug #15 — Read-after-write staleness: `get_file_content` returns the old snapshot

**Status:** Open · **Priority:** High · **Found:** 2026-10-03, live test

**Observation:** After a successful `add_method`,
`get_file_content` still returns the content **before** the edit. In the live test,
the newly inserted method and the added import were missing from the output,
even though both were on disk.

**Cause:** The REWRITE-layer snapshot (`ProjectState.snapshot()`) is not
updated during the edit itself — `refreshSnapshotForEdit()` only runs
**before** the next edit (narrow/broad scope) or on reload.
But read tools like `get_file_content` read exactly this snapshot.

**Consequence:** An agent that checks the file content after an edit
sees a wrong (old) state — even though the dirty-warning header points to staleness,
the content shown is misleading.

**Fix idea:** After a successful edit, update the snapshot for the affected
file (the new contents are available in the EditManager), or have
`get_file_content` read the current files from disk when the state is dirty.

---

## Bug #16 — Stale Spoon model trap in the pre-edit analysis (candidate)

**Status:** Open (design decision needed) · **Priority:** High ·
**Found:** 2026-10-03, live test

**Observation:** `add_method` suppressed an actually needed import
and wrote a non-compiling file because `AutoImportResolver` reads the
import scope from the **Spoon model** — and `edit_line` does not update
the Spoon model. The model still had the state from `load`
(including an import line that had since been removed).

**Cause:** The pre-edit analysis (phase 1: Spoon, read-only) and the
edit pipeline (phase 2: OpenRewrite via snapshots) have different
freshness. `refreshSnapshotForEdit()` only refreshes the REWRITE snapshots,
not the Spoon model. The dirty warning tells the agent to reload — if that
does not happen, the analysis works with wrong data.

**Connection:** Exactly the gap for which the old stale map between
REWRITE and SPOON could be reintroduced (analyses against individual
files only check that one file; broad analyses still need a rebuild).

**Fix idea (alternative):** In `BaseEditTool`, before phase 1, check whether the
target file is dirty; if so, derive the import scope directly from the current
file content (disk/EditManager buffer) instead of from the Spoon model.

**Workaround until then:** After text edits (`edit_line`) and before the next
structured analysis, call `reload_java_project`.

---

## Bug #11 — `UNRESOLVED_TYPES` false positives (documentation candidate)

**Status:** Open (clarify: fix or document) · **Priority:** Medium ·
**Found:** 2026-10-03, Battleship dogfooding

**Observation:** `AutoImportResolver` only knows a type index, no
field/constant scope. Falsely reported as „unresolved“:

- Self-references in the `add_class` body (`Battleship::new`, constructor name)
- Constants as array sizes (`new JLabel[GRID][GRID]`)
- Self-declared constants in the body (`private static final int GRID = 8;`)
- Field constants in method bodies (even when created via `add_field`
  and present in the model after a reload)

**Decision needed:** Costly fix (scope analysis in
`extractTypeNames`) or document as a known limitation in ARCHITEKTUR.md
including workarounds (constants via `add_field` after reload,
literals instead of constants in bodies, no self-reference in the `add_class` body).

---

## Open non-bug topics (for completeness)

- **Performance:** Edits feel subjectively slow. Cause still to be measured —
  suspicion: snapshot refresh per edit resp. Spoon reload costs. Measurement
  possible via the DONE line of the ToolCallGuard (duration per call in the log).
- **Harness note (not a server bug):** The Vibe MCP harness shows the agent
  only `mcp_tool_error` without a body on tool errors — `traceId`/`logHint`
  are only accessible there via the log. Consider passing the body through.
