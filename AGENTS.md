# Breakdown Grid — agent briefing

Read this first. It tells any agent (or developer) what this project is, how it is built,
what is implemented, and the non-obvious traps that already cost debugging cycles.

---

## 1. What this is

A **self-contained, reusable Atlassian plugin for Jira 8.16.1 (Server / Data Center)** that adds
one **configurable grid custom-field type**. A "grid" field renders a table on an issue; the set of
**columns is configured per Jira project** in the add-on's admin page. Values can be edited in Jira
and/or read/written by an external system over REST.

Identity is deliberately **neutral and generic** — nothing is tied to any particular company, domain,
or back-end system.

| | |
|---|---|
| Target | Jira **8.16.1** Server/DC · Atlassian Plugin SDK **8.2.7** · **JDK 8** |
| groupId / artifactId | `com.breakdowngrid` / `breakdown-grid` |
| plugin key | `com.breakdowngrid.breakdown-grid` |
| Java package | `com.breakdowngrid` (+ `.schema` / `.rest` / `.config`) |
| Field type (key / display) | `breakdown-grid` / **Breakdown Grid** |
| REST base | `/rest/breakdown-grid/1.0/…` |
| Admin servlet | `/plugins/servlet/breakdown-grid/config` |
| CSS/JS prefix · i18n · vendor | `bdg-` · `breakdown-grid` · Oleksandr Romanenko |
| Current version | see `pom.xml` (`<version>`); bump on every build |

---

## 2. Data & schema model

**Field value** (stored as a JSON string in an unlimited-text custom-field column):
```json
{"rows":[ { "<key>": value, "<key>Id": "<opaque-id>", ... }, ... ]}
```
- `<key>` is present for every column. `<key>Id` appears only for **source-backed** columns (the id of
  the picked list item). `<key>Id` exists only in the data — it is not a schema column and not shown as
  its own UI column.

**Schema** (stored as one JSON blob in `PluginSettings`, key `com.breakdowngrid.schemaJson`):
```json
{ "resolveBy": "project",
  "schemas": { "FIN": { "columns": [ {GridColumn}, ... ] }, "TEST": { … } } }
```
- Columns are chosen **per context = Jira project** of the issue. `SchemaResolver` is the single
  extension point (today returns the project key; built to extend to `project+issuetype`).

**Column** (`GridColumn`):
| field | meaning |
|---|---|
| `key` | internal id; **unique per project**; the identity of the column (data is keyed by it) |
| `label` | display name (safe to rename freely — data is keyed by `key`, not label) |
| `description?` | optional per-column help text; shown as an AUI tooltip (a `?` badge) on the column header in view & edit, edited via a ✎ popover in the builder |
| `type` | `string` · `int` · `decimal` · `date` · `boolean` |
| `source?` | `<key>/path` — first segment is a **Source-connection key**, the rest is the path appended to that connection's base URL → dropdown from `[{id,name}]`; stores `<key>Id`. May contain a `<value>` token (see below) |
| `dependsOn?` | array of other column keys → cascading options (see below) |
| `sum` | footer aggregate: numeric → Σ; other types → count of distinct values |
| `required` | reject empty on validation |

**Numeric normalization (silent, option "B"):** `int` → rounded to integer; `decimal` → DECIMAL(15,2),
HALF_UP to 2 places, clamped to 15 significant digits. Non-numeric input is a validation error; extra
precision is silently normalized, not rejected. Applied client-side and on REST PUT (`GridNormalizer`).

**Source connections** (`GridConfig`, in `PluginSettings`, key `com.breakdowngrid.connectionsJson`): a
**list** `[{key, baseUrl, authType, username, password}]` (`GridConnection`) — multiple external systems
are supported. `key` is unique/required and is what a column's `source` references as its first segment
(`<key>/path`). `authType` is `basic` for now (the only value; column kept for future types). Passwords
are stored in PluginSettings in clear text (as before) and are **never sent to the browser** (only a
`hasPassword` flag). With no connection configured (none with a base URL) source dropdowns are
disabled/unavailable in the field UI.

---

## 3. Source columns, `<value>`, and `dependsOn` (cascading)

A column with a `source` (`<key>/path`) shows a dropdown fetched via the plugin's own proxy (never the
browser calling the external system directly). The proxy takes the **first path segment as a connection
key** → base URL + auth from that `GridConnection`, and resolves the rest of the **path from the schema**
(whitelist — the client cannot inject an arbitrary URL). Unknown key ⇒ error.

- **Independent source column** (`erp/tools/all`, no `dependsOn`, no `<value>`): options load **once**,
  shared across all rows.
- **`<value>` token** (`source = erp/tools/<value>`): loads **per row**, GET, with `<value>` substituted
  (into the path, after the key) by the current column's own value — its `<key>Id` if source-backed,
  else its value (URL-encoded).
