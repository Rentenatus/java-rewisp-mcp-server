# Review Plan — rewisp 1.1.2

**Created:** 2026-10-25 · **Basis:** static code review (no build, no test runs — see "Limitations")
**Detail reports:** [`docs/review/`](review/) · **Bug overview:** [BUGS.md](BUGS.md)

## How to read this plan

- **Finding references** carry a scope prefix because the reports have their own numbering schemes:
  `ED:E-01` (edit layer) · `RD:R-01` (read tools) · `RW:W-01` (rewrite layer; `D-`, `I-`, `C-` also occur there) ·
  `SN:S-01` (snapshot/event/util) · `RT:P-01` (root/config; `D-`, `E-`, `C-` also occur there) · `BT:B-01` (`build/` package and test hygiene; `D-`, `I-`, `C-`, `T-` also occur there).
- **Size:** `S` ≤ 1 h · `M` ≤ 0.5 day · `L` ≤ 1–2 days. Each package is mergeable on its own.
- **Acceptance:** each bug package first gets a **failing regression test**, then the fix.
- **Dependencies** are given in parentheses. Packages without a dependency can run in parallel.
- Phase order = priority order (data loss → wrong results → cleanup → performance → docs).

## Limitations of this review

All findings come from reading the code. **Nothing was compiled or executed**: offline the Maven plugins are missing, and the locally installed JDK is 17 while `pom.xml` requires 21. AP-0.1 restores the build capability (as of 2026-10-25: JDK 21 is installed via scoop, but `mvn` fails with HTTP 403 at the corporate Artifactory `ipsl-maven` — clarify access/VPN/token in `~/.m2/settings.xml`); until then every finding counts as "plausible, evidenced by a line reference", not as "reproduced". One exception: the line numbers were checked by the reviewers against the revision that was read.

## Open decisions (block individual packages)

| # | Question | Recommendation | Affects |
|---|----------|-----------------|---------|
| D1 ✅ decided: delete | Delete or register the 3 unregistered tools `find_unused_code`, `find_unreachable_code`, `find_deprecated_usages` (≈ 33 KB, 0 tests, several bugs)? | Delete; if needed, rebuild them later with a correct call graph | AP-6.1 |
| D2 | Add Spoon post-validation after a rewrite (`SpoonEngineImpl`/`EffectiveSourceOverlay`) or delete the classes? ARCHITEKTUR.md claims it, but it does not exist in the code. | **DECIDED 2026-10-29: add it, but lean** - `SpoonModelService.validateEdit` builds a candidate model from the rewrite state before writing and rejects edits that introduce new compilation errors (`EDIT_BREAKS_COMPILATION`, nothing written); `SpoonEngine*`/`EffectiveSourceOverlay` remain unused and can be deleted with 6.5 | AP-6.5, AP-9.4 |
| D3 | Target Java: 17 (README) or 21 (`pom.xml`)? | 21, use `rewrite-java-21`; adjust the README | AP-0.2 |
| D4 | Keep the filesystem watcher (S-51: `sourceDirty` is never read) or reduce it to build files? | Reduce it to build files; source-dirty remains snapshot-based | AP-2.8 |
| D5 | `workspace.allowedRoots` default empty = everything allowed. A safer default? | **Decided 2026-10-26: leave it empty** (wait for a practical test), documented in the README; alternative "user home + temp" later | AP-5.2 |
| D6 | Stale concept: global set → per project, or incremental Spoon update instead of a full reload? | First per project (AP-3.1), incremental later (AP-7.5) | AP-3.x, AP-7.5 |

---

## Phase 0 — Foundation (everything else depends on it)

