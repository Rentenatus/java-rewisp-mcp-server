/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
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

package de.jare.rewisp.tools.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.jare.rewisp.snapshot.DirtyCheckResult;

/**
 * Tests for dirty-check helpers in {@link BaseEditTool}: {@code formatExpiredWarning},
 * {@code formatDirtyWarning}, {@code DirtyCheckResult}, and {@link DirtyChecker#checkDirtyFullScan}.
 *
 * @author Janusch Rentenatus
 */
class DirtyCheckTest {

    // --- formatExpiredWarning ---

    @Test
    void formatExpiredWarningEmptyListReturnsEmpty() {
        assertEquals("", BaseEditTool.formatExpiredWarning(List.of()));
    }

    @Test
    void formatExpiredWarningNullReturnsEmpty() {
        assertEquals("", BaseEditTool.formatExpiredWarning(null));
    }

    @Test
    void formatExpiredWarningSingleProject() {
        String warning = BaseEditTool.formatExpiredWarning(List.of("my-project"));
        assertNotNull(warning);
        assertTrue(warning.contains("my-project"));
        assertTrue(warning.contains("Expired projects"));
        assertTrue(warning.contains("reload_java_project"));
    }

    @Test
    void formatExpiredWarningMultipleProjects() {
        String warning = BaseEditTool.formatExpiredWarning(List.of("proj-a", "proj-b", "proj-c"));
        assertTrue(warning.contains("proj-a"));
        assertTrue(warning.contains("proj-b"));
        assertTrue(warning.contains("proj-c"));
    }

    // --- DirtyCheckResult ---

    @Test
    void dirtyCheckResultIsDirtyWhenChanged() {
        DirtyCheckResult r = new DirtyCheckResult(1, List.of("file.java"), List.of(), List.of());
        assertTrue(r.isDirty());
    }

    @Test
    void dirtyCheckResultIsDirtyWhenDeleted() {
        DirtyCheckResult r = new DirtyCheckResult(1, List.of(), List.of("deleted.java"), List.of());
        assertTrue(r.isDirty());
    }

    @Test
    void dirtyCheckResultIsDirtyWhenNew() {
        DirtyCheckResult r = new DirtyCheckResult(1, List.of(), List.of(), List.of("new.java"));
        assertTrue(r.isDirty());
    }

    @Test
    void dirtyCheckResultIsCleanWhenAllEmpty() {
        DirtyCheckResult r = new DirtyCheckResult(5, List.of(), List.of(), List.of());
        assertFalse(r.isDirty());
    }

    // --- formatDirtyWarning ---

    @Test
    void formatDirtyWarningCleanReturnsEmpty() {
        DirtyCheckResult clean = new DirtyCheckResult(1, List.of(), List.of(), List.of());
        assertEquals("", BaseEditTool.formatDirtyWarning(clean));
    }

    @Test
    void formatDirtyWarningDirtyContainsFiles() {
        DirtyCheckResult dirty = new DirtyCheckResult(2, List.of("a.java", "b.java"), List.of(), List.of());
        String warning = BaseEditTool.formatDirtyWarning(dirty);
        assertTrue(warning.contains("a.java"));
        assertTrue(warning.contains("b.java"));
        assertTrue(warning.contains("reload_java_project"));
    }

    @Test
    void formatDirtyWarningCapsAtTenFiles() {
        java.util.List<String> files = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) files.add("file" + i + ".java");
        DirtyCheckResult dirty = new DirtyCheckResult(15, files, List.of(), List.of());
        String warning = BaseEditTool.formatDirtyWarning(dirty);
        assertTrue(warning.contains("file0.java"));
        assertTrue(warning.contains("file9.java"));
        assertFalse(warning.contains("file14.java"));
        assertTrue(warning.contains("5 more"));
    }
}
