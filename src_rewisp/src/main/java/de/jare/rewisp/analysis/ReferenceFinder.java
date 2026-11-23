/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.analysis;

import java.util.LinkedHashSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.jare.rewisp.util.PathUtils;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtFieldWrite;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtAnonymousExecutable;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Shared reference search in the Spoon model (find_references, safe mode of remove_member,
 * caller counting in rename_symbol). Searches every top-level type completely - methods,
 * constructors, initializers, fields, nested and anonymous classes, lambdas.
 *
 * @author Janusch Rentenatus
 */
public final class ReferenceFinder {

    /** Kind of a reference. */
    public enum Kind { METHOD_CALL, METHOD_REFERENCE, FIELD_READ, FIELD_WRITE, CONSTRUCTOR_CALL, TYPE_USAGE }

    /**
     * A reference site.
     *
     * @param kind        kind
     * @param file        path relative to the project (otherwise absolute), {@code null} without position
     * @param line        1-based line, 0 without position
     * @param owner       enclosing element, e.g. {@code Foo.bar(int)}
     * @param description what happens, e.g. {@code calls baz(java.lang.String)}
     */
    public record Reference(Kind kind, String file, int line, String owner, String description) {
        /** {@code file:line} resp. {@code ?} without position. */
        public String location() {
            return file == null ? "?" : file + ":" + line;
        }
    }

    private ReferenceFinder() {}

