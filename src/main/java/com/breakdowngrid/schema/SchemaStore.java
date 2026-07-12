package com.breakdowngrid.schema;

import com.breakdowngrid.config.GridConfig;

/**
 * Персистенція схем гріда у PluginSettings (окремий ключ поряд із налаштуваннями підключення).
 */
public final class SchemaStore {

    private static final String KEY = "com.breakdowngrid.schemaJson";

    private SchemaStore() {
    }

    public static String rawJson() {
        return GridConfig.get(KEY);
    }

    public static void saveRaw(final String json) {
        GridConfig.put(KEY, json == null ? "" : json);
    }

    public static GridSchema load() {
        return GridSchema.parse(rawJson());
    }
}
