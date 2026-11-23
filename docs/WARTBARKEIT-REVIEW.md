# Maintainability Review

> **As of:** 2026-10-26, after the architecture correction "Spoon only reads from rewrite" (see [ARCHITEKTUR.md](ARCHITEKTUR.md)).
> Basis: size, coupling and duplication measurements across `src/main/java` (scripts see section "Measurement method"). Measures that are already implemented are marked with ✅; the open ones are tracked as work packages (WK-xx) and belong in [REVIEW-PLAN.md](REVIEW-PLAN.md).

---

## 1. Overall picture

| Package | Files | Lines | Finding |
|---------|------:|------:|---------|
| `tools` | 44 | ~9 400 | **Too broad.** 35 MCP tools, 3 base classes, text utilities, `FileAccessService`, `ToolCallGuard`, `TypeInfo` in one package |
| (root) | 9 | ~2 500 | Mixed package: server entry point, `ProjectManager` (god class), Lombok support (3 classes, 1 100 lines), `ProjectLoader`, `BuildDetector` |
| `rewrite.recipes` | 12 | 2 185 | Consistent within itself |
| `edit` | 6 | 1 590 | Consistent, but **cycle** `edit → tools` (see 3.2) |
| `snapshot` | 19 | 1 150 | Consistent; `WorkspaceWatcher`/`ChangeTracker` without tests |
| `util` | 7 | ~1 050 | Catch-all (path, build, name helpers) |
| `build` | 13 | 950 | Consistent |
| `rewrite` | 21 | ~990 | Mixed: engine, registry, DTOs, Spoon engine remnants |

Overall impression: the **layers are designed correctly** (Spoon reads, Rewrite writes, disk as storage), but the **package boundaries do not reflect them** – everything tool-related sits in `tools`, and the center (`ProjectManager`) knows everything.

---

## 2. Classes: size and responsibility

### 2.1 God classes

| Class | Lines | Responsibilities | Proposal |
|-------|------:|------------------|----------|
| `BaseEditTool` | 1 505 → **706** ✅ | Tool base, snapshot refresh, fingerprint check, text parsing (imports, braces, annotations), Spoon legacy, warning formatting | Move the remaining text utilities to `SourceCodeUtils` (WK-03), fingerprint logic to `FingerprintValidator` (WK-04) |
| `ProjectManager` | 703 | (1) registry of the loaded projects, (2) load/reload/expiry, (3) Spoon model build, (4) rewrite state (`effectiveSnapshot`), (5) build-context cache, (6) service locator for engines/watcher/tracker/metrics | Split into `ProjectRegistry`, `SpoonModelService`, `BuildContextService`; pass engines/watcher via constructor instead of getters (WK-01) |
| `LombokDelomboker` | 633 | Process invocation, Lombok jar resolution, directory handling, cache | Own package `lombok` (together with `LombokDetector`, `LombokMemberPredictor`) (WK-02) |
| `EditManager` | 605 | Writing, backups, transaction buffer, commit, baselines, event log | Commit pipeline into `TransactionCommitter` (WK-05) |

### 2.2 Duplication between the base classes ✅

`BaseReadTool` and `BaseEditTool` contained **22 identical methods** (argument extraction, `domainError` ×3, `ok`, `error`, `props` ×3, `propsWithDescription` ×3, `req`, `ctx`, `formatExpiredWarning`, `formatModelDirtyWarning`, `findEntry`, the stale-Spoon helpers) – proven byte-identical by script (after whitespace normalization); only `build()` and `formatModelDirtyWarning` differed textually (qualification of the type names).

**Implemented:** new class `BaseTool` (template method `build()` with hooks `beforeCall`/`afterCall`; the edit lock now sits in `BaseEditTool.beforeCall/afterCall`). Both base classes inherit from it.

### 2.3 Further duplication and misplacements (open)

