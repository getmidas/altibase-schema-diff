package com.f9n.altibase.schemadiff;

import java.util.regex.Pattern;

public final class SourceNormalizer {

    private SourceNormalizer() {}

    private static final Pattern SCHEMA_PREFIX = Pattern.compile(
            "(CREATE\\s+OR\\s+REPLACE\\s+(?:PROCEDURE|FUNCTION|VIEW|PACKAGE)\\s+)\\w+\\.",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TRAILING_SEMICOLON = Pattern.compile(
            "END\\s*;\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    // /* ... */ block comments (non-greedy, across lines)
    private static final Pattern BLOCK_COMMENT = Pattern.compile(
            "/\\*.*?\\*/",
            Pattern.DOTALL
    );

    // -- line comments
    private static final Pattern LINE_COMMENT = Pattern.compile(
            "--[^\n]*"
    );

    /**
     * Normalize for equality comparison (single-line, all whitespace collapsed, comments stripped).
     */
    public static String forComparison(String src) {
        if (src == null) return "";
        String s = src.strip();
        s = SCHEMA_PREFIX.matcher(s).replaceFirst("$1");
        s = TRAILING_SEMICOLON.matcher(s).replaceAll("END");
        s = stripComments(s);
        s = s.replaceAll("\\s+", " ").strip();
        return s;
    }

    /**
     * Normalize for display/diff (preserves line structure, cleans cosmetic noise, strips comments).
     */
    public static String forDisplay(String src) {
        if (src == null) return "";
        String s = SCHEMA_PREFIX.matcher(src).replaceFirst("$1");
        s = TRAILING_SEMICOLON.matcher(s).replaceAll("END");
        s = stripComments(s);
        s = normalizeIndentation(s);
        return stripTrailingBlanks(s);
    }

    private static String stripComments(String src) {
        String s = BLOCK_COMMENT.matcher(src).replaceAll("");
        s = LINE_COMMENT.matcher(s).replaceAll("");
        return s;
    }

    private static String normalizeIndentation(String src) {
        String[] lines = src.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                sb.append(trimmed).append('\n');
            }
        }
        return sb.toString();
    }

    private static String stripTrailingBlanks(String src) {
        while (src.endsWith("\n\n")) {
            src = src.substring(0, src.length() - 1);
        }
        if (src.endsWith("\n")) {
            src = src.substring(0, src.length() - 1);
        }
        return src;
    }
}
