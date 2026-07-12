package com.breakdowngrid.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Тихо нормалізує числові значення (варіант B):
 *   int     → округлення до цілого (HALF_UP);
 *   decimal → DECIMAL(15,2): округлення до 2 знаків (HALF_UP) + обрізання до 15 значущих (клемп по модулю).
 * Викликається на записі (REST PUT) вже ПІСЛЯ валідації — значення числових колонок гарантовано парсяться.
 * Не-число / порожнє / бите JSON лишаємо як є (їх ловить валідатор).
 */
public final class GridNormalizer {

    /** decimal(15,2) → максимум по модулю 9 999 999 999 999.99 */
    private static final BigDecimal DEC_MAX =
            new BigDecimal("9999999999999.99");

    private GridNormalizer() {
    }

    public static String normalize(final String json, final List<GridColumn> columns) {
        if (json == null || json.trim().isEmpty()) {
            return json;
        }
        try {
            final JsonElement root = new JsonParser().parse(json);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("rows")
                    || !root.getAsJsonObject().get("rows").isJsonArray()) {
                return json;
            }
            final JsonArray rows = root.getAsJsonObject().getAsJsonArray("rows");
            for (final JsonElement el : rows) {
                if (!el.isJsonObject()) {
                    continue;
                }
                final JsonObject ro = el.getAsJsonObject();
                for (final GridColumn c : columns) {
                    if (!c.isNumeric()) {
                        continue;
                    }
                    final String key = c.keySafe();
                    if (!ro.has(key) || ro.get(key).isJsonNull()) {
                        continue;
                    }
                    final String raw = ro.get(key).getAsString().trim();
                    if (raw.isEmpty()) {
                        continue;
                    }
                    try {
                        BigDecimal bd = new BigDecimal(raw);
                        if (c.isInt()) {
                            bd = bd.setScale(0, RoundingMode.HALF_UP);
                            ro.add(key, new JsonPrimitive(bd.longValue()));
                        } else {
                            bd = bd.setScale(2, RoundingMode.HALF_UP);
                            if (bd.abs().compareTo(DEC_MAX) > 0) {
                                bd = bd.signum() < 0 ? DEC_MAX.negate() : DEC_MAX;
                            }
                            ro.add(key, new JsonPrimitive(bd));
                        }
                    } catch (NumberFormatException ignored) {
                        // не число — лишаємо (валідатор уже відхилив би)
                    }
                }
            }
            return root.toString();
        } catch (Exception e) {
            return json;
        }
    }
}
