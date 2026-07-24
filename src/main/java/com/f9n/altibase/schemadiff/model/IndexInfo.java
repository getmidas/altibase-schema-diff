package com.f9n.altibase.schemadiff.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record IndexInfo(
        String schema,
        String tableName,
        String indexName,
        boolean unique,
        String indexType,
        List<String> columns
) {
    // Altibase names auto-created indexes (e.g. for primary keys) as __SYS_IDX_ID_<n>,
    // where <n> is a server-local object id that differs between servers.
    private static final Pattern AUTO_NAME = Pattern.compile("__SYS_IDX_[A-Z_]*\\d+");

    public String qualifiedName() {
        return schema + "." + tableName + "." + indexName;
    }

    public boolean autoNamed() {
        return AUTO_NAME.matcher(indexName).matches();
    }

    // Auto-named indexes are matched by table + column signature instead of by name,
    // so the same PK index on two servers doesn't show up as two differences.
    public String matchKey() {
        if (autoNamed()) {
            return schema + "." + tableName + ".(auto"
                    + (unique ? ":unique:" : ":") + String.join(",", columns) + ")";
        }
        return qualifiedName();
    }

    public String displayName() {
        return tableName + "." + indexName;
    }

    public String describe() {
        return (unique ? "UNIQUE " : "") + indexType + " (" + String.join(", ", columns) + ")";
    }

    public List<String> differences(IndexInfo other) {
        List<String> diffs = new ArrayList<>();
        if (unique != other.unique) {
            diffs.add("unique " + unique + " → " + other.unique);
        }
        if (!Objects.equals(indexType, other.indexType)) {
            diffs.add("type " + indexType + " → " + other.indexType);
        }
        if (!columns.equals(other.columns)) {
            diffs.add("columns (" + String.join(", ", columns) + ") → ("
                    + String.join(", ", other.columns) + ")");
        }
        return diffs;
    }
}
