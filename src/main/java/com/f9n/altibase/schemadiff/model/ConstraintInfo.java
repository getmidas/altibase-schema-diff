package com.f9n.altibase.schemadiff.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record ConstraintInfo(
        String schema,
        String tableName,
        String constraintName,
        String constraintType,
        List<String> columns,
        String referencedTable,
        String checkCondition
) {
    // Altibase names unnamed constraints __SYS_CON_PRIMARY_ID_<n>, __SYS_CON_TIMESTAMP_<n>
    // etc., where <n> is a server-local object id that differs between servers.
    private static final Pattern AUTO_NAME = Pattern.compile("__SYS_CON_[A-Z_]*\\d+");

    public String qualifiedName() {
        return schema + "." + tableName + "." + constraintName;
    }

    public boolean autoNamed() {
        return AUTO_NAME.matcher(constraintName).matches();
    }

    // Auto-named constraints are matched by table + type + column signature
    // instead of by name, so they don't show up as false differences.
    public String matchKey() {
        if (autoNamed()) {
            return schema + "." + tableName + ".(auto:" + constraintType + ":" + String.join(",", columns) + ")";
        }
        return qualifiedName();
    }

    public String displayName() {
        return tableName + "." + constraintName;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(constraintType);
        if (!columns.isEmpty()) {
            sb.append(" (").append(String.join(", ", columns)).append(")");
        }
        if (referencedTable != null && !referencedTable.isBlank()) {
            sb.append(" REFERENCES ").append(referencedTable);
        }
        if (checkCondition != null && !checkCondition.isBlank()) {
            sb.append(" CHECK: ").append(checkCondition.strip());
        }
        return sb.toString();
    }

    public List<String> differences(ConstraintInfo other) {
        List<String> diffs = new ArrayList<>();
        if (!Objects.equals(constraintType, other.constraintType)) {
            diffs.add("type " + constraintType + " → " + other.constraintType);
        }
        if (!columns.equals(other.columns)) {
            diffs.add("columns (" + String.join(", ", columns) + ") → ("
                    + String.join(", ", other.columns) + ")");
        }
        if (!Objects.equals(referencedTable, other.referencedTable)) {
            diffs.add("references " + referencedTable + " → " + other.referencedTable);
        }
        String srcCheck = checkCondition == null ? "" : checkCondition.strip();
        String tgtCheck = other.checkCondition == null ? "" : other.checkCondition.strip();
        if (!srcCheck.equals(tgtCheck)) {
            diffs.add("check condition '" + srcCheck + "' → '" + tgtCheck + "'");
        }
        return diffs;
    }
}
