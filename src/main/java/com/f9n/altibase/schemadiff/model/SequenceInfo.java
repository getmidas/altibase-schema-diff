package com.f9n.altibase.schemadiff.model;

import java.util.ArrayList;
import java.util.List;

public record SequenceInfo(
        String schema,
        String name,
        long minValue,
        long maxValue,
        String cycle,
        long cacheSize
) {
    public String qualifiedName() {
        return schema + "." + name;
    }

    public boolean hasDetails() {
        return !"N/A".equals(cycle);
    }

    public List<String> differences(SequenceInfo other) {
        if (!hasDetails() || !other.hasDetails()) return List.of();
        List<String> diffs = new ArrayList<>();
        if (minValue != other.minValue) diffs.add("MIN_VALUE: " + minValue + " → " + other.minValue);
        if (maxValue != other.maxValue) diffs.add("MAX_VALUE: " + maxValue + " → " + other.maxValue);
        if (!cycle.equals(other.cycle)) diffs.add("CYCLE: " + cycle + " → " + other.cycle);
        if (cacheSize != other.cacheSize) diffs.add("CACHE_SIZE: " + cacheSize + " → " + other.cacheSize);
        return diffs;
    }
}
