package com.breakdowngrid.config;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.xsrf.XsrfTokenGenerator;
import com.atlassian.jira.user.ApplicationUser;
import com.breakdowngrid.schema.GridColumn;
import com.breakdowngrid.schema.GridSchema;
import com.breakdowngrid.schema.SchemaMigrator;
// GridConnection у пакеті config (той самий), окремий імпорт не потрібен.
import com.breakdowngrid.schema.SchemaStore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Адмін-сторінка аддона: /plugins/servlet/breakdown-grid/config (лише для адміністраторів Jira).
 * Дві вкладки — окремі пункти лівого меню Administration → System → Breakdown Grid:
 *   ?tab=connection — підключення до джерела (base URL / логін / пароль);
 *   ?tab=schema     — конструктор схеми колонок по проектах.
 * Рендериться всередині адмін-лейауту через декоратор atl.admin (контент у правій панелі).
 * POST розрізняється прихованим полем form=conn|schema.
 */
public class ConfigServlet extends HttpServlet {

    private static final Gson GSON = new Gson();

    // Довідка «?» на заголовках стовпців конструктора (показується AUI-тултипом / нативним title).
    private static final String H_LABEL =
            "Column header shown on the issue. Safe to rename anytime — data is stored by Key, not Label. "
            + "Empty falls back to Key.";
    private static final String H_KEY =
            "Internal id, unique per project. Row data is stored under it (<key>, plus <key>Id for source columns). "
            + "Auto-generated from Label if left blank. Renaming it migrates existing data (with confirmation).";
    private static final String H_TYPE =
            "Cell type: string, int, decimal (2 decimals), date (yyyy-mm-dd), boolean. "
            + "int/decimal are rounded silently on save.";
    private static final String H_SOURCE =
            "Written as <key>/path: the first segment is a Source connection key, the rest is the path appended "
            + "to that connection's Base URL; the endpoint returns [{id,name}] shown as a dropdown. "
            + "Empty = plain input by Type. Static path (e.g. erp/tools/all) loads once, shared by all rows. "
            + "Token <value> (e.g. erp/tools/<value>) loads per row using this cell's own value. "
            + "Combined with Depends on it loads per row via POST, filtered by those columns (cascading lists). "
            + "The connection must be defined in Source connection.";
    private static final String H_DEPENDS =
            "Comma-separated Keys of other columns. Makes this column's dropdown load per row, filtered by those "
            + "columns' values (cascading lists). Requires Source.";
    private static final String H_SUM =
            "Footer aggregate: numeric columns show the sum; other types show the count of distinct values.";
    private static final String H_REQ =
            "Required: an empty cell in this column blocks save (client, server and REST).";

    private static String thHelp(final String label, final String help) {
        return "<th>" + label + " <span class='bdg-qh' title='" + esc(help) + "'>?</span></th>";
    }

    private boolean isAdmin() {
        final ApplicationUser user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        return user != null
                && ComponentAccessor.getGlobalPermissionManager().hasPermission(GlobalPermissionKey.ADMINISTER, user);
    }