| AP | Title | Findings | Size | Acceptance |
|----|-------|----------|------|------------|
| 0.1 ✅ | **DONE 2026-10-25** (`build.ps1`, `settings-central.xml`; baseline 567/0/0/13 green, see TESTING.md). Make the build reproducible: install JDK 21/`JAVA_HOME`, make the Maven plugins available in the repo mirror, `mvn -q test` runs green (baseline: record the number of tests/errors/skips) | — | S | Baseline numbers in TESTING.md |
| 0.2 ✅ | Unify the Java target: `maven.compiler.release`, `rewrite-java-21`, README "Requires Java 21" | RT:C-01, RT:C-06 | S | `java -jar` starts on 21; the parser parses Java 21 syntax (test) |
| 0.3 ✅ | **DONE 2026-10-25** (test `SnapshotServiceTest.captureStable_projectUnderPathContainingBuildOrTarget_isNotEmpty`; `SnapshotService.isSourceFile` now checks only segments relative to the root, `target`/`build` only above `src/`; `ChangeTracker`/`JavaMcpServer`/Spoon paths follow with AP-2.1). Isolate the faulty path-ignore rule as a cross-cutting bug: **one** test that loads a project under `…\builds\x\` with package `…\build\` | SN:S-01, ED:E-06, RD:R-19a | S | red test, serves AP-2.1 |
| 0.4 ✅ | **DONE 2026-10-25** (local git repo `main`, `core.autocrlf=false`, `.gitignore` extended: fixture `target/` dirs except `generated-sources`, `settings-central.xml`, `.claude/`; the first commit already contains the fixes AP-0.1–5.3). Repo hygiene: remove `src/test/resources/**/target/`, `*.tmp` from the repo, check `.gitignore`, initialize the git repo (currently **no** VCS!) | RT:P-14 | S | `git status` clean |
| 0.5 ✅ | **PARTIAL 2026-10-25:** the only `System.out` site in `src/main` (`JavaMcpServer.configureLogFile`) switched to `System.err`; **open:** smoke test that starts the server and checks the first stdout line. Logging contract: stdout exclusively JSON-RPC; smoke test that starts the server and checks that the first line is valid JSON | RT:P-01 | S | test green |

## Phase 1 — Data loss and consistency in the edit path (highest priority)

Common pattern: the edit path knows three truths (disk, EditManager buffer, snapshot/Spoon) and synchronizes them only incompletely.

| AP | Title | Findings | Size | Depends |
|----|-------|----------|------|---------|
| 1.1 ✅ | **DONE 2026-10-25** (`EditLineTool` reads the buffered content first; test `twoLineEditsInTransaction_bothSurviveCommit` was red). Open from E-39: snapshot key format. `edit_line`: read the content first from `EditManager.getPendingChanges()`; clarify the snapshot key format (relative to `originalProjectDir`) | ED:E-01, E-39 | S | 0.1 |
| 1.2 ✅ | **DONE 2026-10-25** (rollback re-reads the discarded files into the snapshot (`BaseEditTool.restoreSnapshotFromDisk`); test `TransactionRollbackSnapshotTest`; reboot_spoon/begin not checked separately) Rollback/`begin_transaction`/`reboot_spoon` reset the snapshot overlay (re-read the files from disk) | ED:E-02 | M | 0.1 |
| 1.3 ✅ | **DONE 2026-10-25** (`begin_transaction` while a transaction is active → domain error) `begin_transaction` while a transaction is active → domain error instead of silently discarding | ED:E-11 | S | 1.2 |
| 1.4 ✅ | **DONE 2026-10-25** (`EditManager.writeFile` calls `removePendingDeletion`; test `deleteThenWriteSamePath_writeWins`). `writeFile` reverts `pendingDeletion` (delete + recreate at the same path) | ED:E-08 | S | 0.1 |
| 1.5 ✅ | **DONE 2026-10-25** (EditManager.moveFile transaction-aware, target collision → IOException, RenameSymbolTool checks the collision before writing; tests in TransactionTest) `EditManager.moveFile` transaction-aware (pending write + pending delete), check the target collision | ED:E-05 | M | 1.4 |
| 1.6 ✅ | **DONE 2026-10-25** (`MoveClassTool` checks the target file, the buffered target file and the target type in the model → `CLASS_EXISTS`; test `moveClassRefusesToOverwriteExistingTarget`, red-first not separately confirmed). `move_class`: existence check target file/target type (`CLASS_EXISTS`) | ED:E-04 | S | — |
| 1.7 ✅ | **DONE 2026-10-25** (BaseEditTool.writeAll: all fingerprints up front, entry adopted even on partial failure; used by \rename_symbol, move_class, \rewrite_signature; **side finding N31** fixed: canonical path keys, see BUGS.md; test MultiFileWriteTest) Multi-file writes: validate all fingerprints up front, adopt the entry after each successful write (`rename_symbol`, `move_class`, `rewrite_signature`) | ED:E-03 | M | 1.5 |
| 1.8 ✅ | **DONE 2026-10-25** (EditManager records the disk state on first buffering, commit aborts on external change; test commitDetectsExternalChangeAndReportsCause) Commit re-validates fingerprints/disk hash (external change between buffering and commit) | ED:E-09 | M | — |
| 1.9 ✅ | **DONE 2026-10-25** (fingerprint is updated after its own write/commit, isFileEdited skip removed; test externalChangeAfterOwnEdit_isStillDetected) Update the fingerprint after each own write instead of skipping `editedFiles` entirely | ED:E-48 | M | 1.8 |
| 1.10 ✅ | **DONE 2026-10-25** (getLastCommitError, LOG.error, cause in the commit response) Log commit errors and include the cause in the response | ED:E-10 | S | — |
| 1.11 ✅ | **DONE 2026-10-25** (SnapshotService.removeFiles, \refreshSnapshotForEdit takes buffered deletions into account; test moveClassThenEditInTransaction_commitsConsistently; boundary N32) Take buffered deletions into account in `refreshSnapshotForEdit` (no duplicate types after `move_class` in a transaction) | ED:E-12 | S | 1.2 |
| 1.12 ✅ | **DONE 2026-10-25** (`add_class` checks buffered files, `add_package` does not overwrite `package-info.java`; tests in `StructuralToolsTest`) `add_class`/`add_package`: existence check incl. buffered files; do not overwrite `package-info.java` | ED:E-17, E-18 | S | — |
| 1.13 ✅ | `createDirectories` only on commit (rollback leaves no directories) | ED:E-49 | M | 1.2 |
| 1.14 ✅ | **DONE 2026-11-23** (red-first: new `MoveClassContainmentTest` — an absolute `newPackage` previously wrote outside the project, "Moved: a.A -> C:\…\evil.A"). `move_class` now validates `newPackage` via `JavaNames.isValidPackageName` (empty = default package allowed) and enforces containment of the resolved target file via the new shared `EditSupport.isInsideProject` (now also used by `edit_line`); `add_class`/`add_package` already validated via `JavaNames` (test `addClassAndPackageRejectInvalidNames`). Path containment for `edit_line`/`add_class`/`add_package`/`move_class` + Java identifier validation for `packageName`/`className` | ED:E-51 | M | — |
| 1.15 ✅ | **DONE 2026-10-28** (`SourceFiles`: UTF-8, otherwise ISO-8859-1 lossless; writing in the original charset, unrepresentable characters are rejected; Spoon load encoding adjusted; `Latin1EditTest`) Charset handling: project charset (fallback ISO-8859-1), no silent dropping in the snapshot | ED:E-07, SN:S-13 | M | 2.1 |
| 1.16 ✅ | Lifecycle tools (`load`/`reload`/`unload`/`reboot_spoon`) under the same lock as edits; re-fetch the entry before `updateEntry` | ED:E-26, RT:P-13 | M | 3.1 |
| 1.17 ✅ | **DONE 2026-10-25** (backup skips `IgnoreRules` directories, catches `UncheckedIOException`; test `backupSkipsBuildOutputAndGitDirs`) Catch `UncheckedIOException` in the backup walk; default exclusions (`target`, `build`, `.git`, `node_modules`) | ED:E-23 | S | — |
| 1.18 ✅ | Derive line endings for new files from the project (`.editorconfig`/neighbouring files) | ED:E-55 | S | — |

## Phase 2 — Snapshot, watcher, dirty detection

| AP | Title | Findings | Size | Depends |
|----|-------|----------|------|---------|
| 2.1 ✅ | **DONE 2026-10-25** (additionally `buildFingerprints` and `DirtyChecker` use `IgnoreRules`; review finding H1 fixed). Previously partial: new class `snapshot/IgnoreRules` (segment match relative to the root, `target`/`build`/`out` only above `src/`) + `IgnoreRulesTest`; wired into `SnapshotService`, `ChangeTracker` (there the rule never applied under Windows because it split on `/`) and `WorkspaceWatcher`. **Open:** `ProjectBuildHelpers.buildFingerprints` and Spoon/launcher paths; the watcher event path (`root.resolve(context)` instead of the key directory) belongs to the AP-2.x watcher bugs. **One** `IgnoreRules` component (segment match relative to the root, fed from `workspace.ignoredDirectories`); replaces substring checks in `SnapshotService.isSourceFile`, `ChangeTracker`, `WorkspaceWatcher`, `ProjectBuildHelpers.buildFingerprints` | SN:S-01, S-09, S-10, S-11, S-54, ED:E-06, RD:R-19a | M | 0.3 |
| 2.2 ✅ | **IMPLEMENTED 2026-10-25, UNTESTED** (event paths against `key.watchable()`; `WorkspaceWatcher` has no tests, AP-2.7 pending) `WorkspaceWatcher`: resolve event paths against `key.watchable()` | SN:S-02 | S | 2.1 |
| 2.3 ✅ | `WorkspaceWatcher`: handle `OVERFLOW`, do not end the loop on every exception, discard only the key when `key.reset()==false` | SN:S-03, S-04 | S | 2.2 |
| 2.4 ✅ | `WorkspaceWatcher`: re-register new directories | SN:S-05 | S | 2.2 |
| 2.5 ✅ | `WorkspaceWatcher`: service leaks (errors in `registerSubdirectories`, duplicate `watch`, conditional `remove(root, ws)`) | SN:S-06, S-07 | S | — |
| 2.6 ✅ | `WorkspaceWatcher`: real debounce (use the scheduler), no busy-spin; clean up `lastEventTime` | SN:S-08 | M | 2.3 |
| 2.7 ✅ | Tests for `WorkspaceWatcher` and `ChangeTracker` (previously **none**) | SN:S-39 | M | 2.2–2.6 |
| 2.8 ✅ | **DONE 2026-10-31** (the watcher no longer registers `src` trees and only forwards build configuration files; `ChangeTracker` only marks the build-dirty state; `sourceDirty`, `DirtyEntry`, `DirtyReason`, `ChangeOrigin` deleted; the watcher/initial-snapshot order is uncritical because the build-file baseline is hashed on load) Implement decision D4: consume `sourceDirty` or reduce the watcher to build files; watcher start before the initial snapshot | SN:S-51, S-52 | M | D4 |
| 2.9 ✅ | **DONE 2026-10-26** (`CheckProjectDirtyToolDetectionTest`: missing/empty baseline = "unknown"; own edits tracked separately from external changes) Baseline semantics: track "since load" vs. "since last refresh" separately; missing/empty baseline = "unknown", not "clean" | SN:S-14, S-53, RD:R-04 | M | — |
| 2.10 ✅ | **DONE 2026-10-26** (`BuildFileHasher` in the rewrite layer, baseline in `ProjectState`, parent-POM invalidation in `ProjectManager`) `check_project_dirty`: actually hash the build files (`pom.xml`, `*.gradle*`) (the snapshot contains only `.java`); move parent-POM resolution out of the read layer | RD:R-03, R-28 | M | 2.1 |
| 2.11 ✅ | `SnapshotService.getFileContentForType`: segment-wise path comparison (Windows backslash) | SN:S-12, RD:R-18 | S | — |
| 2.12 ✅ | `refreshFiles` only `.java`; `FileSnapshot.size` in bytes; normalize `rootHash` with `/` | SN:S-24, S-26, S-13 | S | — |
| 2.13 ✅ | `ProjectState` fields `volatile`/atomic; `clearBuildDirty` without lost update | SN:S-15, S-16 | S | — |
| 2.14 ✅ | `check_project_dirty`: report errors as errors (not `ok`) | RD:R-05 | S | — |
| 2.15 ✅ | **OBSOLETE 2026-10-26** (`event` package deleted, see 4.x/WK) make the `SnapshotEvent` type explicit; correct the dispatch docs | SN:S-25, S-56 | S | — |

## Phase 3 — Read tools: correct results, same freshness

| AP | Title | Findings | Size | Depends |
|----|-------|----------|------|---------|
| 3.1 ✅ DROPPED | Stale set per project (`Map<projectId, Set<Path>>`), uniformly relative paths; `markSynced` only on successful reload | RD:R-09, RW:W-15 | M | — |
| 3.2 ✅ DROPPED | `reloadIfSpoonStale` centrally in `BaseReadTool.build()` **before** `findEntry`; treat all Spoon read tools the same | RD:R-01, R-10, RW:W-16 | M | 3.1 |
| 3.3 ✅ | Fix bug #14: replace the old entry only after a successful load; watcher/registry/backup cleanup also only afterwards | BUGS:#14, RT:P-10 | M | — |
| 3.4 ✅ DROPPED | Fix bug #15: update the snapshot for the file after a successful write (also solves parts of performance) | BUGS:#15, ED:E-40 | M | 1.2 |
| 3.5 ✅ DROPPED | Bug #16 + duplicate check: pre-edit analysis (imports, duplicates, erasure) against the current file content instead of stale Spoon | BUGS:#16, ED:E-16 | L | 3.4 |
| 3.6 ✅ | **DONE 2026-10-26** (`TypeLookup`, `TypeLookupTest`; 18 tools migrated; edit tools now find nested types in the Spoon model — whether the recipes also match them still needs checking) Central type lookup with nested-type support (`factory.Type().get(fqn)`) | RD:R-13 | M | — |
| 3.7 ✅ | **DONE 2026-10-26** (`ReferenceFinder`, `FindReferencesToolTest`: whole type, overloads via `parameterTypes`, file:line, `<init>`, nested) `find_references`: scan the entire type (constructors, initializers, fields, nested), distinguish overloads, output file:line; `memberName="<init>"` | RD:R-11, R-12 | L | 3.6 |
| 3.8 ✅ | **DONE 2026-10-26** (`RemoveMemberReferencesTest`; caller counting in `rename_symbol` via `ReferenceFinder`) base `remove_member` (safe mode) and `rename_symbol` (caller counting) on the same reference search | ED:E-14 | M | 3.7 |
| 3.9 ✅ | **DONE 2026-10-26** (`GetTypeHierarchyToolTest`) `get_type_hierarchy`: recursive, interfaces of interfaces, subtypes via superclass **and** interfaces | RD:R-15 | M | 3.6 |
| 3.10 ✅ | **DONE 2026-10-26** (`FindImplementationsToolTest`; a bug was fixed along the way: interfaces inherited via superclasses were not found) `find_implementations`: align description/filter (abstract, sub-interfaces) | RD:R-14 | S | 3.6 |
| 3.11 ✅ | **DONE 2026-10-26** (`GetElementPositionToolTest`; new error code `AMBIGUOUS_ELEMENT`) `get_element_position`: exact match, ambiguity reported as error with candidates, reject invalid `elementType`, constructors | RD:R-16 | M | — |
| 3.12 ✅ | **DONE 2026-10-26** (`SearchSourceToolTest`; search in the effective rewrite state, file:line, exact counting, `maxResults`) `search_source`: search against the snapshot text, file:line, count matches | RD:R-17, R-34 | M | 2.11 |
| 3.13 ✅ | **DONE 2026-10-26** (`GetFileContentToolTest` incl. edit_line with fingerprint from the output; `get_element_position` names real edit_line parameters) `get_file_content`: base `originalProjectDir`, output line numbers + fingerprint (feeds `edit_line`) | RD:R-18, R-26 | M | 2.11 |
| 3.14 ✅ | **DONE 2026-10-25** (`ProjectManager.remove` resolves the alias via `find`; `UnloadJavaProjectToolTest`). `unload_java_project`: resolve the alias | RD:R-07, RT:P-12 | S | — |
| 3.15 ✅ | `reload_java_project expired=true`: adopt `editable`; TTL/`autoDelombok` from the original parameters | RD:R-08, RT:P-08, P-09 | S | — |
| 3.16 ✅ | **DONE 2026-10-25** (`\\n` → `\n` in `ProjectRefreshBuildTool` and `ReloadJavaProjectTool`; test for reload). Output literal `\n` in `project_refresh_build`/`reload_java_project` | RD:R-06 | S | — |
| 3.17 ✅ | Reject alias collisions on load; resolve the name before the alias | RT:P-11 | S | — |
| 3.18 ✅ | Tolerant argument parsers (string/boolean/number) centrally in the base classes | RD:R-43, ED:E-25 | S | — |
| 3.19 ✅ | `expiredNames` from the instance field into the call context | RD:R-42 | S | — |
| 3.20 ✅ | **DONE 2026-10-26** (`InspectToolsDocAlignmentTest`; `SignatureFormatter`; body from the original source) align `list_classes`/`project_metadata`/`inspect_*` with the docs (inner classes, modules, constructors, signature) | RD:R-25, R-27, R-44 | M | 3.6 |
| 3.21 ✅ | Correct the `[SPOON]` prefix of the descriptions; lifecycle tools into their own base class `BaseLifecycleTool` | ED:E-56, RD:R-41 | S | — |

> **As of 2026-10-26:** 3.1, 3.2, 3.4 and 3.5 became obsolete through the architecture correction "Spoon only reads from rewrite" (`ProjectManager.ensureSpoonModel`): there is no longer a stale set and no more reloading from disk; the model is re-synced from the rewrite state on every entry lookup. 3.21 is done with `BaseProjectTool` (name differs from the plan). 3.3 also fixes that a `SECURITY_ERROR` on load was reinterpreted as `PARSE_ERROR`.

## Phase 4 — Recipes (rewrite layer)

| AP | Title | Findings | Size | Depends |
|----|-------|----------|------|---------|
| 4.1 ✅ | **DONE 2026-10-25** (`RewriteSignatureTool` only passes `newParameters` when specified; test `changeReturnTypeOnly_keepsExistingParameters` was red without the fix). `rewrite_signature`: a missing `newParameters` no longer deletes all parameters (null vs. empty); test with a parameterized method | RW:W-01 | S | 0.1 |
| 4.2 ✅ | **DONE 2026-10-26** (`RenameSymbolFieldTest`) `rename_symbol` field: rename unqualified `J.Identifier`; engine tests for scope=field/class | RW:W-02 | M | 0.1 |
| 4.3 ✅ | **DONE 2026-10-26** (`RenameSymbolOverridesTest`; overrides/implementations = subtypes of the target class, including non-overriding overloads there; `MemberReference`, static imports, enum constants) `rename_symbol`: overrides/interface implementations, `MemberReference`, static imports, `EnumValue` | RW:W-03, W-04 | L | 4.2 |
| 4.4 ✅ | **DONE 2026-10-26** simple-name fallback only when the type is missing (rename, rewrite_signature, move_class) | RW:W-05, W-17 | S | — |
| 4.5 ✅ | **DONE 2026-10-26** (`RecipeImportsTest`; AddAnnotation + RewriteSignature, with `onlyIfReferenced=false`; EditAnnotation has no type-import need) `maybeAddImport` in `AddAnnotation`/`RewriteSignature`/`EditAnnotation`; return type via template | RW:W-06, W-07, W-08 | M | — |
| 4.6 ✅ | **DONE 2026-10-26** (`RecipeSupport.enclosingClass`, `AnonymousClassEditTest`) anonymous classes: enclosing check against the direct parent class (9 recipes) | RW:W-09 | M | — |
| 4.7 ✅ | **DONE 2026-10-26** (`RecipeSupport` + `RecipeSupportTest`) `splitSignature` generics-aware, varargs | RW:W-18 | S | — |
| 4.8 ✅ | **DONE 2026-10-26** (`ParseError` sources → diagnostic `PARSE_ERROR`, in 10 edit tools via `formatDiagnostics` in the response; no abort, because unrelated broken files would otherwise block edits) Engine: `ExecutionContext` with error collection; `ParseError` sources as `PARSE_ERROR`; diagnostics in tool responses | RW:W-10, W-11, ED:E-54 | M | — |
| 4.9 ✅ | **DONE 2026-10-26** (bug N39: `classpath(String...)` expected artifact names and threw for every project with dependencies; now `Collection<Path>` across all modules) Engine: classpath of all modules; verify whether `classpath(String...)` accepts paths (test with a foreign jar type) | RW:W-12 | M | 0.1 |
| 4.10 ✅ | **DONE 2026-10-26** (setter part; YAML registry part open) Engine: parameter-setter errors as `RECIPE_PARAM_INVALID`; `YamlRecipeRegistry` logs load errors, `SafeConstructor` | RW:W-13, W-14 | S | — |
| 4.11 ✅ | **DONE 2026-10-26** `RewriteResult` with `LinkedHashMap` (deterministic order) | RW:W-19 | S | — |
| 4.12 ⏸ | **REVERTED** — no recipe deletes files (moves go through `editManager.moveFile`), applying `deletedFiles()` would be dead code; only with a deleting recipe. Dry run vs. real run: apply `deletedFiles()` from the preview | ED:E-54 | S | 4.8 |
| 4.13 ✅ | **DONE 2026-11-05** (add_method, add_field, add_class, replace_method_body and rewrite_signature all use `AutoImportResolver`) `rewrite_signature` now uses `AutoImportResolver` incl. `UNRESOLVED_TYPES` errors like `add_method`; unification of the tools still open. `rewrite_signature` and `add_method` uniformly via `AutoImportResolver` (validate types/imports) | ED:E-52 | M | 4.5 |
| 4.14 ✅ | **DONE 2026-10-26** (`AutoImportResolverScanTest`, `ProjectClassLoader` via the Spoon source classpath; manually requested imports also count when only used qualified) `AutoImportResolver`: nested types, type parameters, text blocks, `import` substring, project classpath instead of server classloader | ED:E-19, E-20, E-21, E-53, BUGS:#11 | L | 3.5 |
| 4.15 ✅ | **DONE 2026-10-26** (`MoveClassNestedImportTest`) `move_class`: `collectSamePackageImports` with top-level type, filter type parameters | ED:E-13 | S | — |
| 4.16 ✅ | **DONE 2026-10-26** `TypeErasureChecker`: qualified names, bound erasure | ED:E-28 | S | — |
| 4.17 ✅ | **DONE 2026-10-26** (`RewriteSignatureUnchangedTest`) `RewriteSignatureTool`: normalize the "unchanged" comparison | ED:E-27 | S | — |
| 4.18 ✅ | **DONE 2026-10-26** (`SourceCodeUtilsInsertImportsTest`) `insertImports` with header comments before `package` | ED:E-24 | S | — |

## Phase 5 — Lifecycle, loading, Lombok, build, configuration

| AP | Title | Findings | Size | Depends |
|----|-------|----------|------|---------|
| 5.1 ✅ | **DONE 2026-10-26** (`ProjectLoaderArchiveTest`: zip-bomb limit 2 GiB/200k entries, wrapper folder only when there is exactly one folder and it is cleaned up too, `file:///` URI, query strings) `findSingleRoot` only when `dirs.size()==1`; `file:///C:/…` via `URI`; close streams; query strings; zip-bomb limit; delete the wrapper folder | RT:P-04, P-05, P-30, P-31 | M | — |
| 5.2 ✅ | **DONE 2026-11-23** (red-first `WorkspaceAllowlistTempTest`: temp-base project was rejected with SECURITY_ERROR although clones land there). Local sources are now checked **before** `resolveSource` (no extraction of disallowed local archives), the `ProjectLoader` temp base is implicitly allowed via `WorkspaceRegistry.allowRecursively` (real-path aware for Windows 8.3 short names), and the post-resolve check before delombok stays. Default `allowedRoots`: **D5 decided 2026-10-26: stays empty = everything allowed**, activatable via `-Dworkspace.allowedRoots=…`; `SECURITY_ERROR` not repackaged as `PARSE_ERROR` (fixed in 3.3) | RT:P-06, P-07, C-04, SN:S-23 | M | D5 |
| 5.3 ✅ | **DONE 2026-10-25** (`JavaMcpServer.flatten` now only sets unset properties; `ConfigFlattenTest` now `@Isolated` with cleanup). Do not let `-D` properties be overwritten by YAML | RT:P-03 | S | — |
| 5.4 ✅ | **DONE 2026-10-26** (`EofSignalingInputStream`, `ServerStdoutContractTest.serverExits_whenStdinIsClosed`) server lifecycle: JVM exits on stdin EOF | RT:P-02 | S | 0.5 |
| 5.5 ✅ | **DONE 2026-10-28** (lock around the `java.home` switch, plus load lock) serialize `System.setProperty("java.home")` (lock) or avoid it | SN:S-19 | M | — |
| 5.6 ✅ | **DONE 2026-10-31** (`gradleSourceDirs`: `src/main/java` of the root project and each submodule with a build file; no `src/test`, `buildSrc`, `build/`; `addGradleSourceDirs` is now the only way for Gradle; `GradleSourceDirsTest`) Gradle source dirs: make `addGradleSourceDirs` reachable, do not blindly load `src/test` | SN:S-17, S-43 | M | — |
| 5.7 ✅ | **DONE 2026-10-26** (`ProcessRunner`) Delombok: decouple output reading and `waitFor(timeout)` (otherwise the timeout never triggers) | RT:P-18 | M | — |
| 5.8 ✅ | **DONE 2026-10-26** (`LombokDelombokerFixesTest`: cache key, Gradle output) Delombok: Windows (`mvn.cmd`, `gradlew.bat`), `java` from `java.home`, classpath cache key (hash of absolute path + build fingerprint), Gradle output parsing | RT:P-19, P-20, P-21, P-22 | M | — |
| 5.9 ◐ | **PARTIAL 2026-10-26** (original clone is deleted too, delombok directory on failure; the latter not testable without a Lombok jar) Delombok: directory cleanup on error; delete the original clone on unload | RT:P-17, P-16, SN:S-20 | M | — |
| 5.10 ◐ | **PARTIAL 2026-10-26** (noclasspath launcher on the delombok directory + classpath of the original; end-to-end not testable without a Lombok jar) Delombok projects: copy `pom.xml` into the delombok directory resp. build context before `createLauncher` | RT:P-15 | M | 5.9 |
| 5.11 ✅ | **DONE 2026-10-26** (`LombokDetectorFixesTest`) `LombokDetector`: only `lombok.` imports/qualified annotations; resolve the `${…}` version | RT:P-23, P-24 | M | — |
| 5.12 ✅ | **DONE 2026-10-26** (`LombokMemberPredictorRulesTest`: only real Lombok annotations, @Data → only RequiredArgs, @Value → AllArgs without setter, builder class `<Type>Builder`, boolean names) `LombokMemberPredictor`: only Lombok annotations, correct getter/builder/constructor rules, `capitalize` length | RT:P-25, P-26, P-27 | M | 5.11 |
| 5.13 ✅ | **DONE 2026-10-26** (`BuildDetectorAntTest`) `BuildDetector`: Ant/Ivy detection | RT:P-28 | S | — |
| 5.14 ✅ | **DONE 2026-10-28** (cache under `~/.rewisp/jdk-classpath`, created atomically) `JdkClasspathResolver`: persistent cache instead of temp leak | RT:P-29, E-02 | M | — |
| 5.15 ✅ | **DONE 2026-10-28** (`ModuleCountingTest`) `ProjectBuildHelpers`: Gradle `include` variants, Maven comments/profiles | SN:S-18 | S | — |
| 5.16 ✅ | **DONE 2026-10-26** (`ProjectEvictionTest`: Spoon model of expired projects released after a 5-minute grace period, rebuilt from the rewrite state on access; delombok excluded; config `project.evictionGraceMinutes`) eviction for expired projects (release the model) and `markExpired` without a record copy per call | RT:C-05, E-05 | M | — |
| 5.17 ✅ | **DONE 2026-10-26** (`ProjectLoaderCloneTest`; also streams closed in `cleanup`/`hasJavaFiles`, empty catch blocks logged) git clone `depth=1` | RT:E-03 | S | — |
| 5.18 ✅ | **DONE 2026-10-26** (dummy keys removed instead of wired up, log-path comment corrected) clean up `application.yaml`: remove or wire up dummy keys (enforce `maxChangedFiles`), comment log path | RT:C-03, RW:C-03 | M | — |
| 5.19 ✅ | **DONE 2026-10-26** (`SNAPSHOT_MISMATCH` in the engine; `SnapshotId.toString` robust for short hashes) check `RewriteRequest.expectedSnapshot` (`SNAPSHOT_MISMATCH`) | RW:C-02 | S | — |
| 5.20 ✅ | **DONE 2026-10-28** (one load after another, `ProjectConcurrentLoadTest`) `load()` atomic (`compute`/project lock) | RT:P-13 | M | 1.16 |
| 5.21 ◐ | **PARTIAL 2026-10-26** (`GradleBuildResolverFixesTest`: allowlist separate for task/configuration/flags, `properties` format `name: value`, OS-dependent executable with `gradle` fallback, `filterSourceRoots` relative; integration tests without Gradle are skipped, therefore untested against real Gradle) **Fix the Gradle resolver:** allowlist checks tasks and `--configuration` values separately (today `GOAL_NOT_ALLOWED` on every resolve → Gradle support effectively dead); `properties` regex (`name: value`); `findGradleExecutable` OS-dependent with `gradle` fallback; `filterSourceRoots` relative to the root | BT:B-01, B-03, B-11, B-12 | M | 0.1 |
| 5.22 | Gradle classpath as **jar paths** (init-script task) instead of Maven coordinates from the dependency tree | BT:B-02, D-05 | L | 5.21 |
| 5.23 ✅ | **DONE 2026-10-31** (`util.ProcessRunner` + `ProcessRunnerTest`: reader thread, timeout triggers, tree-kill, output limit, env cleanup, stdin closed; used by Maven/Gradle/Delombok; `-B` open) shared process helper class for Maven/Gradle/Delombok: reader thread (timeout triggers), tree-kill (`descendants()`), output-limit flag, env cleanup, close stdin, `-B`, output excerpt in the error message | BT:B-04, B-05, B-08, I-02, I-05, RT:P-18 | M | — |
| 5.24 ✅ | **DONE 2026-10-31** (temp directory instead of project root, language level from the effective POM; multi-module: each module declared in `<modules>` built recursively with its own classpath, separate main/test roots, diagnostics `MODULE_CLASSPATH_FAILED`/`MODULES_INCOMPLETE`, limit `-Dbuild.maxModules`, `enrichClasspathFromBuild` merges all modules) Maven resolver: temp files instead of `.effective-pom.xml`/`.maven-classpath.txt` in the project root; own output file per module; build modules from `<modules>`, language level from the POM instead of "17" | BT:B-06, B-07, D-04 | L | 5.23 |
| 5.25 ✅ | **DONE 2026-11-23** (red-first `BuildContextFingerprintTest`: a module POM change without dirty flag previously kept the stale cache). `BuildFileHasher.hash` now covers ALL build files of all modules (`module-*/pom.xml`, `module-*/build.gradle`, skipping `target`/`build`/`.git`/...), and `BuildContextService` stores the hashes with the cached context and compares them on every cache hit, not only the dirty flag. Backoff/negative cache per `BuildContextServiceBackoffTest` (2026-10-26). Fingerprint across all build files of all modules and actually compared in the cache check | BT:I-01, I-03, I-04 | M | 5.24 |
| 5.26 ✅ | **DONE 2026-10-26** (`PomUtilsTest`) `PomUtils.resolveParentPomPath`: only when `<parent>` exists, empty `<relativePath/>`, directory → `pom.xml`, `isRegularFile`; remove unused methods/patterns | BT:B-09, D-01 | S | — |
| 5.27 ✅ | **DONE 2026-10-31** (`-Dbuild.networkAccess=true` for Maven and Gradle (`--offline`); `ProcessRunner.unsafeBatchArgument` rejects cmd.exe metacharacters for .bat/.cmd) pass the network option (`-o` is a hard default) through the server configuration; path/flag validation (BatBadBut under Windows) | BT:C-03, C-01 | M | 5.23 |
| 5.28 ◐ | **PARTIAL 2026-10-28** (8 unused `BuildDiagnosticCode` values removed, severity as enum; `includeTestClasspath` and source-root classification still open) `build/` cleanup: unused `ResolveOptions.includeTestClasspath`, 8 unused `BuildDiagnosticCode` values, severity as enum, uniform source-root classification Maven/Gradle, duplicate imports | BT:D-02, D-03, D-06, C-02, C-04 | S | 5.21 |

