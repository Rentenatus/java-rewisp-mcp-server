/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.time.Instant;
import java.util.List;

/**
 * Java interface to the rewisp backend: everything the MCP tools can do can be called here without
 * stdio and without MCP libraries (embedding in other applications). The MCP layer
 * ({@code src_mcp}) is a thin adapter that parses arguments, calls this driver and renders the
 * result as text.
 *
 * <p>Results are immutable records. Domain errors are reported as
 * {@link RewispException}. Multiple parallel calls are allowed; write
 * operations run under the process-wide {@code ProjectLock}.</p>
 *
 * <p>This interface grows step by step: first project management and read-only analysis,
 * then edit operations and transactions.</p>
 *
 * @author Janusch Rentenatus
 */
public interface RewispDriver {

    // ==================== Projektverwaltung ====================

    /** Loads a project (path, file:// URI, Git URL or archive). */
    ProjectInfo loadProject(LoadRequest request);

    /** Removes a project from memory; {@code null} if it was not loaded. */
    String unloadProject(String nameOrAlias);

    /** All loaded projects. */
    List<ProjectInfo> listProjects();

    /**
     * Reloads a project from its original source. With {@code expired=true}, all
     * expired projects are reloaded (plus {@code nameOrAlias}, if given).
     *
     * @return the reloaded projects; empty if nothing had expired
     */
    List<ProjectInfo> reloadProjects(String nameOrAlias, boolean expired);

    /** Numbers and directories of a loaded project. */
    ProjectMetadata projectMetadata(String project);

    /**
     * Resolves the build context (classpath, modules, source roots) when needed. The result is the
     * backend's build context.
     */
    de.jare.rewisp.build.BuildContext refreshBuild(String project);

    /**
     * Discards all projects and resets the edit state (transaction, backups, edit log) to recover from
     * parse errors or a broken model state.
     */
    RebootResult reboot();

    /**
     * Compares the disk state with the last synchronized state (external changes, own
     * edits, changed build files). Changes state: invalid build contexts are discarded.
     */
    DirtyReport checkDirty(String project);

    // ==================== Transaktionen ====================

    /** Starts a transaction; following edits are collected in memory until {@link #commitTransaction()}. */
    void beginTransaction();

    /** Atomically writes all collected changes to disk (all or nothing); the transaction ends. */
    CommitResult commitTransaction();

    /** Discards all collected changes; the Rewrite state is re-read from disk. */
    void rollbackTransaction();

    /** What the open transaction would change (diff against the frozen state); writes nothing. */
    TransactionDiff transactionDiff(boolean statOnly);

    /** Log of all edits since loading. */
    List<String> editLog();

    // ==================== Edits ====================

    /**
     * Renames a class, method or field, including all callers (OpenRewrite). With
     * {@code dryRun} nothing is written. Without a rewrite engine on the driver: error {@code OPENREWRITE_REQUIRED}.
     *
     * @throws RewispException {@code TYPE_NOT_FOUND}, {@code SYMBOL_NOT_FOUND}, {@code OVERLOADED_METHOD},
     *                         {@code INVALID_SCOPE}, {@code CLASS_EXISTS}, {@code EDIT_BREAKS_COMPILATION}, ...
     */
    RenameResult renameSymbol(RenameRequest request);

    /**
     * Replaces the entire body of an existing method (OpenRewrite); missing imports are resolved.
     *
     * @throws RewispException {@code INVALID_PARAMS}, {@code METHOD_NOT_FOUND}, {@code SIGNATURE_NOT_FOUND},
     *                         {@code OVERLOADED_METHOD}, {@code NO_BODY}, {@code UNRESOLVED_TYPES}, ...
     */
    EditResult replaceMethodBody(ReplaceBodyRequest request);


    /**
     * Changes a method's signature (return type and/or parameters); with mode
     * {@code signature_and_callers}, call sites are marked with TODO comments.
     *
     * @throws RewispException {@code INVALID_MODE}, {@code INVALID_PARAMS}, {@code METHOD_NOT_FOUND},
     *                         {@code OVERLOADED_METHOD}, {@code UNRESOLVED_TYPES}, {@code EDIT_BREAKS_COMPILATION}, ...
     */
    SignatureResult rewriteSignature(SignatureRequest request);


