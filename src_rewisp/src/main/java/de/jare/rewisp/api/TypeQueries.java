/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.jare.rewisp.analysis.SignatureFormatter;
import de.jare.rewisp.analysis.TypeLookup;
import de.jare.rewisp.api.RewispDriver.FieldDetails;
import de.jare.rewisp.api.RewispDriver.HierarchyNode;
import de.jare.rewisp.api.RewispDriver.ImplementationResult;
import de.jare.rewisp.api.RewispDriver.MethodDetails;
import de.jare.rewisp.api.RewispDriver.MethodOverloads;
import de.jare.rewisp.api.RewispDriver.TypeHierarchy;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;

import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;

/**
 * Analysis of single types and their relationships (implementations, hierarchy, methods, fields).
 * Pure functions on the Spoon model of a {@link ProjectEntry}.
 *
 * @author Janusch Rentenatus
 */
final class TypeQueries {

    private TypeQueries() {
    }

    // ==================== find_implementations ====================

    static ImplementationResult findImplementations(ProjectEntry entry, CtType<?> target, String className) {
        if (!target.isInterface() && !target.isAbstract()) {
            throw new RewispException(ErrorType.DOMAIN_ERROR,
                    "'" + className + "' is neither an interface nor an abstract class.");
        }
        List<String> concrete = new ArrayList<>();
        List<String> abstractTypes = new ArrayList<>();
        List<String> subInterfaces = new ArrayList<>();
        for (var type : TypeLookup.allTypes(entry.model())) {
            if (type.equals(target) || !implementsOrExtends(type, target.getQualifiedName(), entry.model())) continue;
            if (type.isInterface()) subInterfaces.add(type.getQualifiedName());
            else if (type.isAbstract()) abstractTypes.add(type.getQualifiedName());
            else concrete.add(type.getQualifiedName());
        }
        concrete.sort(String::compareTo);
        abstractTypes.sort(String::compareTo);
        subInterfaces.sort(String::compareTo);
        return new ImplementationResult(entry.name(), concrete, abstractTypes, subInterfaces);
    }

    /** Checks recursively whether a type implements or extends the target. */
    private static boolean implementsOrExtends(CtType<?> type, String targetFqn, CtModel model) {
        var superClass = type.getSuperclass();
        if (superClass != null) {
            if (targetFqn.equals(superClass.getQualifiedName())) return true;
            // Superklasse rekursiv pruefen: auch ihre Interfaces zaehlen (Impl extends Abs implements Mid)
            var superType = TypeLookup.find(model, superClass.getQualifiedName());
            if (superType != null && superType != type && implementsOrExtends(superType, targetFqn, model)) return true;
        }
        for (var iface : type.getSuperInterfaces()) {
            if (targetFqn.equals(iface.getQualifiedName())) return true;
            var ifaceType = TypeLookup.find(model, iface.getQualifiedName());
            if (ifaceType != null && implementsOrExtends(ifaceType, targetFqn, model)) return true;
        }
        return false;
    }

    // ==================== get_type_hierarchy ====================

    static TypeHierarchy typeHierarchy(ProjectEntry entry, CtType<?> type) {
        CtModel model = entry.model();
        String qn = type.getQualifiedName();

        List<String> superclasses = new ArrayList<>();
        Set<String> seen = new HashSet<>(List.of(qn));
        var superClass = type.getSuperclass();
        while (superClass != null && seen.add(superClass.getQualifiedName())) {
            superclasses.add(superClass.getQualifiedName());
            var superType = TypeLookup.find(model, superClass.getQualifiedName());
            superClass = superType != null ? superType.getSuperclass() : null;
        }

        List<HierarchyNode> interfaces = new ArrayList<>();
        appendInterfaces(model, type, interfaces, 0, new HashSet<>(List.of(qn)));

        Map<String, List<CtType<?>>> children = new HashMap<>();
        for (CtType<?> t : TypeLookup.allTypes(model)) {
            if (t.getSuperclass() != null) {
                children.computeIfAbsent(t.getSuperclass().getQualifiedName(), k -> new ArrayList<>()).add(t);
            }
            for (var i : t.getSuperInterfaces()) {
                children.computeIfAbsent(i.getQualifiedName(), k -> new ArrayList<>()).add(t);
            }
        }
        List<HierarchyNode> subtypes = new ArrayList<>();
        appendSubtypes(children, qn, subtypes, 0, new HashSet<>(List.of(qn)));
        return new TypeHierarchy(entry.name(), qn, superclasses, interfaces, subtypes);
    }

