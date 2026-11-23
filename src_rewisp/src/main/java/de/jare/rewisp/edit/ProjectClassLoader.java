/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import de.jare.rewisp.model.ProjectEntry;

/**
 * Short-lived class loader over the project's classpath (in addition to the server's JVM),
 * to check type names against the real dependencies. Must be closed so that on
 * Windows no JAR files stay locked.
 *
 * @author Janusch Rentenatus
 */
final class ProjectClassLoader implements AutoCloseable {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProjectClassLoader.class);

    private final URLClassLoader loader;

    private ProjectClassLoader(URLClassLoader loader) {
        this.loader = loader;
    }

    /** Builds the loader from the source classpath of the Spoon model (without entries: only system loader). */
    static ProjectClassLoader of(ProjectEntry entry) {
        List<URL> urls = new ArrayList<>();
        try {
            String[] cp = entry != null && entry.model() != null && entry.model().getRootPackage() != null
                    ? entry.model().getRootPackage().getFactory().getEnvironment().getSourceClasspath() : null;
            if (cp != null) {
                for (String p : cp) {
                    if (p == null || p.isBlank()) continue;
                    try {
                        urls.add(new File(p).toURI().toURL());
                    } catch (java.net.MalformedURLException e) {
                        LOG.debug("Ignoring invalid classpath entry '{}': {}", p, e.getMessage());
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("Could not read project classpath: {}", e.getMessage());
        }
        return new ProjectClassLoader(new URLClassLoader(urls.toArray(new URL[0]),
                ClassLoader.getSystemClassLoader()));
    }

    /** True when the class is loadable in the project classpath or in the JVM (without initialization). */
    boolean canLoad(String fqn) {
        try {
            Class.forName(fqn, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            loader.close();
        } catch (IOException e) {
            LOG.debug("Could not close project class loader: {}", e.getMessage());
        }
    }
}
