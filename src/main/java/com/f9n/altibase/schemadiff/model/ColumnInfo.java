package com.f9n.altibase.schemadiff.model;

import java.util.Objects;

public record ColumnInfo(
        String name,
        String typeName,
        int columnSize,
        int decimalDigits,
        boolean nullable,
        String defaultValue,
        int ordinalPosition
) {
    public String fullType() {
        if (decimalDigits > 0 && columnSize > 0) return typeName + "(" + columnSize + "," + decimalDigits + ")";
        if (columnSize > 0) return typeName + "(" + columnSize + ")";
        return typeName;
    }

    public boolean structureEquals(ColumnInfo other) {
        if (other == null) return false;
        return Objects.equals(name, other.name)
                && Objects.equals(typeName, other.typeName)
                && columnSize == other.columnSize
                && decimalDigits == other.decimalDigits
                && nullable == other.nullable
                && Objects.equals(normalizeDefault(defaultValue), normalizeDefault(other.defaultValue));
    }

    private static String normalizeDefault(String val) {
        if (val == null) return null;
        String trimmed = val.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