| Finding | Evidence | WK |
|---------|----------|----|
| `validateFingerprint` duplicated: `BaseEditTool` (public static, called by `EditManager`) and `BaseReadTool` (instance method) | both files | WK-04 |
| `RebootSpoonTool` inherits from `BaseReadTool`, but deletes projects and resets the EditManager (a writing operation in a "read" class); javadoc duplicated | `RebootSpoonTool.java:40-56` | WK-06 |
| `LoadJavaProjectTool`, `UnloadJavaProjectTool`, `ReloadJavaProjectTool`, `ProjectRefreshBuildTool` are lifecycle operations, not Spoon analysis – they belong in their own sub-package | `tools/` | WK-07 |
| Text/source-text utilities spread across four classes: `SourceCodeUtils`, `RewriteTypeUtils`, remnants in `BaseEditTool`, `AutoImportResolver` (edit), `ProjectBuildHelpers` (util) | Measurement | WK-03 |
| `EffectiveSourceOverlay` has only the getter in `ProjectManager` in `src/main` (no caller of the getter), otherwise only tests; it mirrors the same overlay idea as `ProjectManager.effectiveSnapshot` | Grep | WK-08 |
| `FileAccessService` (package `tools`) is used by **no** production class – the base-class javadocs referenced it, but the code accesses `Files` directly. Either delete it or enforce it as the only storage interface | Grep | WK-09 |
| Obsolete architecture comments in base classes (mentioned "STORAGE layer", claimed "no Spoon access" although `findEntry` now calls `ensureSpoonModel`) | old javadocs | ✅ replaced |

---

## 3. Packages and dependencies

### 3.1 Coupling matrix (imports between packages, excerpt)

```
tools → rewrite 53      tools → ProjectManager 38     tools → model 29
tools → snapshot 22     tools → edit 22               tools → util 16
rewrite → snapshot 13   (root) → build 8              (root) → util 8
```

That is expected: `tools` is the top layer. Only the **backward directions** stand out:

### 3.2 Cycles and forbidden directions

| Cycle | Cause | Measure |
|-------|-------|---------|
| `edit → tools` | `EditManager` calls `BaseEditTool.validateFingerprint` (static): the lower layer knows the upper one | `FingerprintValidator` in `model` or `edit` (WK-04) |
| `event → snapshot`, `event → rewrite` and `snapshot → event` | `EditEvent`/`SnapshotEvent` carry `ProjectSnapshot`, `SnapshotId`, `RewriteResult`; `SnapshotService` publishes to the bus | Events carry only IDs/names, or `event` is placed below `snapshot` (WK-10) |
| `ProjectManager` (root) ← → `tools` | `ProjectManager` knows no tools, but has 38 tool imports → strong fan-in coupling | The split (WK-01) distributes the fan-in |

### 3.3 Proposed package structure (target picture)

```
de.jare.rewisp
├─ server          JavaMcpServer, ToolCallGuard, McpTool
├─ project         ProjectRegistry, ProjectLoader, BuildDetector, ProjectEntry (+ Factory), ProjectState
├─ lombok          LombokDetector, LombokDelomboker, LombokMemberPredictor
├─ build           (unchanged)
├─ spoon           SpoonModelService (ensureSpoonModel), SpoonEngine*, sourceRootsOf
├─ rewrite         Engine, Registry, RewriteResult, recipes/
├─ snapshot        SnapshotService, ProjectSnapshot, Watcher, ChangeTracker, IgnoreRules, DirtyChecker
├─ edit            EditManager, TransactionState, TransactionCommitter, FingerprintValidator, AutoImportResolver
├─ storage         FileAccessService, PathUtils, ProjectLayout
└─ tools
   ├─ base         BaseTool, BaseReadTool, BaseEditTool
   ├─ read         Inspect*, Find*, List*, Search*, GetFileContent, GetElementPosition, GetTypeHierarchy
   ├─ edit         Add*, Remove*, Edit*, Replace*, Rename*, Move*, RewriteSignature
   ├─ txn          TransactionTools
   └─ project      Load/Unload/Reload/Refresh/Reboot/ListLoaded/ProjectMetadata/CheckProjectDirty
```

