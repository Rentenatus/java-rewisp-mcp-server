
**Version:** 2.0.0 — rewisp (mistral_lab)
**Letzte Aktualisierung:** 2026-10-03
**Tools:** 35 MCP-Tools

---

## Design-Philosophie (mistral_lab)

Dieser Branch ist die Labor-Version des Servers, gebaut aus der Sicht
eines Agenten, der mit dem Server arbeitet:

- **Ein Schreibpfad.** Alle Edits laufen über OpenRewrite-Preview →
  EditManager (Fingerprint-Lock, Backup, atomare Writes,
  Zeilenenden-Erhaltung). Der alte Plan-basierte Apply-Weg
  (`rewrite_preview`/`rewrite_apply`/`rewrite_validate`,
  PlanStore/CommitService mit TTL) ist entfernt — `dryRun=true` auf
  jedem Edit-Tool ersetzt ihn.
- **Spoon nur lesen.** Analyse ist strikt read-only; Edits sind
  ausschließlich OpenRewrite.
- **Wenige, mächtige Tools.** Jede Einzelfrage, die
  `inspect_class`/`search_source`/`find_references` bereits
  beantworten, hat kein eigenes Tool mehr.
- **Transaktionen überall.** `begin/commit/rollback` mit
  Delete-at-Commit-Semantik; `dryRun` auf jedem Edit-Tool.

---

## Projekt-Lifecycle (8 Tools)

| Tool | Beschreibung |
|------|--------------|
| `load_java_project` | Projekt laden (Pfad, Git-URL, Archiv); auto-delombok |
| `list_loaded_projects` | Geladene Projekte auflisten |
| `unload_java_project` | Projekt entladen |
| `reload_java_project` | Projekt neu laden (nach externen Änderungen) |
| `project_metadata` | Metadaten, Build-Typ, Module |
| `project_refresh_build` | Build-Kontext (Classpath) neu auflösen |
| `check_project_dirty` | Prüfen, ob das Spoon-Modell seit dem letzten Edit veraltet ist |
| `reboot_spoon` | Spoon-Zustand hart zurücksetzen |

## SPOON-Analyse, read-only (10 Tools)

| Tool | Beschreibung |
|------|--------------|
| `list_classes` | Alle Klassen (Filter nach Package, innere Klassen) |
| `inspect_class` | Klassen-Detail inkl. Methoden, Felder, Annotationen |
| `inspect_method` | Methoden-Detail (Signatur, Parameter, Body, Annotationen) |
| `inspect_field` | Feld-Detail |
| `get_file_content` | Dateiinhalt (Grundlage für `edit_line`) |
| `get_element_position` | Exakte Position (Datei, Zeile, Spalte) eines Elements |
| `search_source` | Textsuche im Source |
| `find_references` | Alle Referenzen auf ein Symbol (Klasse/Methode/Feld) |
| `find_implementations` | Implementierungen eines Interfaces |
| `get_type_hierarchy` | Vererbungshierarchie einer Klasse |

## REWRITE-Edits (13 Tools)

Alle Edit-Tools akzeptieren `dryRun` (optional, boolean): nur Preview,
nichts wird geschrieben.

| Tool | Beschreibung |
|------|--------------|
| `edit_line` | Einzelne Zeile ersetzen (erwartet Fingerprint) |
| `rename_symbol` | Klasse/Methode/Feld umbenennen; optional alle Caller |
| `replace_method_body` | Methodenkörper ersetzen |
| `add_method` | Methode einer Klasse hinzufügen |
| `add_field` | Feld einer Klasse hinzufügen |
| `remove_member` | Methode/Feld entfernen |
| `add_annotation` | Annotation hinzufügen |
| `remove_annotation` | Annotation entfernen |
| `edit_annotation` | Annotation-Attribute ändern |
| `rewrite_signature` | Methodensignatur ändern (nur Deklaration oder inkl. Caller) |
| `add_package` | Package-Verzeichnis anlegen (optional package-info.java) |
| `add_class` | Neue Klasse/Interface/Enum anlegen |
| `move_class` | Klasse in anderes Package verschieben (Imports aktualisiert) |

## Transaktionen (4 Tools)

| Tool | Beschreibung |
|------|--------------|
| `begin_transaction` | Edits puffern statt schreiben |
| `commit_transaction` | Alles atomar schreiben (inkl. gepufferter Löschungen) |
| `rollback_transaction` | Alles verwerfen, Platte unberührt |
| `get_edit_summary` | Edit-Log der laufenden Session |

---

## Statistik

| Kategorie | Anzahl |
|-----------|--------|
| Projekt-Lifecycle | 8 |
| SPOON-Analyse (read-only) | 10 |
| REWRITE-Edits | 13 |
| Transaktionen | 4 |
| **Gesamt** | **35** |

Gegenüber 53 Tools in `feature/rewrite`: entfernt wurden der
Plan-basierte Apply-Weg (4 Tools + PlanStore/CommitService/RewriteTransactionManager/
ValidationPipeline/TransactionJournal) und 14 redundante
Einzelzweck-Lese-Tools.

---

## Siehe auch

- [ARCHITEKTUR.md](ARCHITEKTUR.md) — Schichtenmodell und ADRs
- [TESTING.md](TESTING.md) — Suite, Parallelität, Timeouts, Skips
