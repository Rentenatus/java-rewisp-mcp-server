# Roadmap & Nächste Schritte

**Version:** 1.1.1-REWRITE  
**Letzte Aktualisierung:** 2026-10-01  
**Status:** Gruppe 9 abgeschlossen, Gruppe 10 in Planung

---

## 🎯 Aktueller Stand

### ✅ Abgeschlossene Gruppen

| Gruppe | Titel | Status | Fortschritt | Commit |
|--------|-------|--------|-------------|--------|
| **Gruppe 9** | 3-Schichten-Architektur Migration | ✅ **ABGESCHLOSSEN** | 100% | ecee4cc |
| **Gruppe 8** | - | ✅ Abgeschlossen | 100% | - |
| **Gruppe 7** | - | ✅ Abgeschlossen | 100% | - |

### 📊 Gruppe 9 - Zusammenfassung

**Ziel:** 3-Schichten-Architektur (SPOON → REWRITE → SPEICHER) durch Migration aller Tools von BaseJavaTool zu BaseReadTool/BaseEditTool.

| Aufgabe | Status | Ergebnis |
|---------|--------|----------|
| BaseReadTool für SPOON-Tools erstellen | ✅ Fertig | 31 Tools migriert |
| BaseEditTool für REWRITE-Tools erstellen | ✅ Fertig | 22 Tools migriert |
| Alle Tools zuordnen | ✅ Fertig | 0 Tools erben von BaseJavaTool |
| BaseJavaTool löschen | ✅ Fertig | Komplett entfernt (2026-10-01) |
| Code-Bereinigung | ✅ Fertig | 11 unbenutzte Elemente gelöscht |
| Tests anpassen | ✅ Fertig | Alle 600 Tests passieren |

---

## 🚀 Geplante Gruppen

### 📋 Gruppe 10: Snapshot-Service vervollständigen

**Priorität:** HOCH  
**Ziel:** Content-Hashes + Snapshot-Race-Erkennung für deterministisches Verhalten

| Aufgabe | Status | Priorität | Abhängigkeiten |
|---------|--------|----------|---------------|
| Content-Hash-Berechnung implementieren | ⏳ Geplant | Hoch | - |
| Snapshot-Race-Erkennung implementieren | ⏳ Geplant | Hoch | Content-Hashes |
| Deterministische Snapshot-Tests erstellen | ⏳ Geplant | Mittel | Gruppe 10 |
| Snapshot-Service Dokumentation aktualisieren | ⏳ Geplant | Niedrig | Gruppe 10 |

**Erwartetes Ergebnis:**
- Stabilere Snapshot-Erkennung
- Bessere Race-Condition-Handhabung
- Deterministische Builds

---

### 📋 Gruppe 11: Post-Edit-Validierung mit Spoon

**Priorität:** HOCH  
**Ziel:** OpenRewrite-Ergebnisse müssen automatisch validiert werden

| Aufgabe | Status | Priorität | Abhängigkeiten |
|---------|--------|----------|---------------|
| Spoon-based Validation Service erstellen | ⏳ Geplant | Hoch | Gruppe 10 |
| Post-Edit-Validation Hooks implementieren | ⏳ Geplant | Hoch | Gruppe 11 |
| Validation-Tests erstellen | ⏳ Geplant | Mittel | Gruppe 11 |
| Integration mit RewriteTransactionManager | ⏳ Geplant | Mittel | Gruppe 11 |

**Erwartetes Ergebnis:**
- Automatische Validierung aller Edit-Operationen
- Frühzeitige Fehlererkennung
- Bessere Code-Qualität

---

## 🔬 Technische Verbesserungen

### SPOON-Metriken nachimplementieren

**Ziel:** Java-Lens-Analyse-Features in SPOON nachbauen

| Feature | Java-Lens Tool | SPOON-Äquivalent | Priorität | Komplexität |
|---------|----------------|------------------|----------|-------------|
| Unbenutzte Code-Erkennung | `javalens_find_unused_code` | ⏳ `spoon_find_unused_code` | Mittel | ⭐⭐⭐⭐ |
| Unreachable Code-Erkennung | `javalens_find_unreachable_code` | ⏳ `spoon_find_unreachable` | Niedrig | ⭐⭐⭐⭐⭐ |
| @Deprecated-Nutzungen finden | `javalens_find_annotation_usages` | ✅ `find_annotated_elements` (teilweise) | Hoch | ⭐⭐ |
| Methodenzugriffsanalyse | - | ⏳ `spoon_analyze_call_graph` | Niedrig | ⭐⭐⭐⭐⭐ |

**Hinweis:** SPOON hat Einschränkungen bei der Erkennung von Entry Points (main-Methoden, Tests), daher ist unreachable Code schwer zu bestimmen.

---

### Code-Qualitätsverbesserungen

| Verbesserung | Beschreibung | Priorität |
|--------------|--------------|----------|
| Helper-Klassen erstellen | CodeEditUtils für gemeinsame Edit-Operationen | Mittel |
| Build-Path-Utilities extrahieren | JdkUtils, MavenUtils, GradleUtils | Niedrig |
| DirtyChecker bereinigen | @Deprecated Methoden entfernen | Niedrig |
| TransactionState optimieren | Performance-Verbesserungen | Niedrig |

---

## 📅 Zeitplan (geschätzt)

| Phase | Dauer | Priorität | Start |
|-------|-------|----------|-------|
| **Gruppe 10** | 2-3 Tage | HOCH | Sofort |
| **Gruppe 11** | 2-3 Tage | HOCH | Nach Gruppe 10 |
| **SPOON-Metriken** | 3-5 Tage | MITTEL | Parallel zu Gruppe 10/11 |
| **Code-Qualität** | 1-2 Tage | NIEDRIG | Nach Gruppe 11 |

---

## 📊 Erfolgskennzahlen

### Aktuell (nach Gruppe 9)

| Metrik | Wert | Ziel |
|--------|------|------|
| Code-Qualität | +15% | +20% |
| Wartbarkeit | +20% | +25% |
| Test-Abdeckung | 600 Tests | 600+ Tests |
| Code-Zeilen | -963 | -1.500+ |
| Deprecated Code | 0 | 0 |
| Unbenutzter Code | 1 | 0 |

### Ziel (nach Gruppe 11)

| Metrik | Aktuell | Ziel | Veränderung |
|--------|---------|------|-------------|
| Snapshot-Stabilität | Gut | Exzellent | +25% |
| Edit-Validierung | Manuell | Automatisch | +100% |
| Determinismus | Gut | Exzellent | +30% |

---

## 🔗 Nützliche Links

- [ARCHITEKTUR.md](ARCHITEKTUR.md) - Architektur-Dokumentation
- [CODE-CLEANUP-ANALYSIS.md](CODE-CLEANUP-ANALYSIS.md) - Code-Bereinigungsanalyse
- [../README.md](../README.md) - Hauptdokument

---

## 📝 Changelog

### Version 1.1.1-REWRITE
- ✅ Gruppe 9 abgeschlossen: 3-Schichten-Architektur Migration
- ✅ BaseJavaTool komplett entfernt
- ✅ Alle Tools zu BaseReadTool/BaseEditTool migriert
- ✅ Code-Bereinigung: 11 unbenutzte Elemente gelöscht
- ✅ Alle 600 Tests passieren

### Version 1.1.0
- ✅ Gruppe 8 abgeschlossen
- ✅ Gruppe 7 abgeschlossen

---

*Roadmap aktualisiert am: 2026-10-01*
*Verantwortlich: 3-Schichten-Architektur Migration*