The renaming is mechanical (IDE refactoring), but it touches ~230 files and all test imports; it should happen as **one** commit without functional change (WK-11), only after WK-01 to WK-05 have disentangled the dependencies.

---

## 4. Constants and magic values

✅ **Implemented:** `util/ProjectLayout` bundles `JAVA_EXTENSION`, `POM_FILE`, `GRADLE_BUILD_FILES`, `MAIN_JAVA_DIR`, `TEST_JAVA_DIR`, `TEMP_FILE_SUFFIX` (`.mcp-tmp`), `UNNAMED_PACKAGE` (`"unnamed package"`) and `packageSegments(String)`. Replaced `.endsWith(".java")`, `+ ".java"`, `resolve("src/main/java")`, `resolve("pom.xml")` and the temp suffix in 21 files.

**Still open (WK-12):**

| Literal | Occurrences | Target |
|---------|-------------|--------|
| `"build.gradle"`, `"build.gradle.kts"`, `"settings.gradle"` individually (instead of a list) | `LombokDetector`, `GradleBuildResolver`, `BackupConfig`, `BuildFileDetector`, `ChangeTracker`, `CheckProjectDirtyTool` | `ProjectLayout.GRADLE_BUILD_FILES` |
| `"src/test/java"`, `"src/main/kotlin"`, `"src/main/groovy"` | `GetFileContentTool`, `ProjectBuildHelpers` | `ProjectLayout` |
| Error categories as strings (`"DOMAIN_ERROR"`, `"NOT_FOUND"`, `"PARSE_ERROR"`, `"SECURITY_ERROR"`, `"BUILD_FAILED"` …) | over 60 places | `enum ErrorType` (type-safe, listable in the API docs) |
| Warning texts with markdown fragments (`"> ⚠️ **…\"`) | `BaseTool` | `WarningFormatter` |
| Tool names (`"add_field"`, `"move_class"` …) duplicated: in `toolName()` and in `JavaMcpServer`/tests | 35 tools | `ToolNames` constants or enum |

---

## 5. Error handling and logging

| Measurement | Value | Assessment |
|-------------|------:|------------|
| `catch` blocks whose body is empty or only a comment | **142** | High. Many are legitimate (best-effort cleanup), but indistinguishable from swallowed errors. Introduce a rule: cleanup → `LOG.debug`, everything else → log or propagate (WK-13). The watcher case (`catch (Exception e) { break; }`) is a concrete bug, not a style issue (N38) |
| `System.out`/`System.err`/`printStackTrace` | 4 | Acceptable (stdio MCP: stdout belongs to the protocol); check that none of them writes to stdout (WK-14) |
| Fully qualified names in the code (`java.util.…`, `java.nio.file.…`) | 174 | Readability. Introduce imports (WK-15, mechanical) |
| `@Deprecated` | 19 | 9 of them in `BaseEditTool` (now 0 ✅, see 6), `checkDirtyFullScan` is still called (1 production, 7 test callers) |

---

## 6. Dead code (removed in this review ✅)

By fixed-point script (method without a caller in `src/main` and `src/test`, iteratively, because removing methods orphans further methods): removed from `BaseEditTool` were `validateEditableAndFingerprint`, `classClosingBraceOffset`, `annotationWindow`, `annotationSearchStart`, `replaceAnnotationInWindow`, `locateFreshMethod`, `freshParamsMatch`, `eraseSigType`, `replaceInCodeOnly`, `hasUnclosedBlockComment`, `replaceOutsideStrings`; also removed were `RewriteSyncManager` (incl. test, 95 constructor calls simplified), `reloadIfSpoonStale`/`isFileStaleInSpoon`/`hasStaleFilesInSpoon`/`markSpoonSynced`/`reloadIfFileStale` and the five now-effectless `reloadIfSpoonStale` calls in the inspect tools.

Open (caller analysis needed): `EffectiveSourceOverlay`, `SpoonAnalysisCache`, `MetricsCollector.RunningAverage`, `DirtyChecker.checkDirtyFullScan` (deprecated but still used), `ProjectEntry.editedFiles`/`isFileEdited` (N36) → WK-16.

