package com.breakdowngrid.search;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.index.indexers.impl.AbstractCustomFieldIndexer;
import com.atlassian.jira.web.FieldVisibilityManager;
import com.breakdowngrid.schema.GridColumn;
import com.breakdowngrid.schema.GridSchema;
import com.breakdowngrid.schema.SchemaResolver;
import com.breakdowngrid.schema.SchemaStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;

import java.time.LocalDate;
import java.util.List;

/**
 * Індексатор гріда для пошуку ПО КОЛОНКАХ (а не по сирому JSON).
 *
 * На кожне значення комірки кладе терми під імʼям, похідним від ключа колонки,
 * з суфіксом за призначенням:
 *   {@code <cf>_<col>_e} — exact-текст (lowercase, не аналізується) → «=»/«!=»;
 *   {@code <cf>_<col>_t} — аналізований текст                       → «~» (містить);
 *   {@code <cf>_<col>_n} — число (DoublePoint)                      → діапазони для int/decimal;
 *   {@code <cf>_<col>_d} — дата як epochDay (LongPoint)             → діапазони для date.
 * Терми багатозначні — по одному на рядок гріда → «є рядок, де колонка задовольняє».
 *
 * Текстові/числові/датові колонки індексуються; boolean — ні (пошук по «так/ні» без сенсу).
 * Числова колонка додатково лишає текстові терми (представлення), датова — теж.
 */
public class BreakdownGridIndexer extends AbstractCustomFieldIndexer {

    private final CustomField customField;

    public BreakdownGridIndexer(final FieldVisibilityManager fieldVisibilityManager,
                                final CustomField customField) {
        super(fieldVisibilityManager, customField);
        this.customField = customField;
    }

    @Override
    public void addDocumentFieldsSearchable(final Document doc, final Issue issue) {
        addGridTerms(doc, issue);
    }

    @Override
    public void addDocumentFieldsNotSearchable(final Document doc, final Issue issue) {
        // Поле завжди шукабельне; окремого «невидимого» зберігання не потрібно.
    }

    private void addGridTerms(final Document doc, final Issue issue) {
        final Object value = customField.getValue(issue);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            return;
        }
        final String json = (String) value;

        final GridSchema schema = SchemaStore.load();
        final String ctx = SchemaResolver.contextKeyFor(issue);
        final List<GridColumn> columns = schema.columnsFor(ctx);
        if (columns == null || columns.isEmpty()) {
            return;
        }

        final JsonArray rows;
        try {
            final JsonElement root = new JsonParser().parse(json);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("rows")
                    || !root.getAsJsonObject().get("rows").isJsonArray()) {
                return;
            }
            rows = root.getAsJsonObject().getAsJsonArray("rows");
        } catch (Exception e) {
            return; // битий JSON — нема що індексувати
        }

        final String base = getDocumentFieldId(); // customfield_NNNNN
        for (final JsonElement el : rows) {
            if (!el.isJsonObject()) {
                continue;
            }
            final JsonObject ro = el.getAsJsonObject();
            for (final GridColumn c : columns) {
                if (c.isBoolean()) {
                    continue; // boolean не індексуємо
                }
                final String key = c.keySafe();
                if (key.isEmpty() || !ro.has(key) || ro.get(key).isJsonNull()) {
                    continue;
                }
                final String cell;
                try {
                    cell = ro.get(key).getAsString();
                } catch (Exception e) {
                    continue;
                }
                if (cell == null || cell.trim().isEmpty()) {
                    continue;
                }
                final String fname = base + "_" + key;
                final String trimmed = cell.trim();

                // Текстові терми — для всіх типів (exact «=» і «містить» «~»).
                doc.add(new StringField(fname + "_e", trimmed.toLowerCase(), Field.Store.NO));
                doc.add(new TextField(fname + "_t", cell, Field.Store.NO));

                // Числовий терм — для діапазонів int/decimal.
                if (c.isNumeric()) {
                    try {
                        doc.add(new DoublePoint(fname + "_n", Double.parseDouble(trimmed)));
                    } catch (NumberFormatException ignored) {
                        // не число — лишаємо лише текстові терми
                    }
                }

                // Датовий терм — epochDay для діапазонів date (формат yyyy-MM-dd).
                if (c.isDate()) {
                    try {
                        doc.add(new LongPoint(fname + "_d", LocalDate.parse(trimmed).toEpochDay()));
                    } catch (Exception ignored) {
                        // не ISO-дата — лишаємо лише текстові терми
                    }
                }
            }
        }
    }
}
