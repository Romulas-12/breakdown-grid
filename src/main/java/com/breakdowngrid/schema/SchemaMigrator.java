package com.breakdowngrid.schema;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.customfields.persistence.CustomFieldValuePersister;
import com.atlassian.jira.issue.customfields.persistence.PersistenceFieldType;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.breakdowngrid.BreakdownGridCFType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.ofbiz.core.entity.GenericValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Міграція даних при перейменуванні ключа колонки в схемі.
 *
 * renames: projectKey -> (oldKey -> newKey). Для всіх кастомних полів типу Breakdown Grid проходить
 * issue відповідного проекту й переписує в JSON рядків "<oldKey>"→"<newKey>" та "<oldKey>Id"→"<newKey>Id".
 * Запис — через CustomFieldValuePersister (кеш-безпечно і БЕЗ запису в History issue).
 * Обмін ключами (A↔B) коректний — новий обʼєкт рядка будується за один прохід.
 * Перелік issue з непорожнім значенням беремо через OfBiz (лише читання id).
 */
public final class SchemaMigrator {

    private SchemaMigrator() {
    }

    /** Скільки issue буде змінено (для підтвердження) — нічого не пише. */
    public static int countAffected(final Map<String, Map<String, String>> renames) {
        return process(renames, false);
    }

    /** Застосувати міграцію; повертає кількість змінених issue. */
    public static int migrate(final Map<String, Map<String, String>> renames) {
        return process(renames, true);
    }

    private static int process(final Map<String, Map<String, String>> renames, final boolean apply) {
        if (renames == null || renames.isEmpty()) {
            return 0;
        }
        final OfBizDelegator delegator = ComponentAccessor.getOfBizDelegator();
        final IssueManager im = ComponentAccessor.getIssueManager();
        final CustomFieldManager cfm = ComponentAccessor.getCustomFieldManager();
        final CustomFieldValuePersister persister =
                ComponentAccessor.getComponent(CustomFieldValuePersister.class);
        int affected = 0;

        for (final CustomField cf : cfm.getCustomFieldObjects()) {
            if (!(cf.getCustomFieldType() instanceof BreakdownGridCFType)) {
                continue;
            }
            final Map<String, Object> query = new HashMap<String, Object>();
            query.put("customfield", cf.getIdAsLong());
            final List<GenericValue> gvs;
            try {
                gvs = delegator.findByAnd("CustomFieldValue", query);
            } catch (Exception e) {
                continue;
            }

            final Set<Long> issueIds = new LinkedHashSet<Long>();
            for (final GenericValue gv : gvs) {
                final Long iid = gv.getLong("issue");
                if (iid != null) {
                    issueIds.add(iid);
                }
            }

            for (final Long issueId : issueIds) {
                final Issue issue = im.getIssueObject(issueId);
                if (issue == null || issue.getProjectObject() == null) {
                    continue;
                }
                final Map<String, String> map = renames.get(issue.getProjectObject().getKey());
                if (map == null || map.isEmpty()) {
                    continue;
                }

                final List<Object> vals =
                        persister.getValues(cf, issueId, PersistenceFieldType.TYPE_UNLIMITED_TEXT);
                if (vals == null || vals.isEmpty() || !(vals.get(0) instanceof String)) {
                    continue;
                }
                final String json = (String) vals.get(0);
                if (json == null || json.trim().isEmpty()) {
                    continue;
                }

                final String newJson = remap(json, map);
                if (newJson != null && !newJson.equals(json)) {
                    affected++;
                    if (apply) {
                        persister.updateValues(cf, issueId, PersistenceFieldType.TYPE_UNLIMITED_TEXT,
                                Collections.<Object>singletonList(newJson));
                    }
                }
            }
        }
        return affected;
    }

    /** Переписує ключі у всіх рядках JSON за мапою (з супутником "<key>Id"); обмін ключами безпечний. */
    static String remap(final String json, final Map<String, String> renames) {
        final Map<String, String> exp = new HashMap<String, String>();
        for (final Map.Entry<String, String> e : renames.entrySet()) {
            exp.put(e.getKey(), e.getValue());
            exp.put(e.getKey() + "Id", e.getValue() + "Id");
        }
        try {
            final JsonElement root = new JsonParser().parse(json);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("rows")
                    || !root.getAsJsonObject().get("rows").isJsonArray()) {
                return json;
            }
            final JsonObject obj = root.getAsJsonObject();
            final JsonArray rows = obj.getAsJsonArray("rows");
            final JsonArray newRows = new JsonArray();
            for (final JsonElement el : rows) {
                if (!el.isJsonObject()) {
                    newRows.add(el);
                    continue;
                }
                final JsonObject ro = el.getAsJsonObject();
                final JsonObject nr = new JsonObject();
                for (final Map.Entry<String, JsonElement> ent : ro.entrySet()) {
                    final String nk = exp.containsKey(ent.getKey()) ? exp.get(ent.getKey()) : ent.getKey();
                    nr.add(nk, ent.getValue());
                }
                newRows.add(nr);
            }
            obj.add("rows", newRows);
            return obj.toString();
        } catch (Exception e) {
            return json;
        }
    }
}
