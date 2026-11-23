/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.api.RewispDriver.AddPackageRequest;
import de.jare.rewisp.api.RewispDriver.AddPackageResult;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.snapshot.ProjectState;
import de.jare.rewisp.util.JavaNames;
import de.jare.rewisp.util.PathUtils;

/**
 * {@code add_package}: creates a package directory, optionally with {@code package-info.java}. There is no
 * recipe; writing goes through the {@link EditManager}.
 *
 * @author Janusch Rentenatus
 */
final class AddPackageOperations {

    private static final Logger LOG = LoggerFactory.getLogger(AddPackageOperations.class);

    private AddPackageOperations() {
    }

    /** Validation of the package name; happens before project access. */
    static void requireValid(AddPackageRequest request) {
        String packageName = request.packageName();
        if (!JavaNames.isValidPackageName(packageName)) {
            throw new RewispException(ErrorType.INVALID_PACKAGE,
                    "Not a valid Java package name: '" + packageName + "'");
        }
    }

    static AddPackageResult add(ProjectManager manager, EditManager editManager, ProjectEntry entry,
            AddPackageRequest request) throws IOException {
        String packageName = request.packageName();
        boolean genInfo = request.generatePackageInfo();

        EditPipeline.requireEditable(entry);

        Path srcDir = ProjectManager.resolveSourceRoot(entry);
        if (srcDir == null) {
            srcDir = entry.projectDir();
            if (srcDir == null) srcDir = entry.originalProjectDir();
        }
        if (srcDir == null) throw new RewispException(ErrorType.DOMAIN_ERROR, "Cannot determine source directory.");

        Path packageDir = srcDir.resolve(packageName.replace(".", "/"));
        LOG.info("add_package: srcRoot={}, packageDir={}", srcDir, packageDir);

        // Three-layer architecture: check directory existence via snapshot (REWRITE layer)
        Path relativeDir = srcDir.relativize(packageDir);
        ProjectState state = manager.getProjectState(entry.name());
        if (state != null && state.snapshot() != null
                && EditManager.fileExistsInSnapshot(state.snapshot().files(), relativeDir)) {
            throw new RewispException(ErrorType.DOMAIN_ERROR,
                    "Package '" + packageName + "' already exists at " + packageDir);
        }

        if (genInfo) {
            Path existingInfo = packageDir.resolve("package-info.java");
            if (Files.exists(existingInfo)
                    || editManager.getPendingChanges().containsKey(PathUtils.canonical(existingInfo))) {
                throw new RewispException(ErrorType.DOMAIN_ERROR, "package-info.java already exists in " + packageDir
                        + " and will not be overwritten.");
            }
        }

        if (request.dryRun()) {
            return new AddPackageResult(entry.name(), true, srcDir, packageDir, genInfo, entry.modulesDetected(),
                    entry.modulesLoaded());
        }

        editManager.createDirectories(packageDir);

        if (genInfo) {
            Path infoFile = packageDir.resolve("package-info.java");
            String infoContent = "package " + packageName + ";\n\n";
            entry = editManager.writeFile(entry, infoFile, infoContent, null);
            manager.updateEntry(entry);
            editManager.logEdit("add_package: " + packageName + " (with package-info.java)");
        } else {
            manager.markDirty(entry.name());
            editManager.logEdit("add_package: " + packageName + " (empty dir)");
        }

        return new AddPackageResult(entry.name(), false, srcDir, packageDir, genInfo, entry.modulesDetected(),
                entry.modulesLoaded());
    }
}
