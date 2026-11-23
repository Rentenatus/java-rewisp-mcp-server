# Output formats of the tools

Status: 2026-10-31 (plan item 8.7). Describes what a client or AI agent can rely on in the responses
and what is deliberately not guaranteed. The responses are meant for language models, not for a rendered
Markdown UI.

## Basic form

- Every response is **one text block** (`CallToolResult` with a text content) plus the flag `isError`.
- **Success:** plain text (edit tools, transactions) or Markdown (read tools).
- **Error:** either a *domain error* (JSON, see below), or for internal errors `Error: <message>` with a
  reference to the log trace.

## Domain errors (`isError=true`)

Pretty-printed JSON with fixed keys:

| Key | Meaning |
|-----|---------|
| `error` | always `true` |
| `isDomainError` | always `true` |
| `errorType` | stable uppercase category (`ErrorType`, e.g. `TYPE_NOT_FOUND`, `UNRESOLVED_TYPES`, `EDIT_BREAKS_COMPILATION`, `NO_CHANGES`) |
| `message` | readable description |
| `tool` | name of the tool |
| others | context of the error (`className`, `fieldName`, `suggestion`, `unresolvedTypes`, …); values are strings or booleans |
| `traceId`, `logFile`, `logHint` | only when a call trace exists: `grep '<traceId>' <logFile>` finds the details |

You can rely on `errorType` and `message`; the other context keys depend on the error.

## Warnings before the actual result

Warnings appear as a blockquote **before** the result, each with `> ⚠️ **<title>:**`:

| Title | When |
|-------|------|
| `Expired projects` | once, when a project expired during this call |
| `Stale structure model` | only for the project of the call: the current source does not compile, structure tools show the last compilable state; `get_file_content` and `search_source` show the current text |
| `External changes detected` / `Build files changed` | on `check_project_dirty` |
| `Lombok detected but not delomboked` | on `inspect_class` |

The order of warnings is not guaranteed; the title is.

## Read tools

- Headings with `#`, summary lines as `**Total:** …` resp. `**Total references:** …`, lists with `- `.
- Class, method and field names are in backticks or bold; line numbers are **1-based**.
- Source text, names and string literals are **not** Markdown-escaped. A name containing `*`, `_` or `|` appears
  raw. Whoever renders the output must escape it themselves.
- Hit lists (`search_source`, `find_references`) name `file:line`.

## Edit tools

- Success: one line `<action>: <target> [OpenRewrite]` (e.g. `Field added: p.A.names [OpenRewrite]`), then optional
  extra lines (`Callers updated: n`, multi-module warning, diagnostics as `> <SEVERITY> <CODE>: <text> (<path>)`).
- `dryRun=true`: header `DRY RUN - <tool>: no files written. Would change N file(s):`, then per file

  ```
    modify <path>
      @@ line <N> @@
          <context line>
        - <removed line>
        + <added line>
    create <path>
    delete <path>
  ```

  Context is 2 lines; a diff is truncated after 400 lines with `... (n more diff lines omitted)`. The dry run
  also reports rejection by the compile check (`EDIT_BREAKS_COMPILATION`).
- `edit_line` reports `Line <n> replaced in <file>.`

## Transactions

- `get_transaction_diff`: header `Transaction diff (frozen state -> current transaction state): N file(s), +A -R lines`,
  then per file `modify|create|delete <path> (+a -r)` with the same diff format as the dry run (except with
  `statOnly=true`) and `mkdir <path>` for directories to be created. Paths are relative to the project and use `/`.
- `get_edit_summary`: one line per edit since load (rolled-back edits are missing).

## Known imprecisions

- **Path notation:** dry-run output uses the snapshot key (on Windows with `\`), transaction diffs always `/`; some hits name only the file name (rename hints on string literals).
  A client should normalize paths before comparing.
- **Order:** lists are usually sorted, but not guaranteed as an interface.
- **Language:** messages are English and may change in wording; stable are `errorType`, the titles of the warnings and the header lines shown above.
