# Architecture documentation — rewisp

**Version:** 1.1.2
**Last updated:** 2026-11-23 (package overview adjusted to the module split `src_rewisp`/`src_mcp`; previously 2026-10-25 review against the code, see [REVIEW-PLAN.md](REVIEW-PLAN.md))
**Status:** actual state documented; deviations from the target state are named in the section "Target vs. actual".

---

## Basic principle

> **Spoon reads, OpenRewrite writes, the disk is the only source of truth.**

rewisp is an MCP server (stdio, JSON-RPC) that analyzes and structurally modifies Java source code. Three layers:

```
 MCP client (AI agent)
        │ JSON-RPC over stdio (stdout is exclusively the protocol channel)
        ▼
 ┌───────────────────────────────────────────────────────────┐
 │ ToolCallGuard: trace ID, duration, error body with log hint│
 └──────────────────────────┬────────────────────────────────┘
                            ▼
 ┌───────────────────────────────────────────────────────────┐
 │ SPOON — analysis (read-only)                    BaseReadTool│
 │  CtModel per project (ProjectEntry), symbol search,        │
 │  pre-edit analysis (import resolution, erasure, duplicates) │
 └──────────────────────────┬────────────────────────────────┘
                            ▼ Snapshot (contents + SHA-256)
 ┌───────────────────────────────────────────────────────────┐
 │ REWRITE — modification (writing)                BaseEditTool│
 │  Recipe (OpenRewrite, JavaTemplate) → preview (files,      │
 │  diff) → EditManager (transaction buffer, fingerprint lock,│
 │  backup, atomic write, line endings)                       │
 └──────────────────────────┬────────────────────────────────┘
                            ▼
 ┌───────────────────────────────────────────────────────────┐
 │ DISK — filesystem                                          │
 │  *.java, pom.xml, build.gradle*, ~/.rewisp/{logs,backups}  │
 └───────────────────────────────────────────────────────────┘
```

### Responsibilities

| Layer | Task | Classes (selection) |
|-------|------|---------------------|
| **SPOON** | build model, answer queries, analyze edits upfront | `ProjectManager`, `ProjectBuildHelpers.createLauncher`, `tools.read.*` (in `src_mcp`), `SpoonModelService`, `AutoImportResolver`, `TypeErasureChecker` |
| **REWRITE** | run recipes, buffer/write the result | `OpenRewriteEngineImpl`, `rewrite/recipes/*`, `EditManager`, `TransactionState`, `BaseEditTool` (in `src_mcp`), `SnapshotService`, `RewriteStateService` |
| **DISK** | persistence, backup, fingerprint source | `EditManager` (write), `ProjectLoader` (clone/unpack), `WorkspaceWatcher` (watching) |

---

## Package overview (`de.jare.rewisp` — split across the two modules, as of 2026-11-23)

**`src_rewisp` (rewisp-core, backend; no MCP SDK/Jackson 3 on the classpath):**

| Package | Contents |
|---------|----------|
| `.` (root) | `ProjectManager` (facade: load/reload/unload, access to the services), `ProjectLoader` (path/Git/archive), `BuildDetector`, `JdkClasspathResolver`, `VersionLoader`, `Warmup` |
| `api` | **Java interface to the backend:** `RewispDriver`, `DefaultRewispDriver`, `RewispException`, `RewriteEdit`, `EditPipeline`, `EditSupport` plus the operations interfaces (`ProjectOperations`, `SourceQueries`, `TypeQueries`, `TransactionOperations`, `*Operations` for the edits) |
| `project` | `ProjectRegistry` (loaded projects, lookup, dirty/expiry), `ProjectLock` |
| `analysis` | `SpoonModelService` (Spoon model **only from the rewrite state**, source roots, `ensureModel`/`validateEdit`), `ReferenceFinder`, `SignatureFormatter`, `TypeLookup`, `SpoonTempFiles` |
| `lombok` | `LombokDetector`, `LombokDelomboker`, `LombokMemberPredictor` |
| `build` | `MavenBuildResolver`, `GradleBuildResolver`, `BuildContextService` (cache), `BuildContext`/`ModuleContext`, `PomUtils`, `ResolveOptions`, diagnostic/error codes |
| `edit` | `EditManager` (write pipeline, implements `TransactionOverlay`), `TransactionState`, `TransactionCommitter`, `BackupService`, `AtomicFiles`, `FingerprintValidator`, `AutoImportResolver`, `TypeErasureChecker`, `LineEndings`, `BackupConfig`, `ClassSourceGenerator`, `SourceCodeUtils`, `DryRunPreview`, `StringLiteralScanner`, `ProjectClassLoader` |
| `rewrite` | `RewriteEngine`/`OpenRewriteEngineImpl`, `RecipeRegistry`/`YamlRecipeRegistry`, request/result types; `recipes/*` (10 recipes + `RecipeSupport`) |
| `snapshot` | `SnapshotService` (capture with race detection, refresh), `RewriteStateService` (effective rewrite state), `TransactionOverlay`, `SnapshotListener`, `ContentHasher`, `DirtyChecker`, `WorkspaceWatcher` (+`FileChangeListener`), `ChangeTracker`, `WorkspaceRegistry` (allowlist), `IgnoreRules`, `ProjectState`/`ProjectSnapshot` |
| `model` | `ProjectEntry` (+factory), `Fingerprint`, `ProjectLoadException`, `ErrorType` |
| `observability` | `Phases` (interface for phase timings; the MCP side registers there as listener) |
| `util` | `PathUtils`, `ProjectLayout` (constants), `JavaNames`, `ProjectBuildHelpers`, `ProjectCleanupHelper`, `ProcessRunner`, `SourceFiles`, `EofSignalingInputStream` |

