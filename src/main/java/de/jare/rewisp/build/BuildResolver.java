package de.jare.rewisp.build;

import java.nio.file.Path;

/**
 * Polymorphe Build-System-Anbindung.
 * Implementierungen: {@link MavenBuildResolver} (weitere in Folgephasen).
 */
public interface BuildResolver {

    boolean supports(Path projectRoot);

    BuildContext resolve(
            Path projectRoot,
            ResolveOptions options
    ) throws BuildResolutionException;
}