## Phase 6 — Dead code (mechanical, low-risk; each with its associated tests)

Rule: before deleting, grep across `src/main` **and** `src/test`; tests that only test dead code are deleted in the same package.

| AP | Title | Findings | Size | Depends |
|----|-------|----------|------|---------|
| 6.1 ✅ | **DONE 2026-10-26** (D1: deleted, recorded in git) delete the 3 unregistered tools (D1) | RD:R-19, R-02, R-21–R-24, R-32, R-37 | S | D1 |
| 6.2 ✅ | Delete `FileAccessService` | RD:R-20 | S | — |
| 6.3 ✅ | **DONE 2026-10-26** (legacy methods, `RewriteTypeUtils`, `TypeInfo` deleted; `SourceCodeUtils` stays, used by `RewriteSignatureTool` among others) `BaseEditTool`: ≈ 600 lines of legacy (stale methods, delegates, `replaceInCodeOnly` …) + delete `RewriteTypeUtils`, `SourceCodeUtils`, `TypeInfo` | ED:E-29–E-34, RD:R-30 | M | 3.2 |
| 6.4 ✅ | **DONE 2026-10-26** (`BaseReadTool` has no more unused helpers after phase 3, 100 lines) `BaseReadTool`: remove unused helpers or a shared base for argument/warning/schema helpers | RD:R-29 | S | 3.18 |
| 6.5 ✅ | **DONE 2026-10-30** (D2 lean in `SpoonModelService.validateEdit`; `SpoonEngine*`, `SpoonAnalysis*`, `SpoonDiagnostic`, `SpoonFinding`, `EffectiveSourceOverlay`, `EffectiveProjectSources`, `ResolutionMode` incl. tests and 2 Spoon tests in `CrossFrameworkIntegrationTest` deleted) delete the Spoon engine complex per decision D2 | RW:D-02–D-04, RT:D-03 | M | D2 |
| 6.6 ✅ | **DONE 2026-10-26** (`RenameMethodRecipe` is now a pure test recipe under `src/test`; legacy API and `RenameSymbolRecipeTest` removed) delete `RenameMethodRecipe`, move engine tests to `RenameSymbolRecipe`; remove the legacy API in `RenameSymbolRecipe` | RW:D-05, D-06 | M | 4.2 |
| 6.7 ✅ | **DONE 2026-10-26** (`edit.ClassSourceGenerator`, test moved along) rename/convert `AddClassRecipe` into `ClassSourceGenerator` | RW:D-09 | S | — |
| 6.8 ✅ | **DONE 2026-11-05** (heuristic scan: no more unused methods except reflection setters of the recipes and test hooks) (`CommitException`, `requiresBuildRefresh` incl. `isBuildFile` deleted; `RewriteSyncManager`/`RecipeRegistry` remnants checked) `CommitException`, `RewriteSyncManager`/`RecipeRegistry` remnants, unused record fields, `requiresBuildRefresh` | RW:D-01, D-10, D-11, D-12 | S | — |
| 6.9 ✅ | **DONE 2026-10-30** (`getBuildContext`, `metricsCollector`, `spoonEngine`, `effectiveSourceOverlay`, `rewriteEngine`/`recipeRegistry` incl. 2-arg constructor, `markAllDirty` (+ registry), `deriveName` wrapper removed; the `isJar` branch stays: it is needed) `ProjectManager`: unused constructor/fields/getters (`rewriteEngine`, `recipeRegistry`, `spoonEngine`, `effectiveSourceOverlay`, `metricsCollector`, `getBuildContext`, `markAllDirty`, `deriveName`, `isJar` branch, import) | RT:D-02–D-06, D-11, RW:D-13 | S | 6.5 |
| 6.10 ✅ | **DONE 2026-10-26** (`-jar` branch, `findCachedOrSystemLombok`, `BuildDetector.findSourceDir` removed; `LombokMemberPredictor` was already cleaned up in 5.12) `LombokDelomboker`/`LombokMemberPredictor`/`BuildDetector` remnants | RT:D-07–D-10 | S | — |
| 6.11 ✅ | **DONE 2026-11-05** (`SourceCodeUtils` scanner, `ProjectEntryFactory.withEditedFiles` deleted) (`CollectionUtils`, `BuildFileDetector`, unused methods in `ProjectDirtyState`/`SnapshotService`/`ProjectEntryFactory`/`PathUtils` deleted) `snapshot/`: deprecated `checkDirtyFullScan`, unused fields/enums/methods, `CollectionUtils`, `PathUtils`, `BuildFileDetector.INSTANCE`, `ProjectCleanupHelper`, `ProjectEntryFactory`, `ProjectEntry.isModelDirty` | SN:S-27–S-43 | M | — |
| 6.12 ✅ | **DONE 2026-10-26** (`CorrelationId` and `MetricsCollector` incl. wiring and test deleted) `observability`: delete `CorrelationId`; wire up `MetricsCollector` or delete it | SN:S-34, S-35, RT:D-04 | S | — |
| 6.13 ✅ | **OBSOLETE 2026-10-26** (`event` package deleted) `event`: unused event types/imports/javadoc link; retract the bus claim in the docs | SN:S-36 | S | — |
| 6.14 ✅ | **DONE 2026-11-05** (unused methods in `BackupConfig`/`TransactionState` deleted) `TransactionState`/`BackupConfig`/`TransactionTools` shell unused parts | ED:E-35–E-38 | S | 1.4 |
| 6.15 ✅ | **DONE 2026-10-26** (104 unused and duplicate imports removed in 43 files; javadoc references count as usage) unused imports repo-wide | RD:R-33, R-31 | S | 6.1–6.14 |
| 6.16 ✅ | **DONE** (`SpoonAnalysisCache` deleted with 6.5; `RewriteSyncManager` and `RunningAverage` no longer exist) `RewriteSyncManager` methods only in tests, `MetricsCollector.RunningAverage` | RW:D-03, D-12 | S | 6.5 |

