package com.f9n.altibase.schemadiff.model;

import java.util.List;

public record DiffItem(
        Category category,
        String schema,
        String objectName,
        Type type,
        List<String> details,
        String sourceText,
        String targetText
) {
    public enum Category {
        SCHEMA, TABLE, COLUMN, PROCEDURE, FUNCTION, SEQUENCE, VIEW
    }

    public enum Type {
        ONLY_IN_SOURCE, ONLY_IN_TARGET, DIFFERENT
    }

    public static DiffItem onlyInSource(Category cat, String schema, String name) {
        return new DiffItem(cat, schema, name, Type.ONLY_IN_SOURCE, List.of(), null, null);
    }

    public static DiffItem onlyInTarget(Category cat, String schema, String name) {
        return new DiffItem(cat, schema, name, Type.ONLY_IN_TARGET, List.of(), null, null);
    }

    public static DiffItem different(Category cat, String schema, String name, List<String> details) {
        return new DiffItem(cat, schema, name, Type.DIFFERENT, details, null, null);
    }

    public static DiffItem differentWithSource(Category cat, String schema, String name, List<String> details, String sourceText, String targetText) {
        return new DiffItem(cat, schema, name, Type.DIFFERENT, details, sourceText, targetText);
    }

    public boolean hasSourceDiff() {
        return sourceText != null && targetText != null;
    }
}
