# API reference — rewisp

**Version:** 1.1.2
**Last updated:** 2026-11-23 (tool inventory 37 checked against `JavaMcpServer.start()`, unchanged; previously checked against the code on 2026-10-25, deviations at the end)
**Tools:** 37 MCP tools

---

## Design philosophy (mistral_lab)

This branch is the lab version of the server, built from the perspective
of an AI agent working with the server:

- **One write path.** All edits go through OpenRewrite preview →
  EditManager (fingerprint lock, backup, atomic writes,
  line-ending preservation). The old plan-based apply path
  (`rewrite_preview`/`rewrite_apply`/`rewrite_validate`,
  PlanStore/CommitService with TTL) is removed — `dryRun=true` on
  every edit tool replaces it.

- **Spoon only reads.** Analysis is strictly read-only; edits are
  exclusively OpenRewrite.

- **Few, powerful tools.** Any individual question that
  `inspect_class`/`search_source`/`find_references` already
  answer no longer has its own tool.

- **Transactions everywhere.** `begin/commit/rollback` with
  delete-at-commit semantics; `dryRun` on every edit tool.

---

## Project lifecycle (9 tools)

| Tool | Description |
|------|-------------|
| `load_java_project` | load project (path, Git URL, archive); auto-delombok |
| `list_loaded_projects` | list loaded projects |
| `unload_java_project` | unload project |
| `reload_java_project` | reload project (after external changes) |
| `project_metadata` | counters, build type, directory (no module list) |
| `project_refresh_build` | re-resolve the build context (classpath) |
| `check_project_dirty` | check whether the Spoon model is stale since the last edit |
| `reboot_spoon` | hard-reset the Spoon state |
| `get_usage_stats` | usage statistics since server start: per tool calls, errors, run time, flat token estimate (approx. 4 characters per token); number of projects and files; effective timeouts (project lifetime, grace, build resolution) |

## SPOON analysis, read-only (10 tools)

| Tool | Description |
|------|-------------|
| `list_classes` | all top-level classes (package filter exact, without subpackages; inner classes are not listed) |
| `inspect_class` | class detail incl. methods, fields, annotations |
| `inspect_method` | method detail (signature, parameters, body, annotations) |
| `inspect_field` | field detail |
| `get_file_content` | file content from the snapshot (without line numbers and fingerprint) |
| `get_element_position` | exact position (file, line, column) of an element |
| `search_source` | text search in the source |
| `find_references` | all references to a symbol (class/method/field) |
| `find_implementations` | implementations of an interface |
| `get_type_hierarchy` | inheritance hierarchy of a class |

## REWRITE edits (13 tools)

All edit tools except `add_package` accept `dryRun` (optional, boolean): preview only,
nothing is written.

| Tool | Description |
|------|-------------|
| `edit_line` | replace a single line (expects fingerprint) |
| `rename_symbol` | rename class/method/field; optionally all callers |
| `replace_method_body` | replace the method body |
| `add_method` | add a method to a class |
| `add_field` | add a field to a class |
| `remove_member` | remove a method/field |
| `add_annotation` | add an annotation |
| `remove_annotation` | remove an annotation |
| `edit_annotation` | change annotation attributes |
| `rewrite_signature` | change a method signature (declaration only or incl. callers) |
| `add_package` | create a package directory (optional package-info.java); **no** `dryRun` |
| `add_class` | create a new class/interface/enum |
| `move_class` | move a class to another package (imports updated) |

## Transactions (5 tools)

| Tool | Description |
|------|-------------|
| `begin_transaction` | buffer edits instead of writing |
| `commit_transaction` | write everything atomically (incl. buffered deletions) |
| `rollback_transaction` | discard buffered changes and read the snapshot of the affected files back from disk (exception: already created directories remain, AP-1.13) |
| `get_edit_summary` | edit log of the running session |
| `get_transaction_diff` | diff between the frozen state (contents at the first access of the transaction) and the running transaction state; `statOnly` for a file overview; writes nothing |

---

## Statistics

| Category | Count |
|----------|-------|
| Project lifecycle | 9 |
| SPOON analysis (read-only) | 10 |
| REWRITE edits | 13 |
| Transactions | 5 |
| **Total** | **37** |

Compared to 53 tools in `feature/rewrite`: removed were the
plan-based apply path (4 tools + PlanStore/CommitService/RewriteTransactionManager/
ValidationPipeline/TransactionJournal) and 14 redundant
single-purpose read tools.

---

## Known deviations (review 2026-10-25)

- This file contains no parameter schemas; the `inputSchema` in the tool classes is authoritative.
- `reload_java_project`, `unload_java_project`, `reboot_spoon`, `check_project_dirty` change manager state even though their description says `[SPOON]`.
- `find_references`, `search_source` and the caller counting of the edit tools only cover method bodies of top-level types; "no references" is no guarantee.
- The earlier, never registered tools `find_unused_code`, `find_unreachable_code`, `find_deprecated_usages` were deleted (decision D1).
- Details and work packages: [REVIEW-PLAN.md](REVIEW-PLAN.md).

---

## See also

- [ARCHITEKTUR.md](ARCHITEKTUR.md) — layer model and ADRs
- [TESTING.md](TESTING.md) — suite, parallelism, timeouts, skips
