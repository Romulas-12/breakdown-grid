package com.breakdowngrid.schema;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class GridNormalizerTest {

    private static GridColumn col(String key, String type) {
        GridColumn c = new GridColumn();
        c.key = key;
        c.type = type;
        return c;
    }

    private static final List<GridColumn> COLS = Arrays.asList(
            col("qty", "int"),
            col("amount", "decimal"),
            col("name", "string"));

    private static JsonObject firstRow(String out) {
        return new JsonParser().parse(out).getAsJsonObject()
                .getAsJsonArray("rows").get(0).getAsJsonObject();
    }

    @Test
    public void intRoundsHalfUp() {
        String out = GridNormalizer.normalize("{\"rows\":[{\"qty\":\"10.5\"}]}", COLS);
        assertEquals(11L, firstRow(out).get("qty").getAsLong());

        out = GridNormalizer.normalize("{\"rows\":[{\"qty\":\"10.4\"}]}", COLS);
        assertEquals(10L, firstRow(out).get("qty").getAsLong());
    }

    @Test
    public void decimalRoundsToTwoPlaces() {
        String out = GridNormalizer.normalize("{\"rows\":[{\"amount\":\"1.005\"}]}", COLS);
        assertEquals(0, firstRow(out).get("amount").getAsBigDecimal().compareTo(new BigDecimal("1.01")));

        out = GridNormalizer.normalize("{\"rows\":[{\"amount\":\"2.5\"}]}", COLS);
        assertEquals(0, firstRow(out).get("amount").getAsBigDecimal().compareTo(new BigDecimal("2.50")));
    }

    @Test
    public void decimalClampsToRange() {
        String out = GridNormalizer.normalize("{\"rows\":[{\"amount\":\"99999999999999\"}]}", COLS);
        assertEquals(0, firstRow(out).get("amount").getAsBigDecimal()
                .compareTo(new BigDecimal("9999999999999.99")));

        out = GridNormalizer.normalize("{\"rows\":[{\"amount\":\"-99999999999999\"}]}", COLS);
        assertEquals(0, firstRow(out).get("amount").getAsBigDecimal()
                .compareTo(new BigDecimal("-9999999999999.99")));
    }

    @Test
    public void nonNumericColumnsAndValuesUntouched() {
        String out = GridNormalizer.normalize(
                "{\"rows\":[{\"name\":\"Cloudflare\",\"amount\":\"abc\"}]}", COLS);
        JsonObject r = firstRow(out);
        assertEquals("Cloudflare", r.get("name").getAsString()); // string untouched
        assertEquals("abc", r.get("amount").getAsString());       // non-number left as-is
    }

    @Test
    public void emptyOrMissingValuesUntouched() {
        String out = GridNormalizer.normalize("{\"rows\":[{\"qty\":\"\"}]}", COLS);
        assertEquals("", firstRow(out).get("qty").getAsString());
    }

    @Test
    public void malformedInputReturnedAsIs() {
        assertEquals("not json", GridNormalizer.normalize("not json", COLS));
        assertEquals("", GridNormalizer.normalize("", COLS));
        assertEquals(null, GridNormalizer.normalize(null, COLS));
    }
}
