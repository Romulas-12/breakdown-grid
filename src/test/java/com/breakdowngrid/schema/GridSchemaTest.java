package com.breakdowngrid.schema;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GridSchemaTest {

    // Вхідний JSON навмисно містить застаріле поле "resolveBy" — доводимо, що старі збережені
    // схеми досі парсяться (Gson ігнорує невідомі поля після видалення project+issuetype).
    private static final String JSON =
            "{\"resolveBy\":\"project\",\"schemas\":{\"FIN\":{\"columns\":["
            + "{\"key\":\"tool\",\"label\":\"Tool\",\"type\":\"string\",\"source\":\"erp/tools/all\"},"
            + "{\"key\":\"amount\",\"label\":\"Amount\",\"type\":\"decimal\",\"sum\":true}"
            + "]}}}";

    @Test
    public void parseNullAndEmptyGiveDefaults() {
        GridSchema a = GridSchema.parse(null);
        assertNotNull(a.schemas);
        assertTrue(a.schemas.isEmpty());

        GridSchema b = GridSchema.parse("   ");
        assertTrue(b.schemas.isEmpty());
    }

    @Test
    public void parseInvalidJsonGivesDefaultsNotThrow() {
        GridSchema s = GridSchema.parse("{ not json ]");
        assertTrue(s.schemas.isEmpty());
    }

    @Test
    public void parseLegacyResolveByFieldIsIgnored() {
        GridSchema s = GridSchema.parse(JSON);
        assertEquals(2, s.columnsFor("FIN").size());
    }

    @Test
    public void parseValidAndReadColumns() {
        GridSchema s = GridSchema.parse(JSON);
        List<GridColumn> cols = s.columnsFor("FIN");
        assertEquals(2, cols.size());
        assertEquals("tool", cols.get(0).keySafe());
        assertEquals("erp/tools/all", cols.get(0).sourceSafe());
        assertTrue(cols.get(0).hasSource());
        assertTrue(cols.get(1).isDecimal());
        assertTrue(cols.get(1).sum);
    }

    @Test
    public void columnsForUnknownOrNullIsEmpty() {
        GridSchema s = GridSchema.parse(JSON);
        assertTrue(s.columnsFor("MISSING").isEmpty());
        assertTrue(s.columnsFor(null).isEmpty());
    }

    @Test
    public void toJsonRoundTrips() {
        GridSchema s = GridSchema.parse(JSON);
        GridSchema again = GridSchema.parse(s.toJson());
        assertEquals(2, again.columnsFor("FIN").size());
        assertEquals("amount", again.columnsFor("FIN").get(1).keySafe());
    }
}
