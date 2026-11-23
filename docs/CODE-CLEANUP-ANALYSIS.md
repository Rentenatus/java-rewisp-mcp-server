# Code Cleanup Analysis - Deprecated, Outdated & Unused Elements

> **HISTORICAL — status as of 2026-10-01, no longer current.** The classes
> `BaseJavaTool`, `RewriteTransactionManager` and the line numbers in
> `ProjectManager` mentioned here no longer exist in this form (ADR-002). The
> current inventory of dead code, inefficiencies and bugs is in
> [REVIEW-PLAN.md](REVIEW-PLAN.md) and [`review/`](review/).

*Generated on: 2026-10-01*  
*Status: analysis for group 9 completion*  
*Responsible: 3-layer architecture migration*

---

## Executive Summary

| Category | Count | Status | Recommendation |
|----------|-------|--------|----------------|
| Unused private methods | 8 | ✅ Identified | Delete after review |
| Unused private fields | 4 | ✅ Identified | Delete after review |
| @Deprecated classes | 2 | ⚠️ Partially migrated | BaseJavaTool can be deleted |
| @Deprecated methods in BaseEditTool | 9 | ⚠️ Legacy support | Keep or migrate |
| Unreachable code (from entry points) | 353 | ⚠️ Architecture-relevant | Analysis required |
| Static method calls on BaseJavaTool | 4 | ❌ Blocking | Migration required |

---

## 1. Unused private elements (directly deletable)

### 1.1 Unused private methods

**File: `ProjectManager.java`** (7 methods - all private)

| Method | Line | Signature | Status |
|--------|------|-----------|--------|
| `setAndSaveJavaHome` | 654 | `setAndSaveJavaHome(String)` | ✅ Delete |
| `applyJdkClasspath` | 576 | `applyJdkClasspath(Launcher)` | ✅ Delete |
| `findSourceDir` | 661 | `findSourceDir(Path, BuildDetector.BuildInfo)` | ✅ Delete |
| `resolveRealPath` | 637 | `resolveRealPath(Path)` | ✅ Delete |
| `countModules` | 677 | `countModules(Path, BuildDetector.BuildInfo)` | ✅ Delete |
| `addGradleSourceDirs` | 668 | `addGradleSourceDirs(Launcher, Path)` | ✅ Delete |
| `resolveRealJavaHome` | 646 | `resolveRealJavaHome()` | ✅ Delete |

**File: `RewriteTransactionManager.java`** (1 method)

| Method | Line | Signature | Status |
|--------|------|-----------|--------|
| `hasBuildFileChanges` | 477 | `hasBuildFileChanges(RewriteResult)` | ✅ Delete |

### 1.2 Unused private fields

**File: `LombokMemberPredictor.java`** (2 fields)

| Field | Line | Type | Status |
|-------|------|------|--------|
| `FIELD_LEVEL_GETTER` | 48 | `Set<String>` | ✅ Delete |
| `FIELD_LEVEL_SETTER` | 50 | `Set<String>` | ✅ Delete |

**File: `PomUtils.java`** (1 field)

| Field | Line | Type | Status |
|-------|------|------|--------|
| `PARENT_VERSION` | 27 | `Pattern` | ✅ Delete |

**File: `GradleBuildResolverIntegrationTest.java`** (test file)

| Field | Line | Type | Status |
|-------|------|------|--------|
| `tempTestDir` | 29 | `Path` | ✅ Delete (test) |

---

## 2. @Deprecated classes

### 2.1 BaseJavaTool.java

**Status: CAN BE DELETED** ⭐

- **Line 78**: `@Deprecated` at class level
- **Current status**: No class inherits from BaseJavaTool anymore (0 inheritances)
- **Migration status**: ✅ All tools migrated to BaseReadTool/BaseEditTool

**BUT: Blocking static method calls** ❌

