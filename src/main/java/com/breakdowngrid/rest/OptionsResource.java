package com.breakdowngrid.rest;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.user.ApplicationUser;
import com.breakdowngrid.config.GridConfig;
import com.breakdowngrid.config.GridConnection;
import com.breakdowngrid.schema.GridColumn;
import com.breakdowngrid.schema.GridSchema;
import com.breakdowngrid.schema.SchemaStore;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Проксі до зовнішнього джерела для автокомпліту source-колонок (same-origin, під Jira-авторизацією).
 *
 *   GET  /rest/breakdown-grid/1.0/options?ctx={ctx}&col={col}[&val={v}]        -> [{ "id","name" }, ...]
 *   POST /rest/breakdown-grid/1.0/options?ctx={ctx}&col={col}[&val={v}]  body: {залежні поля}
 *
 * Реальний шлях (source) береться зі СХЕМИ на сервері (whitelist проти SSRF), не з параметра клієнта.
 * Токен "<value>" у source замінюється параметром val (URL-екранованим → без path-traversal).
 * POST-варіант форвардить тіло (значення dependsOn-колонок) у джерело як POST — для каскадних списків.
 */
@Path("/options")
public class OptionsResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response optionsGet(@QueryParam("ctx") final String ctx,
                               @QueryParam("col") final String col,
                               @QueryParam("val") final String val) {
        return proxy(ctx, col, val, "GET", null);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response optionsPost(@QueryParam("ctx") final String ctx,
                                @QueryParam("col") final String col,
                                @QueryParam("val") final String val,
                                final String body) {
        return proxy(ctx, col, val, "POST", body);
    }

    private Response proxy(final String ctx, final String col, final String val,
                           final String method, final String body) {
        final ApplicationUser user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        if (user == null) {
            return err(401, "Not authenticated");
        }
        if (ctx == null || ctx.trim().isEmpty() || col == null || col.trim().isEmpty()) {
            return err(400, "ctx and col are required");
        }

        final GridSchema schema = SchemaStore.load();
        final List<GridColumn> columns = schema.columnsFor(ctx.trim());
        String source = null;
        for (final GridColumn c : columns) {
            if (c.keySafe().equals(col.trim()) && c.hasSource()) {
                source = c.sourceSafe();
                break;
            }
        }
        if (source == null || source.isEmpty()) {
            return err(404, "No source-backed column '" + col + "' in schema for '" + ctx + "'");
        }

        // Source має вигляд "<key>/path": перший сегмент — ключ зʼєднання, решта — шлях від його base URL.
        final int slash = source.indexOf('/');
        if (slash <= 0) {
            return err(500, "Source must be in '<key>/path' form: '" + source + "'");
        }
        final String connKey = source.substring(0, slash).trim();
        String path = source.substring(slash + 1);

        // Підстановка токена <value> значенням поточної колонки (URL-екрануємо → без "/../" у шляху).
        if (path.contains("<value>")) {
            path = path.replace("<value>", encodePathSeg(val == null ? "" : val));
        }

        final GridConnection conn = GridConfig.find(connKey);
        if (conn == null) {
            return err(404, "No source connection '" + connKey + "' is configured");
        }
        final String base = conn.baseUrlSafe();
        if (base.isEmpty()) {
            return err(500, "Connection '" + connKey + "' has no Base URL");
        }

        final String fullUrl = (base.endsWith("/") ? base : base + "/")
                + (path.startsWith("/") ? path.substring(1) : path);

        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(fullUrl).openConnection();
            con.setRequestMethod(method);
            con.setConnectTimeout(10000);
            con.setReadTimeout(20000);
            con.setRequestProperty("Accept", "application/json");

            if ("basic".equals(conn.authTypeSafe())) {
                final String usr = conn.usernameSafe();
                if (!usr.isEmpty()) {
                    final String token = Base64.getEncoder()
                            .encodeToString((usr + ":" + conn.passwordSafe()).getBytes(StandardCharsets.UTF_8));
                    con.setRequestProperty("Authorization", "Basic " + token);
                }
            }

            if ("POST".equals(method)) {
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                final byte[] payload = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
                final OutputStream os = con.getOutputStream();
                try {
                    os.write(payload);
                } finally {
                    os.close();
                }
            }

            final int code = con.getResponseCode();
            final boolean ok = code >= 200 && code < 300;
            final String respBody = read(ok ? con.getInputStream() : con.getErrorStream());
            if (!ok) {
                return err(502, "Source returned " + code + ": " + respBody);
            }
            return Response.ok(respBody).build();
        } catch (Exception e) {
            return err(502, "Cannot reach source: " + e.getMessage());
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    private static String encodePathSeg(final String v) {
        try {
            return URLEncoder.encode(v, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private static String read(final InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        final BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            r.close();
        }
        return sb.toString();
    }

    private Response err(final int status, final String message) {
        final String json = "{\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return Response.status(status).entity(json).type(MediaType.APPLICATION_JSON).build();
    }
}
