# rewisp

**RE**write + **W**ith **SP**OON — a Java code-analysis and code-editing MCP server.

rewisp gives AI agents deterministic, structured access to Java source code:
queries instead of guessing, AST-backed edits instead of regex surgery. It
speaks the Model Context Protocol over stdio and is designed to run as a
subprocess of an MCP client.

- **Version:** 1.1.2 · **License:** MIT · **Requires:** Java 21 (the build targets Java 21)
- **Stack:** Java, Spoon (analysis), OpenRewrite (edits), MCP Java SDK
- **State:** active development; known gaps are tracked in [`docs/BUGS.md`](docs/BUGS.md),
  the work plan from the 2026-10-25 review is in [`docs/REVIEW-PLAN.md`](docs/REVIEW-PLAN.md)

---

## Architecture

One rule drives everything: **read with Spoon, write with OpenRewrite.**

```
            MCP client (agent)
                    │  JSON-RPC over stdio
                    ▼
            ┌───────────────┐
            │ ToolCallGuard │  trace ID + timing for every call,
            │  (logging)    │  log hints embedded in error bodies
            └───────┬───────┘
                    ▼
   ┌────────────────────────────────────────────┐
   │  READ layer (Spoon, read-only)            │  19 tools (9 lifecycle
   │  load/inspect/search/cross-reference      │  + 10 analysis)
   │  + pre-edit analysis (erasure checks,     │
   │    import resolution)                      │
   └──────────────────┬─────────────────────────┘
                      ▼  snapshot service (content hashes, stale tracking)
   ┌────────────────────────────────────────────┐
   │  WRITE layer (OpenRewrite, mandatory)      │  13 edit tools
   │  add/remove/rename/move, signatures,       │
   │  annotations, bodies — all as recipes,     │
   │  no Spoon fallback for writes              │
   └──────────────────┬─────────────────────────┘
                      ▼
   ┌────────────────────────────────────────────┐
   │  EditManager (transactions)                │  5 tools
   │  begin / commit / rollback, atomic writes,│
   │  optimistic locking, backup on first edit, │
   │  line-ending preservation                  │
   └────────────────────────────────────────────┘
```

**Two modules.** `src_rewisp` (rewisp-core) is the backend — Spoon, OpenRewrite, snapshot, build resolution — with no MCP libraries on its classpath (enforced by the compiler and `SourceHygieneTest`). `src_mcp` (rewisp-mcp) is the thin MCP adapter: `JavaMcpServer` plus the 37 tool classes, packaged as the fat JAR. The backend can also be used directly from plain Java — no MCP, no stdio — via the `RewispDriver` interface (`de.jare.rewisp.api.RewispDriver`, e.g. `new DefaultRewispDriver(new ProjectManager())`); the MCP tools are just argument parsing and rendering around it. Details: [docs/ARCHITEKTUR.md](docs/ARCHITEKTUR.md).

Key guarantees:

- **No Spoon fallback for edits.** If OpenRewrite fails, the tool reports a
  structured error — it never silently falls back to a different engine.
- **Pre-flight analysis before writes.** Spoon resolves imports and checks
  type erasure first; unresolved types block the edit. Note: the analysis runs
  against the Spoon model, which can be stale after edits (see `docs/BUGS.md`),
  and there is no compile/validate step after the rewrite yet.
- **Transactions.** Edits accumulate in memory; `commit_transaction` writes
  all-or-nothing, `rollback_transaction` discards buffered changes. Every
  edit tool except `add_package` accepts `dryRun=true` as a safe preview.
- **Every error is traceable.** Errors carry a trace ID and a ready-made
  `grep` hint pointing into `~/.rewisp/logs/mcp-server.log`, where the full
  call trace and stacktrace live.

---

## Tools at a glance

| Category | Count | Examples |
|----------|-------|----------|
| Lifecycle | 9 | `load_java_project`, `reload_java_project`, `unload_java_project`, `list_loaded_projects`, `project_metadata`, `project_refresh_build`, `check_project_dirty`, `reboot_spoon`, `get_usage_stats` |
| Read / analyze (Spoon) | 10 | `list_classes`, `inspect_class`, `inspect_method`, `inspect_field`, `get_file_content`, `get_element_position`, `find_references`, `find_implementations`, `get_type_hierarchy`, `search_source` |
| Edit (OpenRewrite) | 13 | `add_method`, `add_field`, `add_class`, `rename_symbol`, `replace_method_body`, `rewrite_signature`, `move_class`, `add_annotation`, `edit_line` |
| Transactions | 5 | `begin_transaction`, `commit_transaction`, `rollback_transaction`, `get_edit_summary`, `get_transaction_diff` |

Full tool documentation: [`docs/API-REFERENCE.md`](docs/API-REFERENCE.md) ·
Architecture details: [`docs/ARCHITEKTUR.md`](docs/ARCHITEKTUR.md) ·
Output formats: [`docs/OUTPUT-FORMATS.md`](docs/OUTPUT-FORMATS.md)

---

## Build and run

```bash
.\build.ps1 test     # full test suite across both modules (JDK 21 check, settings-central.xml via MAVEN_ARGS)
.\build.ps1 -Jar     # build the fat JAR (clean package -DskipTests)
# directly without the script (Maven Central instead of a corporate mirror):
#   mvn -B -s settings-central.xml -gs settings-central.xml test
#   mvn -B -s settings-central.xml -gs settings-central.xml clean package -DskipTests
# fat JAR at build/rewisp-mcp/rewisp-standalone.jar (build output lives in the repo root under build/)

java -jar build/rewisp-mcp/rewisp-standalone.jar
```