- **`dependsOn`** (`["resource","project"]`): loads **per row**, **POST**, body = those columns'
  `{value, <key>Id}`; reloaded whenever a dependency changes in that row. Cascading / dependent lists.

Options proxy: `GET|POST /rest/breakdown-grid/1.0/options?ctx={project}&col={key}[&val={v}]`
(`OptionsResource`). The `col`'s `source` is resolved from the stored schema; its `<key>/` prefix selects
the connection; POST forwards the JSON body to the external source; `<value>` is substituted into the path.

---

## 4. REST contract (for external systems)

- `GET  /rest/breakdown-grid/1.0/issue/{issueKey}/field/{fieldId}` → `{"rows":[…]}` (empty ⇒ `{"rows":[]}`).
- `PUT  /rest/breakdown-grid/1.0/issue/{issueKey}/field/{fieldId}` (body = full JSON) → **full replace**.
  Merge / preservation of manual edits is the **external system's** responsibility (read → build full
  list → write). PUT **validates against the project schema** (required empty → error, wrong type →
  error, `source` membership is NOT checked; **atomic** — all rows or nothing) and then silently
  normalizes numerics before storing.
- `GET|POST /rest/breakdown-grid/1.0/options?ctx=&col=[&val=]` → options for a source column (UI only).

`fieldId` accepts `customfield_NNNNN`, the numeric id, or the field name. Auth: standard Jira REST
(Edit for PUT, Browse for GET).

**Validation runs in three layers:** (1) server on the UI submit — `validateFromParams` resolves the
issue from the current HTTP request via `ExecutingHttpRequest`; (2) client, before submit; (3) REST PUT
(authoritative).

---

## 5. Admin UI (`/plugins/servlet/breakdown-grid/config`)

Rendered **inside the native admin layout** via the SiteMesh decorator
(`<meta name="decorator" content="atl.admin">` + `admin.active.section`/`admin.active.tab`). Two left-menu
items under Administration → System → Breakdown Grid:
- **Source connections** (`?tab=connection`) — a table of connections: `Key · Base URL · Auth ▾ · Username
  · Password · ✕`, with "+ Add connection" and "Save connections". A blank password keeps the current one
  (matched by `key`). Keys must be unique/required; passwords are not rendered back (only a placeholder).
- **Column schema** (`?tab=schema`) — the builder:
  - **Save schema** top-right (button uses `form="schemaForm"`, sits outside the form).
  - **Vertical project tabs on the left** — each tab is one project; the table on the right shows only
    that project's columns. "+ Add project" is a dropdown of not-yet-added Jira projects; the tab's ✕
    removes it. All tabs serialize together on save (detached-tbody-per-project kept in memory).
  - Row = one column: drag-handle · Label · Key · Type · Source · **Depends on** · Sum · Req · ✎ (column
    description popover) · ✕.
  - **Drag to reorder rows** (HTML5 DnD via the handle) — row order = column order in the grid.
  - **"+ Add row"** is a row inside the table (last line), not a button below.
  - Each column header carries a `?` help tooltip (AUI); the **Source** help documents the `<key>/path` form.

**Key-rename migration:** each builder row carries its load-time `data-old-key`. On save, renamed keys
are detected (`[{project,oldKey,newKey}]`); the servlet shows a **confirmation with the affected-issue
count**, then rewrites `<oldKey>`/`<oldKey>Id` → new keys across all issues of that project silently
(`SchemaMigrator` via `CustomFieldValuePersister`, no issue history). Renaming a **label** is always
safe (data is keyed by `key`). Adding a column → old rows show it empty (editable). Duplicate keys per
project are rejected.

---

## 6. Field UI

- **View** — server-rendered table (`view-grid.vm`), works without JS.
- **Edit** — JS-built (`edit-grid.vm` + `breakdown-grid.js`): columns from the schema, typed cells
  (string→text, int/decimal→number, date→date, boolean→checkbox, source→`<select>`), `+ Add row`,
  delete, footer aggregates, hidden JSON kept in sync.
- **Rendered as its own module.** On the issue `breakdown-grid.js` lifts the field out of Details into a
  **native collapsible module** (titled with the field name) inserted right after `#details-module` —
  Jira's own `JIRA.ToggleBlock` (delegated on `document`) draws & manages the collapse triangle, so we
  add no custom button/handler. The table uses **only `bdg-` CSS, not the `aui` class** (border/padding/
  colours reproduced ourselves to avoid AUI specificity fights); column headers match standard field
  labels (14px/400) and show the description tooltip.

---

## 7. Source layout

