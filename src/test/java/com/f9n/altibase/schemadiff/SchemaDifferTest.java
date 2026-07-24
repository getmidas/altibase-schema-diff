package com.f9n.altibase.schemadiff;

import com.f9n.altibase.schemadiff.model.ConstraintInfo;
import com.f9n.altibase.schemadiff.model.DiffItem;
import com.f9n.altibase.schemadiff.model.DiffResult;
import com.f9n.altibase.schemadiff.model.IndexInfo;
import com.f9n.altibase.schemadiff.model.SchemaSnapshot;
import com.f9n.altibase.schemadiff.model.TriggerInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaDifferTest {

    private final SchemaDiffer differ = new SchemaDiffer();

    private SchemaSnapshot snapshotWithIndexes(List<IndexInfo> indexes) {
        return new SchemaSnapshot("server", 20300, "mydb", Instant.EPOCH,
                List.of(), List.of(), indexes, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private SchemaSnapshot snapshotWithConstraints(List<ConstraintInfo> constraints) {
        return new SchemaSnapshot("server", 20300, "mydb", Instant.EPOCH,
                List.of(), List.of(), List.of(), constraints, List.of(), List.of(), List.of(), List.of());
    }

    private SchemaSnapshot snapshotWithTriggers(List<TriggerInfo> triggers) {
        return new SchemaSnapshot("server", 20300, "mydb", Instant.EPOCH,
                List.of(), List.of(), List.of(), List.of(), triggers, List.of(), List.of(), List.of());
    }

    @Test
    void identicalIndexesProduceNoDiff() {
        IndexInfo idx = new IndexInfo("GTPDB", "ORDERS", "IDX_STATUS", false, "OTHER", List.of("STATUS ASC"));
        DiffResult result = differ.diff(snapshotWithIndexes(List.of(idx)), snapshotWithIndexes(List.of(idx)));
        assertFalse(result.hasDifferences());
    }

    @Test
    void missingIndexReportedAsOnlyInSource() {
        IndexInfo idx = new IndexInfo("GTPDB", "ORDERS", "IDX_STATUS", false, "OTHER", List.of("STATUS ASC"));
        DiffResult result = differ.diff(snapshotWithIndexes(List.of(idx)), snapshotWithIndexes(List.of()));

        assertEquals(1, result.items().size());
        DiffItem item = result.items().get(0);
        assertEquals(DiffItem.Category.INDEX, item.category());
        assertEquals(DiffItem.Type.ONLY_IN_SOURCE, item.type());
        assertEquals("GTPDB", item.schema());
        assertEquals("ORDERS.IDX_STATUS", item.objectName());
    }

    @Test
    void changedUniquenessAndColumnsReported() {
        IndexInfo src = new IndexInfo("GTPDB", "ORDERS", "IDX_STATUS", false, "OTHER", List.of("STATUS ASC"));
        IndexInfo tgt = new IndexInfo("GTPDB", "ORDERS", "IDX_STATUS", true, "OTHER", List.of("STATUS ASC", "CREATED_AT ASC"));
        DiffResult result = differ.diff(snapshotWithIndexes(List.of(src)), snapshotWithIndexes(List.of(tgt)));

        assertEquals(1, result.items().size());
        DiffItem item = result.items().get(0);
        assertEquals(DiffItem.Type.DIFFERENT, item.type());
        assertEquals(2, item.details().size());
        assertTrue(item.details().get(0).contains("unique"));
        assertTrue(item.details().get(1).contains("columns"));
    }

    @Test
    void autoNamedIndexesMatchAcrossServersDespiteDifferentIds() {
        IndexInfo src = new IndexInfo("GTPDB", "ORDERS", "__SYS_IDX_ID_142", true, "OTHER", List.of("ID ASC"));
        IndexInfo tgt = new IndexInfo("GTPDB", "ORDERS", "__SYS_IDX_ID_857", true, "OTHER", List.of("ID ASC"));
        DiffResult result = differ.diff(snapshotWithIndexes(List.of(src)), snapshotWithIndexes(List.of(tgt)));

        assertFalse(result.hasDifferences());
    }

    @Test
    void identicalConstraintsProduceNoDiff() {
        ConstraintInfo pk = new ConstraintInfo("GTPDB", "ORDERS", "PK_ORDERS", "PRIMARY KEY", List.of("ID"), null, null);
        DiffResult result = differ.diff(snapshotWithConstraints(List.of(pk)), snapshotWithConstraints(List.of(pk)));
        assertFalse(result.hasDifferences());
    }

    @Test
    void missingConstraintReportedAsOnlyInTarget() {
        ConstraintInfo fk = new ConstraintInfo("GTPDB", "ORDERS", "FK_ORDERS_CUSTOMER", "FOREIGN KEY",
                List.of("CUSTOMER_ID"), "CUSTOMERS", null);
        DiffResult result = differ.diff(snapshotWithConstraints(List.of()), snapshotWithConstraints(List.of(fk)));

        assertEquals(1, result.items().size());
        DiffItem item = result.items().get(0);
        assertEquals(DiffItem.Category.CONSTRAINT, item.category());
        assertEquals(DiffItem.Type.ONLY_IN_TARGET, item.type());
        assertEquals("ORDERS.FK_ORDERS_CUSTOMER", item.objectName());
    }

    @Test
    void changedConstraintColumnsAndReferenceReported() {
        ConstraintInfo src = new ConstraintInfo("GTPDB", "ORDERS", "FK_ORDERS_CUSTOMER", "FOREIGN KEY",
                List.of("CUSTOMER_ID"), "CUSTOMERS", null);
        ConstraintInfo tgt = new ConstraintInfo("GTPDB", "ORDERS", "FK_ORDERS_CUSTOMER", "FOREIGN KEY",
                List.of("CUST_ID"), "CUSTOMERS_V2", null);
        DiffResult result = differ.diff(snapshotWithConstraints(List.of(src)), snapshotWithConstraints(List.of(tgt)));

        assertEquals(1, result.items().size());
        DiffItem item = result.items().get(0);
        assertEquals(DiffItem.Type.DIFFERENT, item.type());
        assertEquals(2, item.details().size());
        assertTrue(item.details().get(0).contains("columns"));
        assertTrue(item.details().get(1).contains("references"));
    }

    @Test
    void autoNamedConstraintsMatchAcrossServersDespiteDifferentIds() {
        ConstraintInfo srcPk = new ConstraintInfo("GTPDB", "ORDERS", "__SYS_CON_PRIMARY_ID_1825", "PRIMARY KEY", List.of("ID"), null, null);
        ConstraintInfo tgtPk = new ConstraintInfo("GTPDB", "ORDERS", "__SYS_CON_PRIMARY_ID_1851", "PRIMARY KEY", List.of("ID"), null, null);
        ConstraintInfo srcTs = new ConstraintInfo("GTPDB", "ORDERS", "__SYS_CON_TIMESTAMP_1658", "TIMESTAMP", List.of(), null, null);
        ConstraintInfo tgtTs = new ConstraintInfo("GTPDB", "ORDERS", "__SYS_CON_TIMESTAMP_1680", "TIMESTAMP", List.of(), null, null);
        DiffResult result = differ.diff(
                snapshotWithConstraints(List.of(srcPk, srcTs)),
                snapshotWithConstraints(List.of(tgtPk, tgtTs)));

        assertFalse(result.hasDifferences());
    }

    @Test
    void onlyInSourceItemsCarryDefinitionDetails() {
        IndexInfo idx = new IndexInfo("GTPDB", "ORDERS", "IDX_STATUS", true, "OTHER", List.of("STATUS ASC"));
        ConstraintInfo fk = new ConstraintInfo("GTPDB", "ORDERS", "FK_ORDERS_CUSTOMER", "FOREIGN KEY",
                List.of("CUSTOMER_ID"), "CUSTOMERS", null);

        DiffResult idxResult = differ.diff(snapshotWithIndexes(List.of(idx)), snapshotWithIndexes(List.of()));
        assertEquals(List.of("UNIQUE OTHER (STATUS ASC)"), idxResult.items().get(0).details());

        DiffResult conResult = differ.diff(snapshotWithConstraints(List.of(fk)), snapshotWithConstraints(List.of()));
        assertEquals(List.of("FOREIGN KEY (CUSTOMER_ID) REFERENCES CUSTOMERS"), conResult.items().get(0).details());
    }

    @Test
    void identicalTriggersProduceNoDiff() {
        TriggerInfo trg = new TriggerInfo("GTPDB", "ORDERS", "TRG_ORDERS_AUDIT", true,
                "CREATE TRIGGER TRG_ORDERS_AUDIT AFTER INSERT ON ORDERS FOR EACH ROW BEGIN NULL; END");
        DiffResult result = differ.diff(snapshotWithTriggers(List.of(trg)), snapshotWithTriggers(List.of(trg)));
        assertFalse(result.hasDifferences());
    }

    @Test
    void triggerSourceAndEnabledDifferencesReported() {
        TriggerInfo src = new TriggerInfo("GTPDB", "ORDERS", "TRG_ORDERS_AUDIT", true,
                "CREATE TRIGGER TRG_ORDERS_AUDIT AFTER INSERT ON ORDERS FOR EACH ROW BEGIN INSERT INTO AUDIT_A VALUES (1); END");
        TriggerInfo tgt = new TriggerInfo("GTPDB", "ORDERS", "TRG_ORDERS_AUDIT", false,
                "CREATE TRIGGER TRG_ORDERS_AUDIT AFTER INSERT ON ORDERS FOR EACH ROW BEGIN INSERT INTO AUDIT_B VALUES (1); END");
        DiffResult result = differ.diff(snapshotWithTriggers(List.of(src)), snapshotWithTriggers(List.of(tgt)));

        assertEquals(1, result.items().size());
        DiffItem item = result.items().get(0);
        assertEquals(DiffItem.Category.TRIGGER, item.category());
        assertEquals(DiffItem.Type.DIFFERENT, item.type());
        assertTrue(item.details().stream().anyMatch(d -> d.contains("enabled")));
        assertTrue(item.details().stream().anyMatch(d -> d.contains("source code differs")));
        assertTrue(item.hasSourceDiff());
    }

    @Test
    void triggerSchemaPrefixDifferenceIgnored() {
        TriggerInfo src = new TriggerInfo("GTPDB", "ORDERS", "TRG_ORDERS_AUDIT", true,
                "CREATE TRIGGER GTPDB.TRG_ORDERS_AUDIT AFTER INSERT ON ORDERS FOR EACH ROW BEGIN NULL; END");
        TriggerInfo tgt = new TriggerInfo("GTPDB", "ORDERS", "TRG_ORDERS_AUDIT", true,
                "CREATE TRIGGER TRG_ORDERS_AUDIT AFTER INSERT ON ORDERS FOR EACH ROW BEGIN NULL; END");
        DiffResult result = differ.diff(snapshotWithTriggers(List.of(src)), snapshotWithTriggers(List.of(tgt)));

        assertFalse(result.hasDifferences());
    }
}