    /** Interfaces of the type, of its interfaces (recursively) and of its superclasses. */
    private static void appendInterfaces(CtModel model, CtType<?> type, List<HierarchyNode> out, int depth,
            Set<String> visited) {
        List<spoon.reflect.reference.CtTypeReference<?>> direct = new ArrayList<>(type.getSuperInterfaces());
        var sc = type.getSuperclass();
        while (sc != null && visited.add("class:" + sc.getQualifiedName())) {
            var superType = TypeLookup.find(model, sc.getQualifiedName());
            if (superType == null) break;
            for (var i : superType.getSuperInterfaces()) {
                if (direct.stream().noneMatch(d -> d.getQualifiedName().equals(i.getQualifiedName()))) direct.add(i);
            }
            sc = superType.getSuperclass();
        }
        for (var iface : direct) {
            String ifaceName = iface.getQualifiedName();
            out.add(new HierarchyNode(ifaceName, "", depth));
            if (!visited.add(ifaceName)) continue; // Zyklenschutz und keine Doppelausgabe
            CtType<?> ifaceType = TypeLookup.find(model, ifaceName);
            if (ifaceType != null) {
                appendInterfaces(model, ifaceType, out, depth + 1, visited);
            } else {
                try {
                    for (var parent : iface.getSuperInterfaces()) { // Klassenpfad-Typ: ueber Reflexion
                        out.add(new HierarchyNode(parent.getQualifiedName(), "", depth + 1));
                    }
                } catch (RuntimeException e) {
                    // nicht aufloesbar: nur der Name wird gezeigt
                }
            }
        }
    }

    /** Subtypes recursively (via superclass and interfaces), with cycle protection. */
    private static void appendSubtypes(Map<String, List<CtType<?>>> children, String qn, List<HierarchyNode> out,
            int depth, Set<String> visited) {
        var subs = children.getOrDefault(qn, List.of()).stream()
                .sorted(Comparator.comparing(t -> t.getQualifiedName())).toList();
        for (var sub : subs) {
            String subName = sub.getQualifiedName();
            String kind = sub.isInterface() ? "interface" : sub.isEnum() ? "enum" : "class";
            out.add(new HierarchyNode(subName, kind, depth));
            if (visited.add(subName)) {
                appendSubtypes(children, subName, out, depth + 1, visited);
            }
        }
    }

    // ==================== inspect_method ====================

    static MethodOverloads inspectMethod(ProjectEntry entry, CtType<?> type, String className, String methodName,
            boolean withJavadoc) {
        var methods = type.getMethods().stream().filter(m -> m.getSimpleName().equals(methodName)).toList();
        if (methods.isEmpty()) {
            throw new RewispException(ErrorType.DOMAIN_ERROR,
                    "Method '" + methodName + "' not found in " + className);
        }
        List<MethodDetails> out = new ArrayList<>();
        for (var method : methods) {
            String doc = withJavadoc ? method.getDocComment() : null;
            out.add(new MethodDetails(SignatureFormatter.method(method),
                    doc == null || doc.isBlank() ? null : doc.stripIndent().strip(),
                    method.getType().toString(), method.getParameters().size(),
                    method.isPublic() ? "public" : method.isProtected() ? "protected"
                            : method.isPrivate() ? "private" : "package-private",
                    method.isStatic(), method.isAbstract(), method.isFinal(),
                    method.getAnnotations().stream().map(String::valueOf).toList(),
                    method.getBody() == null ? null : originalBody(method.getBody())));
        }
        return new MethodOverloads(entry.name(), out);
    }

    /** Body in the original text (comments and formatting), fallback: pretty-print. */
    private static String originalBody(spoon.reflect.code.CtBlock<?> body) {
        try {
            String source = body.getOriginalSourceFragment().getSourceCode();
            if (source != null && !source.isBlank()) return source;
        } catch (RuntimeException e) {
            // keine Originalquelle verfuegbar
        }
        return body.prettyprint();
    }

    // ==================== inspect_field ====================

    static FieldDetails inspectField(ProjectEntry entry, CtType<?> type, String className, String fieldName,
            boolean withJavadoc) {
        var field = type.getFields().stream()
                .filter(f -> f.getSimpleName().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new RewispException(ErrorType.DOMAIN_ERROR,
                        "Field '" + fieldName + "' not found in " + className));
        String doc = withJavadoc ? field.getDocComment() : null;
        return new FieldDetails(entry.name(),
                doc == null || doc.isBlank() ? null : doc.stripIndent().strip(),
                String.valueOf(field.getType()),
                field.isPublic() ? "public" : field.isProtected() ? "protected"
                        : field.isPrivate() ? "private" : "package-private",
                field.isStatic(), field.isFinal(), field.isTransient(), field.isVolatile(),
                field.getDefaultExpression() == null ? null : String.valueOf(field.getDefaultExpression()),
                field.getAnnotations().stream().map(a -> "@" + a.getAnnotationType().getQualifiedName()).toList());
    }
}
