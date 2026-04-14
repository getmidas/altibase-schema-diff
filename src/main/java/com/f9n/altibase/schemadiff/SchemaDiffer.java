package com.f9n.altibase.schemadiff;

import com.f9n.altibase.schemadiff.model.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SchemaDiffer {

    public DiffResult diff(SchemaSnapshot source, SchemaSnapshot target) {
        List<DiffItem> items = new ArrayList<>();

        diffSchemas(source, target, items);
        diffTables(source, target, items);
        diffProceduresAndFunctions(source, target, items);
        diffSequences(source, target, items);
        diffViews(source, target, items);

        return new DiffResult(source, target, items);
    }

    private void diffSchemas(SchemaSnapshot source, SchemaSnapshot target, List<DiffItem> items) {
        Set<String> srcSchemas = new TreeSet<>(source.schemas());
        Set<String> tgtSchemas = new TreeSet<>(target.schemas());

        for (String s : srcSchemas) {
            if (!tgtSchemas.contains(s)) {
                items.add(DiffItem.onlyInSource(DiffItem.Category.SCHEMA, s, s));
            }
        }
        for (String s : tgtSchemas) {
            if (!srcSchemas.contains(s)) {
                items.add(DiffItem.onlyInTarget(DiffItem.Category.SCHEMA, s, s));
            }
        }
    }

    private void diffTables(SchemaSnapshot source, SchemaSnapshot target, List<DiffItem> items) {
        Map<String, TableInfo> srcTables = indexBy(source.tables(), TableInfo::qualifiedName);
        Map<String, TableInfo> tgtTables = indexBy(target.tables(), TableInfo::qualifiedName);

        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(srcTables.keySet());
        allKeys.addAll(tgtTables.keySet());

        for (String key : allKeys) {
            TableInfo src = srcTables.get(key);
            TableInfo tgt = tgtTables.get(key);
            String schema = key.substring(0, key.indexOf('.'));
            String name = key.substring(key.indexOf('.') + 1);

            if (src == null) {
                items.add(DiffItem.onlyInTarget(DiffItem.Category.TABLE, schema, name));
            } else if (tgt == null) {
                items.add(DiffItem.onlyInSource(DiffItem.Category.TABLE, schema, name));
            } else {
                diffColumns(schema, name, src.columns(), tgt.columns(), items);
            }
        }
    }

    private void diffColumns(String schema, String tableName,
                             List<ColumnInfo> srcCols, List<ColumnInfo> tgtCols,
                             List<DiffItem> items) {
        Map<String, ColumnInfo> srcMap = indexBy(srcCols, ColumnInfo::name);
        Map<String, ColumnInfo> tgtMap = indexBy(tgtCols, ColumnInfo::name);

        Set<String> allCols = new LinkedHashSet<>();
        srcCols.stream().map(ColumnInfo::name).forEach(allCols::add);
        tgtCols.stream().map(ColumnInfo::name).forEach(allCols::add);

        List<String> colDiffs = new ArrayList<>();
        for (String col : allCols) {
            ColumnInfo s = srcMap.get(col);
            ColumnInfo t = tgtMap.get(col);

            if (s == null) {
                colDiffs.add("+ " + col + " " + t.fullType() + " [target only]");
            } else if (t == null) {
                colDiffs.add("- " + col + " " + s.fullType() + " [source only]");
            } else if (!s.structureEquals(t)) {
                List<String> changes = describeColumnChanges(s, t);
                for (String change : changes) {
                    colDiffs.add("~ " + col + ": " + change);
                }
            }
        }

        if (!colDiffs.isEmpty()) {
            items.add(DiffItem.different(DiffItem.Category.TABLE, schema, tableName, colDiffs));
        }
    }

    private List<String> describeColumnChanges(ColumnInfo src, ColumnInfo tgt) {
        List<String> changes = new ArrayList<>();
        if (!src.fullType().equals(tgt.fullType())) {
            changes.add("type " + src.fullType() + " → " + tgt.fullType());
        }
        if (src.nullable() != tgt.nullable()) {
            changes.add("nullable " + src.nullable() + " → " + tgt.nullable());
        }
        String srcDef = normalizeDefault(src.defaultValue());
        String tgtDef = normalizeDefault(tgt.defaultValue());
        if (!Objects.equals(srcDef, tgtDef)) {
            changes.add("default '" + srcDef + "' → '" + tgtDef + "'");
        }
        return changes;
    }

    private void diffProceduresAndFunctions(SchemaSnapshot source, SchemaSnapshot target, List<DiffItem> items) {
        Map<String, ProcedureInfo> srcProcs = indexBy(source.procedures(), ProcedureInfo::qualifiedName);
        Map<String, ProcedureInfo> tgtProcs = indexBy(target.procedures(), ProcedureInfo::qualifiedName);

        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(srcProcs.keySet());
        allKeys.addAll(tgtProcs.keySet());

        for (String key : allKeys) {
            ProcedureInfo src = srcProcs.get(key);
            ProcedureInfo tgt = tgtProcs.get(key);
            String schema = key.substring(0, key.indexOf('.'));
            String name = key.substring(key.indexOf('.') + 1);

            DiffItem.Category cat = (src != null ? src.type() : tgt.type()).equals("FUNCTION")
                    ? DiffItem.Category.FUNCTION
                    : DiffItem.Category.PROCEDURE;

            if (src == null) {
                items.add(DiffItem.onlyInTarget(cat, schema, name));
            } else if (tgt == null) {
                items.add(DiffItem.onlyInSource(cat, schema, name));
            } else if (!src.sourceEquals(tgt)) {
                items.add(DiffItem.differentWithSource(cat, schema, name, List.of("source code differs"), src.normalizedSource(), tgt.normalizedSource()));
            }
        }
    }

    private void diffSequences(SchemaSnapshot source, SchemaSnapshot target, List<DiffItem> items) {
        Map<String, SequenceInfo> srcSeqs = indexBy(source.sequences(), SequenceInfo::qualifiedName);
        Map<String, SequenceInfo> tgtSeqs = indexBy(target.sequences(), SequenceInfo::qualifiedName);

        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(srcSeqs.keySet());
        allKeys.addAll(tgtSeqs.keySet());

        for (String key : allKeys) {
            SequenceInfo src = srcSeqs.get(key);
            SequenceInfo tgt = tgtSeqs.get(key);
            String schema = key.substring(0, key.indexOf('.'));
            String name = key.substring(key.indexOf('.') + 1);

            if (src == null) {
                items.add(DiffItem.onlyInTarget(DiffItem.Category.SEQUENCE, schema, name));
            } else if (tgt == null) {
                items.add(DiffItem.onlyInSource(DiffItem.Category.SEQUENCE, schema, name));
            } else {
                List<String> diffs = src.differences(tgt);
                if (!diffs.isEmpty()) {
                    items.add(DiffItem.different(DiffItem.Category.SEQUENCE, schema, name, diffs));
                }
            }
        }
    }

    private void diffViews(SchemaSnapshot source, SchemaSnapshot target, List<DiffItem> items) {
        Map<String, ViewInfo> srcViews = indexBy(source.views(), ViewInfo::qualifiedName);
        Map<String, ViewInfo> tgtViews = indexBy(target.views(), ViewInfo::qualifiedName);

        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(srcViews.keySet());
        allKeys.addAll(tgtViews.keySet());

        for (String key : allKeys) {
            ViewInfo src = srcViews.get(key);
            ViewInfo tgt = tgtViews.get(key);
            String schema = key.substring(0, key.indexOf('.'));
            String name = key.substring(key.indexOf('.') + 1);

            if (src == null) {
                items.add(DiffItem.onlyInTarget(DiffItem.Category.VIEW, schema, name));
            } else if (tgt == null) {
                items.add(DiffItem.onlyInSource(DiffItem.Category.VIEW, schema, name));
            } else if (!src.sourceEquals(tgt)) {
                items.add(DiffItem.differentWithSource(DiffItem.Category.VIEW, schema, name, List.of("view definition differs"), src.normalizedSource(), tgt.normalizedSource()));
            }
        }
    }

    private static <T> Map<String, T> indexBy(List<T> list, Function<T, String> keyFn) {
        return list.stream().collect(Collectors.toMap(keyFn, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private static String normalizeDefault(String val) {
        if (val == null) return "";
        return val.trim();
    }
}
