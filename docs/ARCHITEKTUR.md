# Architektur-Dokumentation — rewisp

**Version:** 1.1.1-REWRITE  
**Letzte Aktualisierung:** 2026-10-01  
**Status:** Aktuell

---

## 🏗️ 3-Schichten-Architektur

### Grundprinzip

> **Regel:** Jede Ebene darf **NUR** von der unter ihr liegenden Ebene lesen!

```
┌─────────────────────┐
│   SPOON              │  ← Lesen
│   (Analyse)          │
│   - CtModel          │
│   - Symbol-Suche     │
│   - Validierung      │
└──────────┬──────────┘
           │ (nur Lesen)
           ↓
┌─────────────────────┐
│   REWRITE            │  ← Lesen + Schreiben
│   (Edit)             │
│   - OpenRewrite      │
│   - FileAccessService│
│   - EditManager      │
└──────────┬──────────┘
           │ (Lesen + Schreiben)
           ↓
┌─────────────────────┐
│   SPEICHER           │  ← Dateisystem
│   (Filesystem)        │
│   - Java-Dateien     │
│   - pom.xml          │
│   - build.gradle     │
└─────────────────────┘
```

### Schichtenzuordnung

| Schicht | Verantwortung | Zugriff auf | Tools erben von |
|---------|---------------|-------------|----------------|
| **SPOON** | Read-only Analyse | Nur REWRITE-Layer | `BaseReadTool` |
| **REWRITE** | Edit-Operationen | SPEICHER + SPOON (nur für Pre-Validation) | `BaseEditTool` |
| **SPEICHER** | Dateisystem | - | - |

### Tool-Klassifizierung

#### SPOON-Tools (Read-only, 31 Tools)
- **Erben von:** `BaseReadTool`
- **Zugriff:** Nur REWRITE-Layer (kein direkter Filesystem-Zugriff)
- **Beispiele:** `list_classes`, `find_references`, `inspect_class`, `get_type_hierarchy`

#### REWRITE-Tools (Edit, 22 Tools)
- **Erben von:** `BaseEditTool`
- **Zugriff:** SPEICHER (Filesystem) + SPOON (nur für Validierung)
- **Beispiele:** `add_class`, `add_method`, `rename_symbol`, `move_class`, `replace_method_body`

---

## 🎯 Architektur-Entscheidungen

### ADR-002: Ein Schreibpfad — Plan-basierter Apply-Weg entfernt (mistral_lab)

**Status:** Implementiert (Branch mistral_lab)
**Datum:** 2026-10-03

**Entscheidung:** Der Plan-basierte Apply-Workflow
(`rewrite_preview` → `rewrite_apply` mit PlanStore-TTL, CommitService,
RewriteTransactionManager, ValidationPipeline, TransactionJournal)
wird vollständig entfernt. Alle Edits laufen ausschließlich über den
direkten Pfad: Edit-Tool → OpenRewrite-Preview → EditManager.

**Begründung:**
- Zwei Wege für dieselbe Aufgabe bedeuteten doppelte Wartung; die
  Aktivierung der Integrationstests deckte im Plan-Pfad fünf echte
  Fehler auf (stiller Datenverlust durch CWD-Write, ignorierte
  commitTransaction-Ergebnisse, leerer Parameter-Replay, fehlender
  Snapshot-Refresh) — der direkte Pfad hatte keinen davon.
- `dryRun=true` auf jedem Edit-Tool liefert dieselbe Sicherheit wie
  Preview/Apply, ohne TTL-, Plan- und Replay-Maschinerie.
- Die Transaktions-Tools (begin/commit/rollback) decken
  Mehrdatei-Atomarität ab, die der Plan-Weg nur umständlich bot.

### ADR-001: Spoon vs. OpenRewrite

**Status:** ✅ Implementiert (Version 1.1.1-REWRITE)  
**Datum:** 2026-XX-XX  

#### Entscheidung
- **Spoon** wird **NUR** für Read-Operationen (Analyse) verwendet
- **OpenRewrite** wird für **ALLE** Edit-Operationen verwendet
- **Kein Fallback** mehr zu Spoon für Write-Operationen

#### Begründung
1. **Trennung der Verantwortlichkeiten:** Spoon ist optimiert für schnelle AST-Analyse, OpenRewrite für sichere Code-Transformationen
2. **Deterministisches Verhalten:** OpenRewrite bietet transaktionssichere Änderungen mit Preview-Funktionalität
3. **Wartbarkeit:** Klare Trennung reduziert Komplexität und Fehleranfälligkeit

#### Rollenverteilung

| Operation | Verantwortlich | Begründung |
|-----------|--------------|------------|
| Code-Analyse | ✅ Spoon | Schnell, gut etabliert für Read-Only |
| Symbol-Suche | ✅ Spoon | Effizient für AST-Navigation |
| Validierung | ✅ Spoon | Bereit verfügbar |
| **EDIT-Operationen** | ✅ **OpenRewrite** | **NUR noch erlaubt!** |
| Transaktionen | ✅ RewriteTransactionManager | OpenRewrite-basiert |
| Apply/Preview | ✅ RewriteEngine | OpenRewrite Kernfunktionalität |

