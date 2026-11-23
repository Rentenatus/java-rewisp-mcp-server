/*
 * MIT License
 *
 * Copyright (c) 2026 Alejandro Ferreira
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.jare.rewisp;

import de.jare.rewisp.util.ProjectLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

/**
 * The {@code BuildDetector} class.
 *
 * @author Alejandro Ferreira
 */
public class BuildDetector {

    public enum BuildType {
        MAVEN,
        GRADLE,
        ECLIPSE,
        ANT,
        RAW
    }

    private static final List<String> MAVEN_MARKERS = List.of(ProjectLayout.POM_FILE);
    private static final List<String> GRADLE_MARKERS = ProjectLayout.GRADLE_BUILD_FILES;
    private static final List<String> ECLIPSE_MARKERS = List.of(".project", ".classpath");
    private static final List<String> IVY_MARKERS = List.of("ivy.xml");
    private static final List<String> ANT_MARKERS = List.of("build.xml");

    /**
     * Build information including the detected build marker files.
     * This lets tools like InspectBuildConfigTool display the found
     * build files without reading the filesystem directly.
     */
    public record BuildInfo(
            BuildType type,
            String detail,
            Path projectDir,
            List<String> detectedMarkers
    ) {}

/**
 * Detects the build system used in the given project directory.
 */
    public BuildInfo detect(Path dir) {
        List<String> foundMarkers = new ArrayList<>();
        
        // Check Maven
        if (checkAndCollectMarkers(dir, MAVEN_MARKERS, foundMarkers)) {
            return new BuildInfo(BuildType.MAVEN, "Maven", dir, foundMarkers);
        }
        
        // Check Gradle
        if (checkAndCollectMarkers(dir, GRADLE_MARKERS, foundMarkers)) {
            return new BuildInfo(BuildType.GRADLE, "Gradle", dir, foundMarkers);
        }
        
        // Check Eclipse
        if (checkAndCollectMarkers(dir, ECLIPSE_MARKERS, foundMarkers)) {
            return new BuildInfo(BuildType.ECLIPSE, "Eclipse", dir, foundMarkers);
        }
        
        // Ant braucht ein build.xml; ein zusaetzliches ivy.xml macht daraus "Ant + Ivy" (ivy.xml allein ist kein Ant)
        if (checkAndCollectMarkers(dir, ANT_MARKERS, foundMarkers)) {
            boolean ivy = checkAndCollectMarkers(dir, IVY_MARKERS, foundMarkers);
            return new BuildInfo(BuildType.ANT, ivy ? "Ant + Ivy" : "Ant", dir, foundMarkers);
        }
        
        return new BuildInfo(BuildType.RAW, "Raw (no build system detected)", dir, List.of());
    }

    /**
     * Checks whether the marker files exist and collects the found files.
     * @return true if at least one marker file was found
     */
    private boolean checkAndCollectMarkers(Path dir, List<String> markers, List<String> foundMarkers) {
        boolean anyFound = false;
        for (String marker : markers) {
            if (hasFile(dir, marker)) {
                foundMarkers.add(marker);
                anyFound = true;
            }
        }
        return anyFound;
    }

/**
 * Checks whether a file with the given name exists in the directory.
 */
    private boolean hasFile(Path dir, String name) {
        return Files.exists(dir.resolve(name));
    }
}
