package de.jare.rewisp.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class ProjectDirtyStateTest {

    @Test
    void markSource_addsEntry() {
        ProjectDirtyState state = new ProjectDirtyState();
        DirtyEntry entry = new DirtyEntry(
                Path.of("src/Foo.java"), 1L, ChangeOrigin.FILESYSTEM,
                EnumSet.of(DirtyReason.SOURCE_CHANGED));
        state.markSource(entry);

        assertTrue(state.hasSourceChanges());
        assertEquals(1, state.sourceDirty().size());
        assertEquals(entry, state.sourceDirty().get(Path.of("src/Foo.java")));
    }

    @Test
    void markBuildConfiguration_setsBuildModelAndClasspath() {
        ProjectDirtyState state = new ProjectDirtyState();
        state.markBuildConfiguration();

        assertTrue(state.isBuildModelDirty());
        assertTrue(state.isClasspathDirty());
        assertTrue(state.requiresFullSemanticRefresh());
    }

    @Test
    void requiresFullSemanticRefresh_trueWhenBuildModelDirty() {
        ProjectDirtyState state = new ProjectDirtyState();
        assertFalse(state.requiresFullSemanticRefresh());
        state.markBuildConfiguration();
        assertTrue(state.requiresFullSemanticRefresh());
    }

    @Test
    void requiresFullSemanticRefresh_trueWhenClasspathDirty() {
        ProjectDirtyState state = new ProjectDirtyState();
        assertFalse(state.requiresFullSemanticRefresh());
        state.markClasspath();
        assertTrue(state.requiresFullSemanticRefresh());
        assertTrue(state.isClasspathDirty());
        assertFalse(state.isBuildModelDirty());
    }

    @Test
    void clearAll_resetsEverything() {
        ProjectDirtyState state = new ProjectDirtyState();
        state.markSource(new DirtyEntry(Path.of("Foo.java"), 1L, ChangeOrigin.FILESYSTEM,
                EnumSet.of(DirtyReason.SOURCE_CHANGED)));
        state.markBuildConfiguration();

        state.clearAll();

        assertFalse(state.hasSourceChanges());
        assertFalse(state.isBuildModelDirty());
        assertFalse(state.isClasspathDirty());
        assertFalse(state.requiresFullSemanticRefresh());
    }

    @Test
    void clearSourceDirty_removesOnlyOneFile() {
        ProjectDirtyState state = new ProjectDirtyState();
        state.markSource(new DirtyEntry(Path.of("Foo.java"), 1L, ChangeOrigin.FILESYSTEM,
                EnumSet.of(DirtyReason.SOURCE_CHANGED)));
        state.markSource(new DirtyEntry(Path.of("Bar.java"), 1L, ChangeOrigin.FILESYSTEM,
                EnumSet.of(DirtyReason.SOURCE_CHANGED)));

        state.clearSourceDirty(Path.of("Foo.java"));

        assertEquals(1, state.sourceDirty().size());
        assertNotNull(state.sourceDirty().get(Path.of("Bar.java")));
    }

    @Test
    void merge_higherSequenceWins() {
        ProjectDirtyState state = new ProjectDirtyState();
        DirtyEntry lowSeq = new DirtyEntry(Path.of("Foo.java"), 1L, ChangeOrigin.FILESYSTEM,
                EnumSet.of(DirtyReason.SOURCE_CHANGED));
        DirtyEntry highSeq = new DirtyEntry(Path.of("Foo.java"), 5L, ChangeOrigin.MCP_APPLY,
                EnumSet.of(DirtyReason.SOURCE_CHANGED));

        state.markSource(lowSeq);
        state.markSource(highSeq);

        DirtyEntry result = state.sourceDirty().get(Path.of("Foo.java"));
        assertEquals(5L, result.observedSequence());
        assertEquals(ChangeOrigin.MCP_APPLY, result.origin());
    }

    @Test
    void merge_lowerSequenceDoesNotOverwrite() {
        ProjectDirtyState state = new ProjectDirtyState();
        DirtyEntry highSeq = new DirtyEntry(Path.of("Foo.java"), 5L, ChangeOrigin.MCP_APPLY,
                EnumSet.of(DirtyReason.SOURCE_CHANGED));
        DirtyEntry lowSeq = new DirtyEntry(Path.of("Foo.java"), 1L, ChangeOrigin.FILESYSTEM,
                EnumSet.of(DirtyReason.SOURCE_CHANGED));

        state.markSource(highSeq);
        state.markSource(lowSeq);

        DirtyEntry result = state.sourceDirty().get(Path.of("Foo.java"));
        assertEquals(5L, result.observedSequence());
        assertEquals(ChangeOrigin.MCP_APPLY, result.origin());
    }

    @Test
    void clearBuildDirty_resetsBuildAndClasspathButKeepsSource() {
        ProjectDirtyState state = new ProjectDirtyState();
        state.markSource(new DirtyEntry(Path.of("Foo.java"), 1L, ChangeOrigin.FILESYSTEM,
                EnumSet.of(DirtyReason.SOURCE_CHANGED)));
        state.markBuildConfiguration();

        state.clearBuildDirty();

        assertFalse(state.isBuildModelDirty());
        assertFalse(state.isClasspathDirty());
        assertTrue(state.hasSourceChanges(), "Source dirty entries should be preserved");
        assertFalse(state.requiresFullSemanticRefresh());
    }
}
