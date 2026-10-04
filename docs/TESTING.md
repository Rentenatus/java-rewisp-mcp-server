# Test-Suite und übersprungene Tests

> **Status:** Aktuell
> **Letzte Prüfung:** 2026-10-02
> **Verantwortlich:** Janusch Rentenatus

---

## Testausführung

```bash
mvn test          # Volle Suite (~550 Tests, parallel, ca. 1,5 min)
mvn test -Dtest='KlassenName'   # Einzelne Testklasse
```

**Aktueller Stand (2026-10-03, Branch mistral_lab):** 550 Tests, 0 Failures, 13 Skipped, Wallclock 1:35 min.

### Parallele Ausführung und Per-Test-Timeouts

Konfiguriert in `src/test/resources/junit-platform.properties`:

- **Per-Test-Timeout (120 s, global):** Kein Test darf unbeobachtet
  hängen — ein hängender Test schlägt nach 120 s fehl statt den Build zu
  blockieren. Der Default gilt für alle testfähigen Methoden (Test- und
  Lifecycle-Methoden); einzelne Klassen/Methoden können ihn mit
  `@Timeout` überschreiben.
- **Klassen-Parallelität:** Klassen laufen parallel (fixed pool, 6
  Threads), Methoden innerhalb einer Klasse sequenziell (`same_thread`).
  Wallclock: 5:41 min (seriell) -> 2:04 min (parallel) -> 1:35 min (parallel, nach ADR-002-Bereinigung).
- **Isolation:** Singleton- (EditEventBus) und Timing-sensitive Klassen
  sind mit `@Isolated` markiert und laufen ausschließlich allein:
  `EditEventBusTest`, `PerformanceSmokeTest`, `ProjectManagerExpiryTest`,
  `ConcurrencyIntegrationTest`, `RaceConditionIntegrationTest`.
- Neue Klassen mit echtem Singleton-Zugriff oder Timing-Abhängigkeiten
  ebenfalls mit `@Isolated` markieren.

**Warum kein Methoden-Parallelität?** Innerhalb einer Klasse teilen sich
Tests Instanzfelder und `@BeforeEach`-Zustand — Methoden-Parallelität
erzwingt ein Umdenken bei jedem einzelnen Test. Klassen-Parallelität
bringt den größten Teil des Gewinns mit minimalem Risiko.

### TTL-Tests beschleunigt

`PlanStore` unterstützt seit 2026-10-03 eine Duration-basierte TTL
(`new PlanStore(Duration)`); der Minuten-Konstruktor bleibt
kompatibel. `PlanStoreTest` verschläft dadurch 2 × 61 s nicht mehr
(122 s → 5 s), und `PlanTtlIntegrationTest` (Sekunden-TTL) ist
aktiviert.

---

## Die 13 übersprungenen Tests und ihre Gründe

Jeder Skip ist bewusst und hat einen dokumentierten Grund. Zwei Kategorien:

### 1. Umgebungs-bedingt (Assumptions) — 9 Tests

Diese Tests überspringen sich selbst, wenn die benötigte Infrastruktur
fehlt, und laufen, sobald sie vorhanden ist:

| Testklasse | Skips | Grund | Läuft, wenn … |
|------------|-------|-------|---------------|
| `GradleBuildResolverIntegrationTest` | 7 | Gradle-Wrapper nicht in der Test-Umgebung verfügbar (`assumeTrue`) | ein Gradle-Wrapper installiert ist |
| `LombokDelombokerTest` | 2 | Lombok-JAR nicht aus Cache/Netz auflösbar (`assumeTrue`, Offline-Umgebung) | das Lombok-JAR (1.18.46) im lokalen Cache liegt |

### 2. Bewusst deaktiviert (`@Disabled`) — 4 Tests

| Testklasse | Skips | Grund |
|------------|-------|-------|
| `GradleMultiProjectIntegrationTest` | 4 | Gradle-Wrapper in der Test-Umgebung erforderlich |

### Frühere Lücken — behoben am 2026-10-02

`ConcurrencyIntegrationTest` (2 Tests) und `CrossFrameworkIntegrationTest`
(4 Tests) sind seit dem 2026-10-02 **aktiviert**. Ihre Aktivierung deckte
vier echte Fehler im Apply-Pfad auf, die alle behoben wurden:

1. **Stiller Datenverlust:** `CommitServiceImpl.apply` schrieb die
   relativen Rewrite-Pfade gegen das Arbeitsverzeichnis des Servers statt
   gegen das Projekt-Root — die Writes scheiterten unbemerkt, weil
2. **`commitTransaction()`-Rückgabe ignoriert** wurde: fehlgeschlagene
   atomare Commits wurden als „COMMITTED" gemeldet.
3. **Keine Reproduzierbarkeit:** der Apply-Re-Run lief mit leeren
   Parametern gegen die Recipe-Defaults („Parameters aus Plan nicht
   verfügbar — MVP"); der Plan speichert jetzt Request-Parameter und
   Scope (`RewritePlan`/`PlanStore` erweitert).
4. **Kein Snapshot-Refresh nach Apply:** nachfolgende Applies liefen gegen
   den Vor-Commit-Zustand und hätten die Änderungen des ersten Applies
   beim Schreiben überschrieben (Single-Writer-Verletzung). Der Apply
   nimmt jetzt einen frischen Snapshot auf und setzt ihn im ProjectState.

---

## Empfohlene nächste Schritte

1. Gradle-Wrapper im CI-Image bereitstellen — aktiviert 11 Tests
   (`GradleBuildResolverIntegrationTest` + `GradleMultiProjectIntegrationTest`).
2. Lombok-JAR im CI-Cache — aktiviert 2 `LombokDelombokerTest`-Tests.

Nach Umsetzung wären alle 13 Skips aktivierbar.

---

**Autor:** Janusch Rentenatus
**Status:** Aktuell
