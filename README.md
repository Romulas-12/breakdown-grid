# Breakdown Grid

A configurable **grid custom-field type** for **Jira 8.16.1 (Server / Data Center)**.

One custom-field type renders a table on an issue. The set of **columns is configured per Jira
project** in the add-on's admin page. Each column has a primitive type and can be **source-backed**
(its value is picked from a list fetched from an external system). Rows can be edited in Jira and/or
read and written by an external system over REST.

> **Status / compatibility.** Built and tested against **Jira 8.16.1 Server/DC** with the Atlassian
> Plugin SDK 8.2.7 (JDK 8). It is a P2 (server) app — **Jira Cloud is not supported** (Cloud uses
> Forge/Connect). Treat it as **beta**. This is an **unofficial, third-party** plugin, not affiliated
> with or endorsed by Atlassian. *Jira* is a trademark of Atlassian.

---

## Features

- **Per-project column schema** — different columns for different projects, from one field type.
- **Typed cells** — `string`, `int`, `decimal` (money), `date`, `boolean`.
- **Source-backed columns** — dropdowns populated from an external HTTP source through the plugin's own
  proxy (the browser never calls the external system directly).
- **Multiple source connections** — reference different back-ends per column (`<key>/path`).
- **Cascading / dependent lists** — a column's options can depend on other columns in the same row.
- **Per-column descriptions** — shown as tooltips on the column headers.
- **Bidirectional REST** — external systems read/write the whole grid (full-replace, validated).
- Renders as its own collapsible section on the issue, styled to match native Jira modules.

---

## Data & schema model

**Field value** (stored as JSON in an unlimited-text custom-field column):

```json
{ "rows": [ { "<key>": value, "<key>Id": "<opaque-id>", ... }, ... ] }
```

`<key>Id` appears only for source-backed columns (the id of the picked list item) and lives in the data
only — it is not a schema column.

**Schema** (stored in `PluginSettings`):

```json
{ "resolveBy": "project",
  "schemas": { "PROJKEY": { "columns": [ { GridColumn }, ... ] } } }
```

**Column** (`GridColumn`):

| field | meaning |
|---|---|
| `key` | internal id, **unique per project**; the data is keyed by it |
| `label` | display name (safe to rename — data is keyed by `key`, not label) |
| `description?` | optional help text; shown as a tooltip on the column header |
| `type` | `string` · `int` · `decimal` · `date` · `boolean` |
| `source?` | `<key>/path` — a Source-connection key + a path → dropdown from `[{id,name}]`; stores `<key>Id` |
| `dependsOn?` | keys of other columns → cascading options |
| `sum` | footer aggregate: numeric → Σ; other types → count of distinct values |
| `required` | reject empty on validation |

**Numeric normalization:** `int` → rounded to integer; `decimal` → `DECIMAL(15,2)`, HALF_UP to 2 places.
Non-numeric input is a validation error; extra precision is silently normalized.

---

## Source connections

Configured in **Administration → System → Breakdown Grid → Source connection** as a table:
`Key · Base URL · Auth · Username · Password`. Each row is one external system.

A column's `source` is written as **`<key>/path`**: the first segment selects a connection (its base URL
and auth), the rest is the path appended to that base URL. The endpoint must return `[{"id":…,"name":…}]`.

- **Static** (`erp/tools/all`) — options load once, shared by all rows.
- **`<value>` token** (`erp/tools/<value>`) — loads per row; `<value>` is replaced by the current cell's
  value (its `<key>Id` if source-backed, else the value; URL-encoded).
- **`dependsOn`** — loads per row via POST, filtered by those columns' values (cascading lists).

Auth is HTTP **Basic** for now. The proxy resolves each column's path **from the stored schema**
(whitelist), so the client cannot make it fetch an arbitrary URL (SSRF-safe). Passwords are stored in
Jira's `PluginSettings` and are never sent back to the browser. Use HTTPS base URLs in production.

---

## REST API (`/rest/breakdown-grid/1.0`)

| Method | Path | Purpose |
|--------|------|---------|
| `GET`  | `/issue/{key}/field/{fieldId}` | read the grid → `{"rows":[…]}` |
| `PUT`  | `/issue/{key}/field/{fieldId}` | **full replace** (validated against the project schema, atomic) |
| `GET\|POST` | `/options?ctx={project}&col={key}[&val={v}]` | source list for a column (UI autocomplete) |

`fieldId` accepts `customfield_NNNNN`, the numeric id, or the field name. Auth is standard Jira REST
(Edit for `PUT`, Browse for `GET`). On `PUT`, merging/preserving manual edits is the **caller's**
responsibility (read → build the full list → write).

**Validation runs in three layers:** (1) server on the UI submit, (2) client before submit,
(3) REST `PUT` (authoritative — rejects the whole write on any error).

---

## Build & install

```bash
atlas-package -o -B -DskipTests      # → target/breakdown-grid-<version>.jar / .obr
```

Requires the **Atlassian Plugin SDK 8.2.7** and **JDK 8**. Install the resulting `.jar`/`.obr` via
*Administration → Manage apps → Upload app*.

Then: create a custom field of type **Breakdown Grid**, add it to the relevant screens, and configure a
column schema for the project in the admin page. See [`AGENTS.md`](AGENTS.md) for the full developer
briefing (architecture, source layout, and the non-obvious Jira gotchas already solved).

---

## License

Licensed under the **Apache License 2.0** — see [`LICENSE`](LICENSE).

Author / vendor: **Oleksandr Romanenko**.