#### Umsetzungsstand (2026-10-02, Commits 99c9480 → 8ba223a)

Alle 11 Edit-Tools sind vollständig auf OpenRewrite umgestellt — echte
Recipe-Visitor mit JavaTemplate statt Platzhalter, kein Spoon-Fallback,
keine Migration-Flags mehr:

| Tool | Recipe-Ansatz |
|------|---------------|
| add_annotation | JavaTemplate an addAnnotation-Koordinate |
| add_field | lastStatement-Koordinate + maybeAddImport |
| add_method | lastStatement-Koordinate + Konstruktor-Fix |
| remove_member | Statement-Filterung (Multi-Feld-Support) |
| rename_symbol | Typ-Attribution für Invocations/FieldAccess |
| move_class | ChangeType-Sub-Recipe + eigener Import-Rewriter |
| replace_method_body | replaceBody-Koordinate |
| rewrite_signature | replaceParameters + Return-Type-Tree |
| edit_annotation | replaceAnnotations (Nachbarn erhalten) |
| remove_annotation | Annotations-Listen-Filterung |
| add_class | OpenRewrite-Parse-Pipeline (Validierung vor Schreibzugriff) |

Wichtige Engine-Fixes im Zuge der Umstellung:

- `OpenRewriteEngineImpl`: alle in-scope-Dateien in EINEM parseInputs-Aufruf
  (Cross-File-Typ-Attribution); MODIFY-Results am before-Pfad keyen
- `PathUtils`: Real-Path-Resolver für Windows-8.3-Kurznamen-Mismatches
- `YamlRecipeRegistry`: verschachtelte `rewrite.recipes`-Struktur
- `Snapshot-Refresh`: bei dreckigem Projekt vor jedem Edit

---

### ADR-002: 3-Schichten-Architektur

**Status:** ✅ Implementiert (Gruppe 9 abgeschlossen)  
**Datum:** 2026-10-01

#### Entscheidung
- **BaseReadTool** für SPOON-Tools (Read-only)
- **BaseEditTool** für REWRITE-Tools (Edit)
- **Keine Vererbung** von BaseJavaTool mehr

#### Implementierung
- ✅ Alle 31 SPOON-Tools erben von `BaseReadTool`
- ✅ Alle 22 REWRITE-Tools erben von `BaseEditTool`
- ✅ `BaseJavaTool` komplett entfernt (2026-10-01)

#### Architektur-Diagramm

```
┌─────────────────────────────────────────────────────────────┐
│                      McpTool (Interface)                       │
└───────────────────────────┬─────────────────────────────────┘
                            │
         ┌──────────────────────┼──────────────────────┐
         │                      │                      │
         ↓                      ↓                      ↓
┌─────────────────────┐ ┌─────────────────────┐ ┌─────────────────────┐
│   BaseReadTool       │ │   BaseEditTool       │ │   Projekt-Tools      │
│   (SPOON-Schicht)    │ │   (REWRITE-Schicht)  │ │   (Sonderfall)       │
│                    │ │                    │ │                    │
│ - Kein Filesystem   │ │ - Filesystem OK    │ │ - Load/Unload       │
│ - Nur CtModel       │ │ - Zugriff auf SPEICHER│ │ - Metadata          │
│ - Read-only         │ │ - Edit-Operationen  │ │                    │
└─────────────────────┘ └─────────────────────┘ └─────────────────────┘
         │                      │                      │
         ├──────────────────────┼──────────────────────┤
         │                      │                      │
         ↓                      ↓                      ↓
┌─────────────────────────────────────────────────────────────┐
│                        SPOON CtModel                            │
│   (geladen aus SPEICHER durch Launcher)                       │
└─────────────────────────────────────────────────────────────┘
         │
         ↓
┌─────────────────────────────────────────────────────────────┐
│                        SPEICHER (Filesystem)                   │
│   - Java-Dateien (.java)                                       │
│   - Build-Dateien (pom.xml, build.gradle)                     │
│   - Konfigurationsdateien                                      │
└─────────────────────────────────────────────────────────────┘
```

---

## 📊 Aktueller Implementierungsstand (mistral_lab)

### Tool-Inventar nach ADR-002

| Kategorie | Anzahl |
|-----------|--------|
| Projekt-Lifecycle | 8 |
| SPOON-Analyse (read-only, BaseReadTool) | 10 |
| REWRITE-Edits (BaseEditTool) | 13 |
| Transaktionen (BaseEditTool) | 4 |
| **Gesamt** | **35** |

Entfernt: 4 Plan-basierte Rewrite-Tools + zugehörige Infrastruktur
(PlanStore, CommitService/Impl, RewriteTransactionManager,
TransactionJournal, DiffGenerator, ValidationPipeline-Paket) und 14
redundante Einzelzweck-Lese-Tools (ohne Testabdeckung) — siehe
[API-REFERENCE](API-REFERENCE.md).

