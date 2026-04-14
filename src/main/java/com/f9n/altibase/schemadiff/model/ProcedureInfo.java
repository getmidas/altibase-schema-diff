package com.f9n.altibase.schemadiff.model;

import com.f9n.altibase.schemadiff.SourceNormalizer;

public record ProcedureInfo(
        String schema,
        String name,
        String type,
        String source
) {
    public String qualifiedName() {
        return schema + "." + name;
    }

    public boolean sourceEquals(ProcedureInfo other) {
        if (other == null) return false;
        return SourceNormalizer.forComparison(source).equals(SourceNormalizer.forComparison(other.source));
    }

    public String normalizedSource() {
        return SourceNormalizer.forDisplay(source);
    }
}