**`src_mcp` (rewisp-mcp, MCP binding):**

| Package | Contents |
|---------|----------|
| `.` (root) | `JavaMcpServer` (start, tool registration of the 37 tools) |
| `tools.base` | `McpTool`, `BaseTool` (shared helpers, final call wrapper, holds the `RewispDriver` in the field `driver`), `BaseReadTool`, `BaseEditTool`, `BaseProjectTool`, `ToolCallGuard`, `UsageStats` |
| `tools.read` | analysis tools (SPOON): `inspect_*`, `list_classes`, `find_*`, `get_*`, `search_source` |
| `tools.edit` | the 13 edit tools (REWRITE) |
| `tools.txn` | `TransactionTools` (begin/commit/rollback/get_edit_summary/get_transaction_diff) |
| `tools.project` | lifecycle and admin tools (load, unload, reload, build refresh, metadata, dirty status, reboot, usage stats) |

Note: a package `validation/` and the classes `RewriteTransactionManager`, `PlanStore`, `CommitService` **no longer exist** (ADR-002). Also removed (as of 2026-11-23): the package `event` (event bus), `RewriteSyncManager`, `CollectionUtils`, `BuildFileDetector`, `RewriteTypeUtils`, `TypeInfo`.

---

## Tool inventory (37, registered by `JavaMcpServer.start()`)

| Category | Count | Base class | Tools |
|----------|-------|------------|-------|
| Project lifecycle | 9 | `BaseProjectTool` (8), `McpTool` (`load_java_project`) | `load_java_project`, `list_loaded_projects`, `unload_java_project`, `reload_java_project`, `project_metadata`, `project_refresh_build`, `check_project_dirty`, `reboot_spoon`, `get_usage_stats` |
| Analysis (SPOON) | 10 | `BaseReadTool` | `list_classes`, `inspect_class`, `inspect_method`, `inspect_field`, `get_file_content`, `get_element_position`, `search_source`, `find_references`, `find_implementations`, `get_type_hierarchy` |
| Edits (REWRITE) | 13 | `BaseEditTool` | `edit_line`, `rename_symbol`, `replace_method_body`, `add_method`, `add_field`, `remove_member`, `add_annotation`, `remove_annotation`, `edit_annotation`, `rewrite_signature`, `add_package`, `add_class`, `move_class` |
| Transactions | 5 | `BaseEditTool` | `begin_transaction`, `commit_transaction`, `rollback_transaction`, `get_edit_summary`, `get_transaction_diff` |

The previously present but never registered tools `find_unused_code`, `find_unreachable_code`, `find_deprecated_usages` were deleted (decision D1, see git history).

Since 2026-10-26 the lifecycle tools inherit from `BaseProjectTool` (WK-06): no Spoon model rebuild on entry lookup, no edit lock.

---

## Data flow of an edit (actual)

