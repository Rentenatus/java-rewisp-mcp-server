package de.jare.rewisp.build;

import java.nio.file.Path;

/**
 * Polymorphic build system binding.
 * Implementations: {@link MavenBuildResolver} (more in later phases).
 */
public interface BuildResolver {

    boolean supports(Path projectRoot);

    BuildContext resolve(
            Path projectRoot,
            ResolveOptions options
    ) throws BuildResolutionException;
}
