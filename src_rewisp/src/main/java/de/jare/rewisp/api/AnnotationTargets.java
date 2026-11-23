/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

/**
 * Common validation of the annotation targets of {@code add_annotation}, {@code edit_annotation} and
 * {@code remove_annotation}.
 *
 * @author Janusch Rentenatus
 */
public final class AnnotationTargets {

    private AnnotationTargets() {
    }

    /**
     * Validates the target specification of an annotation.
     *
     * @param targetType {@code "class"}, {@code "method"} or {@code "field"}
     * @param targetName method or field name ({@code null}/empty for classes)
     * @return an error message when invalid, otherwise {@code null}
     */
    public static String validate(String targetType, String targetName) {
        if (!"class".equals(targetType) && !"method".equals(targetType) && !"field".equals(targetType)) {
            return "targetType must be 'class', 'method', or 'field'";
        }
        if (("method".equals(targetType) || "field".equals(targetType))
                && (targetName == null || targetName.isBlank())) {
            return "targetName required for method and field annotations";
        }
        return null;
    }
}