    /**
     * Moves a class to another package (package declaration, imports, source file).
     *
     * @throws RewispException {@code TYPE_NOT_FOUND}, {@code CLASS_EXISTS}, {@code NO_SOURCE_ROOT},
     *                         {@code EDIT_BREAKS_COMPILATION}, ...
     */
    MoveClassResult moveClass(MoveClassRequest request);


    /**
     * Adds a method to a class or interface (erasure check, automatic imports).
     *
     * @throws RewispException {@code INVALID_PARAMS}, {@code ERASURE_COLLISION}, {@code UNRESOLVED_TYPES}, ...
     */
    EditResult addMethod(AddMethodRequest request);


    /**
     * Adds a field to a class (duplicate check, automatic imports for type and initializer).
     *
     * @throws RewispException {@code INVALID_PARAMS}, {@code FIELD_EXISTS}, {@code UNRESOLVED_TYPES}, ...
     */
    EditResult addField(AddFieldRequest request);


    /**
     * Creates a new class, enum, interface or abstract class. The generated source text is
     * validated by the OpenRewrite parser and written in normalized form.
     *
     * @throws RewispException {@code INVALID_PARAMS}, {@code INVALID_TYPE}, {@code INVALID_PACKAGE},
     *                         {@code INVALID_CLASS_NAME}, {@code CLASS_EXISTS}, {@code PARSE_ERROR},
     *                         {@code EDIT_BREAKS_COMPILATION}, ...
     */
    AddClassResult addClass(AddClassRequest request);


    /**
     * Adds an annotation to a class, method or field (duplicate and {@code @Override} check).
     *
     * @throws RewispException {@code INVALID_PARAMS}, {@code MISSING_TARGET_NAME}, {@code INVALID_TARGET_TYPE},
     *                         {@code DUPLICATE_ANNOTATION}, {@code OVERLOADED_METHOD}, {@code OVERRIDE_INVALID}, ...
     * @throws IllegalArgumentException if the target method or field does not exist
     */
    EditResult addAnnotation(AddAnnotationRequest request);


    /**
     * Changes the attributes of an existing annotation on a class, method or field.
     *
     * @throws RewispException {@code INVALID_PARAMS}, {@code INVALID_TARGET}, {@code ANNOTATION_NOT_FOUND},
     *                         {@code TARGET_NOT_FOUND}, {@code OVERLOADED_METHOD}, ...
     */
    EditResult editAnnotation(EditAnnotationRequest request);


    /**
     * Removes an annotation from a class, method or field.
     *
     * @throws RewispException {@code INVALID_PARAMS}, {@code INVALID_TARGET}, {@code ANNOTATION_NOT_FOUND},
     *                         {@code TARGET_NOT_FOUND}, {@code OVERLOADED_METHOD}, ...
     */
    EditResult removeAnnotation(RemoveAnnotationRequest request);


    /**
     * Removes a method or field. In {@code safe} mode (default) the call is rejected when references
     * exist; in {@code hard} mode it is removed anyway (without compile check) and the dangling
     * references are reported.
     *
     * @throws RewispException {@code INVALID_SCOPE}, {@code REFERENCES_EXIST}, {@code MEMBER_NOT_FOUND},
     *                         {@code SIGNATURE_NOT_FOUND}, {@code OVERLOADED_METHOD}, ...
     */
    RemoveMemberResult removeMember(RemoveMemberRequest request);


    /**
     * Replaces a single line of a source file (no recipe, direct file edit with fingerprint check).
     * CR characters are removed before the comparison; the written line takes over the file's line ending.
     *
     * @throws RewispException {@code DOMAIN_ERROR} (line outside, file missing, {@code lineNumber < 1}),
     *                         {@code SECURITY_ERROR} (file outside the project), fingerprint conflicts, ...
     */
    EditLineResult editLine(EditLineRequest request);


    /**
     * Creates a package (directory structure), optionally with {@code package-info.java}.
     *
     * @throws RewispException {@code INVALID_PACKAGE}, {@code DOMAIN_ERROR} (package or
     *                         {@code package-info.java} already exists, no source directory)
     */
    AddPackageResult addPackage(AddPackageRequest request);


    // ==================== Lesende Analyse ====================

    /** Lists types of a project, optionally filtered by package. */
    List<TypeSummary> listClasses(String project, String packageFilter, boolean recursive, boolean includeNested);

