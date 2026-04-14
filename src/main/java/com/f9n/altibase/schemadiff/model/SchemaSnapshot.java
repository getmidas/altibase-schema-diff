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
        List<ProcedureInfo> procedures,
        List<SequenceInfo> sequences,
        List<ViewInfo> views
) {
    public String label() {
        return server + ":" + port + "/" + database;
    }
}
