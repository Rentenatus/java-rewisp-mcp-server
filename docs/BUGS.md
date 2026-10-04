# BUGS.md — Offene Bugs

> Nur offene, ungelöste Probleme. Behobene Bugs werden hier nicht geführt;
> sie stehen in den Commit-Messages und zugehörigen Regressionstests.
> Zuletzt aktualisiert: 2026-10-03, nach Live-Verifikation der Fixes für
> Bugs #12/#13 am laufenden Server (gefunden im Battleship-Dogfooding).

---

## Bug #14 — Fehlgeschlagener `reload_java_project` wirft das Projekt aus dem Manager

**Status:** Offen · **Priorität:** Hoch · **Gefunden:** 2026-10-03, Live-Test

**Beobachtung:** Ein `reload_java_project`, der an unparsebarem Quellcode
scheitert, entfernt das Projekt vollständig aus dem Manager. Der zweite
Aufruf meldet „No project found with name or alias '...'". Alle geladenen
Projektdaten (Modell, Fingerprints, Dirty-Tracking, Snapshot) sind weg.

**Ursache:** `ProjectManager.load()` entfernt den alten Entry ganz am Anfang
(`entries.remove(name)`), **bevor** geparst wird. Schlägt das Parsen mit
`ProjectLoadException` fehl, ist der alte Entry bereits gelöscht und wird
nicht wiederhergestellt.

**Reproduktion:**
1. Projekt laden
2. Quelldatei so verändern, dass sie semantisch kaputt ist
   (z. B. Import entfernen, Simple-Name bleibt)
3. `reload_java_project` → „Reload failed: Failed to parse project: ..."
4. Nochmal `reload_java_project` → „No project found"

**Fix-Idee:** Den alten Entry erst nach erfolgreichem Load ersetzen
(remove erst unmittelbar vor dem Eintragen des neuen Entry), oder bei
`ProjectLoadException` den alten Entry wiederherstellen.

**Regressionstest:** Beim Fix einen Test ergänzen, der einen fehlgeschlagenen
Reload auslöst und danach `find()`/Edit auf dem Projekt ausführt.

---

## Bug #15 — Read-after-Write-Staleness: `get_file_content` liefert alten Snapshot

**Status:** Offen · **Priorität:** Hoch · **Gefunden:** 2026-10-03, Live-Test

**Beobachtung:** Nach einem erfolgreichen `add_method` liefert
`get_file_content` weiterhin den Inhalt **vor** dem Edit. Im Live-Test
fehlten die neu eingefügte Methode und der gesetzte Import im Output,
obwohl beide auf der Platte standen.

**Ursache:** Der REWRITE-Layer-Snapshot (`ProjectState.snapshot()`) wird
beim Edit selbst nicht aktualisiert — `refreshSnapshotForEdit()` läuft
nur **vor** dem nächsten Edit (schmaler/breiter Scope) oder beim Reload.
Lesende Tools wie `get_file_content` lesen aber genau diesen Snapshot.

**Konsequenz:** Ein Agent, der nach einem Edit den Dateiinhalt prüft,
sieht falschen (alten) Stand — obwohl der Dirty-Warn-Header auf Staleness
hinweist, ist der gezeigte Inhalt irreführend.

**Fix-Idee:** Nach erfolgreichem Edit den Snapshot für die betroffene
Datei aktualisieren (die neuen Inhalte liegen im EditManager vor), oder
`get_file_content` bei dreckigem Zustand die aktuellen Dateien von der
Platte lesen lassen.

---

## Bug #16 — Stale-Spoon-Modell-Falle in der Pre-Edit-Analyse (Kandidat)

**Status:** Offen (Design-Entscheidung nötig) · **Priorität:** Hoch ·
**Gefunden:** 2026-10-03, Live-Test

**Beobachtung:** `add_method` unterdrückte einen eigentlich nötigen Import
und schrieb eine nicht kompilierende Datei, weil `AutoImportResolver` die
Import-Scope aus dem **Spoon-Modell** liest — und `edit_line` aktualisiert
das Spoon-Modell nicht. Das Modell hatte noch den Stand von `load`
(einschließlich einer inzwischen entfernten Import-Zeile).

**Ursache:** Die Pre-Edit-Analyse (Phase 1: Spoon, read-only) und die
Edit-Pipeline (Phase 2: OpenRewrite über Snapshots) haben unterschiedliche
Frische. `refreshSnapshotForEdit()` erfrischt nur die REWRITE-Snapshots,
nicht das Spoon-Modell. Die Dirty-Warnung sagt dem Agenten, er solle
reloaded — geschieht das nicht, arbeitet die Analyse mit falschen Daten.

**Zusammenhang:** Genau die Lücke, für die die alte Stale-Map zwischen
REWRITE und SPOON wieder eingebaut werden könnte (Analysen gegen einzelne
Dateien prüfen nur diese eine Datei; breite Analysen brauchen trotzdem
einen Rebuild).

**Fix-Idee (alternativ):** In `BaseEditTool` vor Phase 1 prüfen, ob die
Zieldatei dreckig ist; falls ja, die Import-Scope direkt vom aktuellen
Dateiinhalt (Platte/EditManager-Puffer) ableiten statt aus dem Spoon-Modell.

**Workaround bis dahin:** Nach Text-Edits (`edit_line`) und vor der nächsten
strukturierten Analyse `reload_java_project` aufrufen.

---

## Bug #11 — `UNRESOLVED_TYPES`-False-Positives (Dokumentations-Kandidat)

**Status:** Offen (Klären: fixen oder dokumentieren) · **Priorität:** Mittel ·
**Gefunden:** 2026-10-03, Battleship-Dogfooding

**Beobachtung:** `AutoImportResolver` kennt nur einen Typ-Index, keinen
Feld-/Konstanten-Scope. Fälschlich als „unresolved" gemeldet werden:
- Selbstreferenzen im `add_class`-Body (`Battleship::new`, Konstruktorname)
- Konstanten als Array-Größen (`new JLabel[GRID][GRID]`)
- Selbst deklarierte Konstanten im Body (`private static final int GRID = 8;`)
- Feld-Konstanten in Methoden-Bodies (auch wenn per `add_field` erzeugt
  und via Reload im Modell)

**Entscheidung nötig:** Aufwändiger Fix (Scope-Analyse in
`extractTypeNames`) oder als bekannte Grenze in ARCHITEKTUR.md
dokumentieren inkl. Workarounds (Konstanten per `add_field` nach Reload,
Literale statt Konstanten in Bodies, kein Selbstbezug im `add_class`-Body).

---

## Offene Nicht-Bug-Themen (zur Vollständigkeit)

- **Performance:** Edits laufen subjektiv langsam. Ursache noch zu messen —
  Verdacht: Snapshot-Refresh pro Edit bzw. Spoon-Reload-Kosten. Messung mit
  der DONE-Zeile des ToolCallGuard (Dauer je Aufruf im Log) möglich.
- **Harness-Anmerkung (nicht Server-Bug):** Der Vibe-MCP-Harness zeigt dem
  Agenten bei Tool-Fehlern nur `mcp_tool_error` ohne Body — `traceId`/
  `logHint` sind dort nur über das Log erreichbar. Ggf. Body-Durchreichung
  in Betracht ziehen.
