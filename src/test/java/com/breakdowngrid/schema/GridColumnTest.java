package com.breakdowngrid.schema;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GridColumnTest {

    private static GridColumn col(String key, String type) {
        GridColumn c = new GridColumn();
        c.key = key;
        c.type = type;
        return c;
    }

    @Test
    public void keySafeTrimsAndHandlesNull() {
        assertEquals("", new GridColumn().keySafe());
        GridColumn c = col("  amount ", "int");
        assertEquals("amount", c.keySafe());
    }

    @Test
    public void labelSafeFallsBackToKey() {
        GridColumn c = col("amount", "int");
        assertEquals("amount", c.labelSafe());   // no label → key
        c.label = "  ";
        assertEquals("amount", c.labelSafe());   // blank label → key
        c.label = "Amount";
        assertEquals("Amount", c.labelSafe());
    }

    @Test
    public void typeSafeDefaultsToString() {
        assertEquals("string", new GridColumn().typeSafe());
        assertEquals("string", col("k", "  ").typeSafe());
        assertEquals("int", col("k", "int").typeSafe());
    }

    @Test
    public void typePredicates() {
        assertTrue(col("k", "int").isInt());
        assertTrue(col("k", "decimal").isDecimal());
        assertTrue(col("k", "int").isNumeric());
        assertTrue(col("k", "decimal").isNumeric());
        assertFalse(col("k", "string").isNumeric());
        assertTrue(col("k", "date").isDate());
        assertTrue(col("k", "boolean").isBoolean());
        assertTrue(col("k", "string").isString());
        assertTrue(new GridColumn().isString());     // null type → string
    }

    @Test
    public void hasSourceIgnoresBlank() {
        GridColumn c = col("k", "string");
        assertFalse(c.hasSource());
        c.source = "   ";
        assertFalse(c.hasSource());
        c.source = "erp/tools/all";
        assertTrue(c.hasSource());
        assertEquals("erp/tools/all", c.sourceSafe());
    }

    @Test
    public void hasDependsOn() {
        GridColumn c = col("k", "string");
        assertFalse(c.hasDependsOn());
        c.dependsOn = Collections.emptyList();
        assertFalse(c.hasDependsOn());
        c.dependsOn = Arrays.asList("resource", "project");
        assertTrue(c.hasDependsOn());
    }

    @Test
    public void description() {
        GridColumn c = col("k", "string");
        assertFalse(c.hasDescription());
        assertEquals("", c.descriptionSafe());
        c.description = "  ";
        assertFalse(c.hasDescription());
        c.description = "  help ";
        assertTrue(c.hasDescription());
        assertEquals("help", c.descriptionSafe());
    }
}
