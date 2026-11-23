# Roadmap & next steps

**Version:** 1.1.2
**Last updated:** 2026-11-23
**Status:** stabilization phase — review completed, implementation per [REVIEW-PLAN.md](REVIEW-PLAN.md)

---

## Current state

### Completed

| Group | Title | Result |
|-------|-------|--------|
| 7, 8 | (early groups) | completed |
| 9 | 3-layer architecture (`BaseReadTool`/`BaseEditTool`) | `BaseJavaTool` removed |
| 10 | Snapshot service: content hash and race detection | **already implemented** (previously listed as "planned"): `ContentHasher`, `SnapshotService.captureStable` (double read, `MAX_RETRIES=3`, race events), `refreshFiles`/`applyContents`, tests present. Only optimization (AP-7.2) and platform determinism (`rootHash`, AP-2.12) remain open. |
| — | ADR-002: one write path | plan-based apply path removed (35 tools) |
| — | Phase 8 "Observability/Workspace": `WorkspaceWatcher`, `ChangeTracker`, `WorkspaceRegistry`, `EditEventBus`, `MetricsCollector` | implemented, but partly without effect/tests (see REVIEW-PLAN phases 2, 6) |

### Review 2026-10-25

Static review of the entire code: ≈ 125 independent bugs, ≈ 50 dead-code findings, 35 inefficiencies, 43 concept points (incl. the `build/` package and test hygiene). Result: [REVIEW-PLAN.md](REVIEW-PLAN.md) with 149 work packages in 10 phases, detailed reports in [`review/`](review/).

---

## Next steps (in order)

| Priority | Phase | Goal |
|----------|-------|------|
| 1 | **0 Foundation** | build reproducible under JDK 21, unify Java target, create Git repo |
| 2 | **1 Edit consistency** | eliminate silent data loss in the edit/transaction path |
| 3 | **2 Snapshot/watcher** | one ignore rule, correct watcher, honest dirty detection |
| 4 | **3 Read tools** | same freshness, complete reference search, bugs #14–#16 |
| 5 | **4 Recipes** | close rename/signature/import gaps |
| 6 | **5–6** | clean up lifecycle/Lombok/build; remove dead code |
| 7 | **7 Performance** | measure first, then snapshot/parser caching, incremental Spoon update |

### Group 11: post-edit validation with Spoon (implemented, D2)

**Status:** implemented (2026-10-29) as a lean pre-write check: `SpoonModelService.validateEdit` builds a candidate model from the rewrite state plus the edit before writing and rejects edits that would make the project uncompilable with `EDIT_BREAKS_COMPILATION` (nothing is written). The validated model is adopted after the write (one build per edit, otherwise fallback to the regular rebuild). Not checked: `edit_line`, RAW/noclasspath projects (Spoon reports no type errors there), delomboked projects and already broken projects. The earlier `SpoonEngine` layer was deleted. Open: type errors in RAW projects would only be checkable with a real compiler (javac run).

---

## Technical improvements

### Analysis features

The tools `find_unused_code`, `find_unreachable_code`, `find_deprecated_usages` existed as code but were not registered, untested and buggy (among others an endless loop on cycles in the call graph). Recommendation (D1): delete and, if needed, rebuild with a correct call graph (constructors, overrides, method references, entry points). The earlier reference to "Java Lens equivalents" is obsolete.

### Code quality

| Improvement | Package |
|-------------|---------|
| template method for the 13-times copied edit flow | AP-8.1 |
| shared recipe helper class | AP-8.2 |
| uniform `IgnoreRules` | AP-2.1 |
| wire up or remove configuration | AP-5.18 |

---

## Success metrics

| Metric | Today | Goal |
|--------|-------|------|
| Registered tools | 37 | 37 |
| `@Test` methods (statically counted) | 567 | 650+ (every bug fix with regression test) |
| Open bugs (high priority) | see BUGS.md | 0 |
| Dead-code findings | ≈ 50 | 0 |
| Tests for `WorkspaceWatcher`/`ChangeTracker` | 0 | ≥ 10 |

---

## Changelog

### 2026-11-23
- Version set to **1.1.2** (root `pom.xml` + parent references in `src_rewisp`/`src_mcp`; `version.properties` follows via `${project.version}`).
- All Markdown docs translated to English (README + `docs/*`); Javadoc of both modules reviewed, set to English and AI-agent references generalized to "AI agent".
- `build.ps1` created: `.\build.ps1 test` (reactor test run over both modules), `.\build.ps1 -Jar` (fat JAR `src_mcp\target\rewisp-standalone.jar`); checks JDK 21, sets `MAVEN_ARGS` with `settings-central.xml` (Maven Central instead of a corporate mirror, also for resolver child processes). Doc references (README, ARCHITEKTUR, TESTING) switched back to `build.ps1`.
- Doc alignment after the module split: ARCHITEKTUR package overview split across `src_rewisp`/`src_mcp` (`api`/`observability` added, `event` and deleted classes removed), tool count 37, test counts 478/358; `docs/review/*` marked as historical status 2026-10-25; outdated root `target/` deleted.
- Maven build output moved out of the modules: `<directory>` in both module POMs points to `build/<artifactId>` in the repo root — `src_rewisp`/`src_mcp` stay clean without `target/`; fat JAR under `build/rewisp-mcp/rewisp-standalone.jar` (`build.ps1` and docs adjusted).

### 2026-11-18
- Two Maven modules under a parent POM: `src_rewisp` (rewisp-core, backend without MCP SDK) and `src_mcp` (rewisp-mcp, MCP binding). Backend directly usable from Java via `de.jare.rewisp.api.RewispDriver` (see ARCHITEKTUR, section "Module boundary").

### 2026-10-25
- Review of all packages; docs (ARCHITEKTUR, ROADMAP, API-REFERENCE, TESTING, BUGS, README) updated against the code; `CODE-CLEANUP-ANALYSIS.md` marked historical.

- ROADMAP: group 10 booked as implemented; group 11 re-evaluated.

### Version 2.0.0 (mistral_lab)
- ADR-002: one write path, 14 redundant read tools removed, 35 tools.

### Version 1.1.1-REWRITE
- Group 9: 3-layer migration, `BaseJavaTool` removed.

### Version 1.1.0
- Groups 7 and 8 completed.
