# Field test — findings

First field test (TicTacToe example, copy in the scratchpad). Every tool statement was verified against the disk,
`javac` or text search.

| # | Finding | Status |
|---|---------|--------|
| P1 | `edit_line` wrote files outside the project using an absolute path | fixed: `SECURITY_ERROR` (`EditLineContainmentTest`) |
| P2 | `add_field` wrote invalid types/names/modifiers (`Listlt;;Stringgt;;`) | fixed: syntactic validation, `INVALID_PARAMS` (`JavaNamesTypeTest`, `AddFieldInputValidationTest`) |
| P3 | `find_references` listed 58 position-less type hits, total about three times too high | fixed: synthetic expression types removed, `I[]` uses the array type's position (`PracticeFindingsTest`) |
| P4 | Banner "Model is dirty ... Data may be stale" after every edit, although read tools see the rewrite state | fixed: banner removed; fingerprint errors name the current value and point to `get_file_content` instead of reload |
| P5 | `rename_symbol`: "Callers updated: 2" counted the declaration | fixed: `Declarations renamed` and `Callers updated` separated |
| P6 | Spoon left one copy per source file in `%TEMP%` on every model rebuild (until JVM end) | fixed: `SpoonTempFiles` deletes the newly created copies (`SpoonTempFilesTest`) |

Side finding: `LineEndingsNewFileTest.withoutNeighbors_defaultsToLf` depended on foreign `.java` files in the temp directory; now hermetic.

Verified live 2026-10-31: the default expiry date was 10 minutes and expired in the middle of a session; expired projects keep working (edits function), the Spoon model is freed after 5 minutes idle and rebuilt in about 8 s on access. Default therefore raised to 60 minutes (`-Dproject.defaultExpiryMinutes`), warning text adjusted. Earlier behaviour: afterwards the hint "Expired projects" appears; after 5 minutes grace the Spoon model is freed and rebuilt on access.

Not yet tested: `move_class`, `rewrite_signature`, `replace_method_body`, `remove_member`, `add_method`,
commit error cases (external change during a transaction).

## Second round (broad): add_method, replace_method_body, remove_member, rewrite_signature, move_class, annotations, transactions

Reliable (verified against disk/javac): safe/hard mode of `remove_member` (references correct, rollback restores everything),
`move_class` (file, imports, javac 0, CRLF), annotation duplicates/errors, commit conflict on external change
(nothing written, transaction stays open), fingerprint protection after external change, reload, hierarchy/implementations/positions.

| # | Finding | Severity |
|---|---------|----------|
| Q1 | `add_method` `parameters`/`body`/`returnType` accept arbitrary text: injection creates foreign methods (e.g. with `System.exit`) | high |
| Q2 | Dry run shows only file names, no diff | low - **fixed**: shared `DryRunPreview` shows diff with context (`DryRunDiffTest`) |
| Q3 | `add_method` with non-void return type without body creates `int count() { }` (does not compile) | medium |
| Q4 | `replace_method_body`/`add_annotation attributes`: excess input is silently truncated, success reported | high |
| Q5 | `remove_member` of an interface method does not mention `@Override` implementations | low - **fixed**: overriding methods count as references (safe rejects, hard warns; `RemoveMemberOverrideTest`) |
| Q6 | Owner specification `AbstractStone.com.example.AbstractStone(char,...)` doubly qualified | cosmetic |
| Q7 | `rewrite_signature` removes the indentation of the call line | low |
| Q8 | `rewrite_signature newParameters="int"` writes `/*~~(...)~~>*/` error markers into the source text, reports success; the message names an injection that is not in the code at all | high |
| Q9 | `move_class`: "Imports updated: 5" / dry run "6 files", actually 2 changed | low |
| Q10 | `get_edit_summary` lists rolled-back edits as applied | medium |
| Q11 | `check_project_dirty` reports "No external changes" for an externally changed file previously edited by rewisp | high |
| Q12 | ditto: external deletion of an edited file is not reported | high |
| Q13 | If the Spoon rebuild fails (non-compilable source), read tools silently answer with the old model | high - fixed (warning "Stale structure model" in every response) |
| Q14 | check_project_dirty reported files deleted/moved by rewisp itself as externally deleted | medium - fixed |

Tests: PracticeRound2Test (10), JavaNamesSnippetTest, ErrorMarkerGuardTest; without the fixes 9 resp. all of them were red (verified via stash).