---

## 7. Duplicate representation of the rewrite state ✅

`BaseEditTool.refreshSnapshotForEdit` and `ProjectManager.effectiveSnapshot` built the same overlay (dirty files + transaction buffer + pending deletions) separately; in addition there were three private helper methods in `BaseEditTool`.

**Implemented:** `ProjectManager.effectiveSnapshot(entry, broadScope)` is the **only** place; `refreshSnapshotForEdit` calls it and only stores the result. New helper method `hasUnsyncedChanges(entry)`.

## 8. `RewriteSyncManager` ✅

With `ensureSpoonModel`, the hash of the rewrite state (snapshot root hash) decides staleness; the stale set was redundant and moreover global instead of per project (a single edit in project A caused read accesses to B to reload). Removed, including constructor parameters in 12 edit tools, `JavaMcpServer`, `RebootSpoonTool` and test infrastructure.

---

## 9. Line endings and formatting

The repository consists of **229 CRLF and 8 LF files**; several script runs had temporarily produced mixed line endings (27 files, all normalized to CRLF ✅). **WK-17:** introduce `.gitattributes` with `*.java text eol=crlf` (or uniformly LF) to prevent this from recurring; `.editorconfig` for indentation.

---

## 10. Work packages (open)

| WK | Content | Effort | Dependency |
|----|---------|:------:|------------|
| WK-01 ✅ | **DONE 2026-10-26** (`ProjectRegistry`, `RewriteStateService` + `TransactionOverlay`, `analysis.SpoonModelService`, `build.BuildContextService`; `ProjectManager` remains as a facade with unchanged API, 703 → 451 lines incl. license header and javadoc; constructor injection of the engines/watcher still pending) split `ProjectManager` (`ProjectRegistry`, `SpoonModelService`, `BuildContextService`); replace service-locator getters with constructor injection | L | — |
| WK-02 ✅ | **DONE 2026-10-26** (`de.jare.rewisp.lombok` with Detector, Delomboker, MemberPredictor and their tests) create package `lombok` | S | — |
| WK-03 ✅ | **DONE 2026-10-26** (`insertImports`, `splitTopLevelCommas` moved to `SourceCodeUtils`; four dead delegates removed; the fresh-parse workaround `freshTypesForDirtyFiles`/`formatFreshParsedWarning`/`locateFreshType` is gone because Spoon now builds from the rewrite state; `BaseEditTool` 1 505 → 421 lines. Open: scan helpers forward (`RewriteTypeUtils`) and backward (`SourceCodeUtils`) are implemented separately; `guardStaleThreshold` is substantively obsolete due to `ensureSpoonModel`) consolidate text/source-text utilities (`SourceCodeUtils`) and pull remnants out of `BaseEditTool` | M | — |
| WK-04 ✅ | **DONE 2026-10-26** (`edit.FingerprintValidator`; cycle `edit → tools` and duplicated `validateFingerprint` eliminated) `FingerprintValidator` (resolves the cycle `edit → tools`, duplicated `validateFingerprint`) | S | — |
| WK-05 ✅ | **DONE 2026-10-26** (`TransactionCommitter`, `BackupService`, `AtomicFiles`; `EditManager` 605 → 379 lines; snapshot helpers typed instead of reflection) `TransactionCommitter` out of `EditManager` | M | WK-04 |
| WK-06 ✅ | **DONE 2026-10-26** (`BaseProjectTool` for 7 lifecycle/management tools; `findEntry` now rebuilds the Spoon model only in `BaseReadTool`/`BaseEditTool`) detach `RebootSpoonTool` from `BaseReadTool` (lifecycle base) | S | — |
| WK-07 | Tools into sub-packages (`read`, `edit`, `txn`, `project`) | M | WK-03, WK-06 |
| WK-08 | Remove `EffectiveSourceOverlay` or merge it with `effectiveSnapshot` | S | — |
| WK-09 ✅ | **DONE 2026-10-26** (`FileAccessService` deleted: not a single production or test caller, likewise REVIEW-PLAN 6.2) `FileAccessService`: delete or make mandatory | S | — |
| WK-10 ✅ | **DONE 2026-10-26** (`SnapshotListener` in `snapshot`, bridge `event.SnapshotEventPublisher`, wiring in `JavaMcpServer`; `event` now depends only on `snapshot`, not the other way round; `event → rewrite` removed. Finding: the bus has no consumer in production) decouple events from `snapshot`/`rewrite` | M | — |
| WK-11 ✅ | **DONE 2026-10-26** (`tools` → `tools.base/read/edit/txn/project`, 35 tools + 3 base classes; tests move along; `handle(...)` of all tools is now `public`, allowing tests and orchestration to call across package boundaries. **Deliberately not moved:** `JavaMcpServer`/`ProjectManager`/`ProjectLoader`/`BuildDetector` stay in the root package (main class is in the manifest), `util` stays unsplit) package renaming (one mechanical commit) | M | WK-01…WK-10 |
| WK-12 ◐ | **PARTIAL 2026-10-26:** `model.ErrorType` (55 categories) replaces the string literals in `domainError`, `ProjectLoadException` and the validation arrays (25 files; names unchanged, API-compatible); `ProjectLayout` extended with Gradle configuration, Kotlin/Groovy, test directory and `isBuildConfigFile`. **Open:** tool names as constants, warning texts (`WarningFormatter`), dynamic error codes from `BuildResolutionException`, Gradle lists in `BackupConfig`/`BuildFileDetector`/`GradleBuildResolver` leftovers. Leftovers | M | — |
| WK-13 ✅ | **DONE 2026-10-26.** Correction of the measurement: of the "142", only **34** were really empty (the rest had bodies). Of those: 10 identical blocks in the edit tools replaced by `BaseEditTool.buildContextOrNull` (the reason is now logged with `info`); the remaining 24 now log (`warn` for configuration/security errors: invalid debounce, invalid allowed roots, unsettable recipe parameters, failed dirty scan; otherwise `debug`/`trace`). **Rule:** a `catch` may never be empty. Cleanup and expected failures (file disappeared, class not found) → `debug`; anything that can affect configuration, security or the result → `warn` or propagate. Catch blocks with a non-empty body that only return `null`/default were not checked. Catch rule + cleanup of the empty catch blocks | M | — |
| WK-14 ✅ | stdout check (protocol only) — done 2026-11-05, `SourceHygieneTest` | S | — |
| WK-15 ✅ | Fully qualified names → imports — done 2026-11-18: core names (2026-11-05, 42 files) and sub-packages like regex/stream/attribute/concurrent (24 files); only strings and system properties remain | S | — |
| WK-16 ✅ | Further caller analysis of dead classes — done 2026-11-05: no more unreferenced classes; last remnant `checkDirtyFullScan` (only tests used it) incl. BaseEditTool wrapper and 7 tests removed | S | — |
| WK-17 ✅ | `.gitattributes` (`* -text`, no conversion), `.editorconfig`, all sources CRLF, test `SourceHygieneTest` — done 2026-11-05; **as of 2026-11-23:** `.gitattributes` and `.editorconfig` are currently not checked into the repo (CRLF is only checked by `SourceHygieneTest`) | S | — |
| WK-18 ✅ | Unused imports — done 2026-11-18: `UnusedImportsTest` checks all sources (main and test; `-Xlint` does not report them), 18 found imports removed | S | — |

---

## Measurement method

PowerShell scripts (in the conversation history, not checked in): lines per file/package (`Measure-Object -Line`), package import matrix (regex on `import de.jare.rewisp.<package>`), method comparison of two classes (method body via brace counting, whitespace normalized), fixed-point deletion of methods without callers (regex `\bname\(` across `src/main` and `src/test`, overloads conservatively counted together). **Limitations:** calls via reflection or generated names are not detected; line counts include comments and blank lines.

**Author:** Janusch Rentenatus
