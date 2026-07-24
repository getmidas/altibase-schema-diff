package com.f9n.altibase.schemadiff;

import com.f9n.altibase.schemadiff.model.IndexInfo;
import com.f9n.altibase.schemadiff.model.SequenceInfo;
import com.f9n.altibase.schemadiff.model.TableInfo;
import com.f9n.altibase.schemadiff.model.TriggerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.*;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchemaExtractorTest {

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    @Mock
    private ResultSet resultSet;

    private ConnectionConfig config;
    private SchemaExtractor extractor;

    @BeforeEach
    void setUp() {
        // server, port, user, password, database, connectTimeoutSeconds
        config = new ConnectionConfig("server", 1234, "user", "pass", "db", 30);
        extractor = new SchemaExtractor(connection, config, Set.of());
    }

    @Test
    void testExtractSequences() throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);

        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn("GTPDB");
        when(resultSet.getString(2)).thenReturn("SEQ_TEST");

        // Execute
        List<SequenceInfo> sequences = extractor.extractSequences();

        // Verify
        assertNotNull(sequences);
        assertEquals(1, sequences.size());
        assertEquals("GTPDB", sequences.get(0).schema());
        assertEquals("SEQ_TEST", sequences.get(0).name());
        assertEquals(0, sequences.get(0).minValue());
        assertFalse(sequences.get(0).hasDetails());
        
        verify(statement).executeQuery(argThat(s -> s != null && s.contains("SYS_TABLES_")));
    }

    @Test
    void testExtractIndexes() throws SQLException {
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(meta);
        when(meta.getIndexInfo(null, "GTPDB", "ORDERS", false, true)).thenReturn(resultSet);

        when(resultSet.next()).thenReturn(true, true, true, false);
        when(resultSet.getShort("TYPE")).thenReturn(DatabaseMetaData.tableIndexOther);
        when(resultSet.getString("INDEX_NAME")).thenReturn("IDX_ORDERS_STATUS", "IDX_ORDERS_STATUS", "__SYS_IDX_ID_142");
        when(resultSet.getString("COLUMN_NAME")).thenReturn("STATUS", "CREATED_AT", "ID");
        when(resultSet.getBoolean("NON_UNIQUE")).thenReturn(true, true, false);
        when(resultSet.getShort("ORDINAL_POSITION")).thenReturn((short) 1, (short) 2, (short) 1);
        when(resultSet.getString("ASC_OR_DESC")).thenReturn("A", "D", "A");

        List<IndexInfo> indexes = extractor.extractIndexes(List.of(new TableInfo("GTPDB", "ORDERS", List.of())));

        assertEquals(2, indexes.size());

        IndexInfo statusIdx = indexes.get(0);
        assertEquals("IDX_ORDERS_STATUS", statusIdx.indexName());
        assertEquals("GTPDB.ORDERS.IDX_ORDERS_STATUS", statusIdx.qualifiedName());
        assertFalse(statusIdx.unique());
        assertFalse(statusIdx.autoNamed());
        assertEquals(List.of("STATUS ASC", "CREATED_AT DESC"), statusIdx.columns());

        IndexInfo pkIdx = indexes.get(1);
        assertEquals("__SYS_IDX_ID_142", pkIdx.indexName());
        assertTrue(pkIdx.unique());
        assertTrue(pkIdx.autoNamed());
        assertEquals(List.of("ID ASC"), pkIdx.columns());
    }

    @Test
    void testExtractTriggers() throws SQLException {
        PreparedStatement triggerPs = mock(PreparedStatement.class);
        PreparedStatement sourcePs = mock(PreparedStatement.class);
        ResultSet sourceRs = mock(ResultSet.class);

        when(connection.prepareStatement(contains("SYS_TRIGGERS_"))).thenReturn(triggerPs);
        when(connection.prepareStatement(contains("SYS_TRIGGER_STRINGS_"))).thenReturn(sourcePs);

        when(triggerPs.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getLong(1)).thenReturn(42L);
        when(resultSet.getString(2)).thenReturn("TRG_ORDERS_AUDIT");
        when(resultSet.getString(3)).thenReturn("ORDERS");
        when(resultSet.getString(4)).thenReturn("T");

        when(sourcePs.executeQuery()).thenReturn(sourceRs);
        when(sourceRs.next()).thenReturn(true, true, false);
        when(sourceRs.getString(1)).thenReturn("CREATE TRIGGER TRG_ORDERS_AUDIT ", "AFTER INSERT ON ORDERS");

        List<TriggerInfo> triggers = extractor.extractTriggers(List.of("GTPDB"));

        assertEquals(1, triggers.size());
        TriggerInfo trg = triggers.get(0);
        assertEquals("GTPDB", trg.schema());
        assertEquals("ORDERS", trg.tableName());
        assertEquals("TRG_ORDERS_AUDIT", trg.triggerName());
        assertTrue(trg.enabled());
        assertEquals("CREATE TRIGGER TRG_ORDERS_AUDIT AFTER INSERT ON ORDERS", trg.source());
        verify(sourcePs).setLong(1, 42L);
    }
}
