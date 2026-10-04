/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility-Klasse für das Parsen von Maven POM-Dateien.
 * Extrahiere Parent-POM-Referenzen und andere Metadaten ohne vollständigen XML-Parser.
 */
public final class PomUtils {

    private static final Pattern PARENT_GROUP_ID = Pattern.compile(
            "<parent>.*?<groupId>([^<]+)</groupId>.*?</parent>",
            Pattern.DOTALL);

    private static final Pattern PARENT_ARTIFACT_ID = Pattern.compile(
            "<parent>.*?<artifactId>([^<]+)</artifactId>.*?</parent>",
            Pattern.DOTALL);

    private static final Pattern PARENT_RELATIVE_PATH = Pattern.compile(
            "<parent>.*?<relativePath>([^<]+)</relativePath>.*?</parent>",
            Pattern.DOTALL);

    private PomUtils() {}

    /**
     * Extrahiere den Pfad zur Parent-POM relativ zum Projektverzeichnis.
     * Standardmäßig ist dies "../pom.xml" für Maven Multi-Module-Projekte.
     * 
     * @param projectDir Das Projektverzeichnis
     * @return Der absolute Pfad zur Parent-POM, oder null wenn nicht vorhanden
     */
    public static Path resolveParentPomPath(Path projectDir) {
        Path pomPath = projectDir.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            return null;
        }

        String parentRelativePath = extractParentRelativePath(pomPath);
        if (parentRelativePath != null && !parentRelativePath.isBlank()) {
            Path parentPath = projectDir.resolve(parentRelativePath).normalize();
            if (Files.exists(parentPath)) {
                return parentPath;
            }
        }

        // Standard-Pfad für Maven Multi-Module: ../pom.xml
        Path defaultParent = projectDir.resolve("..").resolve("pom.xml").normalize();
        if (Files.exists(defaultParent)) {
            return defaultParent;
        }

        return null;
    }

    /**
     * Extrahiere den relativePath aus der Parent-Sektion der POM.
     */
    private static String extractParentRelativePath(Path pomPath) {
        try {
            String content = Files.readString(pomPath);
            Matcher matcher = PARENT_RELATIVE_PATH.matcher(content);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (IOException e) {
            // Ignorieren
        }
        return null;
    }

    /**
     * Prüfe, ob die angegebene pom.xml eine Parent-POM Referenz enthält.
     * 
     * @param pomPath Der Pfad zur pom.xml
     * @return true wenn eine Parent-Referenz vorhanden ist
     */
    public static boolean hasParent(Path pomPath) {
        try {
            String content = Files.readString(pomPath);
            return content.contains("<parent>") && content.contains("</parent>");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Extrahiere die Parent GroupId aus der POM.
     */
    public static String extractParentGroupId(Path pomPath) {
        try {
            String content = Files.readString(pomPath);
            Matcher matcher = PARENT_GROUP_ID.matcher(content);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (IOException e) {
            // Ignorieren
        }
        return null;
    }

    /**
     * Extrahiere die Parent ArtifactId aus der POM.
     */
    public static String extractParentArtifactId(Path pomPath) {
        try {
            String content = Files.readString(pomPath);
            Matcher matcher = PARENT_ARTIFACT_ID.matcher(content);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (IOException e) {
            // Ignorieren
        }
        return null;
    }
}
