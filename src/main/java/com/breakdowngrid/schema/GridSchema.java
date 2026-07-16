package com.breakdowngrid.schema;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Уся конфігурація схем гріда (зберігається одним JSON у PluginSettings).
 *
 *   schemas — контекстний ключ (наразі ключ проекту, напр. "FIN") -> набір колонок.
 *
 * {
 *   "schemas": { "FIN": { "columns": [ {GridColumn}, ... ] }, ... }
 * }
 */
public class GridSchema {

    public Map<String, ProjectSchema> schemas = new LinkedHashMap<String, ProjectSchema>();

    public static class ProjectSchema {
        public List<GridColumn> columns = new ArrayList<GridColumn>();
    }

    /** Колонки для заданого контекстного ключа (порожній список, якщо схеми немає). */
    public List<GridColumn> columnsFor(final String contextKey) {
        if (contextKey != null && schemas != null) {
            final ProjectSchema ps = schemas.get(contextKey);
            if (ps != null && ps.columns != null) {
                return ps.columns;
            }
        }
        return Collections.emptyList();
    }

    private static final Gson GSON = new Gson();

    public static GridSchema parse(final String json) {
        if (json == null || json.trim().isEmpty()) {
            return new GridSchema();
        }
        try {
            final GridSchema s = GSON.fromJson(json, GridSchema.class);
            if (s == null) {
                return new GridSchema();
            }
            if (s.schemas == null) {
                s.schemas = new LinkedHashMap<String, ProjectSchema>();
            }
            return s;
        } catch (Exception e) {
            return new GridSchema();
        }
    }

    public String toJson() {
        return GSON.toJson(this);
    }
}
