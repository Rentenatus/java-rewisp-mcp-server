package de.jare.rewisp.rewrite;

import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.build.ModuleContext;
import de.jare.rewisp.snapshot.EffectiveProjectSources;
import de.jare.rewisp.snapshot.ResolutionMode;

import spoon.Launcher;
import spoon.SpoonModelBuilder;
import spoon.reflect.CtModel;
import spoon.support.compiler.VirtualFile;
import spoon.support.compiler.jdt.JDTBasedSpoonCompiler;

import org.eclipse.jdt.core.compiler.CategorizedProblem;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Implementierung von {@link SpoonEngine}.
 *
 * <p>Baut ein {@code CtModel} aus In-Memory-Quellen ({@link VirtualFile}),
 * ohne Dateien auf die Festplatte zu schreiben. Dies ermoeglicht die
 * Analyse von transformierten Quellen aus der OpenRewrite-Pipeline.
 *
 * <p>Das bestehende Singleton-Modell in {@code ProjectEntry} bleibt fuer
 * die regulaeren Analyse-Tools erhalten. Diese Implementierung wird
 * von der OpenRewrite-Pipeline (Phase 4+) genutzt.
 */
public final class SpoonEngineImpl implements SpoonEngine {

    @Override
    public SpoonAnalysis analyze(
            EffectiveProjectSources sources,
            BuildContext buildContext,
            SpoonAnalysisOptions options
    ) {
        Instant start = Instant.now();

        Launcher launcher = new Launcher();
        launcher.getEnvironment().setAutoImports(options.autoImports());
        launcher.getEnvironment().setCommentEnabled(options.commentEnabled());
        launcher.getEnvironment().setComplianceLevel(options.complianceLevel());

        // Resolution Mode setzen
        ResolutionMode mode = options.resolutionMode();
        String[] classpathArray = extractClasspath(buildContext);

        if (mode == ResolutionMode.NO_CLASSPATH) {
            launcher.getEnvironment().setNoClasspath(true);
        } else if (mode == ResolutionMode.FULL_CLASSPATH) {
            launcher.getEnvironment().setNoClasspath(false);
            if (classpathArray != null && classpathArray.length > 0) {
                launcher.getEnvironment().setSourceClasspath(classpathArray);
            }
        } else {
            // PARTIAL: noClasspath mit verfuegbarem Classpath
            launcher.getEnvironment().setNoClasspath(true);
            if (classpathArray != null && classpathArray.length > 0) {
                launcher.getEnvironment().setSourceClasspath(classpathArray);
            }
        }

        // Sources als VirtualFile hinzufuegen
        for (Map.Entry<Path, String> entry : sources.sources().entrySet()) {
            launcher.addInputResource(
                    new VirtualFile(entry.getValue(), entry.getKey().toString())
            );
        }

        List<SpoonDiagnostic> diagnostics = new ArrayList<>();
        CtModel model;
        try {
            model = launcher.buildModel();
        } catch (Exception e) {
            diagnostics.add(new SpoonDiagnostic(
                    "PARSE_ERROR", "ERROR",
                    "Spoon could not parse sources: " + e.getMessage(),
                    "spoon"));
            Duration duration = Duration.between(start, Instant.now());
            return new SpoonAnalysis(mode, List.of(), diagnostics, duration, false);
        }

        // Spoon baut auch bei Syntaxfehlern ein (partielles) Modell, ohne eine
        // Exception zu werfen, und stuft die Fehler im noClasspath-Modus zusaetzlich
        // zu Warnungen herab (getErrorCount bleibt 0). Die zuverlaessige Quelle
        // sind die rohen JDT-Probleme: Syntaxfehler tragen die Kategorie CAT_SYNTAX
        // und sind von Classpath-Aufloesungsfehlern (CAT_TYPE) zu unterscheiden,
        // die im noClasspath-Modus ignoriert werden muessen.
        SpoonModelBuilder modelBuilder = launcher.getModelBuilder();
        if (modelBuilder instanceof JDTBasedSpoonCompiler jdt) {
            long syntaxErrors = jdt.getProblems().stream()
                    .filter(CategorizedProblem::isError)
                    .filter(p -> p.getCategoryID() == CategorizedProblem.CAT_SYNTAX)
                    .count();
            if (syntaxErrors > 0) {
                diagnostics.add(new SpoonDiagnostic(
                        "PARSE_ERROR", "ERROR",
                        "Spoon reported " + syntaxErrors + " syntax error(s) while building the model",
                        "spoon"));
                Duration duration = Duration.between(start, Instant.now());
                return new SpoonAnalysis(mode, List.of(), diagnostics, duration, false);
            }
        }

        // Grundlegende Analyse: Zaehle Typen
        List<SpoonFinding> findings = new ArrayList<>();
        int typeCount = model.getAllTypes().size();

        Duration duration = Duration.between(start, Instant.now());

        // Diagnose bei leerem Classpath im FULL-Modus
        if (mode == ResolutionMode.FULL_CLASSPATH && (buildContext == null
                || buildContext.modules().isEmpty()
                || buildContext.modules().get(0).compileClasspath().isEmpty())) {
            diagnostics.add(new SpoonDiagnostic(
                    "CLASSPATH_STALE", "WARNING",
                    "Full classpath mode requested but no classpath available",
                    "spoon"));
        }

        return new SpoonAnalysis(mode, findings, diagnostics, duration, true);
    }

    private String[] extractClasspath(BuildContext buildContext) {
        if (buildContext == null || buildContext.modules().isEmpty()) return null;
        ModuleContext mod = buildContext.modules().get(0);
        if (mod.compileClasspath().isEmpty()) return null;
        return mod.compileClasspath().toArray(new String[0]);
    }
}