| Call site | Line | Method | Alternative |
|-----------|------|--------|-------------|
| `EditManager.java:101` | 101 | `BaseJavaTool.validateFingerprint(...)` | ✅ `BaseEditTool.validateFingerprint()` exists (protected) |
| `MoveClassTool.java:301` | 301 | `BaseJavaTool.replaceInCodeOnly(...)` | ❌ **MISSING** - must be migrated |
| `RenameSymbolTool.java:266` | 266 | `BaseJavaTool.replaceInCodeOnly(...)` | ❌ **MISSING** - must be migrated |
| `RemoveAnnotationTool.java:238` | 238 | `BaseJavaTool.validateAnnotationTarget(...)` | ✅ `BaseEditTool.validateAnnotationTarget()` exists (protected) |

**Recommended action**:
1. Migrate `replaceInCodeOnly` to `BaseEditTool` (static or instance method)
2. Adjust the calls in EditManager, MoveClassTool, RenameSymbolTool, RemoveAnnotationTool
3. Delete BaseJavaTool

### 2.2 DirtyChecker.java

**Status: PARTIALLY DEPRECATED**

- **Line 37**: `@Deprecated` on method `checkDirtyFullScan`
- **Note**: Full 3-layer conformance requires using `checkDirtyAgainstSnapshot()`
- **Recommendation**: keep the method, but update the documentation

---

## 3. @Deprecated methods in BaseEditTool

The following methods are marked `@Deprecated` in BaseEditTool (9 methods):

| Method | Line | Purpose | Recommendation |
|--------|------|---------|----------------|
| `replaceAnnotationInWindow` | 884 | Annotation replacement | Migrate to a new helper class |
| `findMatchingParen` (overloaded) | 918, 887 | Finding parentheses | Migrate to a helper class |
| `classClosingBraceOffset` | 940 | Class closing brace | **Already present in BaseEditTool** (line 907) |
| `locateFreshType` | 1037 | Type localization | **Already present in BaseEditTool** (line 885) |
| `locateFreshMethod` | 1099 | Method localization | **Already present in BaseEditTool** (line 1333) |
| `validateAnnotationTarget` | 1178 | Annotation validation | **Already present in BaseEditTool** (line 1083) |
| `replaceInCodeOnly` | 1261 | Code replacement | **MISSING** - migrate to BaseEditTool |
| `insertImports` | 1332 | Import insertion | Check in BaseEditTool |

**Important**: 
- 5 methods already exist in BaseEditTool (not deprecated)
- 2 methods still need to be migrated
- 2 methods can be kept or moved to a helper class

---

## 4. Unreachable code analysis

### 4.1 Overview

- **Total unreachable elements**: 353 (out of 600 test methods as entry points)
- **Main problem**: Many types and methods are reachable only via reflection/dependency injection
- **Architectural relevance**: This is NORMAL for framework code

### 4.2 Categories

#### 4.2.1 Expected unreachable (framework code)

**Build system & project detection:**
- `BuildDetector.BuildType` (enum)
- `BuildDetector.BuildInfo` (class)
- `BuildDetector.findSourceDir(...)`
- `LombokDetector.LombokInfo`
- `LombokDelomboker` methods

**Model & state:**
- `BuildContext`, `BuildDiagnostic`, `ModuleContext`
- `ProjectEntryFactory` - all methods
- `Fingerprint` (class)

**Edit & rewrite:**
- `EditEvent.Type` (enum)
- `SnapshotEvent` - all methods
- `ValidationEvent` - all methods
- `RewritePlan`, `RewriteDiagnostic`, `SpoonAnalysis`, etc.

**Utilities:**
- `BackupConfig.MINIMAL_EXTENSIONS`
- `LineEndings.LineEndings()` (constructor)
- `TransactionState` - various methods

#### 4.2.2 False positive (probably reached)

- `JavaMcpServer.rewriteTransactionManager()` - reached via MCP tool calls
- All `*Tool` classes - called dynamically via the MCP server

### 4.3 Recommendation

