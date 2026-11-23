/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Searches string literals that contain a name as a whole word (hint at references that a rename
 * or delete does not catch). A single literal without position does not abort the scan, and the number
 * of returned hits is capped (short names like {@code id} would otherwise produce masses of hits).
 *
 * @author Janusch Rentenatus
 */
public final class StringLiteralScanner {

    /** Upper limit of the delivered hits; further ones are only counted. */
    static final int MAX_HITS = 20;

    public record Hit(String file, int line, String text) {
    }

    /** @param hits the first {@link #MAX_HITS} hits, @param total total number of hits */
    public record Result(List<Hit> hits, int total) {
        public int omitted() {
            return total - hits.size();
        }
    }

    private StringLiteralScanner() {
    }

    public static Result scan(CtModel model, String name) {
        List<Hit> hits = new ArrayList<>();
        int total = 0;
        if (model == null || name == null || name.isEmpty()) return new Result(hits, 0);
        Pattern word = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])");
        for (CtType<?> type : model.getAllTypes()) {
            try {
                for (CtLiteral<?> lit : type.getElements(new TypeFilter<CtLiteral<?>>(CtLiteral.class))) {
                    if (!(lit.getValue() instanceof String s) || !word.matcher(s).find()) continue;
                    total++;
                    if (hits.size() >= MAX_HITS) continue;
                    String file = type.getPosition() != null && type.getPosition().getFile() != null
                            ? type.getPosition().getFile().getName() : type.getQualifiedName();
                    int line = lit.getPosition() != null && lit.getPosition().isValidPosition()
                            ? lit.getPosition().getLine() : -1;
                    hits.add(new Hit(file, line, "\"" + s + "\""));
                }
            } catch (RuntimeException e) {
                // ein defekter Typ darf den bereits geschriebenen Edit nicht in einen internen Fehler verwandeln
            }
        }
        return new Result(hits, total);
    }
}