1. **Guard:** `ToolCallGuard` assigns the trace ID. `BaseEditTool` holds `EDIT_LOCK` (serializes only edit tools).
2. **Editable?** `ProjectEntry.editable`; not editable → domain error.
3. **Snapshot refresh** (`refreshSnapshotForEdit`): if the project is dirty, the affected files (narrow scope) or all files (broad scope, `captureStable` with double read) are re-read; inside a transaction the buffered contents are overlaid on top.
4. **Pre-edit analysis (SPOON):** `AutoImportResolver` (imports), `TypeErasureChecker`, duplicate/reference checks — **against the Spoon model**, which can be stale after edits (bugs #11, #16).
5. **Preview (REWRITE):** `OpenRewriteEngineImpl.preview` parses all scope files in one `parseInputs` call, runs the recipe, returns changed files (before/after). `dryRun=true` ends here.
6. **Write:** `EditManager.writeFile` — fingerprint validation, backup on the first edit of a file, line endings preserved, atomic write. With an open transaction only buffering happens; `commit_transaction` writes everything, `rollback_transaction` discards.
7. **Follow-up:** `ProjectEntry` is marked "dirty/edited"; `ProjectManager.ensureSpoonModel` rebuilds the Spoon model on the next read access from the rewrite state (snapshot + dirty files + transaction overlay) as soon as it is stale (Spoon only reads from the rewrite state).

### State and freshness

| State | Held in | Updated when |
|-------|---------|--------------|
| Disk | filesystem | always current |
| Snapshot (contents + hashes) | `ProjectState.snapshot()` | load, reload, **before** the next edit (not after → bug #15) |
| Spoon model | `ProjectEntry.model()` | load; afterwards on every `findEntry` of the read and edit tools via `ProjectManager.ensureSpoonModel`: rebuilt from the rewrite state (snapshot + dirty files + transaction overlay, as `VirtualFile`) when it changed since the last build |
| Transaction buffer | `TransactionState` | `writeFile` in a transaction |
| Dirty marking | `ProjectEntry.dirtyFiles/editedFiles` | after edit; `dirtyFiles` grows until reload |

---

## Target vs. actual — known deviations

These sections replace the earlier statements "Each layer only reads from the layer below" and "Spoon tools have no file access".

| Target (earlier docs) | Actual (review 2026-10-25) |
|-----------------------|-----------------------------|
| Spoon reads only via the REWRITE layer | the Spoon model is built directly from the filesystem (`ProjectBuildHelpers.createLauncher`, `MavenLauncher`). Only `get_file_content` and `check_project_dirty` read the snapshot. |
| Read tools do not access the filesystem | no read tool uses `Files.*` directly, but `check_project_dirty` calls `PomUtils.resolveParentPomPath` (reads `pom.xml`). |
| REWRITE validates results with Spoon | **implemented since D2 (lean):** `SpoonModelService.validateEdit` builds a candidate model before writing and rejects edits with `EDIT_BREAKS_COMPILATION` (except `edit_line`, RAW/noclasspath projects, already broken projects; `-Dedit.validate=false` disables). The earlier `SpoonEngine` layer was deleted (6.5). |
| All edits transactional, rollback leaves the disk untouched | largely: writing, deleting and `moveFile` are buffered (commit checks the disk against the baseline, creates target directories), rollback reads the affected files back into the snapshot. Exception: `createDirectories` (add_class/add_package/move_class) takes effect immediately and remains as an empty directory after rollback (AP-1.13). |
| Fingerprint lock on every edit | also applies after own writes: `EditManager.writeFile` and commit update the fingerprint; self-deleted/moved paths (`removedFiles`) are excepted. Multi-file edits check all fingerprints upfront (`BaseEditTool.writeAll`). Keys are canonical paths (`PathUtils.canonical`). |
| Event bus "prioritizing, non-blocking" | **package `event` removed (as of 2026-11-23):** no more bus; `SnapshotListener` in `snapshot` has no consumers in production. |
| Filesystem watcher delivers dirty information | **since D4 (2026-10-31) only build configuration:** the watcher no longer watches `src` trees and only reports `pom.xml`/`build.gradle*`/`settings.gradle*`/`gradle.properties`; source changes are detected by the snapshot-based dirty check (hashes). |
| `application.yaml` controls limits | since AP-5.18 the file only contains read keys: `workspace.*`, `rewrite.recipes` and `org.slf4j.simpleLogger.*`. The earlier placeholders (`maxChangedFiles`, `build.*`, `validation.*`, `spoon.*`) are removed. |

Detailed findings: [`docs/review/`](review/).

---

## Architecture decisions

### ADR-001: Spoon vs. OpenRewrite

**Status:** implemented · **Date:** 2026-10-02 (migration completed)

- **Spoon** only for reading/analysis. **OpenRewrite** for all edit operations. No Spoon fallback on write; if OpenRewrite fails, a structured error is returned.

- **Rationale:** Spoon is strong at AST analysis, OpenRewrite at preserving transformations (formatting/comments outside touched regions remain byte-exact).

Recipe approach per tool (checked against the code):

| Tool | Recipe approach |
|------|-----------------|
| `add_annotation` | JavaTemplate at the `addAnnotation` coordinate (import missing for FQN — bug RW:W-06) |
| `add_field` | `lastStatement` coordinate + `maybeAddImport` |
| `add_method` | `lastStatement` coordinate + constructor fix |
| `remove_member` | statement filtering (multi-field declarations) |
| `rename_symbol` | type attribution for invocations/field access (unqualified field accesses missing — bug RW:W-02) |
| `move_class` | `ChangeType` sub-recipe + own import rewriter |
| `replace_method_body` | `replaceBody` coordinate |
| `rewrite_signature` | `replaceParameters` + return-type tree (import missing — RW:W-07) |
| `edit_annotation` | `replaceAnnotations` (neighbors kept) |
| `remove_annotation` | annotation list filtering |
| `add_class` | **no recipe:** `ClassSourceGenerator` is a string generator; the parse check runs in the tool |
| `edit_line`, `add_package` | no recipe (direct text/directory edit) |

Engine properties: all scope files in **one** `parseInputs` call (cross-file type attribution, but most tools only pass the target file); MODIFY results at the before-path; `PathUtils` resolves Windows 8.3 short names; `YamlRecipeRegistry` reads `rewrite.recipes` (nested).

### ADR-002: One write path (plan-based apply path removed)

**Status:** implemented (branch `mistral_lab`) · **Date:** 2026-10-03

`rewrite_preview` → `rewrite_apply` (PlanStore, CommitService, RewriteTransactionManager, ValidationPipeline, TransactionJournal) was removed. All edits run directly: edit tool → OpenRewrite preview → `EditManager`. `dryRun=true` replaces preview/apply; `begin/commit/rollback` cover multi-file atomicity.

**Rationale:** two paths mean double maintenance; enabling the integration tests exposed five real bugs in the plan path (silent data loss through CWD write, ignored `commitTransaction` results, empty parameter replay, missing snapshot refresh).

### ADR-003: Two base classes instead of `BaseJavaTool`

**Status:** implemented · **Date:** 2026-10-01 (earlier listed as a second "ADR-002")

`BaseReadTool` (analysis) and `BaseEditTool` (edit); `BaseJavaTool` is removed. Limitation: `BaseEditTool` still contains ≈ 600 lines of unreferenced legacy code, and both base classes duplicate helpers (review ED:E-33, RD:R-29).

---

## Known limitations

### OpenRewrite auto-format in touched regions

OpenRewrite formats code that a recipe newly generates (JavaTemplate) in the parser's default style. Example: `edit_annotation` with `newAttributes="since=\"1.0\""` produces `@Deprecated(since = "1.0")` (spaces around `=`).

Scope (empirically 2026-10-02): only newly written snippets; unchanged regions remain byte-exact. In `rewrite-java` 8.90.4 there is no option to suppress formatting on the `JavaTemplate.Builder`. **Mitigation:** `dryRun=true` shows exactly what changes beforehand.

### Stale model after edits

The Spoon model is not incrementally updated after an edit. Analysis tools partly work with the old state until the next reload (bugs #15, #16; review RD:R-01, R-09, R-10). Workaround: after text edits, call `reload_java_project`.

### Only top-level types and method bodies in some analyses

`find_references`, `search_source`, `remove_member` (safe mode) and `rename_symbol` (caller counting) only search method bodies of top-level types — constructors, initializers, field initializers and nested types are missing. "No references found" is therefore **no** guarantee (review RD:R-11, ED:E-14).

### Character encoding

Source files are read/written strictly as UTF-8; other encodings lead to read errors or silent re-encoding (review ED:E-07).

---

## Module boundary (backend and MCP binding)

Since 2026-11-18 the project consists of two Maven modules under a parent POM:

| Module | Contents | Dependencies |
|--------|----------|--------------|
| `src_rewisp` (`rewisp-core`) | backend: read with Spoon, write with OpenRewrite, snapshot, build resolution, transactions | Spoon, OpenRewrite, JGit, commons-compress, snakeyaml, slf4j-api; **no MCP SDK, no Jackson 3** |
| `src_mcp` (`rewisp-mcp`) | `JavaMcpServer`, `tools/*` (37 tools), `ToolCallGuard`, `UsageStats`, fat JAR | `rewisp-core`, MCP SDK, Jackson 3, slf4j-simple |

Rules:

- The backend must not know the call layer. It reports phase timings via `observability.Phases` (an interface); `ToolCallGuard` registers there as a listener.

- The compiler enforces the boundary: `src_rewisp` does not have the MCP SDK on its classpath. `SourceHygieneTest.backendDoesNotReferenceTheMcpLayer` additionally checks imports for `tools.*`.

- **Java interface (`de.jare.rewisp.api.RewispDriver`)**: the backend can be used directly from Java without MCP and without stdio (`new DefaultRewispDriver(new ProjectManager())`). Results are records, domain errors are `RewispException` (`code()` + `context()`). The MCP tools parse arguments, call the driver (field `driver` in `BaseTool`) and render Markdown/JSON; `BaseTool.invoke` maps `RewispException` to the domain error. Status: all tools run via the driver (read, project, transactions, all edit tools; the rewrite template lives in the backend as `EditPipeline`). Only `get_usage_stats` remains on the MCP side. `BaseTool.handle` is a final wrapper, the tools implement `doHandle`.

- The remaining tool classes still contain flow logic (e.g. the `BaseEditTool` template, collision check on rename); it migrates step by step into the driver.

- Tests: backend tests and fixtures in `src_rewisp`; tool tests in `src_mcp`. Shared helpers (`testsupport/`) and the fixtures come via the backend's test-jar; `TestFixtures.RESOURCES` resolves the path via the property `rewisp.test.resources`.

- Build: `.\build.ps1 test` (reactor over both modules; checks JDK 21 and sets `MAVEN_ARGS` with `settings-central.xml` — see `build.ps1`), `.\build.ps1 -Jar` produces `build\rewisp-mcp\rewisp-standalone.jar`. Directly without the script: `mvn -B -s settings-central.xml -gs settings-central.xml test` resp. `mvn clean package -DskipTests`. The build output of the modules is not in `src*` but in the repo root under `build/<artifactId>` (`<directory>` in the module POMs; `build/` is gitignored).

---

## Directory structure

```
rewisp/
├── pom.xml                         # parent POM: Java 21, modules src_rewisp and src_mcp
├── build.ps1                       # build helper: .\build.ps1 test | -Jar (JDK 21 check, MAVEN_ARGS with settings-central.xml)
├── settings-central.xml            # local Maven settings against Maven Central (see comment in the file)
├── build/                          # Maven output of the modules, e.g. build/rewisp-mcp/rewisp-standalone.jar (gitignored)
├── src_rewisp/                     # backend (rewisp-core), NO MCP libraries
│   ├── pom.xml                     # Spoon 11.3.0, OpenRewrite 8.90.4, JGit, snakeyaml; produces a test-jar
│   ├── src/main/java/de/jare/rewisp/   # analysis, api, build, edit, lombok, model, observability, project, rewrite, snapshot, util
│   ├── src/main/resources/         # application.yaml, version.properties
│   └── src/test/                   # 478 backend tests (counted 2026-11-23), fixtures (src/test/resources), testsupport/ (shared test helpers)
├── src_mcp/                        # MCP binding (rewisp-mcp), uses src_rewisp
│   ├── pom.xml                     # MCP SDK 2.0.0, Jackson 3, builds the fat JAR (build/rewisp-mcp/rewisp-standalone.jar)
│   ├── src/main/java/de/jare/rewisp/   # JavaMcpServer, tools/ (base, edit, project, read, txn)
│   └── src/test/                   # 358 tool tests (use the backend's test-jar)
└── docs/
    ├── ARCHITEKTUR.md              # this document
    ├── API-REFERENCE.md            # tool reference
    ├── BUGS.md                     # open bugs
    ├── ROADMAP.md                  # planning
    ├── TESTING.md                  # test suite
    ├── OUTPUT-FORMATS.md           # output formats of the tools
    ├── PRAXISTEST.md               # live test protocol
    ├── REVIEW-PLAN.md              # work packages from the 2026-10-25 review
    ├── WARTBARKEIT-REVIEW.md       # maintainability review (WK-xx)
    ├── review/                     # detailed reports on the 2026-10-25 review (historical code state, see note in the files)
    └── CODE-CLEANUP-ANALYSIS.md    # historical (2026-10-01)
```

---

## See also

[README](../README.md) · [API reference](API-REFERENCE.md) · [Bugs](BUGS.md) · [Review plan](REVIEW-PLAN.md) · [Roadmap](ROADMAP.md) · [Tests](TESTING.md)