## Phase 7 — Performance (measure first, then change)

| AP | Title | Findings | Size | Depends |
|----|-------|----------|------|---------|
| 7.1 ✅ | **DONE 2026-10-28** (`ToolCallGuard.startPhase/endPhase`: snapshot refresh, rewrite preview, write, Spoon rebuild in the DONE line) measurement points: duration per edit phase (refresh, preview, write, reload) logged in `ToolCallGuard`; baseline for BUGS.md "Performance" | BUGS:Perf | S | 0.1 |
| 7.2 ✅ | **DONE 2026-10-28** (race detection via modification time+size instead of a second full read; boundary: a change of equal size in the same timestamp tick goes undetected) `captureStable`: second read only via `lastModified`+`size` | SN:S-44, RD:R-40 | M | 7.1 |
| 7.3 ✅ | **DONE 2026-10-28** (snapshot; watcher registration still open) `walkFileTree` with `preVisitDirectory`/`SKIP_SUBTREE` (snapshot, watcher) | SN:S-45 | S | 2.1 |
| 7.4 ✅ | **DONE 2026-10-31** (checked: one `dirtyFiles` entry costs only one `stat` and one map copy, negligible compared to the Spoon build, and `PerFileDirtyTest` pins the dirty behavior, therefore the set stays; unchanged files are not re-read (size + mtime), now with a racy window of 2 s like git: a file that was just modified is always re-read; test `refreshFiles_rereadsARecentlyModifiedFileEvenWithSameSizeAndMtime` was red) do not let `dirtyFiles` grow monotonically; remember the hash per file, only re-read changed files | ED:E-40 | M | 3.4 |
| 7.5 ⏸ | **REVERTED 2026-10-31 after measurement** (`PerformanceSmokeTest.testEditOnLargeFixtureBuildsTheSpoonModelOnce`: 250 classes: Spoon build 1.5 s, second edit total 1.4–2.5 s; thanks to the adopted candidate model the build is only due once per edit. Spoon has no reliable incremental API for in-memory sources. Protection for very large projects instead: `-Dedit.validate.maxSources` (default 3000) skips the pre-check) incremental Spoon update instead of a full reload (D6) | RD:R-38, RW:I-02 | L | 3.1, 7.1 |
| 7.6 ✅ | **DONE 2026-10-31 as warmup** (measurement: the first edit of a JVM cost 12 s startup, further ones 1.4–2.5 s; `Warmup` warms OpenRewrite (javac, classpath scan, a real recipe) and Spoon in the background at server start, first edit now ca. 4 s; can be disabled with `-Dwarmup=false`. The LST cache saves only fractions of a second per edit, because only the target file is parsed) cache `JavaParser`/classpath per project, LST reuse | RW:I-01, ED:E-43 | L | 4.9 |
| 7.7 ✅ | **DONE 2026-10-28** (was already removed; `countCallers` now uses `ReferenceFinder`) remove `freshTypesForDirtyFiles` (Spoon launcher per dirty type) | ED:E-41, E-47 | S | 3.5 |
| 7.8 ✅ | **DONE 2026-10-29** (`TypeLookup.topLevelNames`/`topLevelNamesBySimpleName` from the model index) `AutoImportResolver`: cache the type index | ED:E-44 | M | 4.14 |
| 7.9 ✅ | **DONE 2026-10-28** (type index per model in `TypeLookup`, `project_metadata` in one pass) `getAllTypes()`/hierarchy lookups via index | RD:R-35, R-39 | M | 3.6 |
| 7.10 ✅ | **DONE 2026-10-29** (arguments truncated per value; `toRealPath` once per committed path) `ToolCallGuard.summarize` truncates arguments before `toString`; `toRealPath` once | ED:E-46 | S | — |
| 7.11 ✅ | **DONE 2026-10-31** (line ending taken from the first 8 KB; the commit reads each file at most once (`readCached`); `getPendingChanges()` copies only references of a few entries, stays) `writeFile`: line ending from the file header, no double read in the commit; `getPendingChanges()` without a full copy | ED:E-45 | M | — |
| 7.12 ✅ | **DONE 2026-10-29** (`DirtyChecker` without a hash copy; `addToCopy`/`MetricsCollector.getOrDefault` no longer existed; `SnapshotService` copies remain: immutable records need them) remainder: `SnapshotService` map copies, `DirtyChecker` copy, `CollectionUtils.addToCopy`, `MetricsCollector.getOrDefault` | SN:S-46–S-50 | S | 6.11 |
| 7.13 ✅ | `delombok()` + `LombokDetector` double scan, early exit | RT:E-01, E-04 | S | 5.11 |