    /** Details of a type (fields, methods, inheritance, annotations, incl. Lombok prediction if applicable). */
    TypeDetails inspectClass(String project, String className, boolean withJavadoc);

    /** All references to a type or member. */
    ReferenceResult findReferences(String project, String className, String memberName, String memberKind,
            List<String> parameterTypes);

    /** Implementations of an interface or abstract class (transitive, incl. nested types). */
    ImplementationResult findImplementations(String project, String className);

    /** Superclass chain, all interfaces and all subtypes of a type. */
    TypeHierarchy typeHierarchy(String project, String className);

    /** All overloads of a method incl. signature, modifiers, annotations and body. */
    MethodOverloads inspectMethod(String project, String className, String methodName, boolean withJavadoc);

    /** A field with type, modifiers, initializer and annotations. */
    FieldDetails inspectField(String project, String className, String fieldName, boolean withJavadoc);

    /**
     * Text search in the effective Rewrite state (snapshot, dirty files, transaction overlay).
     *
     * @param maxResults at most this many hit lines are listed; the counters remain exact
     */
    SearchResult searchSource(String project, String query, boolean caseSensitive, int maxResults);

    /**
     * Source text of the file containing a type, incl. fingerprint (for the optimistic locking of
     * {@code edit_line}).
     *
     * @param startLine first line (1-based) or {@code null}
     * @param endLine   last line (inclusive) or {@code null}
     */
    FileContent fileContent(String project, String className, Integer startLine, Integer endLine);

    /**
     * Exact position (file, line, column) of a class, method, constructor or field.
     *
     * @param element     name, for methods/constructors optionally with parameter types ({@code run(String,int)});
     *                    constructor: {@code <init>}
     * @param elementType {@code CLASS}, {@code METHOD}, {@code FIELD}, {@code CONSTRUCTOR} or {@code null}
     *                    (automatic; ambiguous = error {@code AMBIGUOUS_ELEMENT})
     */
    ElementPosition elementPosition(String project, String className, String element, String elementType);

    // ==================== Datentypen ====================

    /**
     * Parameters of {@link #loadProject}.
     *
     * @param source   path, file:// URI, Git URL or archive
     * @param alias    optional alias
     * @param expiry   optional expiry date (otherwise default lifetime)
     * @param delombok {@code true} (default): detect Lombok and delombok
     * @param editable {@code null}: default (editable); {@code false}: read-only
     */
    record LoadRequest(String source, String alias, Instant expiry, boolean delombok, Boolean editable) {
        public static LoadRequest of(String source) {
            return new LoadRequest(source, null, null, true, null);
        }
    }

    /**
     * A loaded project.
     *
     * @param types number of types; {@code -1} when the Spoon model has been released (rebuilt on access)
     */
    record ProjectInfo(String name, String alias, int types, String build, boolean delomboked,
            String lombokVersion, boolean editable, Instant expiry, boolean expired) {
        public boolean modelReleased() {
            return types < 0;
        }
    }

    /** Numbers and directories of a project ({@code analysedDirectory} only for a delomboked project). */
    record ProjectMetadata(String name, String alias, String build, String directory, String analysedDirectory,
            int modulesLoaded, int modulesDetected, long packages, long classes, long interfaces, long enums,
            long methods, long fields) { }

    /** Result of {@link #reboot}. */
    record RebootResult(List<String> removedProjects, int discardedChanges) { }

    /**
     * Result of {@link #checkDirty}. {@code baselineMissing}: there is no usable baseline snapshot
     * (state unknown; {@code changed}/{@code deleted}/{@code added} are then empty). {@code state} is
     * {@code null} when no state is tracked for the project.
     */
    record DirtyReport(String project, String previousSnapshotId, String currentSnapshotId, boolean baselineMissing,
            int filesChecked, List<String> changed, List<String> deleted, List<String> added, int editedFiles,
            List<String> changedBuildFiles, DirtyFlags state) {
        /** {@code true} when there are external changes to source files. */
        public boolean external() {
            return !changed.isEmpty() || !deleted.isEmpty() || !added.isEmpty();
        }
    }

    /** State flags of a project. */
    record DirtyFlags(boolean buildModelDirty, boolean classpathDirty, boolean buildFilesChanged,
            boolean requiresFullSemanticRefresh) { }

    /** Result of {@link #commitTransaction()}. */
    record CommitResult(int filesWritten, int filesDeleted) { }

