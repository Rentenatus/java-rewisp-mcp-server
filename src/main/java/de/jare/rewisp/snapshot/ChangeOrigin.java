package de.jare.rewisp.snapshot;

public enum ChangeOrigin {
    FILESYSTEM,
    EDITOR_BUFFER,
    OPENREWRITE,
    SPOON,
    BUILD_TOOL,
    MCP_APPLY
}
