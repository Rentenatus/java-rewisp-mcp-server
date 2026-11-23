/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import java.nio.file.InvalidPathException;

import de.jare.rewisp.util.ProjectLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing Maven POM files.
 * Extracts the parent POM reference without a full XML parser.
 */
public final class PomUtils {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PomUtils.class);

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private static final Pattern PARENT_BLOCK = Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL);

    /** Group 1 is the path; an empty {@code <relativePath/>} does not match (= parent not in the reactor). */
    private static final Pattern RELATIVE_PATH = Pattern.compile("<relativePath>([^<]*)</relativePath>");

    private static final Pattern EMPTY_RELATIVE_PATH = Pattern.compile("<relativePath\\s*/>");

    private PomUtils() {}

    /**
     * Determines the parent POM of the project in the filesystem.
     *
     * <p>Only when the {@code pom.xml} contains a {@code <parent>}. An empty
     * {@code <relativePath/>} means the parent is not local. Without a specification,
     * {@code ../pom.xml} applies. If the path points to a directory, {@code pom.xml} inside it is used.
     *
     * @param projectDir the project directory
     * @return the path to the parent POM, or null when no local parent POM exists
     */
    public static Path resolveParentPomPath(Path projectDir) {
        Path pomPath = projectDir.resolve(ProjectLayout.POM_FILE);
        if (!Files.isRegularFile(pomPath)) {
            return null;
        }
        String content;
        try {
            content = COMMENT.matcher(Files.readString(pomPath)).replaceAll("");
        } catch (IOException e) {
            LOG.debug("POM lookup failed: {}", e.getMessage());
            return null;
        }
        Matcher parent = PARENT_BLOCK.matcher(content);
        if (!parent.find()) {
            return null;
        }
        String block = parent.group(1);
        if (EMPTY_RELATIVE_PATH.matcher(block).find()) {
            return null;
        }
        Matcher rel = RELATIVE_PATH.matcher(block);
        String relative = rel.find() ? rel.group(1).trim() : "";
        if (relative.isEmpty()) {
            if (rel.reset().find()) {
                return null; // <relativePath></relativePath>: explizit kein lokaler Parent
            }
            relative = "../" + ProjectLayout.POM_FILE;
        }
        Path candidate;
        try {
            candidate = projectDir.resolve(relative).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (Files.isDirectory(candidate)) {
            candidate = candidate.resolve(ProjectLayout.POM_FILE);
        }
        return Files.isRegularFile(candidate) ? candidate : null;
    }
}
