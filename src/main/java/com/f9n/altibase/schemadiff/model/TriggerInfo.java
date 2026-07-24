package com.f9n.altibase.schemadiff.model;

import com.f9n.altibase.schemadiff.SourceNormalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record TriggerInfo(
        String schema,
        String tableName,
        String triggerName,
        boolean enabled,
        String source
) {
    // Trigger names are unique per schema in Altibase, so the table is not part
    // of the key; a moved trigger shows as a difference instead of an add/remove pair.
    public String qualifiedName() {
        return schema + "." + triggerName;
    }

    public String describe() {
        return "on " + tableName + (enabled ? "" : ", disabled");
    }

    public List<String> differences(TriggerInfo other) {
        List<String> diffs = new ArrayList<>();
        if (!Objects.equals(tableName, other.tableName)) {
            diffs.add("table " + tableName + " → " + other.tableName);
        }
        if (enabled != other.enabled) {
            diffs.add("enabled " + enabled + " → " + other.enabled);
        }
        if (!sourceEquals(other)) {
            diffs.add("source code differs");
        }
        return diffs;
    }

    public boolean sourceEquals(TriggerInfo other) {
        if (other == null) return false;
        return SourceNormalizer.forComparison(source).equals(SourceNormalizer.forComparison(other.source));
    }

    public String normalizedSource() {
        return SourceNormalizer.forDisplay(source);
    }
}