    /**
     * A change of the open transaction. {@code kind} is {@code create}, {@code modify}, {@code delete}
     * or {@code mkdir}; {@code diff} is {@code null} for {@code statOnly} and for kinds without a line diff.
     */
    record FileChange(String kind, String path, int added, int removed, String diff) { }

    /**
     * Result of {@link #transactionDiff}. {@code restored}: touched files that are back at the
     * frozen state. There is no net change when {@code changes} is empty.
     */
    record TransactionDiff(List<FileChange> changes, int files, int added, int removed, int restored) { }

    /**
     * Parameter von {@link #renameSymbol}.
     *
     * @param scope         {@code method} (default), {@code field} or {@code class}
     * @param updateCallers {@code null}/{@code true}: update all callers; {@code false}: declaration only
     */
    record RenameRequest(String project, String className, String oldName, String newName, String scope,
            Boolean updateCallers, boolean dryRun) { }

    /**
     * Result of {@link #renameSymbol}. With {@code dryRun} nothing is written; {@code dryRunSnapshot}
     * is used to render the diff. {@code unresolved} (string literals with the old name) is
     * {@code null} for {@code dryRun}.
     */
    record RenameResult(String project, int declarationsRenamed, int callersUpdated, boolean updateCallers,
            de.jare.rewisp.rewrite.RewriteResult preview, de.jare.rewisp.snapshot.ProjectSnapshot dryRunSnapshot,
            boolean dryRun, de.jare.rewisp.edit.StringLiteralScanner.Result unresolved, int modulesDetected,
            int modulesLoaded) {
        public int changedFiles() {
            return preview.changedFiles().size();
        }
    }

    /** Kind of a type. */
    enum TypeKind { CLASS, INTERFACE, ENUM, ANNOTATION, TYPE }

    /** Short description of a type for {@link #listClasses}. */
    record TypeSummary(String qualifiedName, TypeKind kind, int methods, boolean isPublic) { }

    /** A field in {@link TypeDetails}. */
    record FieldInfo(String visibility, String type, String name, boolean isStatic, boolean isFinal) { }

    /** Member generated by Lombok at compile time, not present in the AST. */
    record PredictedMember(String kind, String signature) { }

    /**
     * Details of a type. {@code visibility} is {@code public}, {@code protected}, {@code private}
     * or {@code package-private}. {@code lombokDetected} only applies when the project is not delomboked.
     */
    record TypeDetails(String project, String qualifiedName, TypeKind kind, String visibility,
            boolean isAbstract, boolean isFinal, boolean isStatic, String superclass,
            List<String> interfaces, String javadoc, List<FieldInfo> fields, List<String> constructors,
            List<String> methods, List<String> nestedTypes, List<String> annotations,
            boolean delomboked, String lombokVersion, boolean lombokDetected,
            List<PredictedMember> predictedMembers) { }

    /** A reference to a symbol. {@code file} is {@code null} when no position is known. */
    record Reference(String file, String location, String owner, String description) { }

    /** Result of {@link #findImplementations}; all lists are sorted. */
    record ImplementationResult(String project, List<String> concrete, List<String> abstractTypes,
            List<String> subInterfaces) {
        public boolean isEmpty() {
            return concrete.isEmpty() && abstractTypes.isEmpty() && subInterfaces.isEmpty();
        }
    }

    /** Entry with indentation depth (0 = direct) in the type hierarchy. */
    record HierarchyNode(String name, String kind, int depth) { }

    /**
     * Result of {@link #typeHierarchy}. {@code superclasses} starts with the direct superclass;
     * {@code interfaces} and {@code subtypes} are in output order (depth for indentation);
     * {@code kind} is empty for interfaces.
     */
    record TypeHierarchy(String project, String qualifiedName, List<String> superclasses,
            List<HierarchyNode> interfaces, List<HierarchyNode> subtypes) { }

    /** A method in {@link MethodOverloads}. {@code body} is {@code null} for abstract/native methods. */
    record MethodDetails(String signature, String javadoc, String returnType, int parameterCount,
            String visibility, boolean isStatic, boolean isAbstract, boolean isFinal,
            List<String> annotations, String body) { }

    /** Result of {@link #inspectMethod}: one entry per overload. */
    record MethodOverloads(String project, List<MethodDetails> overloads) { }