**DO NOT DELETE** unreachable code, because:
1. Framework code is often reached via reflection/DI
2. MCP tools are called dynamically via `JavaMcpServer`
3. The 353 elements are mostly framework infrastructure

**Action**: update the documentation to state that this is expected behavior.

---

## 5. Static method calls - migration required

### 5.1 Critical dependencies

#### EditManager.java

```java
// Line 101
BaseJavaTool.validateFingerprint(entry, file, expectedFingerprint);
```

**Solution**: The method `validateFingerprint` in BaseEditTool already exists (line 419), but is `protected`. 
- **Option 1**: change the method to `public static`
- **Option 2**: EditManager gets a BaseEditTool instance and calls the protected method
- **Recommendation**: **Option 2** (better encapsulation)

#### MoveClassTool.java

```java
// Line 301
String updatedContent = BaseJavaTool.replaceInCodeOnly(content, qualPattern, newQualified);
```

**Solution**: 
1. Migrate `replaceInCodeOnly` to `BaseEditTool` (static)
2. Adjust the call in MoveClassTool

#### RenameSymbolTool.java

```java
// Line 266
newContent = BaseJavaTool.replaceInCodeOnly(source, namePattern, newName);
```

**Solution**: Same as MoveClassTool

#### RemoveAnnotationTool.java

```java
// Line 238
String targetErr = BaseJavaTool.validateAnnotationTarget(targetType, targetName);
```

**Solution**: The method already exists in BaseEditTool (line 1083) as `protected static`.
- **Option 1**: change the call to `BaseEditTool.validateAnnotationTarget`
- **Option 2**: change the method to `public static`
- **Recommendation**: **Option 1** (since BaseEditTool is already imported)

---

## 6. Consolidation opportunities

### 6.1 Helper class potential

The following methods in BaseJavaTool and BaseEditTool could be extracted into a **`CodeEditUtils`** helper class:

| Method | Current location | Purpose | Recommendation |
|--------|------------------|---------|----------------|
| `replaceInCodeOnly` | BaseJavaTool:1238 | Code replacement without string literals | ✅ Extract |
| `replaceAnnotationInWindow` | BaseEditTool:884 | Annotation window replacement | ✅ Extract |
| `findMatchingParen` | BaseEditTool:918, 887 | Finding parentheses | ✅ Extract |
| `classClosingBraceOffset` | BaseEditTool:907 | Finding the class closing brace | ✅ Extract |
| `insertImports` | BaseEditTool:1332 | Import insertion | ✅ Extract |

**Advantages**:
- Reduces the complexity of BaseEditTool
- Reusable in other components
- Better testability

### 6.2 Consolidation with other classes

**ProjectManager.java**:
- Contains 7 unused private methods for JDK/build path resolution
- These could be extracted into **`JdkUtils`** or **`BuildPathUtils`**
- Currently only unused because they are never called

**PomUtils.java**:
- The `PARENT_VERSION` pattern is never used
- `hasParent`, `extractParentGroupId`, `extractParentArtifactId` could be consolidated into **`MavenUtils`**

---

## 7. Immediately actionable items (priority)

### 7.1 High priority (blocks deletion of BaseJavaTool)

1. **Migrate `replaceInCodeOnly` to BaseEditTool**
   - Copy the method from BaseJavaTool (line 1238)
   - Insert it into BaseEditTool as `protected static`
   - Adjust the calls in MoveClassTool and RenameSymbolTool
   
2. **EditManager.java - adjust the validateFingerprint call**
   - Option: EditManager gets a BaseEditTool reference
   - Or: change the method in BaseEditTool to `public static`
   
3. **RemoveAnnotationTool.java - adjust the call**
   - `BaseJavaTool.validateAnnotationTarget` → `BaseEditTool.validateAnnotationTarget`

4. **Delete BaseJavaTool** (after 1-3)
   

### 7.2 Medium priority (code hygiene)

5. **Clean up ProjectManager.java**
   - Delete 7 unused private methods

