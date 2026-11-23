# Test suite and skipped tests

> **Status:** statically re-counted on 2026-10-25, recount on 2026-11-23 after the module split (code counted, **suite not executed** — Maven access to the corporate Artifactory returned HTTP 403, see [REVIEW-PLAN.md](REVIEW-PLAN.md) AP-0.1).
> Run-time and success figures of earlier runs are listed below as "historical".

---

## Running the tests

```bash
mvn test                        # full suite (parallel at class level)
mvn test -Dtest='ClassName'     # single test class
```

Prerequisite: **JDK 21** (the project compiles with `source/target 21`).

### Inventory (statically counted)

| Size | Value |
|------|-------|
| Test files with at least one test | 77 (2026-10-25) → **153** (2026-11-23: 82 in `src_rewisp`, 71 in `src_mcp`) |
| `@Test`/`@ParameterizedTest`/`@RepeatedTest` methods | 567 (2026-10-25) → **836** (2026-11-23: 478 + 358) |

**Baseline run 2026-10-25** (JDK 21, Maven Central): **567 tests, 0 failures, 0 errors, 13 skipped, BUILD SUCCESS** (approx. 1.5 min); after the first fixes **583**, after phase 1 and the architecture correction "Spoon reads only from Rewrite" (2026-10-26) **605 tests**, after the maintainability refactoring (`RewriteSyncManager` plus 9 tests removed) 596 tests, after the watcher tests (AP-2.7: `WorkspaceWatcherTest`, `ChangeTrackerTest`) 611 tests, after WK-10 (`SnapshotEventPublisherTest`) 613 tests, after the phase-0/1/2 packages (stdout contract, Java 21 parsing, ProjectLock, transaction directories, line endings, snapshot corrections, dirty state) 623 tests, after the phase-3 packages (load errors, alias rules, tolerant arguments) 633 tests, after phase 4 (recipes, engine, AutoImportResolver) and parts of phase 5 (Gradle resolver, stdin EOF, clone, Lombok, Ant) 807 tests, after the driver-API refactoring (`DefaultRewispDriverTest`) **836 tests** (478 in `src_rewisp`, 358 in `src_mcp`), 0 failures, 3 skipped, green. Invocation: `.\build.ps1 test` (checks JDK 21, sets `MAVEN_ARGS` with `settings-central.xml`); directly also `mvn -B -s settings-central.xml -gs settings-central.xml test` (`-gs` makes sure that even the `mvn` child processes started by the resolver bypass the corporate mirror — see comment in `settings-central.xml`).

The first run was red (6 failures `BUILD_FAILED: Maven exit code 1`). Causes: (1) child processes used the corporate mirror (403) — fixed via `MAVEN_ARGS`; (2) the resolver runs offline (`-o`), and the fixture dependencies (e.g. `junit:4.13.2` in `fixtures/maven-single-module`) were missing from the local repo — fixed by a one-time `mvn dependency:resolve` per fixture. The suite therefore **depends on the state of `~/.m2`** (see AP-9.6). `fixtures/maven-complex-multi` also cannot be resolved online (exit 1) without any test failing on it (presumably skipped). On errors `MavenBuildResolver` now appends the last 2000 characters of the Maven output to the exception.

Historical (as of 2026-10-03, branch `mistral_lab`, last known run): 550 tests, 0 failures, 13 skipped, 1:35 min. Since then 17 tests were added; a new baseline run is pending (AP-0.1).

### Parallel execution and timeouts

