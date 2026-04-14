package com.f9n.altibase.schemadiff;

import com.f9n.altibase.schemadiff.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class SchemaExtractor {

    private static final Logger log = LoggerFactory.getLogger(SchemaExtractor.class);

    private final Connection connection;
    private final ConnectionConfig config;
    private final Set<String> schemaFilter;

    public SchemaExtractor(Connection connection, ConnectionConfig config, Set<String> schemaFilter) {
        this.connection = connection;
        this.config = config;
        this.schemaFilter = schemaFilter;
    }

    public SchemaSnapshot extract() throws SQLException {
        Instant start = Instant.now();
        log.info("Extracting schema from {}", config.label());

        List<String> schemas = extractSchemas();
        log.info("Found {} schema(s): {}", schemas.size(), schemas);

        List<TableInfo> tables = extractTables(schemas);
        log.info("Found {} table(s)", tables.size());

        List<ProcedureInfo> procedures;
        try {
            procedures = extractProcedures(schemas);
            log.info("Found {} procedure/function(s)", procedures.size());
        } catch (SQLException e) {
            log.warn("Failed to extract procedures (SYS_PROCEDURES_ not available?), skipping: {}", e.getMessage());
            procedures = List.of();
        }

        List<SequenceInfo> sequences = extractSequences();
        log.info("Found {} sequence(s)", sequences.size());

        List<ViewInfo> views;
        try {
            views = extractViews(schemas);
            log.info("Found {} view(s)", views.size());
        } catch (SQLException e) {
            log.warn("Failed to extract views (SYS_VIEWS_ not available?), skipping: {}", e.getMessage());
            views = List.of();
        }

        long elapsed = Duration.between(start, Instant.now()).toMillis();
        log.info("Schema extraction completed in {}ms from {}", elapsed, config.label());

        return new SchemaSnapshot(
                config.server(), config.port(), config.database(),
                Instant.now(), schemas, tables, procedures, sequences, views
        );
    }

    private List<String> extractSchemas() throws SQLException {
        List<String> result = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        String sql = "SELECT USER_ID, USER_NAME FROM SYSTEM_.SYS_USERS_ WHERE USER_ID > 1 ORDER BY USER_NAME";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int userId = rs.getInt(1);
                String name = rs.getString(2).trim();
                if (schemaFilter.isEmpty() || schemaFilter.contains(name.toUpperCase())) {
                    result.add(name);
                    log.debug("  schema: {} (USER_ID={})", name, userId);
                } else {
                    skipped.add(name);
                }
            }
        }
        if (!skipped.isEmpty()) {
            log.debug("Skipped schemas (not in filter): {}", skipped);
        }
        return result;
    }

    private List<TableInfo> extractTables(List<String> schemas) throws SQLException {
        List<TableInfo> tables = new ArrayList<>();
        DatabaseMetaData meta = connection.getMetaData();

        for (String schema : schemas) {
            Instant schemaStart = Instant.now();
            List<String> tableNames = new ArrayList<>();
            try (ResultSet rs = meta.getTables(null, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tableNames.add(rs.getString("TABLE_NAME"));
                }
            }

            log.debug("Schema {}: found {} table(s)", schema, tableNames.size());
            for (String tableName : tableNames) {
                List<ColumnInfo> columns = extractColumns(meta, schema, tableName);
                tables.add(new TableInfo(schema, tableName, columns));
                log.debug("  table: {}.{} ({} columns)", schema, tableName, columns.size());
            }
            long elapsed = Duration.between(schemaStart, Instant.now()).toMillis();
            log.debug("Schema {} tables extracted in {}ms", schema, elapsed);
        }
        return tables;
    }

    private List<ColumnInfo> extractColumns(DatabaseMetaData meta, String schema, String tableName) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(null, schema, tableName, "%")) {
            while (rs.next()) {
                ColumnInfo col = new ColumnInfo(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getInt("DECIMAL_DIGITS"),
                        rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        rs.getString("COLUMN_DEF"),
                        rs.getInt("ORDINAL_POSITION")
                );
                columns.add(col);
                log.trace("    column: {} {} nullable={} default={}", col.name(), col.fullType(), col.nullable(), col.defaultValue());
            }
        }
        columns.sort(Comparator.comparingInt(ColumnInfo::ordinalPosition));
        return columns;
    }

    private List<ProcedureInfo> extractProcedures(List<String> schemas) throws SQLException {
        List<ProcedureInfo> result = new ArrayList<>();
        String sql = """
                SELECT P.PROC_OID, P.PROC_NAME, U.USER_NAME,
                       CASE P.OBJECT_TYPE WHEN 0 THEN 'PROCEDURE' WHEN 1 THEN 'FUNCTION' ELSE 'OTHER' END
                FROM SYSTEM_.SYS_PROCEDURES_ P
                JOIN SYSTEM_.SYS_USERS_ U ON P.USER_ID = U.USER_ID
                WHERE U.USER_NAME = ?
                ORDER BY P.PROC_NAME
                """;

        for (String schema : schemas) {
            int count = 0;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long procOid = rs.getLong(1);
                        String name = rs.getString(2).trim();
                        String type = rs.getString(4).trim();
                        String source = extractProcedureSource(procOid);
                        result.add(new ProcedureInfo(schema, name, type, source));
                        log.debug("  {}: {}.{} (source={}chars)", type.toLowerCase(), schema, name, source.length());
                        count++;
                    }
                }
            }
            log.debug("Schema {}: {} procedure/function(s)", schema, count);
        }
        return result;
    }

    private String extractProcedureSource(long procOid) throws SQLException {
        StringBuilder sb = new StringBuilder();
        String sql = "SELECT PARSE FROM SYSTEM_.SYS_PROC_PARSE_ WHERE PROC_OID = ? ORDER BY SEQ_NO";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, procOid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String chunk = rs.getString(1);
                    if (chunk != null) sb.append(chunk);
                }
            }
        }
        return sb.toString();
    }

    private List<SequenceInfo> extractSequences() {
        List<SequenceInfo> result;

        // Strategy 1: SYS_SEQUENCES_ (available in some Altibase versions)
        try {
            result = tryExtractFromSysSequences();
            if (!result.isEmpty()) {
                log.debug("Extracted sequences via SYS_SEQUENCES_");
                return applySchemaFilter(result);
            }
        } catch (SQLException e) {
            log.debug("SYS_SEQUENCES_ not available: {}", e.getMessage());
        }

        // Strategy 2: SYS_TABLES_ WHERE TABLE_TYPE = 'S' (works on all versions)
        try {
            result = tryExtractFromSysTables();
            log.debug("Extracted sequences via SYS_TABLES_ (TABLE_TYPE='S')");
            return applySchemaFilter(result);
        } catch (SQLException e) {
            log.warn("Failed to extract sequences: {}", e.getMessage());
            return List.of();
        }
    }

    private List<SequenceInfo> tryExtractFromSysSequences() throws SQLException {
        List<SequenceInfo> result = new ArrayList<>();
        String sql = "SELECT USER_NAME, SEQUENCE_NAME, MIN_VALUE, MAX_VALUE, CYCLE, CACHE_SIZE FROM SYSTEM_.SYS_SEQUENCES_";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String user = rs.getString(1);
                if (user == null || user.isBlank()) continue;
                user = user.trim();
                String seqName = rs.getString(2);
                if (seqName == null || seqName.isBlank()) continue;
                seqName = seqName.trim();

                long minVal = rs.getLong(3);
                long maxVal = rs.getLong(4);
                String cycle = parseCycle(rs.getObject(5));
                long cacheSize = rs.getLong(6);

                result.add(new SequenceInfo(user, seqName, minVal, maxVal, cycle, cacheSize));
                log.debug("  sequence: {}.{} min={} max={} cycle={} cache={}", user, seqName, minVal, maxVal, cycle, cacheSize);
            }
        }
        return result;
    }

    private List<SequenceInfo> tryExtractFromSysTables() throws SQLException {
        List<SequenceInfo> result = new ArrayList<>();
        String sql = """
                SELECT U.USER_NAME, T.TABLE_NAME
                FROM SYSTEM_.SYS_TABLES_ T
                JOIN SYSTEM_.SYS_USERS_ U ON T.USER_ID = U.USER_ID
                WHERE T.TABLE_TYPE = 'S' AND U.USER_ID > 1
                ORDER BY U.USER_NAME, T.TABLE_NAME
                """;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String user = rs.getString(1).trim();
                String seqName = rs.getString(2).trim();
                result.add(new SequenceInfo(user, seqName, 0, 0, "N/A", 0));
                log.debug("  sequence: {}.{}", user, seqName);
            }
        }
        return result;
    }

    private List<SequenceInfo> applySchemaFilter(List<SequenceInfo> result) {
        if (!schemaFilter.isEmpty()) {
            result = result.stream()
                    .filter(s -> schemaFilter.contains(s.schema().toUpperCase()))
                    .toList();
        }
        return result;
    }

    private static String parseCycle(Object value) {
        if (value == null) return "N/A";
        if (value instanceof Number n) return n.intValue() != 0 ? "YES" : "NO";
        String s = String.valueOf(value).trim().toUpperCase();
        return switch (s) {
            case "Y", "YES", "1" -> "YES";
            case "N", "NO", "0" -> "NO";
            default -> s;
        };
    }

    private List<ViewInfo> extractViews(List<String> schemas) throws SQLException {
        // Strategy 1: SYS_TABLES_ (TABLE_TYPE='V') + SYS_VIEW_PARSE_ (TABLE_ID as VIEW_ID)
        // SYS_VIEWS_ may not have TABLE_ID on all versions, so we go through SYS_TABLES_ directly.
        List<ViewInfo> result = new ArrayList<>();
        String sql = """
                SELECT T.TABLE_ID, T.TABLE_NAME, U.USER_NAME
                FROM SYSTEM_.SYS_TABLES_ T
                JOIN SYSTEM_.SYS_USERS_ U ON T.USER_ID = U.USER_ID
                WHERE T.TABLE_TYPE = 'V' AND U.USER_NAME = ?
                ORDER BY T.TABLE_NAME
                """;

        for (String schema : schemas) {
            int count = 0;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long tableId = rs.getLong(1);
                        String viewName = rs.getString(2).trim();
                        String source = extractViewSource(tableId);
                        result.add(new ViewInfo(schema, viewName, source));
                        log.debug("  view: {}.{} (source={}chars)", schema, viewName, source.length());
                        count++;
                    }
                }
            }
            log.debug("Schema {}: {} view(s)", schema, count);
        }
        return result;
    }

    private String extractViewSource(long viewId) {
        StringBuilder sb = new StringBuilder();
        String sql = "SELECT PARSE FROM SYSTEM_.SYS_VIEW_PARSE_ WHERE VIEW_ID = ? ORDER BY SEQ_NO";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, viewId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String chunk = rs.getString(1);
                    if (chunk != null) sb.append(chunk);
                }
            }
        } catch (SQLException e) {
            log.debug("Could not get view source for VIEW_ID={}: {}", viewId, e.getMessage());
        }
        return sb.toString();
    }
}