6. **Clean up LombokMemberPredictor.java**
   - Delete 2 unused private fields  

7. **Clean up PomUtils.java**
   - Delete the `PARENT_VERSION` pattern

### 7.3 Low priority (architecture improvement)

8. **Create the `CodeEditUtils` helper class**
   - Extract code edit helper methods

9. **DirtyChecker.java - update the @Deprecated method**
   - Improve the documentation

---

## 8. Tool recommendations

### 8.1 Existing Java-Lens tools

✅ **Available and used:**

1. **`javalens_find_unused_code`** - Find unused private methods/fields
   ```bash
   javalens_find_unused_code
   ```

2. **`javalens_find_unreachable_code`** - Find code that is not reachable from entry points
   ```bash
   javalens_find_unreachable_code
   ```

3. **`javalens_find_annotation_usages`** - Find all @Deprecated usages
   ```bash
   javalens_find_annotation_usages(typeName="java.lang.Deprecated")
   ```

### 8.2 Missing tools (manual analysis required)

❌ **Not available, but useful:**

1. **Static analysis of method calls**
   - Which static methods are called where?
   - **Workaround**: `grep -r "Klassenname\.methode" src/`

2. **Dependency graph for classes**
   - Which classes depend on which?
   - **Workaround**: `javalens_get_dependency_graph`

3. **Inheritance hierarchy analysis**
   - Which classes inherit from which?
   - **Available**: `javalens_get_type_hierarchy`

---

## 9. Implementation plan

### Phase 1: Resolve critical blockers (1-2 days)

```
1. Migrate replaceInCodeOnly to BaseEditTool
   ├─ Extract the method from BaseJavaTool
   ├─ Insert into BaseEditTool
   └─ Adjust the calls in MoveClassTool, RenameSymbolTool

2. EditManager - adjust validateFingerprint
   ├─ Change BaseEditTool.validateFingerprint to public static
   └─ Or EditManager gets a BaseEditTool instance

3. RemoveAnnotationTool - adjust the validateAnnotationTarget call
   └─ BaseJavaTool → BaseEditTool

4. Delete BaseJavaTool
   └─ Check and remove all references
```

### Phase 2: Code hygiene (1 day)

```
5. ProjectManager - delete 7 unused methods
6. LombokMemberPredictor - delete 2 unused fields  
7. PomUtils - delete 1 unused field
```

### Phase 3: Documentation (0.5 day)

```
8. Update ARCHITEKTUR-REVIEW-GRUPPE-A.md
   └─ Mark group 9 as completed
   └─ Document the BaseJavaTool deletion

9. Finalize CODE-CLEANUP-ANALYSIS.md (this document)
```

### Phase 4: Optional improvements (2-3 days)

```
10. Create the CodeEditUtils helper class
11. Analyze and document unreachable code
12. Extract build path utilities
```

---

## 10. Summary & recommendations

### ✅ IMMEDIATELY ACTIONABLE

1. **BaseJavaTool can be deleted** - no inheritance left
2. **4 static method calls must be migrated** - they block the deletion
3. **12 unused private elements can be deleted** - direct improvement

### ⚠️ CAUTION

1. **DO NOT delete unreachable code (353 elements)** - framework code is often dynamically reachable
2. **Keep @Deprecated methods in BaseEditTool** - they might still be used

### 🎯 PRIORITY

1. **Highest priority**: migrate static method calls on BaseJavaTool
2. **High priority**: delete BaseJavaTool
3. **Medium priority**: clean up unused private elements

### 📊 SUCCESS METRICS

- **Before**: 1 deprecated class, 9 deprecated methods, 12 unused elements
- **After**: 0 deprecated classes, 0-2 deprecated methods, 0 unused elements
- **Code quality**: +15% (estimated)
- **Maintainability**: +20% (estimated)

---

*Analysis performed with the Java-Lens MCP server and manual code review*
*Tool queries: javalens_find_unused_code, javalens_find_unreachable_code, grep*