    private static XsrfTokenGenerator xsrf() {
        return ComponentAccessor.getComponent(XsrfTokenGenerator.class);
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        if (!isAdmin()) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Jira administrator required");
            return;
        }
        final boolean schema = "schema".equals(req.getParameter("tab"));
        render(resp, schema, req.getParameter("status"), xsrf().generateToken(req));
    }

    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        if (!isAdmin()) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Jira administrator required");
            return;
        }
        final String form = req.getParameter("form");
        final String base = req.getContextPath() + "/plugins/servlet/breakdown-grid/config";

        // CSRF: приймаємо POST лише з валідним токеном сесії (форми несуть atl_token).
        if (!xsrf().validateToken(req, req.getParameter(XsrfTokenGenerator.TOKEN_WEB_PARAMETER_KEY))) {
            resp.sendRedirect(base + ("schema".equals(form) ? "?tab=schema" : "?tab=connection") + "&status=xsrf");
            return;
        }

        if ("schema".equals(form)) {
            final String schemaJson = req.getParameter("schemaJson");
            if (schemaJson == null || schemaJson.trim().isEmpty() || !isJsonObject(schemaJson)) {
                resp.sendRedirect(base + "?tab=schema&status=schemaerror");
                return;
            }
            if (hasDuplicateKeys(schemaJson)) {
                resp.sendRedirect(base + "?tab=schema&status=dupkey");
                return;
            }
            if (hasInvalidKeys(schemaJson)) {
                resp.sendRedirect(base + "?tab=schema&status=keyinvalid");
                return;
            }
            final String renamesJson = req.getParameter("renames");
            final Map<String, Map<String, String>> renames = parseRenames(renamesJson);
            final boolean confirmed = "true".equals(req.getParameter("confirmed"));

            // Перейменування ключів → мігруємо дані. Якщо є що змінювати й ще не підтверджено — показуємо підтвердження.
            if (!renames.isEmpty() && !confirmed) {
                final int n = SchemaMigrator.countAffected(renames);
                if (n > 0) {
                    renderConfirm(resp, base, schemaJson.trim(), renamesJson, renames, n, xsrf().generateToken(req));
                    return;
                }
            }
            if (!renames.isEmpty()) {
                SchemaMigrator.migrate(renames);
            }
            SchemaStore.saveRaw(schemaJson.trim());
            resp.sendRedirect(base + "?tab=schema&status=saved");
        } else {
            final String connectionsJson = req.getParameter("connectionsJson");
            if (connectionsJson == null || connectionsJson.trim().isEmpty() || !isJsonArray(connectionsJson)) {
                resp.sendRedirect(base + "?tab=connection&status=connerror");
                return;
            }
            if (hasDuplicateConnKeys(connectionsJson)) {
                resp.sendRedirect(base + "?tab=connection&status=conndup");
                return;
            }
            // Порожній пароль у рядку = лишити поточний (матч по key).
            GridConfig.saveConnections(mergeConnPasswords(connectionsJson));
            resp.sendRedirect(base + "?tab=connection&status=saved");
        }
    }

    private static boolean isJsonArray(final String json) {
        try {
            final JsonElement e = new JsonParser().parse(json);
            return e != null && e.isJsonArray();
        } catch (Exception ex) {
            return false;
        }
    }

    /** Ключі зʼєднань мають бути унікальні. */
    private static boolean hasDuplicateConnKeys(final String json) {
        try {
            final List<GridConnection> list =
                    GSON.fromJson(json, new TypeToken<List<GridConnection>>() { }.getType());
            final Set<String> seen = new HashSet<String>();
            if (list != null) {
                for (final GridConnection c : list) {
                    final String k = c == null ? "" : c.keySafe();
                    if (k.isEmpty()) {
                        continue;
                    }
                    if (!seen.add(k)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Рядки з порожнім паролем беруть поточний пароль зʼєднання з тим самим key; порожні key відкидаються. */
    private static String mergeConnPasswords(final String json) {
        final List<GridConnection> out = new ArrayList<GridConnection>();
        try {
            final List<GridConnection> incoming =
                    GSON.fromJson(json, new TypeToken<List<GridConnection>>() { }.getType());
            if (incoming != null) {
                for (final GridConnection c : incoming) {
                    if (c == null || c.keySafe().isEmpty()) {
                        continue;
                    }
                    if (c.passwordSafe().isEmpty()) {
                        final GridConnection old = GridConfig.find(c.keySafe());
                        if (old != null) {
                            c.password = old.passwordSafe();
                        }
                    }
                    out.add(c);
                }
            }
        } catch (Exception ignored) {
        }
        return GSON.toJson(out);
    }

    private static boolean isJsonObject(final String json) {
        try {
            final JsonElement e = new JsonParser().parse(json);
            return e != null && e.isJsonObject();
        } catch (Exception ex) {
            return false;
        }
    }

    /** renames-масив [{project,oldKey,newKey}] → projectKey -> (oldKey -> newKey). */
    private static Map<String, Map<String, String>> parseRenames(final String json) {
        final Map<String, Map<String, String>> out = new LinkedHashMap<String, Map<String, String>>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            final JsonElement root = new JsonParser().parse(json);
            if (!root.isJsonArray()) {
                return out;
            }
            for (final JsonElement el : root.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                final JsonObject o = el.getAsJsonObject();
                final String p = optStr(o, "project");
                final String ok = optStr(o, "oldKey");
                final String nk = optStr(o, "newKey");
                if (p.isEmpty() || ok.isEmpty() || nk.isEmpty() || ok.equals(nk)) {
                    continue;
                }
                if (!out.containsKey(p)) {
                    out.put(p, new LinkedHashMap<String, String>());
                }
                out.get(p).put(ok, nk);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String optStr(final JsonObject o, final String key) {
        try {
            if (o.has(key) && !o.get(key).isJsonNull()) {
                return o.get(key).getAsString().trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** Ключ колонки — лише [A-Za-z0-9_]. Інші символи ламають HTML-атрибути/JSON-ключі/CSS-селектори у гріді. */
    private static final Pattern KEY_OK = Pattern.compile("^[A-Za-z0-9_]+$");

    /** Чи є хоч один ключ колонки з недопустимими символами (або порожній). */
    private static boolean hasInvalidKeys(final String schemaJson) {
        try {
            final GridSchema s = GridSchema.parse(schemaJson);
            for (final GridSchema.ProjectSchema ps : s.schemas.values()) {
                for (final GridColumn c : ps.columns) {
                    if (!KEY_OK.matcher(c.keySafe()).matches()) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Дублікати ключів у межах одного проекту неприпустимі. */
    private static boolean hasDuplicateKeys(final String schemaJson) {
        try {
            final GridSchema s = GridSchema.parse(schemaJson);
            for (final GridSchema.ProjectSchema ps : s.schemas.values()) {
                final Set<String> seen = new HashSet<String>();
                for (final GridColumn c : ps.columns) {
                    final String k = c.keySafe();
                    if (k.isEmpty()) {
                        continue;
                    }
                    if (!seen.add(k)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void renderConfirm(final HttpServletResponse resp, final String base, final String schemaJson,
                               final String renamesJson, final Map<String, Map<String, String>> renames,
                               final int count, final String token) throws IOException {
        resp.setContentType("text/html;charset=UTF-8");
        final PrintWriter w = resp.getWriter();
        w.println("<html><head>");
        w.println("<meta name='decorator' content='atl.admin'>");
        w.println("<meta name='admin.active.section' content='admin_system_menu/bdg-admin-section'>");
        w.println("<meta name='admin.active.tab' content='bdg-schema-link'>");
        w.println("<title>Breakdown Grid — Confirm changes</title>");
        w.println("<style>#bdg{max-width:640px}#bdg li{margin:3px 0}"
                + "#bdg .warn{background:#fffae6;border:1px solid #ffe380;padding:10px;border-radius:3px;margin-bottom:12px}"
                + "#bdg button{padding:7px 16px;background:#0052cc;color:#fff;border:0;border-radius:3px;cursor:pointer}"
                + "#bdg a.cancel{margin-left:12px}#bdg code{background:#f4f5f7;padding:1px 4px;border-radius:3px}</style>");
        w.println("</head><body><div id='bdg'>");
        w.println("<h2>Breakdown Grid — confirm key changes</h2>");
        w.println("<div class='warn'>You renamed column key(s). Existing data will be migrated so it stays visible:</div>");
        w.println("<ul>");
        for (final Map.Entry<String, Map<String, String>> pe : renames.entrySet()) {
            for (final Map.Entry<String, String> ke : pe.getValue().entrySet()) {
                w.println("<li><b>" + esc(pe.getKey()) + "</b>: <code>" + esc(ke.getKey())
                        + "</code> &rarr; <code>" + esc(ke.getValue()) + "</code></li>");
            }
        }
        w.println("</ul>");
        w.println("<p>This will update <b>" + count + "</b> issue(s).</p>");
        w.println("<form method='post'>");
        w.println("<input type='hidden' name='form' value='schema'/>");
        w.println("<input type='hidden' name='confirmed' value='true'/>");
        w.println("<input type='hidden' name='atl_token' value='" + esc(token) + "'/>");
        w.println("<input type='hidden' name='schemaJson' value='" + esc(schemaJson) + "'/>");
        w.println("<input type='hidden' name='renames' value='" + esc(renamesJson == null ? "" : renamesJson) + "'/>");
        w.println("<button type='submit'>Confirm &amp; migrate</button>");
        w.println("<a class='cancel' href='" + base + "?tab=schema'>Cancel</a>");
        w.println("</form>");
        w.println("</div></body></html>");
    }

    private void render(final HttpServletResponse resp, final boolean schema, final String status,
                        final String token) throws IOException {
        resp.setContentType("text/html;charset=UTF-8");
        final PrintWriter w = resp.getWriter();
        final String activeTab = schema ? "bdg-schema-link" : "bdg-conn-link";
        final String heading = schema ? "Column schema" : "Source connection";

        w.println("<html><head>");
        // Декоратор адмінки: контент опиняється в правій панелі з лівим меню Administration.
        w.println("<meta name='decorator' content='atl.admin'>");
        w.println("<meta name='admin.active.section' content='admin_system_menu/bdg-admin-section'>");
        w.println("<meta name='admin.active.tab' content='" + activeTab + "'>");
        w.println("<title>Breakdown Grid — " + heading + "</title>");
        w.println("<style>"
                + "#bdg label{display:block;font-weight:600;margin:14px 0 4px}"
                + "#bdg input[type=text],#bdg input[type=password],#bdg select{padding:6px;box-sizing:border-box;"
                + "border:1px solid #ccc;border-radius:3px}"
                + "#bdg .full{width:100%;max-width:640px}#bdg .hint{color:#6b778c;font-size:12px;margin-top:4px}"
                + "#bdg button{padding:7px 16px;background:#0052cc;color:#fff;border:0;border-radius:3px;cursor:pointer}"
                + "#bdg button.sec{background:#f4f5f7;color:#172b4d;border:1px solid #ccc}"
                + "#bdg .ok{background:#e3fcef;border:1px solid #57d9a3;padding:10px;border-radius:3px;margin-bottom:14px}"
                + "#bdg .bad{background:#ffebe6;border:1px solid #ff8f73;padding:10px;border-radius:3px;margin-bottom:14px}"
                + "#bdg table.cols{border-collapse:collapse;width:100%;margin-top:8px}"
                + "#bdg table.cols th,#bdg table.cols td{border:1px solid #dfe1e6;padding:5px 6px;text-align:left;font-size:13px}"
                + "#bdg table.cols th{background:#f4f5f7}#bdg table.cols input[type=text],#bdg table.cols input[type=password]{width:100%}"
                + "#bdg .row-actions{margin-top:10px}#bdg .row-actions button{margin-right:8px}"
                + "#bdg .del{background:#fff;color:#bf2600;border:1px solid #ffbdad;border-radius:3px;"
                + "width:26px;height:24px;padding:0;box-sizing:border-box;font-size:13px;line-height:22px;text-align:center;cursor:pointer}"
                + "#bdg .bdg-head{display:flex;align-items:center;justify-content:space-between;gap:12px}"
                + "#bdg .bdg-cols{display:flex;border:1px solid #dfe1e6;border-radius:6px;overflow:hidden;margin-top:6px}"
                + "#bdg .bdg-tabs{width:172px;flex:none;border-right:1px solid #dfe1e6;background:#f4f5f7;padding:8px 6px;display:flex;flex-direction:column;gap:2px}"
                + "#bdg .bdg-tab{display:flex;align-items:center;justify-content:space-between;gap:6px;padding:8px 10px;border-radius:3px;cursor:pointer;color:#42526e;font-size:14px}"
                + "#bdg .bdg-tab.active{background:#fff;border-left:3px solid #0052cc;font-weight:600;color:#172b4d}"
                + "#bdg .bdg-tab .rm{color:#bf2600;cursor:pointer;font-size:12px}"
                + "#bdg .bdg-tab .pn{color:#6b778c;font-weight:400}"
                + "#bdg .bdg-addproj{padding:8px 10px;margin-top:4px;border-top:1px solid #dfe1e6;color:#0052cc;cursor:pointer;font-size:14px}"
                + "#bdg .bdg-tabwrap{flex:1;padding:10px;overflow-x:auto}"
                + "#bdg .bdg-tabwrap table.cols{margin-top:0}"
                + "#bdg .grip{cursor:grab;color:#7a869a;text-align:center;user-select:none;width:24px;letter-spacing:-2px}"
                + "#bdg tr.dragging{opacity:.4}"
                + "#bdg .addrow{color:#0052cc;cursor:pointer;background:#f4f5f7;border-top:1px dashed #b3bac5}"
                + "#bdg .bdg-qh{display:inline-block;width:14px;height:14px;line-height:14px;text-align:center;font-size:10px;"
                + "font-weight:700;color:#fff;background:#6b778c;border-radius:50%;cursor:help;margin-left:3px}"
                + "#bdg .cDescBtn{background:#fff;color:#7a869a;border:1px solid #dfe1e6;border-radius:3px;"
                + "width:26px;height:24px;padding:0;box-sizing:border-box;cursor:pointer;margin-right:6px;font-size:13px;line-height:22px;text-align:center}"
                + "#bdg .cDescBtn.on{color:#0052cc;border-color:#4c9aff;background:#deebff}"
                + ".bdg-descpop{position:absolute;z-index:1000;background:#fff;border:1px solid #c1c7d0;border-radius:5px;"
                + "box-shadow:0 4px 12px rgba(9,30,66,.25);padding:8px;width:264px;display:none}"
                + ".bdg-descpop textarea{width:100%;box-sizing:border-box;border:1px solid #ccc;border-radius:3px;padding:6px;"
                + "font:inherit;resize:vertical}"
                + ".bdg-descpop .bdg-descdone{margin-top:6px;padding:4px 12px;background:#0052cc;color:#fff;border:0;"
                + "border-radius:3px;cursor:pointer}"
                + "</style>");
        w.println("</head><body>");
        w.println("<div id='bdg'>");

        if ("saved".equals(status)) {
            w.println("<div class='ok'>Saved.</div>");
        } else if ("schemaerror".equals(status)) {
            w.println("<div class='bad'>Schema was not saved: invalid data. Please try again.</div>");
        } else if ("dupkey".equals(status)) {
            w.println("<div class='bad'>Schema was not saved: duplicate column key within a project. Keys must be unique per project.</div>");
        } else if ("keyinvalid".equals(status)) {
            w.println("<div class='bad'>Schema was not saved: a column key has invalid characters. A key may contain only letters, digits and underscore (A–Z, a–z, 0–9, _) and cannot be empty.</div>");
        } else if ("connerror".equals(status)) {
            w.println("<div class='bad'>Connections were not saved: invalid data. Please try again.</div>");
        } else if ("conndup".equals(status)) {
            w.println("<div class='bad'>Connections were not saved: duplicate connection key. Keys must be unique.</div>");
        } else if ("xsrf".equals(status)) {
            w.println("<div class='bad'>Nothing was saved: the form security token was missing or expired. Please try again.</div>");
        }

        if (schema) {
            renderSchema(w, token);
        } else {
            renderConnection(w, token);
        }

        w.println("</div></body></html>");
    }

    private void renderConnection(final PrintWriter w, final String token) {
        w.println("<div class='bdg-head'>");
        w.println("<h2>Breakdown Grid — Source connections</h2>");
        w.println("<button type='submit' form='connForm'>Save connections</button>");
        w.println("</div>");
        w.println("<div class='hint'>One row per external system. In Column schema a Source is written as "
                + "<code>&lt;key&gt;/path</code> (e.g. <code>erp/tools/all</code>) — the first segment picks the "
                + "connection. Leave a password blank to keep the current one.</div>");
        w.println("<form method='post' id='connForm'><input type='hidden' name='form' value='conn'/>"
                + "<input type='hidden' name='atl_token' value='" + esc(token) + "'/>");
        w.println("<div class='bdg-tabwrap'><table class='cols' id='connTable'><thead><tr>"
                + "<th>Key</th><th>Base URL</th><th>Auth</th><th>Username</th><th>Password</th><th></th>"
                + "</tr></thead></table></div>");
        w.println("<input type='hidden' name='connectionsJson' id='connectionsJson'/>");
        w.println("</form>");
        w.println("<script>var BDG_CONNECTIONS = " + connectionsJson() + ";</script>");
        w.println(connJs());
    }

    /** Зʼєднання для клієнта БЕЗ паролів (лише прапорець hasPassword). */
    private String connectionsJson() {
        final List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (final GridConnection c : GridConfig.connections()) {
            if (c == null) {
                continue;
            }
            final Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("key", c.keySafe());
            m.put("baseUrl", c.baseUrlSafe());
            m.put("authType", c.authTypeSafe());
            m.put("username", c.usernameSafe());
            m.put("hasPassword", !c.passwordSafe().isEmpty());
            list.add(m);
        }
        return scriptSafe(GSON.toJson(list));
    }

    private static String connJs() {
        return "<script>(function(){\n"
            + "var AUTHS=['basic'];\n"
            + "var table=document.getElementById('connTable');\n"
            + "var tbody=document.createElement('tbody');table.appendChild(tbody);\n"
            + "function el(t,c){var e=document.createElement(t);if(c)e.className=c;return e;}\n"
            + "function td(child){var c=el('td');if(child)c.appendChild(child);return c;}\n"
            + "function txt(cls,val,ph){var i=el('input',cls);i.type='text';i.value=val||'';if(ph)i.placeholder=ph;return i;}\n"
            + "function pwd(cls,ph){var i=el('input',cls);i.type='password';i.value='';if(ph)i.placeholder=ph;return i;}\n"
            + "function authSel(v){var s=el('select','cAuth');for(var i=0;i<AUTHS.length;i++){var o=el('option');o.value=AUTHS[i];o.textContent=AUTHS[i];if(AUTHS[i]===v)o.selected=true;s.appendChild(o);}return s;}\n"
            + "function row(c){c=c||{};var tr=el('tr');\n"
            + "  tr.appendChild(td(txt('cKey',c.key,'key')));\n"
            + "  tr.appendChild(td(txt('cBase',c.baseUrl,'https://host/base/')));\n"
            + "  tr.appendChild(td(authSel(c.authType||'basic')));\n"
            + "  tr.appendChild(td(txt('cUser',c.username)));\n"
            + "  tr.appendChild(td(pwd('cPwd',c.hasPassword?'\\u2022\\u2022\\u2022\\u2022 (leave blank to keep)':'')));\n"
            + "  var b=el('button','del');b.type='button';b.textContent='\\u2715';b.onclick=function(){tr.parentNode.removeChild(tr);};\n"
            + "  tr.appendChild(td(b));return tr;}\n"
            + "function addRowTr(){var tr=el('tr','addrow-tr');var c=el('td','addrow');c.setAttribute('colspan','6');c.innerHTML='+ Add connection';c.onclick=function(){tbody.insertBefore(row({}),tr);};tr.appendChild(c);return tr;}\n"
            + "var addtr=addRowTr();var cur=BDG_CONNECTIONS||[];for(var i=0;i<cur.length;i++)tbody.appendChild(row(cur[i]));tbody.appendChild(addtr);\n"
            + "document.getElementById('connForm').addEventListener('submit',function(){\n"
            + "  var rows=tbody.querySelectorAll('tr');var out=[];\n"
            + "  for(var i=0;i<rows.length;i++){var tr=rows[i];if(tr.className.indexOf('addrow-tr')>=0)continue;\n"
            + "    var key=(tr.querySelector('.cKey').value||'').trim();if(!key)continue;\n"
            + "    out.push({key:key,baseUrl:(tr.querySelector('.cBase').value||'').trim(),authType:tr.querySelector('.cAuth').value,username:(tr.querySelector('.cUser').value||'').trim(),password:tr.querySelector('.cPwd').value||''});}\n"
            + "  document.getElementById('connectionsJson').value=JSON.stringify(out);\n"
            + "});\n"
            + "})();</script>";
    }

    private void renderSchema(final PrintWriter w, final String token) {
        final String schemaJson = scriptSafe(SchemaStore.rawJson());
        w.println("<div class='bdg-head'>");
        w.println("<h2>Breakdown Grid — Column schema</h2>");
        w.println("<button type='submit' form='schemaForm'>Save schema</button>");
        w.println("</div>");
        w.println("<div class='hint'>Pick a project on the left. Drag the handle to reorder rows — row order is the column order shown in the grid. "
                + "Hover the <b>?</b> on any column header for what it does; use <b>&#9998;</b> on a row to add a column description (shown as a tooltip on the issue).</div>");
        w.println("<form method='post' id='schemaForm'><input type='hidden' name='form' value='schema'/>"
                + "<input type='hidden' name='atl_token' value='" + esc(token) + "'/>");
        w.println("<div class='bdg-cols'>");
        w.println("<div class='bdg-tabs' id='bdgTabs'></div>");
        w.println("<div class='bdg-tabwrap'><table class='cols' id='bdgTable'><thead><tr>"
                + "<th></th>"
                + thHelp("Label", H_LABEL)
                + thHelp("Key", H_KEY)
                + thHelp("Type", H_TYPE)
                + thHelp("Source", H_SOURCE)
                + thHelp("Depends on", H_DEPENDS)
                + thHelp("Sum", H_SUM)
                + thHelp("Req", H_REQ)
                + "<th></th></tr></thead></table></div>");
        w.println("</div>");
        w.println("<input type='hidden' name='schemaJson' id='schemaJson'/>");
        w.println("<input type='hidden' name='renames' id='renames'/>");
        w.println("</form>");

        w.println("<script>var BDG_SCHEMA = " + (schemaJson.isEmpty() ? "null" : schemaJson) + ";"
                + "var BDG_PROJECTS = " + projectsJson() + ";</script>");
        w.println(builderJs());
    }

    private String projectsJson() {
        final ProjectManager pm = ComponentAccessor.getProjectManager();
        final List<Map<String, String>> list = new ArrayList<Map<String, String>>();
        for (final Project p : pm.getProjectObjects()) {
            final Map<String, String> m = new LinkedHashMap<String, String>();
            m.put("key", p.getKey());
            m.put("name", p.getName());
            list.add(m);
        }
        return scriptSafe(GSON.toJson(list));
    }

    private static String builderJs() {
        return "<script>(function(){\n"
            + "var TYPES=['string','int','decimal','date','boolean'];\n"
            + "var PROJ=BDG_PROJECTS||[];\n"
            + "var tabsEl=document.getElementById('bdgTabs');\n"
            + "var table=document.getElementById('bdgTable');\n"
            + "var tbodies={};var order=[];var active=null;var dragRow=null;\n"
            + "function nameOf(k){for(var i=0;i<PROJ.length;i++){if(PROJ[i].key===k)return PROJ[i].name;}return k;}\n"
            + "function slug(s){return (s||'').toLowerCase().replace(/[^a-z0-9]+/g,'_').replace(/^_+|_+$/g,'');}\n"
            + "function el(t,c){var e=document.createElement(t);if(c)e.className=c;return e;}\n"
            + "function typeSel(v){var s=el('select');s.className='cType';for(var i=0;i<TYPES.length;i++){var o=el('option');o.value=TYPES[i];o.textContent=TYPES[i];if(TYPES[i]===v)o.selected=true;s.appendChild(o);}return s;}\n"
            + "function txt(cls,val,ph,wd){var i=el('input',cls);i.type='text';i.value=val||'';if(ph)i.placeholder=ph;if(wd)i.style.width=wd;return i;}\n"
            + "function chk(cls,val){var i=el('input',cls);i.type='checkbox';i.checked=!!val;return i;}\n"
            + "function td(child,center){var c=el('td');if(center)c.style.textAlign='center';if(child)c.appendChild(child);return c;}\n"
            + "var descPop=null,descPopTa=null,descPopTarget=null,descPopBtn=null;\n"
            + "function markDesc(hid){var b=hid.parentNode&&hid.parentNode.querySelector('.cDescBtn');if(b){b.className=(hid.value||'').trim()?'cDescBtn on':'cDescBtn';}}\n"
            + "function hideDescPop(){if(descPop)descPop.style.display='none';descPopTarget=null;descPopBtn=null;}\n"
            + "function ensureDescPop(){if(descPop)return;descPop=el('div','bdg-descpop');descPopTa=document.createElement('textarea');descPopTa.rows=3;descPopTa.placeholder='Shown as a tooltip on the column header on the issue';descPop.appendChild(descPopTa);var d=el('button','bdg-descdone');d.type='button';d.textContent='Done';d.onclick=function(e){e.preventDefault();hideDescPop();};descPop.appendChild(d);document.body.appendChild(descPop);descPopTa.addEventListener('input',function(){if(descPopTarget){descPopTarget.value=descPopTa.value;markDesc(descPopTarget);}});document.addEventListener('mousedown',function(e){if(descPop.style.display==='block'&&!descPop.contains(e.target)&&!(descPopBtn&&descPopBtn.contains(e.target)))hideDescPop();});}\n"
            + "function openDescPop(btn,hid){ensureDescPop();descPopTarget=hid;descPopBtn=btn;descPopTa.value=hid.value||'';descPop.style.display='block';var r=btn.getBoundingClientRect();var left=window.pageXOffset+r.right-264;if(left<4)left=4;descPop.style.left=left+'px';descPop.style.top=(window.pageYOffset+r.bottom+4)+'px';descPopTa.focus();}\n"
            + "function colRow(c){c=c||{};var tr=el('tr');tr.setAttribute('data-old-key',(c&&c.key)||'');\n"
            + "  var g=el('td','grip');g.innerHTML='\\u22EE\\u22EE';g.title='Drag to reorder';\n"
            + "  g.addEventListener('mousedown',function(){tr.setAttribute('draggable','true');});\n"
            + "  tr.appendChild(g);\n"
            + "  tr.appendChild(td(txt('cLabel',c.label)));\n"
            + "  tr.appendChild(td(txt('cKey',c.key,'auto','110px')));\n"
            + "  tr.appendChild(td(typeSel(c.type||'string')));\n"
            + "  tr.appendChild(td(txt('cSource',c.source)));\n"
            + "  tr.appendChild(td(txt('cDepends',(c.dependsOn||[]).join(', '),'key, key')));\n"
            + "  tr.appendChild(td(chk('cSum',c.sum),true));\n"
            + "  tr.appendChild(td(chk('cReq',c.required),true));\n"
            + "  var act=el('td');act.style.textAlign='center';act.style.whiteSpace='nowrap';\n"
            + "  var hid=el('input','cDesc');hid.type='hidden';hid.value=(c&&c.description)||'';\n"
            + "  var eb=el('button','cDescBtn'+((((c&&c.description)||'').trim())?' on':''));eb.type='button';eb.innerHTML='\\u270E';eb.title='Column description (shown as a tooltip on the issue)';eb.onclick=function(e){e.preventDefault();openDescPop(eb,hid);};\n"
            + "  var b=el('button','del');b.type='button';b.textContent='\\u2715';b.onclick=function(){tr.parentNode.removeChild(tr);};\n"
            + "  act.appendChild(hid);act.appendChild(eb);act.appendChild(b);tr.appendChild(act);\n"
            + "  tr.addEventListener('dragstart',function(e){dragRow=tr;tr.classList.add('dragging');try{e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('text/plain','');}catch(x){}});\n"
            + "  tr.addEventListener('dragend',function(){tr.classList.remove('dragging');tr.removeAttribute('draggable');dragRow=null;});\n"
            + "  return tr;}\n"
            + "function addRowTr(tb){var tr=el('tr','addrow-tr');var c=el('td','addrow');c.setAttribute('colspan','9');c.innerHTML='+ Add row';c.onclick=function(){tb.insertBefore(colRow({}),tr);};tr.appendChild(c);return tr;}\n"
            + "function rowAfter(tb,y){var rows=Array.prototype.slice.call(tb.querySelectorAll('tr:not(.dragging):not(.addrow-tr)'));var res=null,cl=-1e9;for(var i=0;i<rows.length;i++){var box=rows[i].getBoundingClientRect();var off=y-box.top-box.height/2;if(off<0&&off>cl){cl=off;res=rows[i];}}return res;}\n"
            + "function makeBody(key,cols){var tb=el('tbody');tb.setAttribute('data-project',key);cols=cols||[];for(var i=0;i<cols.length;i++)tb.appendChild(colRow(cols[i]));tb.appendChild(addRowTr(tb));\n"
            + "  tb.addEventListener('dragover',function(e){e.preventDefault();if(!dragRow||dragRow.parentNode!==tb)return;var add=tb.querySelector('tr.addrow-tr');var aft=rowAfter(tb,e.clientY);if(aft==null)tb.insertBefore(dragRow,add);else if(aft!==dragRow)tb.insertBefore(dragRow,aft);});return tb;}\n"
            + "function setActive(k){active=k;var cur=table.querySelector('tbody');if(cur)table.removeChild(cur);if(k&&tbodies[k])table.appendChild(tbodies[k]);renderTabs();}\n"
            + "function removeProject(k){delete tbodies[k];order=order.filter(function(x){return x!==k;});if(active===k)active=order.length?order[0]:null;var cur=table.querySelector('tbody');if(cur)table.removeChild(cur);if(active)table.appendChild(tbodies[active]);renderTabs();}\n"
            + "function addProject(k){if(!k)return;if(!tbodies[k])tbodies[k]=makeBody(k,[]);if(order.indexOf(k)<0)order.push(k);setActive(k);}\n"
            + "function showAdd(){var avail=PROJ.filter(function(p){return order.indexOf(p.key)<0;});if(!avail.length)return;var s=el('select');var e0=el('option');e0.value='';e0.textContent='Select project\\u2026';s.appendChild(e0);for(var i=0;i<avail.length;i++){var o=el('option');o.value=avail[i].key;o.textContent=avail[i].key+' \\u2014 '+avail[i].name;s.appendChild(o);}s.onchange=function(){if(s.value)addProject(s.value);else renderTabs();};renderTabs();if(tabsEl.lastChild)tabsEl.removeChild(tabsEl.lastChild);tabsEl.appendChild(s);s.focus();}\n"
            + "function renderTabs(){tabsEl.innerHTML='';for(var i=0;i<order.length;i++){(function(k){var t=el('div','bdg-tab'+(k===active?' active':''));var lb=el('span');lb.innerHTML=k+' <span class=\"pn\">'+nameOf(k)+'</span>';t.appendChild(lb);var rm=el('span','rm');rm.innerHTML='\\u2715';rm.title='Remove project';rm.onclick=function(e){e.stopPropagation();removeProject(k);};t.appendChild(rm);t.onclick=function(){setActive(k);};tabsEl.appendChild(t);})(order[i]);}var ap=el('div','bdg-addproj');ap.innerHTML='+ Add project';ap.onclick=showAdd;tabsEl.appendChild(ap);}\n"
            + "var sc=(BDG_SCHEMA&&BDG_SCHEMA.schemas)||{};for(var k in sc){if(sc.hasOwnProperty(k)){order.push(k);tbodies[k]=makeBody(k,(sc[k]&&sc[k].columns)||[]);}}\n"
            + "active=order.length?order[0]:null;if(active)table.appendChild(tbodies[active]);renderTabs();\n"
            + "if(window.AJS&&AJS.$){try{AJS.$('#bdgTable .bdg-qh').tooltip({gravity:'n'});}catch(x){}}\n"
            + "function clearSchemaError(){var e=document.getElementById('bdgClientErr');if(e&&e.parentNode)e.parentNode.removeChild(e);}\n"
            + "function showSchemaError(msg){clearSchemaError();var d=el('div','bad');d.id='bdgClientErr';d.textContent=msg;var host=document.getElementById('bdg');host.insertBefore(d,host.firstChild);if(d.scrollIntoView)d.scrollIntoView({block:'nearest'});}\n"
            + "var KEYRE=/^[A-Za-z0-9_]+$/;\n"
            + "document.getElementById('schemaForm').addEventListener('submit',function(e){\n"
            + "  var out={schemas:{}};var renames=[];var problems=[];\n"
            + "  for(var pk in tbodies){if(tbodies.hasOwnProperty(pk)){var kk=tbodies[pk].querySelectorAll('input.cKey');for(var ci=0;ci<kk.length;ci++)kk[ci].style.borderColor='';}}\n"
            + "  for(var oi=0;oi<order.length;oi++){var P=order[oi];var tb=tbodies[P];var rows=tb.querySelectorAll('tr');var cols=[];var seen={};\n"
            + "    for(var i=0;i<rows.length;i++){var tr=rows[i];if(tr.className.indexOf('addrow-tr')>=0)continue;\n"
            + "      var lblEl=tr.querySelector('.cLabel');if(!lblEl)continue;\n"
            + "      var keyEl=tr.querySelector('.cKey');\n"
            + "      var label=(lblEl.value||'').trim();var key=(keyEl.value||'').trim()||slug(label);if(!key)continue;\n"
            + "      if(!KEYRE.test(key)){problems.push(P+' - invalid key: '+key);keyEl.style.borderColor='#bf2600';}\n"
            + "      else if(Object.prototype.hasOwnProperty.call(seen,key)){problems.push(P+' - duplicate key: '+key);keyEl.style.borderColor='#bf2600';}\n"
            + "      else{seen[key]=1;}\n"
            + "      var oldK=tr.getAttribute('data-old-key')||'';if(oldK&&key!==oldK)renames.push({project:P,oldKey:oldK,newKey:key});\n"
            + "      var deps=(tr.querySelector('.cDepends').value||'').split(',').map(function(s){return s.trim();}).filter(function(s){return s;});\n"
            + "      var descEl=tr.querySelector('.cDesc');var desc=descEl?(descEl.value||'').trim():'';\n"
            + "      cols.push({key:key,label:label||key,description:desc,type:tr.querySelector('.cType').value,source:(tr.querySelector('.cSource').value||'').trim(),dependsOn:deps,sum:tr.querySelector('.cSum').checked,required:tr.querySelector('.cReq').checked});}\n"
            + "    out.schemas[P]={columns:cols};}\n"
            + "  if(problems.length){e.preventDefault();showSchemaError('Schema not saved - fix column keys ['+problems.join('; ')+']. Allowed: A-Z a-z 0-9 _');return false;}\n"
            + "  clearSchemaError();\n"
            + "  document.getElementById('schemaJson').value=JSON.stringify(out);document.getElementById('renames').value=JSON.stringify(renames);\n"
            + "});\n"
            + "})();</script>";
    }

    private static String trim(final String s) {
        return s == null ? "" : s.trim();
    }

    private static String esc(final String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "&#39;").replace("\"", "&quot;");
    }

    private static String scriptSafe(final String s) {
        return s == null ? "" : s.replace("</", "<\\/");
    }
}
