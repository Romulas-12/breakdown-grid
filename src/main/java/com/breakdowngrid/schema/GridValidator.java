package com.breakdowngrid.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Валідація значення гріда проти схеми. Спільна для REST PUT та серверної UI-перевірки.
 *
 * Правила:
 *   - required + порожнє → помилка;
 *   - int/decimal: значення має бути числом (зайва точність НЕ помилка — її тихо нормалізує GridNormalizer);
 *   - date не ISO / boolean не булеве → помилка (порожнє в не-required — ок);
 *   - source НЕ звіряється (у даних її нема — це властивість схеми, аддон у джерело не ходить).
 * Повертає перелік повідомлень; порожній список = валідно.
 */
public final class GridValidator {

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private GridValidator() {
    }

    public static List<String> validate(final String json, final List<GridColumn> columns) {
        final List<String> errors = new ArrayList<String>();
        if (json == null || json.trim().isEmpty()) {
            return errors;
        }
        final JsonArray rows;
        try {
            final JsonElement root = new JsonParser().parse(json);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("rows")
                    || !root.getAsJsonObject().get("rows").isJsonArray()) {
                errors.add("Invalid data format (expected object with a \"rows\" array).");
                return errors;
            }
            rows = root.getAsJsonObject().getAsJsonArray("rows");
        } catch (Exception e) {
            errors.add("Invalid JSON.");
            return errors;
        }

        int idx = 0;
        for (final JsonElement el : rows) {
            idx++;
            if (!el.isJsonObject()) {
                continue;
            }
            final JsonObject ro = el.getAsJsonObject();
            for (final GridColumn c : columns) {
                final String key = c.keySafe();
                if (key.isEmpty()) {
                    continue;
                }
                final boolean empty = isEmpty(ro, key);

                if (c.required && !c.isBoolean() && empty) {
                    errors.add("Row " + idx + ", «" + c.labelSafe() + "»: required.");
                    continue;
                }
                if (empty) {
                    continue; // не-required порожнє — типи не перевіряємо
                }
                if (!typeOk(ro.get(key), c)) {
                    errors.add("Row " + idx + ", «" + c.labelSafe() + "»: expected " + c.typeSafe() + ".");
                }
            }
        }
        return errors;
    }

    private static boolean isEmpty(final JsonObject ro, final String key) {
        if (!ro.has(key) || ro.get(key).isJsonNull()) {
            return true;
        }
        final JsonElement v = ro.get(key);
        return v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()
                && v.getAsString().trim().isEmpty();
    }

    private static boolean typeOk(final JsonElement v, final GridColumn c) {
        if (v == null || v.isJsonNull()) {
            return true;
        }
        if (c.isNumeric()) {
            try {
                Double.parseDouble(v.getAsString().trim());
                return true; // int/decimal: досить, щоб було число; точність нормалізується окремо
            } catch (Exception e) {
                return false;
            }
        }
        if (c.isBoolean()) {
            if (v.isJsonPrimitive()) {
                final JsonPrimitive p = v.getAsJsonPrimitive();
                if (p.isBoolean()) {
                    return true;
                }
                final String s = p.getAsString().trim();
                return "true".equals(s) || "false".equals(s);
            }
            return false;
        }
        if (c.isDate()) {
            try {
                return ISO_DATE.matcher(v.getAsString().trim()).matches();
            } catch (Exception e) {
                return false;
            }
        }
        return true; // string — будь-що
    }
}
