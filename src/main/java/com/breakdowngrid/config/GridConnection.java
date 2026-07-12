package com.breakdowngrid.config;

/**
 * Одне зовнішнє зʼєднання (рядок вкладки Source connection).
 *
 *   key       — унікальний ідентифікатор зʼєднання; ним починається Source у схемі: {@code <key>/path}.
 *   baseUrl   — база для шляхів колонок цього зʼєднання.
 *   authType  — тип автентифікації (поки лише {@code basic}).
 *   username  — логін (для basic).
 *   password  — пароль (для basic); зберігається у PluginSettings у відкритому вигляді, як і раніше.
 *
 * Поля public — Gson наповнює їх напряму.
 */
public class GridConnection {

    public String key;
    public String baseUrl;
    public String authType;
    public String username;
    public String password;

    public String keySafe()      { return key == null ? "" : key.trim(); }
    public String baseUrlSafe()  { return baseUrl == null ? "" : baseUrl.trim(); }
    public String authTypeSafe() { return (authType == null || authType.trim().isEmpty()) ? "basic" : authType.trim(); }
    public String usernameSafe() { return username == null ? "" : username; }
    public String passwordSafe() { return password == null ? "" : password; }

    public boolean hasBaseUrl()  { return !baseUrlSafe().isEmpty(); }
}