## Phase 8 — Concept refactorings (after stable tests)

| AP | Title | Findings | Size | Depends |
|----|-------|----------|------|---------|
| 8.1 ✅ | **DONE 2026-10-31** (`previewRewrite`/`commitRewrite`/`applyRewrite` in `BaseEditTool`; 9 of 10 tools fully migrated, `move_class` uses `previewRewrite` and keeps its own phase 3 (different target path, charset, deletion); `DryRunPreview` moved to `tools.base`; ca. 420 lines less) template method `applyRewrite(...)` in `BaseEditTool` for the 13-times-copied phase-2/3 block | ED:E-57 | L | Phase 1 |
| 8.2 | `RecipeSupport` for copied helper methods (`matchesClass`, `splitImports`, `eraseType`) | RW:I-03 | M | Phase 4 |
| 8.3 | ✅ `guardStaleThreshold` abolished (obsolete since OpenRewrite); EditEventBus/SnapshotEventPublisher/`event` package deleted | ED:E-47 | S | 3.5 |
| 8.4 ✅ | **DONE 2026-10-31** (`dryRun` shows mkdir/package-info incl. diff; `AddPackageDryRunTest`) `add_package` with `dryRun` | ED:E-50 | S | — |
| 8.5 | Merge the two change-detection mechanisms (fingerprint vs. content hash) | SN:S-55 | M | 2.9 |
| 8.6 ✅ | **DONE 2026-10-31** (`StringLiteralScanner`: whole words, position check, cap 20 with remainder count, errors in one type do not abort; used by rename and `remove_member`) secure and cap the edge cases of `rename`: string-literal scan | ED:E-15, E-42 | S | 3.8 |
| 8.7 ✅ | **DONE 2026-10-31** (`docs/OUTPUT-FORMATS.md`) document the markdown escape/format contracts of the tool outputs | — | S | — |