## Third round (fresh server, sandboxes `mastermind`, `battleship`)

Live against the new server (all fixes up to 5.27): injections in `add_method`/`rewrite_signature`/`replace_method_body`/`add_annotation` are rejected, Q2 (dry-run diff) and Q5 (overriders) work, transaction rollback/commit leave the disk untouched before commit; after commit: no BOM, UTF-8 umlauts and CRLF preserved, `javac` green.

| No. | Finding | Severity |
|------|---------|----------|
| R1 | `replace_method_body` writes a semantically wrong body (`return "text";` in an `int` method) and reports success; only the stale warning on the next request shows the error. Only syntax and type names are checked upfront | medium - **fixed with D2**: `validateEdit` builds a candidate model before writing and rejects new compile errors (`EDIT_BREAKS_COMPILATION`); `EditCompileValidationTest` |
| R2 | Stale warning stayed after repair when the source again exactly matched the last built state (error status was only cleared on rebuild) | medium - fixed (`PracticeRound2Test.staleWarningClearsWhenSourceReturnsToLastBuiltContent`) |
| R3 | Stale warning of one project appeared in every response, also for requests about other projects | low - **fixed**: warning only for the project of the call (`ToolCallGuard.noteProject`) |
| R4 | `add_field`/`add_method` do not resolve JDK types (`List`, `ArrayList`) themselves, `imports` is needed; the error message only names the FQN | low - **fixed**: message names `imports` and suggests JDK types from common packages (`AutoImportResolver.importHint`) |

## Fourth round (2026-10-31, broad live test)

Fresh copies of tictactoe-sample (6 classes, interface, abstract class) and mastermind-sample against the newly built server; read tools cross-checked against the disk (45 hits, 19 references match), all edit tools individually, transaction with rollback and commit (CRLF, no BOM, `javac exit=0`), external change and fingerprint protection, error paths (unknown class, injection in the field name, path in the class name).

| No. | Finding | Severity | Status |
|------|---------|----------|--------|
| V1 | `remove_member mode=hard` was rejected by D2 although the error message names exactly this mode as the escape hatch | medium | fixed: hard mode skips the check |
| V2 | `add_class` created package-visible types (`class X`) with no way to choose `public`; after `move_class` to another package, callers would break | medium | fixed: default `public`, `visibility=package` optional |
| V3 | After `move_class` the empty old package directory remains | low | fixed: empty package directories are removed (only outside transactions) |
| V4 | `rewrite_signature` (both modes) was rejected by D2 as soon as callers no longer fit; but the mode is intended for staged refactorings | medium | fixed: `allowBreakingChanges=true`, the rejection names the parameter |
| V5 | `rename_symbol scope=class` was impossible for public classes: D2 checked `XStone.java` with `public class CrossStone` | high | fixed: the check simulates the file rename |
| V6 | `project_refresh_build` fails offline (`maven-help-plugin` not local) without hinting at the escape hatch | low | fixed: errors of an offline run name `-Dbuild.networkAccess=true` |
| V7 | `add_method` on an unknown class reports `DOMAIN_ERROR` instead of `TYPE_NOT_FOUND` | low | fixed: `TypeNotFoundException` is reported as `TYPE_NOT_FOUND` with `className` |
| V8 | `rename_symbol scope=class` did not rename the class's constructors (`public XStone()` stayed): the compile check reported "Return type for the method is missing"; without D2 broken code would have been written. Discovered during the live test of the V5 fix | high | fixed: `RenameSymbolRecipe` renames constructors too (test red/green with mutation) |
| V9 | `rename_symbol scope=class` reported `Callers updated: 0` although usages were changed | low | fixed: counting over all type references |
| V10 | `get_usage_stats` did not list `load_java_project` (different tool base, no `BaseTool`) | low | fixed: `UsageStats.instrument` wraps the tool |
| V11 | The first edit after load took 21.7 s, of which only 0.5 s OpenRewrite: the failing Maven run for the build context (offline, `maven-help-plugin` missing, JVM start with `_JAVA_OPTIONS=-Xms4096m`) was in no phase; the negative cache was fixed at 60 s, so every edit after a minute's pause paid another ~20 s | medium | fixed: phase `build-context` in the log, backoff doubles per failure (1, 2, 4 ... at most 30 min) and restarts on build file changes |