    /** Result of {@link #inspectField}. {@code initializer} is {@code null} when there is no initializer. */
    record FieldDetails(String project, String javadoc, String type, String visibility, boolean isStatic,
            boolean isFinal, boolean isTransient, boolean isVolatile, String initializer,
            List<String> annotations) { }

    /** Hit line of the text search ({@code file} with forward slashes, {@code line} 1-based). */
    record SearchHit(String file, int line, String text) { }

    /**
     * Result of {@link #searchSource}: {@code hits} is limited to {@code maxResults},
     * {@code matchingLines} counts all hit lines, {@code occurrences} all occurrences.
     */
    record SearchResult(String project, List<SearchHit> hits, int occurrences, int matchingLines, int files) { }

    /**
     * Result of {@link #fileContent}: {@code lines} contains the lines {@code from..to}
     * (1-based, inclusive); {@code fingerprint} is {@code null} when unknown.
     */
    record FileContent(String project, String path, String absolutePath, String fingerprint, int totalLines,
            int from, int to, List<String> lines) { }

    /** Result of {@link #elementPosition}; {@code kind} is the Spoon element type (e.g. {@code CtMethodImpl}). */
    record ElementPosition(String project, String kind, String file, int line, int column, String fingerprint) { }

    /** Result of {@link #findReferences}. */
    record ReferenceResult(String project, List<Reference> references, int fileCount) { }

    /**
     * Common result of the rewrite edits. With {@code dryRun} nothing is written; {@code dryRunSnapshot}
     * is used to render the diff. {@code importsAdded} are the automatically added imports (never
     * {@code null}).
     */
    record EditResult(String project, de.jare.rewisp.rewrite.RewriteResult preview,
            de.jare.rewisp.snapshot.ProjectSnapshot dryRunSnapshot, boolean dryRun, int modulesDetected,
            int modulesLoaded, List<String> importsAdded) {
        public int changedFiles() {
            return preview.changedFiles().size();
        }
    }

    /**
     * Parameter von {@link #replaceMethodBody}.
     *
     * @param signature comma-separated parameter types to select an overload; {@code null}/empty: unique
     * @param newBody   new body without enclosing braces
     * @param imports   optional fully qualified imports for disambiguation; {@code null} same as empty
     */
    record ReplaceBodyRequest(String project, String className, String methodName, String signature,
            String newBody, List<String> imports, boolean dryRun) { }

    /**
     * Parameter von {@link #rewriteSignature}.
     *
     * @param newReturnType        {@code null}: return type unchanged
     * @param newParameters        {@code null}: parameters unchanged; empty: remove all parameters
     * @param mode                 {@code signature_only} or {@code signature_and_callers} (default, {@code null})
     * @param allowBreakingChanges {@code true}: apply the change even if callers no longer compile afterwards
     */
    record SignatureRequest(String project, String className, String methodName, String newReturnType,
            String newParameters, String mode, List<String> imports, boolean allowBreakingChanges,
            boolean dryRun) { }

    /**
     * Result of {@link #rewriteSignature}. {@code unchanged}: the signature was already as requested, nothing
     * was changed ({@code edit} is then {@code null}). {@code oldParameters} is the previous parameter list
     * ({@code Type name, ...}), {@code newDeclaration} the new declaration without modifiers.
     */
    record SignatureResult(String project, boolean unchanged, String oldParameters, String newDeclaration,
            String mode, int callersUpdated, EditResult edit) { }

    /** Parameter von {@link #moveClass}. */
    record MoveClassRequest(String project, String className, String newPackage, boolean dryRun) { }

    /**
     * Result of {@link #moveClass}. {@code alreadyThere}: the class is already in the target package, nothing was
     * changed ({@code edit} is then {@code null}). {@code movedTo}: package + simple name of the class at the target.
     * {@code deletionDeferred}: the old file is only deleted when
     * the open transaction is committed.
     */
    record MoveClassResult(String project, boolean alreadyThere, String movedTo, int importsUpdated,
            boolean deletionDeferred, EditResult edit) { }

    /**
     * Parameter von {@link #addMethod}.
     *
     * @param parameters comma-separated {@code Type name} pairs; {@code null}/empty: no parameters
     * @param modifiers  optional modifiers (e.g. {@code public static})
     * @param body       optional body without braces; for interfaces without body abstract, with body {@code default}
     */
    record AddMethodRequest(String project, String className, String methodName, String returnType,
            String parameters, String modifiers, String body, List<String> imports, boolean dryRun) { }

