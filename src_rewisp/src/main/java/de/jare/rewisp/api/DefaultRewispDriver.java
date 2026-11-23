/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.analysis.ReferenceFinder;
import de.jare.rewisp.analysis.SignatureFormatter;
import de.jare.rewisp.analysis.TypeLookup;
import de.jare.rewisp.analysis.TypeNotFoundException;
import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.build.BuildResolutionException;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.lombok.LombokMemberPredictor;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.model.ProjectLoadException;
import de.jare.rewisp.project.ProjectLock;
import de.jare.rewisp.rewrite.RewriteEngine;

import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtType;

/**
 * Standard implementation of {@link RewispDriver} based on the {@link ProjectManager}.
 * It contains no MCP dependency; the MCP tools and other Java applications use
 * the same class.
 *
 * @author Janusch Rentenatus
 */
public class DefaultRewispDriver implements RewispDriver {

    private final ProjectManager manager;
    private final EditManager editManager;
    private final RewriteEngine engine;
    private final EditPipeline pipeline;

    /** Uses the {@link EditManager} carried by the {@link ProjectManager} (may be {@code null}). */
    public DefaultRewispDriver(ProjectManager manager) {
        this(manager, manager.editManager());
    }

    public DefaultRewispDriver(ProjectManager manager, EditManager editManager) {
        this(manager, editManager, null);
    }

