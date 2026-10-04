package de.jare.rewisp.snapshot;

import java.nio.file.Path;
import java.util.EnumSet;

public record DirtyEntry(
        Path relativePath,
        long observedSequence,
        ChangeOrigin origin,
        EnumSet<DirtyReason> reasons
) {}
