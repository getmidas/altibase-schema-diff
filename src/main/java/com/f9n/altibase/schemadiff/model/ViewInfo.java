package com.f9n.altibase.schemadiff.model;

import com.f9n.altibase.schemadiff.SourceNormalizer;

public record ViewInfo(
        String schema,
        String name,
        String source
) {
    public String qualifiedName() {
        return schema + "." + name;
    }

    public boolean sourceEquals(ViewInfo other) {
        if (other == null) return false;
        return SourceNormalizer.forComparison(source).equals(SourceNormalizer.forComparison(other.source));
    }

    public String normalizedSource() {
        return SourceNormalizer.forDisplay(source);
    }
}