    /**
     * Finds references to a type or one of its members.
     *
     * @param model          the Spoon model
     * @param target         the target type
     * @param memberName     method/field name, {@code <init>} for constructors, {@code null} = everything
     * @param memberKind     {@code method}, {@code field}, {@code type}, {@code constructor} or {@code null}
     * @param parameterTypes optional overload filter (type names, qualified or simple)
     * @param projectRoot    base for relative paths (may be {@code null})
     */
    public static List<Reference> find(CtModel model, CtType<?> target, String memberName, String memberKind,
                                       List<String> parameterTypes, Path projectRoot) {
        String targetName = target.getQualifiedName();
        boolean ctorName = "<init>".equals(memberName);
        boolean methods = (memberKind == null || "method".equals(memberKind)) && !ctorName;
        boolean fields = (memberKind == null || "field".equals(memberKind)) && !ctorName;
        boolean ctors = ("constructor".equals(memberKind) || (memberKind == null && (memberName == null || ctorName)));
        boolean types = memberName == null && (memberKind == null || "type".equals(memberKind));
        String name = ctorName ? null : memberName;
        Path root = projectRoot == null ? null : PathUtils.canonical(projectRoot);

        Map<CtElement, Boolean> seen = new IdentityHashMap<>();
        List<Reference> out = new ArrayList<>();

        for (CtType<?> top : model.getAllTypes()) {
            if (methods) {
                for (CtInvocation<?> inv : top.getElements(new TypeFilter<>(CtInvocation.class))) {
                    CtExecutableReference<?> exec = inv.getExecutable();
                    if (inv.isImplicit() || exec == null || exec.isConstructor()) continue;
                    if (matchesMethod(exec, targetName, name, parameterTypes) && seen.put(inv, true) == null) {
                        out.add(ref(Kind.METHOD_CALL, inv, root, "calls " + signature(exec)));
                    }
                }
                for (CtExecutableReferenceExpression<?, ?> mr
                        : top.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class))) {
                    CtExecutableReference<?> exec = mr.getExecutable();
                    if (exec == null || exec.isConstructor()) continue;
                    if (matchesMethod(exec, targetName, name, parameterTypes) && seen.put(mr, true) == null) {
                        out.add(ref(Kind.METHOD_REFERENCE, mr, root, "references method " + signature(exec)));
                    }
                }
            }
            if (ctors) {
                for (CtConstructorCall<?> cc : top.getElements(new TypeFilter<>(CtConstructorCall.class))) {
                    CtExecutableReference<?> exec = cc.getExecutable();
                    if (cc.isImplicit() || exec == null) continue;
                    if (declaredIn(exec, targetName) && paramsMatch(exec, parameterTypes)
                            && seen.put(cc, true) == null) {
                        out.add(ref(Kind.CONSTRUCTOR_CALL, cc, root, "instantiates new " + signature(exec)));
                    }
                }
                for (CtInvocation<?> inv : top.getElements(new TypeFilter<>(CtInvocation.class))) {
                    CtExecutableReference<?> exec = inv.getExecutable();
                    if (inv.isImplicit() || exec == null || !exec.isConstructor()) continue; // this(...)/super(...)
                    if (declaredIn(exec, targetName) && paramsMatch(exec, parameterTypes)
                            && seen.put(inv, true) == null) {
                        out.add(ref(Kind.CONSTRUCTOR_CALL, inv, root, "delegates to constructor " + signature(exec)));
                    }
                }
            }
            if (fields) {
                for (CtFieldAccess<?> fa : top.getElements(new TypeFilter<>(CtFieldAccess.class))) {
                    CtFieldReference<?> field = fa.getVariable();
                    if (fa.isImplicit() || field == null || field.getDeclaringType() == null) continue;
                    if (field.getDeclaringType().getQualifiedName().equals(targetName)
                            && (name == null || field.getSimpleName().equals(name)) && seen.put(fa, true) == null) {
                        boolean write = fa instanceof CtFieldWrite;
                        out.add(ref(write ? Kind.FIELD_WRITE : Kind.FIELD_READ, fa, root,
                                (write ? "writes field " : "reads field ") + field.getSimpleName()));
                    }
                }
            }
            if (types) {
                for (CtTypeReference<?> tr : top.getElements(new TypeFilter<>(CtTypeReference.class))) {
                    if (tr.isImplicit() || !targetName.equals(tr.getQualifiedName()) || isPartOfMemberReference(tr)) {
                        continue;
                    }
                    // Komponententyp eines Arrays (I[]) traegt keine eigene Position: die des Array-Typs gilt
                    CtElement positioned = hasSourcePosition(tr) ? tr : arrayParentWithPosition(tr);
                    if (positioned == null) continue; // synthetische Ausdruckstypen (z.B. Typ von a[i]) stehen nirgends im Quelltext
                    if (seen.put(tr, true) == null) {
                        out.add(ref(Kind.TYPE_USAGE, positioned, root, "uses type " + target.getSimpleName()));
                    }
                }
            }
        }
        out.sort(Comparator.comparing((Reference r) -> r.file() == null ? "" : r.file())
                .thenComparingInt(Reference::line).thenComparing(Reference::description));
        return out;
    }

    private static boolean matchesMethod(CtExecutableReference<?> exec, String targetName, String name,
                                         List<String> parameterTypes) {
        return declaredIn(exec, targetName) && (name == null || exec.getSimpleName().equals(name))
                && paramsMatch(exec, parameterTypes);
    }

    private static boolean declaredIn(CtExecutableReference<?> exec, String targetName) {
        return exec.getDeclaringType() != null && exec.getDeclaringType().getQualifiedName().equals(targetName);
    }

    /** Types that only appear as part of a member reference (call, field access, new) are not counted twice. */
    private static boolean isPartOfMemberReference(CtTypeReference<?> tr) {
        try {
            CtElement parent = tr.getParent();
            return parent instanceof CtExecutableReference || parent instanceof CtFieldReference
                    || parent instanceof CtConstructorCall;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean paramsMatch(CtExecutableReference<?> exec, List<String> given) {
        if (given == null) return true;
        List<CtTypeReference<?>> actual = exec.getParameters();
        if (actual.size() != given.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            String want = erase(given.get(i));
            CtTypeReference<?> have = actual.get(i);
            if (!want.equals(have.getQualifiedName()) && !want.equals(have.getSimpleName())) return false;
        }
        return true;
    }

    private static String erase(String type) {
        String t = type.trim();
        int lt = t.indexOf('<');
        return lt >= 0 ? t.substring(0, lt).trim() : t;
    }

    private static String signature(CtExecutableReference<?> exec) {
        StringBuilder sb = new StringBuilder(exec.isConstructor() && exec.getDeclaringType() != null
                ? exec.getDeclaringType().getSimpleName() : exec.getSimpleName()).append('(');
        List<CtTypeReference<?>> params = exec.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(params.get(i).getSimpleName());
        }
        return sb.append(')').toString();
    }

    private static CtElement arrayParentWithPosition(CtTypeReference<?> tr) {
        try {
            CtElement parent = tr.isParentInitialized() ? tr.getParent() : null;
            return parent instanceof spoon.reflect.reference.CtArrayTypeReference && hasSourcePosition(parent)
                    ? parent : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean hasSourcePosition(CtElement element) {
        try {
            var pos = element.getPosition();
            return pos != null && pos.isValidPosition() && pos.getFile() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Reference ref(Kind kind, CtElement element, Path root, String description) {
        String file = null;
        int line = 0;
        try {
            var pos = element.getPosition();
            if (pos != null && pos.isValidPosition() && pos.getFile() != null) {
                Path p = PathUtils.canonical(pos.getFile().toPath());
                file = (root != null && p.startsWith(root) ? root.relativize(p) : p).toString().replace('\\', '/');
                line = pos.getLine();
            }
        } catch (RuntimeException e) {
            // Position nicht bestimmbar: Fundstelle ohne Datei/Zeile
        }
        return new Reference(kind, file, line, ownerOf(element), description);
    }

    /** Description of the enclosing member, e.g. {@code Foo.bar(int)}. */
    static String ownerOf(CtElement element) {
        try {
            CtType<?> enclosingType = null;
            for (CtElement p = element; p != null; p = p.isParentInitialized() ? p.getParent() : null) {
                if (enclosingType == null && p instanceof CtType<?> ct) enclosingType = ct;
                if (p instanceof CtMethod<?> m) return typeName(m.getDeclaringType()) + "." + m.getSignature();
                if (p instanceof CtConstructor<?> c) {
                    // Spoon liefert die Signatur mit qualifiziertem Typnamen: nur die Parameterliste uebernehmen
                    String sig = c.getSignature();
                    String simple = c.getDeclaringType().getSimpleName();
                    return typeName(c.getDeclaringType()) + "." + simple + sig.substring(Math.max(sig.indexOf('('), 0));
                }
                if (p instanceof CtField<?> f) return typeName(f.getDeclaringType()) + "." + f.getSimpleName();
                if (p instanceof CtAnonymousExecutable a) return "initializer of " + typeName(a.getDeclaringType());
            }
            // Fundstelle im Typkopf (extends/implements, Annotation am Typ): kein Member, aber ein Typ
            if (enclosingType != null) return "declaration of " + enclosingType.getSimpleName();
        } catch (RuntimeException e) {
            // Elternkette unvollstaendig
        }
        return "?";
    }

    private static String typeName(CtType<?> type) {
        return type == null ? "?" : type.getSimpleName();
    }

    /** Distinct files of the hits (for summaries). */
    public static Set<String> files(List<Reference> refs) {
        Set<String> files = new LinkedHashSet<>();
        for (Reference r : refs) if (r.file() != null) files.add(r.file());
        return files;
    }
}
