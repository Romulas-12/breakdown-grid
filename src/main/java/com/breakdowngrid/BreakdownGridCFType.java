package com.breakdowngrid;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.customfields.impl.GenericTextCFType;
import com.atlassian.jira.issue.customfields.manager.GenericConfigManager;
import com.atlassian.jira.issue.customfields.persistence.CustomFieldValuePersister;
import com.atlassian.jira.issue.customfields.persistence.PersistenceFieldType;
import com.atlassian.jira.issue.customfields.view.CustomFieldParams;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.TextFieldCharacterLengthValidator;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.util.ErrorCollection;
import com.atlassian.jira.web.ExecutingHttpRequest;
import com.breakdowngrid.config.GridConfig;
import com.breakdowngrid.schema.GridColumn;
import com.breakdowngrid.schema.GridSchema;
import com.breakdowngrid.schema.GridValidator;
import com.breakdowngrid.schema.SchemaResolver;
import com.breakdowngrid.schema.SchemaStore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Кастомне поле "Breakdown Grid" — грід, набір колонок якого налаштовується в адмінці аддона
 * окремо для кожного контексту (за замовчуванням — проекту Jira).
 *
 * Значення — JSON `{"rows":[ { "<colKey>": <value>, "<colKey>Id": "<id>", ... }, ... ]}`
 * (успадковано від GenericTextCFType, зберігається в необмежену текстову колонку).
 */
public class BreakdownGridCFType extends GenericTextCFType {

    private static final Gson GSON = new Gson();

    public BreakdownGridCFType() {
        super(
                ComponentAccessor.getComponent(CustomFieldValuePersister.class),
                ComponentAccessor.getComponent(GenericConfigManager.class),
                ComponentAccessor.getComponent(TextFieldCharacterLengthValidator.class),
                ComponentAccessor.getJiraAuthenticationContext()
        );
    }

    @Override
    protected PersistenceFieldType getDatabaseType() {
        return PersistenceFieldType.TYPE_UNLIMITED_TEXT;
    }

    /**
     * Серверна валідація UI-сабміту (шар B): issue штатно недоступний у цьому методі, тож
     * дістаємо його з поточного HTTP-запиту (ExecutingHttpRequest) → проект → схема → перевірка.
     * Якщо issue/проект не резолвиться (напр. bulk edit) — серверну перевірку тихо пропускаємо,
     * її підстрахують клієнт (перед сабмітом) та REST (авторитет).
     */
    @Override
    public void validateFromParams(final CustomFieldParams relevantParams,
                                   final ErrorCollection errorCollectionToAddTo,
                                   final FieldConfig config) {
        final Object raw = relevantParams == null ? null : relevantParams.getFirstValueForNullKey();
        final String json = (raw instanceof String) ? (String) raw : null;
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        final String fieldId = config.getCustomField().getId();

        final List<GridColumn> columns = resolveColumnsFromRequest();
        if (columns == null) {
            // Контекст не визначено — базова перевірка формату, детальну лишаємо REST/клієнту.
            try {
                final JsonElement root = new JsonParser().parse(json);
                if (!root.isJsonObject()) {
                    errorCollectionToAddTo.addError(fieldId, "Breakdown Grid: invalid data format.");
                }
            } catch (Exception e) {
                errorCollectionToAddTo.addError(fieldId, "Breakdown Grid: invalid data format.");
            }
            return;
        }

        final List<String> errors = GridValidator.validate(json, columns);
        if (!errors.isEmpty()) {
            errorCollectionToAddTo.addError(fieldId,
                    "Breakdown Grid — " + String.join(" ", errors));
        }
    }

    /** Колонки для issue/проекту поточного запиту (edit: параметр id; create: параметр pid). null — якщо не визначити. */
    private List<GridColumn> resolveColumnsFromRequest() {
        try {
            final HttpServletRequest req = ExecutingHttpRequest.get();
            if (req == null) {
                return null;
            }
            final GridSchema schema = SchemaStore.load();
            String projectKey = null;

            final String issueId = req.getParameter("id");
            if (issueId != null && issueId.matches("\\d+")) {
                final IssueManager im = ComponentAccessor.getIssueManager();
                final Issue issue = im.getIssueObject(Long.valueOf(issueId));
                if (issue != null) {
                    final Project p = issue.getProjectObject();
                    projectKey = p == null ? null : p.getKey();
                }
            }
            if (projectKey == null) {
                final String pid = req.getParameter("pid");
                if (pid != null && pid.matches("\\d+")) {
                    final ProjectManager pm = ComponentAccessor.getProjectManager();
                    final Project p = pm.getProjectObj(Long.valueOf(pid));
                    projectKey = p == null ? null : p.getKey();
                }
            }
            final String ctx = SchemaResolver.contextKey(projectKey);
            if (ctx == null) {
                return null;
            }
            return schema.columnsFor(ctx);
        } catch (Exception e) {
            return null;
        }
    }