## Phase 9 — Tests and documentation maintenance

| AP | Title | Findings | Size | Depends |
|----|-------|----------|------|---------|
| 9.1 | Close test gaps from phases 1–4 (every bug: at least one regression test) | — | ongoing | — |
| 9.2 ✅ | **DONE 2026-10-31** (every full run since the baseline run: 13 skipped, matches TESTING.md) verify skip/disabled counts after the baseline run (0.1); TESTING.md already statically corrected | BT:T-06, T-07 | S | 0.1 |
| 9.3 ✅ | **DONE 2026-10-31** (`TestFixtures.copy`; duplicates under `fixtures/` merged; 5 unreferenced fixtures deleted, the sample projects `*-sample` remain as sandbox templates) consolidate fixtures: unify duplicates (`gradle-multi-project`, `maven-multi-module`), delete 8 unreferenced fixture directories, resolver tests in `@TempDir` instead of the source tree (they create `target/`) | BT:T-01, T-02, T-03 | M | 0.4 |
| 9.6 ✅ | **DONE 2026-10-31** (`FakeGradle`/`FakeMaven` scripts: the 11 `@Disabled` Gradle tests now run and never skip themselves; `RecipeSecurityTest` checks real process starts; `changeReturnTypeOnly` compilable; `testModuleStructureDetected` checks the module IDs and revealed that multi-module Maven only delivered the root module, see 5.24) replace masking tests: Gradle tests (`catch // Expected`, `assertTrue(true)`, enum-vs-string) with a fake Gradle script; 7× `assertTrue(true)` in `RecipeSecurityTest`; `RewriteSignatureToolTest.changeReturnTypeOnly` with a parameterized, consistent method; `testModuleStructureDetected` (`>= 1`) concrete | BT:T-05, T-08, T-09, T-13, T-04 | M | 4.1, 5.21 |
| 9.7 ✅ | **DONE 2026-10-31** (`TransactionToolLevelTest`) close test gaps: `edit_line` in a transaction + rollback at tool level; rollback after a failed commit | BT:T-12 | M | 1.1, 1.2 |
| 9.8 | Delete tests that only test dead code together with the code (`SpoonEngineImplTest`, `SpoonAnalysisCacheTest`, `EffectiveSourceOverlayTest`, `MetricsCollectorTest`, `EditEventBusTest` parts, `DirtyCheckTest`/`PerformanceSmokeTest` `checkDirtyFullScan`) | BT:T-10 | S | 6.5, 6.11–6.13 |
| 9.4 ✅ | **DONE 2026-10-31** (group 11 in ROADMAP.md booked as implemented) re-plan roadmap group 11 (post-edit validation) after D2 | RW:C-01 | M | D2 |
| 9.5 | Update the documentation state in the ROADMAP.md changelog after each phase | — | ongoing | — |

