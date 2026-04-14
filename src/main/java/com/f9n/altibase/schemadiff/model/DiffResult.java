package com.f9n.altibase.schemadiff.model;

import java.util.List;

public record DiffResult(
        SchemaSnapshot source,
        SchemaSnapshot target,
        List<DiffItem> items
) {
    public boolean hasDifferences() {
        return !items.isEmpty();
    }

    public long countByCategory(DiffItem.Category category) {
        return items.stream().filter(i -> i.category() == category).count();
    }

    public long countByType(DiffItem.Type type) {
        return items.stream().filter(i -> i.type() == type).count();
    }
}