    /** id issue з параметра поточного HTTP-запиту (edit: 'id'). На edit переданий issue має null id. */
    private static Long requestIssueId() {
        try {
            final HttpServletRequest req = ExecutingHttpRequest.get();
            final String p = req == null ? null : req.getParameter("id");
            if (p != null && p.matches("\\d+")) {
                return Long.valueOf(p);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public Map<String, Object> getVelocityParameters(final Issue issue,
                                                     final CustomField field,
                                                     final FieldLayoutItem fieldLayoutItem) {
        final Map<String, Object> params = super.getVelocityParameters(issue, field, fieldLayoutItem);

        final GridSchema schema = SchemaStore.load();
        final String contextKey = SchemaResolver.contextKeyFor(issue);
        final List<GridColumn> columns = schema.columnsFor(contextKey);

        final List<Map<String, Object>> bdgColumns = new ArrayList<Map<String, Object>>();
        for (final GridColumn c : columns) {
            final Map<String, Object> cm = new HashMap<String, Object>();
            cm.put("key", c.keySafe());
            cm.put("label", c.labelSafe());
            cm.put("description", c.descriptionSafe());
            cm.put("hasDescription", c.hasDescription());
            cm.put("type", c.typeSafe());
            cm.put("source", c.sourceSafe());
            cm.put("hasSource", c.hasSource());
            cm.put("sum", c.sum);
            cm.put("required", c.required);
            cm.put("isNumeric", c.isNumeric());
            cm.put("isInt", c.isInt());
            cm.put("isDecimal", c.isDecimal());
            cm.put("isDate", c.isDate());
            cm.put("isBoolean", c.isBoolean());
            bdgColumns.add(cm);
        }

        // Значення для VIEW читається зі свіжого issue. На EDIT переданий issue має null id й порожнє
        // (кешоване) значення поля, тому edit-грід довантажується на клієнті через наш REST за ключем issue.
        String json = "";
        String bdgIssueKey = "";
        final Long readId = (issue != null && issue.getId() != null) ? issue.getId() : requestIssueId();
        if (readId != null) {
            try {
                final Issue fresh = ComponentAccessor.getIssueManager().getIssueObject(readId);
                if (fresh != null) {
                    bdgIssueKey = fresh.getKey();
                    final Object value = fresh.getCustomFieldValue(field);
                    if (value instanceof String) {
                        json = (String) value;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if ((json == null || json.trim().isEmpty()) && issue != null) {
            final Object value = issue.getCustomFieldValue(field);
            if (value instanceof String) {
                json = (String) value;
            }
        }

        final List<Map<String, Object>> bdgRows = new ArrayList<Map<String, Object>>();
        final Map<String, Double> sums = new LinkedHashMap<String, Double>();
        final Map<String, Set<String>> distinct = new LinkedHashMap<String, Set<String>>();
        for (final GridColumn c : columns) {
            if (c.sum && c.isNumeric()) {
                sums.put(c.keySafe(), 0d);
            } else if (c.sum) {
                distinct.put(c.keySafe(), new LinkedHashSet<String>());
            }
        }

        if (json != null && !json.trim().isEmpty()) {
            try {
                final JsonElement root = new JsonParser().parse(json);
                if (root.isJsonObject() && root.getAsJsonObject().has("rows")
                        && root.getAsJsonObject().get("rows").isJsonArray()) {
                    final JsonArray arr = root.getAsJsonObject().getAsJsonArray("rows");
                    for (final JsonElement el : arr) {
                        if (!el.isJsonObject()) {
                            continue;
                        }
                        final JsonObject ro = el.getAsJsonObject();
                        final Map<String, Object> rm = new HashMap<String, Object>();
                        for (final GridColumn c : columns) {
                            final String key = c.keySafe();
                            if (c.isNumeric()) {
                                final double d = optDouble(ro, key);
                                rm.put(key, c.isInt() ? formatInt(d) : formatDec(d));
                                if (sums.containsKey(key)) {
                                    sums.put(key, sums.get(key) + d);
                                }
                            } else if (c.isBoolean()) {
                                final boolean bv = optBool(ro, key);
                                rm.put(key, bv ? "✔" : "");
                                if (distinct.containsKey(key)) {
                                    distinct.get(key).add(Boolean.toString(bv));
                                }
                            } else {
                                final String sv = optString(ro, key);
                                rm.put(key, sv);
                                if (distinct.containsKey(key) && !sv.trim().isEmpty()) {
                                    distinct.get(key).add(sv);
                                }
                            }
                        }
                        bdgRows.add(rm);
                    }
                }
            } catch (Exception ignored) {
                // битий JSON — показуємо порожньо, raw лишається у bdgJson
            }
        }

        final Map<String, String> bdgTotals = new LinkedHashMap<String, String>();
        for (final GridColumn c : columns) {
            final String key = c.keySafe();
            if (sums.containsKey(key)) {
                bdgTotals.put(key, c.isInt() ? formatInt(sums.get(key)) : formatDec(sums.get(key)));
            }
        }
        for (final Map.Entry<String, Set<String>> e : distinct.entrySet()) {
            bdgTotals.put(e.getKey(), String.valueOf(e.getValue().size()));
        }

        params.put("bdgColumns", bdgColumns);
        params.put("bdgRows", bdgRows);
        params.put("bdgTotals", bdgTotals);
        params.put("bdgHasTotals", !bdgTotals.isEmpty());
        params.put("bdgContextKey", contextKey == null ? "" : contextKey);
        params.put("bdgConnected", GridConfig.isConfigured());
        params.put("bdgColumnsJson", scriptSafe(GSON.toJson(columns)));
        params.put("bdgJson", json == null ? "" : json);
        params.put("bdgIssueKey", bdgIssueKey);
        return params;
    }

    private static String formatDec(final double d) {
        return String.format(Locale.US, "%.2f", d);
    }

    private static String formatInt(final double d) {
        return String.format(Locale.US, "%.0f", d);
    }

    private static String scriptSafe(final String s) {
        return s == null ? "" : s.replace("</", "<\\/");
    }

    private static String optString(final JsonObject o, final String key) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            try {
                return o.get(key).getAsString();
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static double optDouble(final JsonObject o, final String key) {
        try {
            if (o.has(key) && !o.get(key).isJsonNull()) {
                return o.get(key).getAsDouble();
            }
        } catch (Exception ignored) {
        }
        return 0d;
    }

    private static boolean optBool(final JsonObject o, final String key) {
        try {
            if (o.has(key) && !o.get(key).isJsonNull()) {
                return o.get(key).getAsBoolean();
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
