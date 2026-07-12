package com.breakdowngrid.schema;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GridValidatorTest {

    private static GridColumn col(String key, String type, boolean required) {
        GridColumn c = new GridColumn();
        c.key = key;
        c.type = type;
        c.required = required;
        return c;
    }

    private static boolean anyContains(List<String> errs, String needle) {
        for (String e : errs) {
            if (e.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void requiredEmptyIsError() {
        List<GridColumn> cols = Arrays.asList(col("name", "string", true));
        List<String> errs = GridValidator.validate("{\"rows\":[{\"name\":\"\"}]}", cols);
        assertEquals(1, errs.size());
        assertTrue(anyContains(errs, "required"));

        // missing key entirely also counts as empty
        errs = GridValidator.validate("{\"rows\":[{}]}", cols);
        assertTrue(anyContains(errs, "required"));
    }

    @Test
    public void requiredBooleanEmptyIsNotError() {
        // required + boolean + empty is intentionally allowed (unchecked checkbox = false)
        List<GridColumn> cols = Arrays.asList(col("flag", "boolean", true));
        List<String> errs = GridValidator.validate("{\"rows\":[{}]}", cols);
        assertTrue(errs.isEmpty());
    }

    @Test
    public void numericTypeChecked() {
        List<GridColumn> cols = Arrays.asList(col("amount", "decimal", false));
        assertTrue(GridValidator.validate("{\"rows\":[{\"amount\":\"abc\"}]}", cols).size() == 1);
        assertTrue(GridValidator.validate("{\"rows\":[{\"amount\":\"12.34\"}]}", cols).isEmpty());
        // extra precision is NOT a validation error (normalized separately)
        assertTrue(GridValidator.validate("{\"rows\":[{\"amount\":\"12.3456\"}]}", cols).isEmpty());
    }

    @Test
    public void dateTypeChecked() {
        List<GridColumn> cols = Arrays.asList(col("d", "date", false));
        assertTrue(GridValidator.validate("{\"rows\":[{\"d\":\"2026-01-31\"}]}", cols).isEmpty());
        assertEquals(1, GridValidator.validate("{\"rows\":[{\"d\":\"2026/01/31\"}]}", cols).size());
    }

    @Test
    public void booleanTypeChecked() {
        List<GridColumn> cols = Arrays.asList(col("flag", "boolean", false));
        assertTrue(GridValidator.validate("{\"rows\":[{\"flag\":\"true\"}]}", cols).isEmpty());
        assertTrue(GridValidator.validate("{\"rows\":[{\"flag\":false}]}", cols).isEmpty());
        assertEquals(1, GridValidator.validate("{\"rows\":[{\"flag\":\"maybe\"}]}", cols).size());
    }

    @Test
    public void stringAcceptsAnything() {
        List<GridColumn> cols = Arrays.asList(col("s", "string", false));
        assertTrue(GridValidator.validate("{\"rows\":[{\"s\":\"anything 123 !@#\"}]}", cols).isEmpty());
    }

    @Test
    public void emptyNonRequiredSkipsTypeCheck() {
        List<GridColumn> cols = Arrays.asList(col("amount", "int", false));
        assertTrue(GridValidator.validate("{\"rows\":[{\"amount\":\"\"}]}", cols).isEmpty());
    }

    @Test
    public void sourceMembershipNotChecked() {
        // a source-backed column's value is not verified against any list
        GridColumn c = col("tool", "string", false);
        c.source = "erp/tools/all";
        List<String> errs = GridValidator.validate(
                "{\"rows\":[{\"tool\":\"Anything not in the list\",\"toolId\":\"x\"}]}", Arrays.asList(c));
        assertTrue(errs.isEmpty());
    }

    @Test
    public void badJsonAndShapeReported() {
        List<GridColumn> cols = Arrays.asList(col("s", "string", false));
        assertTrue(anyContains(GridValidator.validate("{ not json ]", cols), "Invalid JSON"));
        assertTrue(anyContains(GridValidator.validate("{\"nope\":1}", cols), "Invalid data format"));
    }

    @Test
    public void emptyOrNullJsonIsValid() {
        List<GridColumn> cols = Arrays.asList(col("name", "string", true));
        assertTrue(GridValidator.validate(null, cols).isEmpty());
        assertTrue(GridValidator.validate("", cols).isEmpty());
    }

    @Test
    public void rowIndexInMessage() {
        List<GridColumn> cols = Arrays.asList(col("name", "string", true));
        List<String> errs = GridValidator.validate(
                "{\"rows\":[{\"name\":\"ok\"},{\"name\":\"\"}]}", cols);
        assertEquals(1, errs.size());
        assertTrue(anyContains(errs, "Row 2"));
    }
}