    /**
     * Parameter von {@link #addField}.
     *
     * @param modifiers   optional modifiers (e.g. {@code private static final})
     * @param initializer optional initializer expression
     */
    record AddFieldRequest(String project, String className, String fieldName, String type, String modifiers,
            String initializer, List<String> imports, boolean dryRun) { }

    /**
     * Parameter von {@link #addClass}.
     *
     * @param type       {@code class} (default, {@code null}), {@code enum}, {@code interface} or {@code abstract}
     * @param visibility {@code public} (default, {@code null}/empty) or {@code package}
     * @param body       optional class body (members)
     */
    record AddClassRequest(String project, String packageName, String className, String type, String visibility,
            String body, List<String> imports, boolean dryRun) { }

    /**
     * Result of {@link #addClass}. {@code content} is the (normalized) source text of the new file
     * {@code file}; with {@code dryRun} nothing was written.
     */
    record AddClassResult(String project, boolean dryRun, java.nio.file.Path file, String content,
            int modulesDetected, int modulesLoaded) { }

    /**
     * Parameter von {@link #addAnnotation}.
     *
     * @param targetType {@code class}, {@code method} or {@code field}
     * @param targetName method or field name; {@code null} for {@code class}
     * @param attributes optional {@code key=value} pairs
     */
    record AddAnnotationRequest(String project, String targetType, String className, String targetName,
            String annotation, String attributes, boolean dryRun) { }

    /**
     * Parameter von {@link #editAnnotation}.
     *
     * @param targetType {@code class} (default, {@code null}), {@code method} or {@code field}
     * @param targetName method or field name; {@code null} for {@code class}
     */
    record EditAnnotationRequest(String project, String targetType, String className, String targetName,
            String annotation, String newAttributes, boolean dryRun) { }

    /**
     * Parameter von {@link #removeAnnotation}.
     *
     * @param targetType {@code class}, {@code method} or {@code field}
     * @param targetName method or field name; {@code null} for {@code class}
     */
    record RemoveAnnotationRequest(String project, String targetType, String className, String targetName,
            String annotation, boolean dryRun) { }

    /**
     * Parameter von {@link #removeMember}.
     *
     * @param scope     {@code method} (default, {@code null}) or {@code field}
     * @param mode      {@code safe} (default) or {@code hard}
     * @param signature comma-separated parameter types to select an overload; {@code null}/empty: unique
     */
    record RemoveMemberRequest(String project, String className, String memberName, String scope, String mode,
            String signature, boolean dryRun) { }

    /**
     * Result of {@link #removeMember}. {@code danglingReferences}: references that no longer compile after a
     * {@code hard} removal; {@code stringReferences}: prepared lines for
     * string literals with the member name (determined after writing, empty for {@code dryRun}).
     */
    record RemoveMemberResult(EditResult edit, boolean hard, List<String> danglingReferences,
            List<String> stringReferences) { }

    /**
     * Parameter von {@link #editLine}.
     *
     * @param file                path relative to the source root or absolute
     * @param lineNumber          1-based
     * @param newContent          new line content without line ending; {@code null} same as empty
     * @param expectedFingerprint optional, for optimistic locking
     */
    record EditLineRequest(String project, String file, int lineNumber, String newContent,
            String expectedFingerprint, boolean dryRun) { }

    /**
     * Result of {@link #editLine}. {@code oldLine}/{@code newLine} are the line before and after the change
     * (without line ending); with {@code dryRun} nothing was written.
     */
    record EditLineResult(String project, boolean dryRun, java.nio.file.Path file, int lineNumber, String oldLine,
            String newLine, int modulesDetected, int modulesLoaded) { }

    /** Parameter von {@link #addPackage}. */
    record AddPackageRequest(String project, String packageName, boolean generatePackageInfo, boolean dryRun) { }

    /**
     * Result of {@link #addPackage}. {@code sourceRoot} is the source root, {@code packageDir} the
     * (new) package directory; with {@code dryRun} nothing was written.
     */
    record AddPackageResult(String project, boolean dryRun, java.nio.file.Path sourceRoot,
            java.nio.file.Path packageDir, boolean packageInfoCreated, int modulesDetected, int modulesLoaded) { }
}