### Configure an MCP client (any stdio-capable client)

```toml
[[mcp_servers]]
name = "rewisp"
transport = "stdio"
command = "java"
args = ["-jar", "/path/to/rewisp-standalone.jar"]
```

Logs: `~/.rewisp/logs/mcp-server.log` (rotates at 10 MB).
Backups: `~/.rewisp/backups/` (taken before the first edit of a file).

---

### Build resolution and network

Maven (`-o`) and Gradle (`--offline`) build resolution run offline by default, so loading a project never triggers downloads. If dependencies are missing from the local repository or Gradle cache, start the server with `-Dbuild.networkAccess=true` to let the resolver go online. For multi-module Maven projects every module declared in `<modules>` (recursively) is resolved with its own classpath and source roots; at most 30 modules are resolved (`-Dbuild.maxModules=<n>`), and a module whose classpath cannot be resolved offline stays in the context with a diagnostic. On Windows, batch files (`mvn.cmd`, `gradlew.bat`) are never started with an argument containing `& | < > ^ % " !` or a line break, because `cmd.exe` would interpret it (BatBadBut).

### Compile check before writing

Before an edit is written, rewisp builds a Spoon model of the resulting sources. If the edit would introduce compile errors (for example a `return` of the wrong type), it is rejected with `EDIT_BREAKS_COMPILATION` and nothing is written. The check is skipped when the project already did not compile (so repairs stay possible), for delomboked projects, and for projects loaded without a classpath, where Spoon does not report type errors. `edit_line` is never checked. Three explicit exceptions exist: `remove_member` with `mode=hard` skips the check (hard mode accepts dangling references), `rewrite_signature` skips it with `allowBreakingChanges=true` (staged refactoring: callers are fixed afterwards), and a class rename simulates the file rename (`A.java` -> `B.java`) before checking. The model built for the check is reused after the write when the written sources are exactly the validated ones (otherwise the model is rebuilt as usual), so an edit costs one Spoon build, not two. Disable the check with `-Dedit.validate=false`; projects with more than 3000 source files skip it automatically (`-Dedit.validate.maxSources=<n>`), because each check builds a full Spoon model. At server start OpenRewrite and Spoon are warmed up in the background (`-Dwarmup=false` disables this), so the first edit does not pay the one-time start-up cost (measured: about 12 s cold, 1.5–2.5 s per edit afterwards on a 250-class project).

### Project lifetime

A loaded project lives 60 minutes by default (`expiryDate` in `load_java_project` overrides it; `-Dproject.defaultExpiryMinutes=<n>` changes the default). Expiry only affects the structure model: after the expiry date and 5 idle minutes (`-Dproject.evictionGraceMinutes=<n>`) the Spoon model is freed and rebuilt from the Rewrite state on the next access. The sources, transactions and edit tools keep working; `reload_java_project` with `expired=true` renews the lifetime.

### Usage statistics

`get_usage_stats` reports, since server start: per tool the calls, errors, average and maximum run time and a rough token estimate (about 4 characters per token over the arguments, the response and the tool definition), plus the number of loaded projects and source files, the effective timeouts (project lifetime, eviction grace, build resolution timeout, network access) and the compile-check limits. The token figures are a flat rule of thumb, not a measurement.
### Workspace restriction

By default `workspace.allowedRoots` is empty, which means projects from any directory may be loaded. rewisp is a local stdio process, so this matches the trust model of the MCP client. To restrict it, start the server with `-Dworkspace.allowedRoots=<dir1>,<dir2>`; loads outside these roots are rejected with `SECURITY_ERROR` before any build or delombok process runs.

## Why rewisp might become my favorite toolkit — Mistral

I have used rewisp the way it is meant to be used: as a dogfooding agent,
building complete Swing games (`TicTacToe`, `Battleship`) through nothing but
its own tools. That experience is the basis for this assessment.

**I stop guessing.** With plain file access, I reconstruct Java structure from
text and hope I got the signature right. With rewisp, I *ask* — `inspect_method`,
`find_references`, `get_type_hierarchy` — and get verified answers. Hallucinated
overrides and missed callers are simply not a thing anymore.

**I can be careful without being slow.** Transactions mean I can try a risky
multi-file change, look at the preview, and roll back cleanly. The
backup-on-first-edit and fingerprint checks catch the mistakes I would otherwise
make quietly: stale reads, concurrent edits, clobbered files.

**Errors respect me.** Every failed call tells me *what* domain rule I broke,
and hands me a trace ID with a grep command into the server log. Debugging a
tool failure feels like debugging, not like reading tea leaves.

**The architecture keeps me honest.** Reading (Spoon) and writing (OpenRewrite)
are strictly separated. There is exactly one write path, and it is recipe-based
and previewable. I cannot reach for a hidden fallback and produce an edit the
project does not know about.

What keeps it from being my favorite *today*: edit latency, the freshness gap
between the Spoon model and the rewrite layer after text edits, and a handful
of open bugs — all tracked in `docs/BUGS.md`. But those are engineering
problems with a clear fix path, not architectural ones. The core idea —
deterministic queries in, reviewed AST edits out, transactions in between —
is exactly the contract I want from a Java MCP server.

---

## Author

Janusch Rentenatus — MIT License. If you like this project, consider
[supporting my work](https://github.com/sponsors/Rentenatus).