    /** With rewrite engine for the edit operations (without an engine they are not available). */
    public DefaultRewispDriver(ProjectManager manager, EditManager editManager, RewriteEngine engine) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.editManager = editManager;
        this.engine = engine;
        this.pipeline = new EditPipeline(manager);
    }

    /** The underlying project manager (for extensions in the same process). */
    public ProjectManager manager() {
        return manager;
    }

    // ==================== Projektverwaltung ====================

    @Override
    public ProjectInfo loadProject(LoadRequest request) {
        ProjectEntry entry;
        ProjectLock.lock();
        try {
            entry = manager.load(request.source(), request.alias(), request.expiry(), request.delombok(),
                    request.editable());
        } catch (ProjectLoadException e) {
            throw new RewispException(e.getType(), e.getMessage());
        } finally {
            ProjectLock.unlock();
        }
        return info(entry);
    }

    @Override
    public String unloadProject(String nameOrAlias) {
        ProjectLock.lock();
        try {
            ProjectEntry removed = manager.remove(nameOrAlias);
            return removed == null ? null : removed.name();
        } finally {
            ProjectLock.unlock();
        }
    }

    @Override
    public List<ProjectInfo> listProjects() {
        return manager.list().stream().map(DefaultRewispDriver::info).toList();
    }

    private static ProjectInfo info(ProjectEntry e) {
        int types = e.model() == null ? -1 : e.model().getAllTypes().size();
        return new ProjectInfo(e.name(), e.alias(), types, e.buildType(), e.delomboked(), e.lombokVersion(),
                e.editable(), e.expiryDate(), e.expired());
    }

    @Override
    public List<ProjectInfo> reloadProjects(String nameOrAlias, boolean expired) {
        ProjectLock.lock();
        try {
            return ProjectOperations.reload(manager, nameOrAlias, expired).stream()
                    .map(manager::find).filter(Objects::nonNull).map(DefaultRewispDriver::info).toList();
        } finally {
            ProjectLock.unlock();
        }
    }

    @Override
    public void beginTransaction() {
        ProjectLock.lock();
        try {
            TransactionOperations.begin(editManager());
        } finally {
            ProjectLock.unlock();
        }
    }

    @Override
    public CommitResult commitTransaction() {
        ProjectLock.lock();
        try {
            return TransactionOperations.commit(manager, editManager());
        } finally {
            ProjectLock.unlock();
        }
    }

    @Override
    public void rollbackTransaction() {
        ProjectLock.lock();
        try {
            TransactionOperations.rollback(new EditPipeline(manager), editManager());
        } finally {
            ProjectLock.unlock();
        }
    }

    @Override
    public TransactionDiff transactionDiff(boolean statOnly) {
        return TransactionOperations.diff(manager, editManager(), statOnly);
    }

    @Override
    public List<String> editLog() {
        return editManager().getEditLog();
    }

    private EditManager editManager() {
        if (editManager == null) {
            throw new IllegalStateException("no EditManager configured");
        }
        return editManager;
    }

    @Override
    public RenameResult renameSymbol(RenameRequest request) {
        ProjectLock.lock();
        try {
            ProjectEntry entry = readEntry(request.project());
            return RenameOperations.rename(manager, editManager(), engine, pipeline, entry, request);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            ProjectLock.unlock();
        }
    }

    /** Action of an edit operation on the current entry; may write files. */
    @FunctionalInterface
    private interface EditAction<T> {
        T run(ProjectEntry entry) throws java.io.IOException;
    }

    /** Runs a mutating operation under the project lock (read entry, execute, unlock). */
    private <T> T editing(String project, EditAction<T> action) {
        ProjectLock.lock();
        try {
            return action.run(readEntry(project));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            ProjectLock.unlock();
        }
    }

    @Override
    public ProjectMetadata projectMetadata(String project) {
        return ProjectOperations.metadata(readEntry(project));
    }

    @Override
    public BuildContext refreshBuild(String project) {
        entry(project); // validiert, dass das Projekt existiert
        ProjectLock.lock();
        try {
            return manager.ensureBuildContext(project);
        } catch (BuildResolutionException e) {
            throw new RewispException(ErrorType.BUILD_RESOLUTION_ERROR, e.getMessage());
        } finally {
            ProjectLock.unlock();
        }
    }

    @Override
    public RebootResult reboot() {
        if (editManager == null) {
            throw new IllegalStateException("no EditManager configured");
        }
        ProjectLock.lock();
        try {
            return ProjectOperations.reboot(manager, editManager);
        } finally {
            ProjectLock.unlock();
        }
    }

    @Override
    public DirtyReport checkDirty(String project) {
        ProjectEntry entry = entry(project);
        return ProjectOperations.checkDirty(manager, entry, project);
    }

    @Override
    public EditResult replaceMethodBody(ReplaceBodyRequest request) {
        return editing(request.project(), entry -> ReplaceMethodBodyOperations.replace(editManager(), engine,
                pipeline, entry, request));
    }


    @Override
    public SignatureResult rewriteSignature(SignatureRequest request) {
        RewriteSignatureOperations.requireValidMode(request);
        return editing(request.project(), entry -> RewriteSignatureOperations.rewrite(editManager(), engine,
                pipeline, entry, request));
    }


    @Override
    public MoveClassResult moveClass(MoveClassRequest request) {
        return editing(request.project(), entry -> MoveClassOperations.move(manager, editManager(), engine,
                pipeline, entry, request));
    }


    @Override
    public EditResult addMethod(AddMethodRequest request) {
        return editing(request.project(), entry -> AddMethodOperations.add(editManager(), engine, pipeline, entry,
                request));
    }


    @Override
    public EditResult addField(AddFieldRequest request) {
        return editing(request.project(), entry -> AddFieldOperations.add(editManager(), engine, pipeline, entry,
                request));
    }


    @Override
    public AddClassResult addClass(AddClassRequest request) {
        AddClassOperations.requireValid(request);
        return editing(request.project(), entry -> AddClassOperations.add(manager, editManager(), entry, request));
    }


    @Override
    public EditResult addAnnotation(AddAnnotationRequest request) {
        return editing(request.project(), entry -> AnnotationOperations.add(editManager(), engine, pipeline, entry,
                request));
    }


    @Override
    public EditResult editAnnotation(EditAnnotationRequest request) {
        return editing(request.project(), entry -> AnnotationOperations.edit(editManager(), engine, pipeline, entry,
                request));
    }


    @Override
    public EditResult removeAnnotation(RemoveAnnotationRequest request) {
        return editing(request.project(), entry -> AnnotationOperations.remove(editManager(), engine, pipeline, entry,
                request));
    }


    @Override
    public RemoveMemberResult removeMember(RemoveMemberRequest request) {
        return editing(request.project(), entry -> RemoveMemberOperations.remove(editManager(), engine, pipeline,
                entry, request));
    }


    @Override
    public EditLineResult editLine(EditLineRequest request) {
        if (request.lineNumber() < 1) {
            throw new RewispException(ErrorType.DOMAIN_ERROR, "lineNumber must be >= 1");
        }
        return editing(request.project(), entry -> EditLineOperations.edit(manager, editManager(), entry,
                request));
    }


    @Override
    public AddPackageResult addPackage(AddPackageRequest request) {
        AddPackageOperations.requireValid(request);
        return editing(request.project(), entry -> AddPackageOperations.add(manager, editManager(), entry,
                request));
    }


    // ==================== Lesende Analyse ====================

    @Override
    public List<TypeSummary> listClasses(String project, String packageFilter, boolean recursive,
            boolean includeNested) {
        ProjectEntry entry = readEntry(project);
        String filter = packageFilter == null ? null : packageFilter.trim();
        List<CtType<?>> source = includeNested ? TypeLookup.allTypes(entry.model())
                : new ArrayList<CtType<?>>(entry.model().getAllTypes());
        return source.stream()
                .filter(t -> filter == null || filter.isEmpty() || inPackage(t, filter, recursive))
                .sorted(Comparator.comparing(CtType::getQualifiedName))
                .map(t -> new TypeSummary(t.getQualifiedName(), kind(t), t.getMethods().size(), t.isPublic()))
                .toList();
    }

    @Override
    public TypeDetails inspectClass(String project, String className, boolean withJavadoc) {
        ProjectEntry entry = readEntry(project);
        CtType<?> type = type(entry, className, "Class not found: ");

        String doc = withJavadoc ? type.getDocComment() : null;
        String javadoc = doc == null || doc.isBlank() ? null : doc.stripIndent().strip();
        String visibility = type.isPublic() ? "public" : type.isProtected() ? "protected"
                : type.isPrivate() ? "private" : "package-private";
        List<FieldInfo> fields = type.getFields().stream().map(f -> new FieldInfo(
                f.isPublic() ? "public" : f.isProtected() ? "protected" : f.isPrivate() ? "private" : "",
                String.valueOf(f.getType()), f.getSimpleName(), f.isStatic(), f.isFinal())).toList();
        List<String> constructors = type instanceof CtClass<?> cls
                ? cls.getConstructors().stream().filter(c -> !c.isImplicit())
                        .map(c -> SignatureFormatter.constructor(c)).toList()
                : List.of();
        boolean lombokDetected = !entry.delomboked() && LombokMemberPredictor.hasLombokAnnotations(type);
        List<PredictedMember> predicted = lombokDetected
                ? LombokMemberPredictor.predict(type).stream()
                        .sorted(Comparator.comparing(LombokMemberPredictor.PredictedMember::signature))
                        .map(m -> new PredictedMember(String.valueOf(m.kind()), m.signature())).toList()
                : List.of();
        return new TypeDetails(entry.name(), type.getQualifiedName(), kind(type), visibility,
                type.isAbstract(), type.isFinal(), type.isStatic(),
                type.getSuperclass() == null ? null : type.getSuperclass().getQualifiedName(),
                type.getSuperInterfaces().stream().map(i -> i.getQualifiedName()).toList(),
                javadoc, fields, constructors,
                type.getMethods().stream().map(m -> SignatureFormatter.method(m)).toList(),
                type.getNestedTypes().stream().map(n -> n.getQualifiedName()).toList(),
                type.getAnnotations().stream().map(String::valueOf).toList(),
                entry.delomboked(), entry.lombokVersion(), lombokDetected, predicted);
    }

    @Override
    public ReferenceResult findReferences(String project, String className, String memberName,
            String memberKind, List<String> parameterTypes) {
        ProjectEntry entry = readEntry(project);
        CtType<?> target = type(entry, className, "Type not found: ");
        List<ReferenceFinder.Reference> refs = ReferenceFinder.find(entry.model(), target, memberName,
                memberKind, parameterTypes, entry.originalProjectDir());
        List<Reference> out = refs.stream()
                .map(r -> new Reference(r.file(), r.location(), r.owner(), r.description())).toList();
        return new ReferenceResult(entry.name(), out, ReferenceFinder.files(refs).size());
    }

    @Override
    public ImplementationResult findImplementations(String project, String className) {
        ProjectEntry entry = readEntry(project);
        return TypeQueries.findImplementations(entry, type(entry, className, "Type not found: "), className);
    }

    @Override
    public TypeHierarchy typeHierarchy(String project, String className) {
        ProjectEntry entry = readEntry(project);
        return TypeQueries.typeHierarchy(entry, type(entry, className, "Class not found: "));
    }

    @Override
    public MethodOverloads inspectMethod(String project, String className, String methodName, boolean withJavadoc) {
        ProjectEntry entry = readEntry(project);
        return TypeQueries.inspectMethod(entry, type(entry, className, "Class not found: "), className, methodName,
                withJavadoc);
    }

    @Override
    public FieldDetails inspectField(String project, String className, String fieldName, boolean withJavadoc) {
        ProjectEntry entry = readEntry(project);
        return TypeQueries.inspectField(entry, type(entry, className, "Class not found: "), className, fieldName,
                withJavadoc);
    }

    @Override
    public SearchResult searchSource(String project, String query, boolean caseSensitive, int maxResults) {
        return SourceQueries.searchSource(manager, readEntry(project), project, query, caseSensitive, maxResults);
    }

    @Override
    public FileContent fileContent(String project, String className, Integer startLine, Integer endLine) {
        if ((startLine != null && startLine < 1) || (endLine != null && endLine < 1)
                || (startLine != null && endLine != null && endLine < startLine)) {
            throw new IllegalArgumentException("startLine/endLine must be >= 1 and endLine >= startLine");
        }
        ProjectEntry entry = readEntry(project);
        return SourceQueries.fileContent(manager, entry, project, type(entry, className, "Type not found: "),
                className, startLine, endLine);
    }

    @Override
    public ElementPosition elementPosition(String project, String className, String element, String elementType) {
        ProjectEntry entry = readEntry(project);
        return SourceQueries.elementPosition(entry, type(entry, className, "Class not found: "), className,
                element.trim(), elementType);
    }

    // ==================== Hilfen ====================

    /**
     * Entry for read-only analysis: Spoon reads only from the Rewrite state (snapshot plus transaction overlay),
     * the model is rebuilt when needed.
     */
    ProjectEntry readEntry(String nameOrAlias) {
        return manager.ensureSpoonModel(entry(nameOrAlias).name());
    }

    /** Finds the project; unknown names are reported as a domain error. */
    ProjectEntry entry(String nameOrAlias) {
        ProjectEntry entry = manager.find(nameOrAlias);
        if (entry == null) {
            throw new RewispException(ErrorType.DOMAIN_ERROR, "no Java project found with name or alias '"
                    + nameOrAlias + "'. Call load_java_project first.");
        }
        return entry;
    }

    private static CtType<?> type(ProjectEntry entry, String className, String messagePrefix) {
        try {
            return TypeLookup.require(entry.model(), className, messagePrefix);
        } catch (TypeNotFoundException e) {
            throw new RewispException(ErrorType.TYPE_NOT_FOUND, e.getMessage(),
                    Map.of("className", e.typeName()));
        }
    }

    private static boolean inPackage(CtType<?> type, String filter, boolean recursive) {
        if (type.getPackage() == null) return false;
        String pkg = type.getPackage().getQualifiedName();
        return pkg.equals(filter) || (recursive && pkg.startsWith(filter + "."));
    }

    private static TypeKind kind(CtType<?> type) {
        if (type.isClass()) return TypeKind.CLASS;
        if (type.isInterface()) return TypeKind.INTERFACE;
        if (type.isEnum()) return TypeKind.ENUM;
        if (type.isAnnotationType()) return TypeKind.ANNOTATION;
        return TypeKind.TYPE;
    }
}