```
src/main/java/com/breakdowngrid/
  BreakdownGridCFType.java     # the custom field type (view params, server validation, value read)
  config/
    BreakdownGridConfig.java   # minimal Spring @Configuration (ModuleFactory/PluginAccessor)
    GridConfig.java            # PluginSettings: connections list (connectionsJson) + find(key)/isConfigured()
    GridConnection.java        # one source connection: key/baseUrl/authType/username/password
    ConfigServlet.java         # admin page (decorated), tabs, connection + schema builder JS, migration confirm
  rest/
    GridResource.java          # GET/PUT issue field (full replace, validate, normalize)
    OptionsResource.java       # GET/POST options proxy (resolve <key>/path → connection, whitelist, <value>, dependsOn body)
  schema/
    GridColumn.java  GridSchema.java  SchemaStore.java  SchemaResolver.java
    GridValidator.java         # required/type checks (shared by REST + UI)
    GridNormalizer.java        # int/decimal(15,2) silent rounding on write
    SchemaMigrator.java        # key-rename data migration across issues (silent, via persister)
src/main/resources/
  atlassian-plugin.xml
  templates/customfield/{view-grid.vm, edit-grid.vm}
  js/breakdown-grid.js   css/breakdown-grid.css
  META-INF/spring/plugin-context.xml
```

---

## 8. Build, deploy, test

- **Build:** `atlas-package -o -B -DskipTests` (Atlassian Plugin SDK **8.2.7**, JDK 8) →
  `target/breakdown-grid-<version>.jar` / `.obr`. **Bump `<version>` in `pom.xml` on every build** so UPM
  shows the update. Lint the client JS with `node --check src/main/resources/js/breakdown-grid.js`.
- **Install:** upload the `.jar` (or `.obr`) in *Administration → Manage apps → Upload app* on any
  Jira 8.16.1 Server/DC instance. `atlas-run` also works for a throwaway dev instance.
- **Make the field appear:** installing the plugin only registers the *type*. Create a custom field of
  type "Breakdown Grid" → set its context (global or a project) → add it to the screens the project uses
  (a Scrum project uses its own screen scheme). Then configure a column schema for the project in the
  admin page (*Administration → System → Breakdown Grid*).
- **Testing source columns:** a column's `source` (`<key>/path`) needs a matching Source connection whose
  base URL serves `[{"id":…,"name":…}]` at that path. Any small HTTP service returning that shape works.

---

## 9. Gotchas already paid for — do not rediscover

1. **`<script>` is stripped in inline Quick-Edit.** Jira injects the field's edit HTML via AJAX and
   drops `<script>` tags → data must ride in **attributes**. The column schema is in
   `data-schema` on `.bdg-edit`, not a `<script>` tag.
2. **Edit-form issue is stale.** On the edit screen Jira passes an issue with a **null id** and an
   **empty (cached) field value**; even a fresh `getIssueObject(id).getCustomFieldValue()` inside
   `getVelocityParameters` returns empty. The edit grid therefore **loads its rows from the plugin REST
   endpoint by issue key** when the embedded value is empty. (View passes a live issue and reads fine.)
3. **Velocity HTML-escapes `$!var` output** (quotes → `&quot;`). Client JS HTML-decodes attribute JSON
   before `JSON.parse` (attribute reads via `getAttribute` are already decoded).
4. **Quick-Edit resource loading.** The field's CSS/JS must be present before the dialog opens:
   `<web-resource>` declares contexts `jira.view.issue` / `jira.create.issue` / `jira.edit.issue` /
   `atl.general`; the JS binds listeners **delegated on `document`** + a `MutationObserver` (the grid
   appears in the DOM later).
5. **`validateFromParams` has no issue** — get it from `com.atlassian.jira.web.ExecutingHttpRequest`
   (`id` on edit, `pid` on create); the class is in **jira-api**.
6. **Verify Jira API against the jar** (`unzip`/`javap` under `~/.m2/.../jira-api-8.16.1.jar`) — do not
   guess signatures. JAX-RS is **1.1** (jsr311).
7. **Storage column.** `getDatabaseType()` returns `TYPE_UNLIMITED_TEXT` (the JSON exceeds the 255-char
   `stringvalue` limit); read/write via that same `PersistenceFieldType`.
8. **Uninstalling a plugin does not delete its custom fields** — Jira keeps the field + data (orphaned).
   Delete the field manually (`DeleteCustomField!default.jspa?id=<numeric-id>`).

---

## 10. Conventions

- **Verify Jira API against the jar** — don't guess method signatures (see gotcha 6). JAX-RS is 1.1.
- Keep the identity **neutral** — no company / domain / back-end names anywhere in code, config, or docs.
- The code and this briefing are the source of truth; keep this file in sync when behaviour changes.
- `README.md` is the user-facing spec; this file is the developer/contributor briefing.
