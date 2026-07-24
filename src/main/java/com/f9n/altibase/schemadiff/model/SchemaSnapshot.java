package com.f9n.altibase.schemadiff.model;

import java.time.Instant;
import java.util.List;

public record SchemaSnapshot(
        String server,
        int port,
        String database,
        Instant extractedAt,
        List<String> schemas,
        List<TableInfo> tables,
        List<IndexInfo> indexes,
        List<ConstraintInfo> constraints,
        List<TriggerInfo> triggers,
        List<ProcedureInfo> procedures,
        List<SequenceInfo> sequences,
        List<ViewInfo> views
) {
    // Cache files written before a field existed deserialize it as null
    public SchemaSnapshot {
        schemas = schemas == null ? List.of() : schemas;
        tables = tables == null ? List.of() : tables;
        indexes = indexes == null ? List.of() : indexes;
        constraints = constraints == null ? List.of() : constraints;
        triggers = triggers == null ? List.of() : triggers;
        procedures = procedures == null ? List.of() : procedures;
        sequences = sequences == null ? List.of() : sequences;
        views = views == null ? List.of() : views;
    }

    public String label() {
        return server + ":" + port + "/" + database;
    }
}