Configured in `src_rewisp/src/test/resources/junit-platform.properties` (also applies to `src_mcp` via the backend's test-jar):

- **Per-test timeout 120 s** (global; overridable per `@Timeout`).
- **Classes in parallel** (fixed pool, 6 threads), methods of a class sequential (`same_thread`).
- **`@Isolated`** (run alone; as of 2026-11-23): `ConfigFlattenTest`, `PerformanceSmokeTest`, `ProjectEvictionTest`, `ProjectManagerExpiryTest`, `RaceConditionIntegrationTest`. Mark new classes with singleton access or timing dependencies as well.

Rationale against method parallelism: tests share instance fields and `@BeforeEach` state.

---

## Skipped tests (according to the code)

### Deliberately disabled — 11 tests

| Test class | Tests | Reason |
|------------|-------|--------|
| `build/GradleBuildResolverIntegrationTest` | 7 | `@Disabled` at class level: "needs Gradle wrapper in the test environment" |
| `integration/GradleMultiProjectIntegrationTest` | 4 | `@Disabled` at class level, same reason |

Earlier docs wrongly listed the 7 tests of the first class as "assumption" skips.

### Environment-dependent (`assumeTrue`) — count depends on the environment

| Test class | `assumeTrue` sites | skips when … |
|------------|--------------------|--------------|
| `LombokDelombokerTest` | 9 (7 tests) | Lombok JAR not resolvable from cache/network |
| `build/BuildResolverIntegrationTest` | 5 | `mvn` not available |
| `build/MavenBuildResolverTest` | 3 | `mvn` not available |
| `build/GradleBuildResolverIntegrationTest` | 3 | (only relevant if `@Disabled` is removed) |

The `mvn`-dependent skips were missing in the earlier docs. In an environment without `mvn` or without network/Artifactory access these tests skip **without** turning the build red — a green run may therefore not have covered resolver paths.

---

## Known quality problems of the suite

Details: [`review/BT-build-tests.md`](review/BT-build-tests.md), packages AP-9.x in the review plan.

| Problem | Evidence | Package |
|---------|----------|---------|
| **Tests that mask errors:** Gradle tests with `catch … // Expected`, `assertTrue(true)`, enum-vs-string comparison (always false) — the Gradle resolver bug B-01 stays undetected because of this | `GradleBuildResolverTest`, `GradleBuildResolverIntegrationTest` | AP-9.6 |
| 7 tests consist only of `assertTrue(true, "…")` | `RecipeSecurityTest` | AP-9.6 |
| `changeReturnTypeOnly` changes the return type of a method without parameters and body `return "test"` and counts that as success; masks RW:W-01 (deletes all parameters) | `RewriteSignatureToolTest:53-66` | AP-9.6, 4.1 |
| ~~`WorkspaceWatcher` and `ChangeTracker` without any test~~ — covered since 2026-10-26 (15 tests) | `src/test/.../snapshot` | AP-2.7 ✅ |
| ~~`edit_line` never tested in a transaction~~ — covered since 2026-10-25 (`twoLineEditsInTransaction_bothSurviveCommit`, `TransactionRollbackSnapshotTest`); still open: rollback with multi-file edits and watcher interaction | `EditLineToolTest`, `TransactionRollbackSnapshotTest` | AP-9.7 |
| Tests only for production code without callers in `src/main` (`SpoonEngineImpl`, `SpoonAnalysisCache`, `EffectiveSourceOverlay` (deleted with 6.5), `MetricsCollector`, `EditEventBus` listener, `checkDirtyFullScan`) — these classes no longer exist (as of 2026-11-23) | see review | AP-9.8 |
| ~~Resolver tests work in the source tree and produce checked-in `target/` directories (7 of them) plus `spoon.classpath-app.tmp`~~ — done (AP-9.3: fixtures copied via `TestFixtures.copy` to `@TempDir`; as of 2026-11-23 no `target/` or `.tmp` leftovers in `src_rewisp/src/test/resources`) | `src/test/resources/**` | AP-9.3, 0.4 ✅ |
| ~~Duplicate fixtures and unreferenced fixture directories~~ done 2026-10-31 (AP-9.3): the duplicates now only exist under `fixtures/`, five unreferenced fixtures deleted, Maven/Gradle resolver tests copy via `TestFixtures.copy` into `@TempDir` | `src/test/resources` | AP-9.3 |
| `testModuleStructureDetected` and `testPomXmlChangeInvalidatesBuildContext` check too weakly (`>= 1`, reload instead of invalidation) | `MavenMultiModuleIntegrationTest` | AP-9.6 |

---

## Removed compared to earlier docs

These sections described classes that no longer exist (ADR-002): `PlanStore`/`PlanStoreTest`, `PlanTtlIntegrationTest`, `CommitServiceImpl`, `ConcurrencyIntegrationTest`. The related "earlier gaps" explanations (silent data loss in the plan apply path) are summarized in ADR-002 in [ARCHITEKTUR.md](ARCHITEKTUR.md).

---

## Next steps

1. **Baseline run** under JDK 21 with working Artifactory access; enter the numbers here (AP-0.1, AP-9.2).
2. Fake `gradle`/`mvn` scripts in `@TempDir`, so resolver tests run without network and without a wrapper and the 11 `@Disabled` tests can be enabled (AP-9.6).
3. Every bug fix from the review plan gets a regression test that is red before the fix.

---

**Author:** Janusch Rentenatus