### Code-Qualitätsmetriken

| Metrik | Vorher | Nachher | Veränderung |
|--------|--------|---------|-------------|
| Deprecated Klassen | 1 | 0 | -1 |
| Unbenutzte Methoden | 8 | 0 | -8 |
| Unbenutzte Felder | 4 | 1 | -3 |
| Statische Methodenzugriffe auf BaseJavaTool | 4 | 0 | -4 |
| **Gesamt Code-Zeilen** | - | - | **-963** |

---

## 📁 Verzeichnisstruktur

```
git_java-mcp-server/   # Produktname: rewisp
├── src/
│   ├── main/java/de/jare/rewisp/
│   │   ├── ProjectManager.java           # Zentrale Projektverwaltung
│   │   ├── JavaMcpServer.java            # MCP-Server Hauptklasse
│   │   ├── build/                        # Build-Resolver
│   │   │   ├── BuildDetector.java         # Build-System Erkennung
│   │   │   ├── MavenBuildResolver.java    # Maven-Spezifisch
│   │   │   ├── GradleBuildResolver.java  # Gradle-Spezifisch
│   │   │   └── PomUtils.java              # Maven-POM Utilities
│   │   ├── edit/                         # REWRITE-Layer
│   │   │   ├── EditManager.java           # File-Write Pipeline
│   │   │   ├── RewriteTransactionManager.java # Transaktionsmanagement
│   │   │   └── ...
│   │   ├── rewrite/                      # OpenRewrite Integration
│   │   │   ├── OpenRewriteEngineImpl.java # OpenRewrite Engine
│   │   │   ├── SpoonEngineImpl.java      # Spoon Engine
│   │   │   └── ...
│   │   ├── snapshot/                     # Snapshot-Service
│   │   ├── tools/                        # MCP-Tools
│   │   │   ├── BaseReadTool.java          # Basis für SPOON-Tools
│   │   │   ├── BaseEditTool.java          # Basis für REWRITE-Tools
│   │   │   ├── BaseJavaTool.java          # ❌ GELÖSCHT (2026-10-01)
│   │   │   └── *Tool.java                # 35 MCP-Tools (mistral_lab)
│   │   └── validation/                   # Validierung
│   └── test/java/de/jare/rewisp/   # Tests
└── docs/                              # Dokumentation
    ├── ARCHITEKTUR.md                  # ⭐ DIESES DOKUMENT
    ├── CODE-CLEANUP-ANALYSIS.md         # Code-Bereinigungsanalyse
    ├── API-REFERENCE.md                # Tool-Referenz (geplant)
    └── ROADMAP.md                      # Nächste Schritte (geplant)
```

---

## ⚠️ Bekannte Grenzen

### OpenRewrite-Autoformat in berührten Regionen

OpenRewrite formatiert Code, den ein Recipe neu generiert (JavaTemplate),
nach dem Default-Stil des Parsers. Beispiel: `edit_annotation` mit
`newAttributes="since=\"1.0\""` erzeugt `@Deprecated(since = "1.0")` —
mit Leerzeichen um `=`, auch wenn die ursprüngliche Annotation
`@Deprecated` (ohne Attribute) keine Aussage über den Stil machte.

**Geltungsbereich (empirisch verifiziert 2026-10-02):** Nur die vom Recipe
neu geschriebenen Snippets werden formatiert. Unveränderte Code-Regionen
bleiben byte-genau erhalten — z.B. bleibt `@SuppressWarnings(value="unchecked")`
in derselben Datei exakt so stehen, unabhängig davon, welche andere
Annotation gerade editiert wird.

**Warum nicht unterdrückbar:** OpenRewrite 8.90.4 bietet am `JavaTemplate.Builder`
keine Option zur Format-Unterdrückung (`doNotModifyFormatting` existiert in
dieser Version nicht); die Style-Parameter des JavaParser decken die
Leerzeichen-Setzung um Zuweisungsoperatoren nicht ab.

**Mitigation:** Alle Edit-Tools unterstützen `dryRun=true` — der Agent sieht
vor dem Schreiben exakt, welche Dateien und Zeilen das Recipe ändern werden.

---

## 🔗 Siehe auch

- [README.md](../README.md) - Hauptdokument mit Features und Usage
- [API-Reference](API-REFERENCE.md) - Detaillierte Tool-Beschreibungen
- [Test-Suite](TESTING.md) - Testausführung und übersprungene Tests
- [ROADMAP](ROADMAP.md) - Geplante Features und nächste Schritte
- [Code Cleanup Analysis](CODE-CLEANUP-ANALYSIS.md) - Analyse der Code-Bereinigung

---

*Dokumentation konsolidiert am: 2026-10-01*
*Verantwortlich: 3-Schichten-Architektur Migration*
