package com.breakdowngrid.config;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/**
 * Глобальні налаштування плагіна у PluginSettings:
 *   - список зовнішніх зʼєднань (ключ {@code connectionsJson}) — база/логін/пароль на кожне джерело;
 *   - сховище JSON-схеми (ключ {@code schemaJson}, через generic {@link #get}/{@link #put}).
 * PluginSettingsFactory беремо через OSGi напряму — Spring-інʼєкція не потрібна.
 */
public final class GridConfig {

    private static final String NS = "com.breakdowngrid";
    /** Список зʼєднань (JSON-масив [{key,baseUrl,authType,username,password}]). */
    public static final String CONNECTIONS = NS + ".connectionsJson";

    private static final Gson GSON = new Gson();

    private GridConfig() {
    }

    private static PluginSettings settings() {
        final PluginSettingsFactory factory =
                ComponentAccessor.getOSGiComponentInstanceOfType(PluginSettingsFactory.class);
        return factory.createGlobalSettings();
    }

    public static String get(final String key) {
        final Object value = settings().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    public static void put(final String key, final String value) {
        settings().put(key, value == null ? "" : value);
    }

    /** Усі налаштовані зʼєднання (порожній список, якщо ще нічого не задано). */
    public static List<GridConnection> connections() {
        final String json = get(CONNECTIONS);
        if (json.trim().isEmpty()) {
            return new ArrayList<GridConnection>();
        }
        try {
            final List<GridConnection> list =
                    GSON.fromJson(json, new TypeToken<List<GridConnection>>() { }.getType());
            return list == null ? new ArrayList<GridConnection>() : list;
        } catch (Exception e) {
            return new ArrayList<GridConnection>();
        }
    }

    /** Зʼєднання за ключем (той, що стоїть першим сегментом у Source), або null. */
    public static GridConnection find(final String key) {
        if (key == null) {
            return null;
        }
        final String k = key.trim();
        for (final GridConnection c : connections()) {
            if (c != null && c.keySafe().equals(k)) {
                return c;
            }
        }
        return null;
    }

    public static void saveConnections(final String json) {
        put(CONNECTIONS, json == null ? "" : json);
    }

    /** Є щонайменше одне зʼєднання з base URL → source-колонки можна читати/редагувати в UI. */
    public static boolean isConfigured() {
        for (final GridConnection c : connections()) {
            if (c != null && c.hasBaseUrl()) {
                return true;
            }
        }
        return false;
    }
}
