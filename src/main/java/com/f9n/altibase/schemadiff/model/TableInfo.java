package com.f9n.altibase.schemadiff.model;

import java.util.List;

public record TableInfo(
        String schema,
        String name,
        List<ColumnInfo> columns
) {
    public String qualifiedName() {
        return schema + "." + name;
    }
}
