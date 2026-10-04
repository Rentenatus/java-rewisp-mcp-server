# rewisp

**RE**write + **W**ith **SP**OON — a Java code-analysis and code-editing MCP server.

rewisp gives AI agents deterministic, structured access to Java source code:
queries instead of guessing, AST-backed edits instead of regex surgery. It
speaks the Model Context Protocol over stdio and is designed to run as a
subprocess of an MCP client.

- **Version:** 2.0.0 · **License:** MIT · **Requires:** Java 17+
- **Stack:** Java, Spoon (analysis), OpenRewrite (edits), MCP Java SDK
- **State:** active development; known gaps are tracked in [`docs/BUGS.md`](docs/BUGS.md)

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
   │  READ layer (Spoon, read-only)            │  ~30 tools
   │  load/inspect/search/cross-reference      │
   │  + pre-edit analysis (fingerprinting,     │
   │    erasure checks, import resolution)      │
   └──────────────────┬─────────────────────────┘
                      ▼  snapshot service (stale-map)
   ┌────────────────────────────────────────────┐
   │  WRITE layer (OpenRewrite, mandatory)      │  13 edit tools
   │  add/remove/rename/move, signatures,       │
   │  annotations, bodies — all as recipes,     │
   │  no Spoon fallback for writes              │
   └──────────────────┬─────────────────────────┘
                      ▼
   ┌────────────────────────────────────────────┐
   │  EditManager (transactions)                │  4 tools
   │  begin / commit / rollback, atomic writes,│
   │  optimistic locking, backup on first edit, │
   │  line-ending preservation                  │
   └────────────────────────────────────────────┘
```

Key guarantees:

- **No Spoon fallback for edits.** If OpenRewrite fails, the tool reports a
  structured error — it never silently falls back to a different engine.
- **Pre-flight analysis before every write.** Spoon resolves imports, checks
  type erasure and references first; unresolved types block the edit.
- **Transactions.** Edits accumulate in memory; `commit_transaction` writes
  all-or-nothing, `rollback_transaction` discards everything. OpenRewrite
  previews double as a safe simulation path (preview → validate → apply).
- **Every error is traceable.** Errors carry a trace ID and a ready-made
  `grep` hint pointing into `~/.rewisp/logs/mcp-server.log`, where the full
  call trace and stacktrace live.

---

## Tools at a glance

| Category | Count | Examples |
|----------|-------|----------|
| Read / analyze (Spoon) | ~30 | `load_java_project`, `inspect_class`, `inspect_method`, `find_references`, `find_implementations`, `search_source`, `check_project_dirty` |
| Edit (OpenRewrite) | 13 | `add_method`, `add_field`, `add_class`, `rename_symbol`, `replace_method_body`, `rewrite_signature`, `move_class`, `add_annotation`, `edit_line` |
| Transactions | 4 | `begin_transaction`, `commit_transaction`, `rollback_transaction`, `get_edit_summary` |

Full tool documentation: [`docs/API-REFERENCE.md`](docs/API-REFERENCE.md) ·
Architecture details: [`docs/ARCHITEKTUR.md`](docs/ARCHITEKTUR.md)

---

## Build and run

```bash
mvn clean package -DskipTests
# fat JAR at target/rewisp-standalone.jar

java -jar target/rewisp-standalone.jar
```

### Configure an MCP client (e.g. Mistral Vibe)

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
