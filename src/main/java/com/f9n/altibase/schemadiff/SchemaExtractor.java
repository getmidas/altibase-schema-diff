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

        List<IndexInfo> indexes = extractIndexes(tables);
        log.info("Found {} index(es)", indexes.size());

        List<ConstraintInfo> constraints;
        try {
            constraints = extractConstraints(schemas);
            log.info("Found {} constraint(s)", constraints.size());
        } catch (SQLException e) {
            log.warn("Failed to extract constraints (SYS_CONSTRAINTS_ not available?), skipping: {}", e.getMessage());
            constraints = List.of();
        }

        List<TriggerInfo> triggers;
        try {
            triggers = extractTriggers(schemas);
            log.info("Found {} trigger(s)", triggers.size());
        } catch (SQLException e) {
            log.warn("Failed to extract triggers (SYS_TRIGGERS_ not available?), skipping: {}", e.getMessage());
            triggers = List.of();
        }

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
                Instant.now(), schemas, tables, indexes, constraints, triggers, procedures, sequences, views
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

    List<IndexInfo> extractIndexes(List<TableInfo> tables) {
        List<IndexInfo> result = new ArrayList<>();
        DatabaseMetaData meta;
        try {
            meta = connection.getMetaData();
        } catch (SQLException e) {
            log.warn("Failed to extract indexes, skipping: {}", e.getMessage());
            return List.of();
        }

        for (TableInfo table : tables) {
            result.addAll(extractTableIndexes(meta, table.schema(), table.name()));
        }
        return result;
    }

    private List<IndexInfo> extractTableIndexes(DatabaseMetaData meta, String schema, String tableName) {
        Map<String, Boolean> uniqueByName = new LinkedHashMap<>();
        Map<String, String> typeByName = new LinkedHashMap<>();
        Map<String, SortedMap<Integer, String>> columnsByName = new LinkedHashMap<>();

        try (ResultSet rs = meta.getIndexInfo(null, schema, tableName, false, true)) {
            while (rs.next()) {
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) continue;
                indexName = indexName.trim();

                uniqueByName.put(indexName, !rs.getBoolean("NON_UNIQUE"));
                typeByName.put(indexName, indexTypeName(rs.getShort("TYPE")));

                String direction = "D".equalsIgnoreCase(rs.getString("ASC_OR_DESC")) ? "DESC" : "ASC";
                columnsByName
                        .computeIfAbsent(indexName, k -> new TreeMap<>())
                        .put((int) rs.getShort("ORDINAL_POSITION"), columnName.trim() + " " + direction);
            }
        } catch (SQLException e) {
            log.warn("Failed to extract indexes for {}.{}, skipping table: {}", schema, tableName, e.getMessage());
            return List.of();
        }

        List<IndexInfo> result = new ArrayList<>();
        for (String indexName : columnsByName.keySet()) {
            IndexInfo idx = new IndexInfo(
                    schema, tableName, indexName,
                    uniqueByName.get(indexName),
                    typeByName.get(indexName),
                    List.copyOf(columnsByName.get(indexName).values())
            );
            result.add(idx);
            log.debug("  index: {} unique={} type={} columns={}", idx.qualifiedName(), idx.unique(), idx.indexType(), idx.columns());
        }
        return result;
    }

    private static String indexTypeName(short type) {
        return switch (type) {
            case DatabaseMetaData.tableIndexClustered -> "CLUSTERED";
            case DatabaseMetaData.tableIndexHashed -> "HASH";
            default -> "OTHER";
        };
    }

    List<ConstraintInfo> extractConstraints(List<String> schemas) throws SQLException {
        List<ConstraintInfo> result = new ArrayList<>();
        // NOT NULL constraints (type 1) are skipped: column nullability is already
        // compared per table, and each one carries an auto-generated name.
        // TABLE_TYPE = 'T' skips sequence backing objects (SEQ_X$SEQ), which carry
        // auto-created PRIMARY/TIMESTAMP constraints on every server.
        String sql = """
                SELECT C.CONSTRAINT_ID, C.CONSTRAINT_NAME, C.CONSTRAINT_TYPE,
                       T.TABLE_NAME, RT.TABLE_NAME, C.CHECK_CONDITION
                FROM SYSTEM_.SYS_CONSTRAINTS_ C
                JOIN SYSTEM_.SYS_TABLES_ T ON C.TABLE_ID = T.TABLE_ID
                JOIN SYSTEM_.SYS_USERS_ U ON T.USER_ID = U.USER_ID
                LEFT OUTER JOIN SYSTEM_.SYS_TABLES_ RT ON C.REFERENCED_TABLE_ID = RT.TABLE_ID
                WHERE U.USER_NAME = ? AND C.CONSTRAINT_TYPE <> 1 AND T.TABLE_TYPE = 'T'
                ORDER BY T.TABLE_NAME, C.CONSTRAINT_NAME
                """;

        for (String schema : schemas) {
            int count = 0;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long constraintId = rs.getLong(1);
                        String name = rs.getString(2).trim();
                        String type = constraintTypeName(rs.getInt(3));
                        String tableName = rs.getString(4).trim();
                        String refTable = rs.getString(5);
                        String checkCondition = rs.getString(6);

                        List<String> columns = extractConstraintColumns(constraintId);
                        ConstraintInfo con = new ConstraintInfo(
                                schema, tableName, name, type, columns,
                                refTable != null ? refTable.trim() : null,
                                checkCondition
                        );
                        result.add(con);
                        log.debug("  constraint: {} type={} columns={}", con.qualifiedName(), type, columns);
                        count++;
                    }
                }
            }
            log.debug("Schema {}: {} constraint(s)", schema, count);
        }
        return result;
    }

    private List<String> extractConstraintColumns(long constraintId) throws SQLException {
        List<String> columns = new ArrayList<>();
        String sql = """
                SELECT COL.COLUMN_NAME
                FROM SYSTEM_.SYS_CONSTRAINT_COLUMNS_ CC
                JOIN SYSTEM_.SYS_COLUMNS_ COL ON CC.COLUMN_ID = COL.COLUMN_ID
                WHERE CC.CONSTRAINT_ID = ?
                ORDER BY CC.CONSTRAINT_COL_ORDER
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, constraintId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1).trim());
                }
            }
        }
        return columns;
    }

    private static String constraintTypeName(int type) {
        return switch (type) {
            case 0 -> "FOREIGN KEY";
            case 1 -> "NOT NULL";
            case 2 -> "UNIQUE";
            case 3 -> "PRIMARY KEY";
            case 5 -> "TIMESTAMP";
            case 6 -> "LOCAL UNIQUE";
            case 7 -> "CHECK";
            default -> "TYPE_" + type;
        };
    }

    List<TriggerInfo> extractTriggers(List<String> schemas) throws SQLException {
        List<TriggerInfo> result = new ArrayList<>();
        String sql = """
                SELECT TR.TRIGGER_OID, TR.TRIGGER_NAME, T.TABLE_NAME, TR.IS_ENABLE
                FROM SYSTEM_.SYS_TRIGGERS_ TR
                JOIN SYSTEM_.SYS_USERS_ U ON TR.USER_ID = U.USER_ID
                JOIN SYSTEM_.SYS_TABLES_ T ON TR.TABLE_ID = T.TABLE_ID
                WHERE U.USER_NAME = ?
                ORDER BY TR.TRIGGER_NAME
                """;

        for (String schema : schemas) {
            int count = 0;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long triggerOid = rs.getLong(1);
                        String name = rs.getString(2).trim();
                        String tableName = rs.getString(3).trim();
                        boolean enabled = isEnabled(rs.getString(4));
                        String source = extractTriggerSource(triggerOid);
                        result.add(new TriggerInfo(schema, tableName, name, enabled, source));
                        log.debug("  trigger: {}.{} on {} enabled={} (source={}chars)", schema, name, tableName, enabled, source.length());
                        count++;
                    }
                }
            }
            log.debug("Schema {}: {} trigger(s)", schema, count);
        }
        return result;
    }

    // IS_ENABLE is 'T'/'F' on some Altibase versions and 1/0 on others
    private static boolean isEnabled(String value) {
        if (value == null) return true;
        String v = value.trim();
        return v.equalsIgnoreCase("T") || v.equals("1") || v.equalsIgnoreCase("Y");
    }

    private String extractTriggerSource(long triggerOid) throws SQLException {
        StringBuilder sb = new StringBuilder();
        String sql = "SELECT SUBSTRING FROM SYSTEM_.SYS_TRIGGER_STRINGS_ WHERE TRIGGER_OID = ? ORDER BY SEQNO";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, triggerOid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String chunk = rs.getString(1);
                    if (chunk != null) sb.append(chunk);
                }
            }
        }
        return sb.toString();
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

    List<SequenceInfo> extractSequences() {
        try {
            List<SequenceInfo> result = tryExtractFromSysTables();
            return applySchemaFilter(result);
        } catch (SQLException e) {
            log.warn("Failed to extract sequences: {}", e.getMessage());
            return List.of();
        }
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
                // min/max/cycle/cache are not exposed in a central system table on this version
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

    private List<ViewInfo> extractViews(List<String> schemas) throws SQLException {
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