---

## Recommended first iteration (2–3 days)

`0.1 → 0.3 → 4.1 → 1.1 → 1.4 → 2.1 → 1.6 → 3.14 → 3.16 → 5.3 → 0.5` — all small, mutually independent packages with high value (silent data loss, wrong results, Windows path bugs).

## Counting the findings (6 reports, as available)

| Report | BUG | DEAD | INEFF | CONCEPT |
|--------|-----|------|-------|---------|
| ED Edit layer | 28 | 11 | 7 | 11 |
| RD Read tools | 19 + 1 | 7 | 7 | 12 |
| RW Rewrite layer | 19 | 13 | 3 | 4 |
| SN Snapshot/Util | 26 | 17 | 7 | 6 |
| RT Root/Config | 31 | 11 | 5 | 6 |
| BT build/ + tests | 12 (+13 TEST) | 11 | 6 | 4 |
| **Total** | **136** (+13 test findings) | **70** | **35** | **43** |

Individual findings appear in several reports (e.g. `isSourceFile` in ED/RD/SN, dead-code findings for `SpoonEngine*`, `CorrelationId`, the 3 unused tools in 3–4 reports, `"\\n"` in RD and BT). Adjusted: ≈ 125 independent bugs and ≈ 50 independent dead-code findings.

**Correction versus the first draft:** `RewriteSyncManager` is **not** dead (only `getStaleFiles`/`getStaleFileCount`/`reset` are used exclusively by tests). `RenameMethodRecipe` is unreferenced in `src/main` and is only used via the test registration.
