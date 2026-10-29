package com.breakdowngrid.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.UUID;

/**
 * Стабільний ідентифікатор рядка гріда — службовий ключ {@value #KEY}.
 *
 * Потрібен для подій/історії по рядках (додано / видалено / змінено рядок): дозволяє зіставити
 * рядки старого й нового значення, а не вважати будь-яку правку «видалив усе + додав усе».
 *
 * Авторитет — сервер: {@link #ensure(String)} проставляє id кожному рядку без нього на будь-якому
 * шляху запису (UI-сабміт і REST PUT). Клієнт (breakdown-grid.js) теж генерує id при додаванні рядка,
 * щоб він був стабільним уже під час редагування форми, але сервер — остання інстанція.
 *
 * {@value #KEY} — зарезервований ключ: колонка з таким (і взагалі будь-яка з ведучим «_») у схемі
 * заборонена (див. ConfigServlet), тож службовий id не конфліктує з даними колонок.
 */
public final class GridRowIds {

    /** Службовий ключ ідентифікатора рядка в JSON. */
    public static final String KEY = "_rowId";

    private GridRowIds() {
    }

    /**
     * Проставляє {@link #KEY} кожному рядку, у якого його немає (або порожній).
     * Повертає новий JSON, якщо щось додано, інакше — вхідний рядок без змін.
     * Биті/несподівані дані повертає як є (їх ловлять валідатор/нормалізатор).
     */
    public static String ensure(final String json) {
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
            boolean changed = false;
            for (final JsonElement el : rows) {
                if (!el.isJsonObject()) {
                    continue;
                }
                final JsonObject ro = el.getAsJsonObject();
                if (!ro.has(KEY) || ro.get(KEY).isJsonNull() || isBlank(ro, KEY)) {
                    ro.addProperty(KEY, UUID.randomUUID().toString());
                    changed = true;
                }
            }
            return changed ? root.toString() : json;
        } catch (Exception e) {
            return json;
        }
    }

    private static boolean isBlank(final JsonObject ro, final String key) {
        try {
            return ro.get(key).getAsString().trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
