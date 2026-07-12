/* Breakdown Grid — edit-режим.
 * Грід будується на клієнті за схемою з <script.bdg-schema>:
 *   [ { key, label, description, type: string|int|decimal|date|boolean, source, sum, required }, ... ]
 * Початкові дані — у <input.bdg-json> ({"rows":[...]}).
 *
 * Колонки з source рендеряться як <select>, наповнений зі списку джерела
 * (проксі /rest/breakdown-grid/1.0/options?ctx=..&col=..); вибір назви підставляє id у "<key>Id".
 * Якщо джерело не сконфігуроване (data-connected=0) — такий <select> вимкнений.
 *
 * Слухачі — делеговані на document + MutationObserver, щоб працювати і в inline Quick-Edit. */
(function () {
    "use strict";

    function num(v) { var n = parseFloat(v); return isNaN(n) ? 0 : n; }
    var ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

    // Тултипи-описи колонок: значок «?» несе title; на issue (view+edit) показуємо їх AUI-тултипом,
    // бо нативний title у Jira часто не спливає. AUI переносить title у власне сховище → [title] лишає лише неініціалізовані.
    function initTooltips() {
        if (window.AJS && AJS.$) {
            try { AJS.$(".bdg-qh[title]").tooltip({ gravity: "n" }); } catch (e) { /* no-op */ }
        }
    }

    // У перегляді issue виносимо грід окремим модулем одразу після Details (структура як у
    // Description: div.module.toggle-wrap > .mod-header h4 + .mod-content), заголовок = ім'я поля,
    // таблиця блоком на всю ширину. Оригінальний рядок label|value ховаємо.
    // SVG-трикутник, ідентичний рідній кнопці згортки модуля Jira.
    var TWIXI_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14">'
        + '<g fill="none" fill-rule="evenodd"><path d="M3.29175 4.793c-.389.392-.389 1.027 0 1.419l2.939 2.965'
        + 'c.218.215.5.322.779.322s.556-.107.769-.322l2.93-2.955c.388-.392.388-1.027 0-1.419-.389-.392-1.018-.392-1.406 0'
        + 'l-2.298 2.317-2.307-2.327c-.194-.195-.449-.293-.703-.293-.255 0-.51.098-.703.293z" fill="#344563"></path></g></svg>';

    function relocateToModule() {
        var details = document.getElementById("details-module");
        if (!details) { return; }
        var mainCol = details.parentElement;
        if (!mainCol) { return; }

        // Свіжі значення = ті, що ще НЕ в нашому модулі (перший рендер або перерендер issue).
        // Якщо таких нема — все вже на місці, виходимо (щоб не чіпати вже перенесену таблицю).
        var all = [].slice.call(document.querySelectorAll(".value.type-breakdown-grid"));
        var fresh = [];
        for (var f = 0; f < all.length; f++) { if (!closest(all[f], ".bdg-module")) { fresh.push(all[f]); } }
        if (!fresh.length) { return; }

        // Є свіжий рендер → прибираємо застарілі наші модулі від попереднього (їхні val — старі вузли).
        var old = document.querySelectorAll(".bdg-module");
        for (var o = 0; o < old.length; o++) { if (old[o].parentNode) { old[o].parentNode.removeChild(old[o]); } }

        var anchor = details.nextSibling;
        for (var i = 0; i < fresh.length; i++) {
            var val = fresh[i];
            var li = closest(val, "li.item");
            var nameEl = li ? li.querySelector("strong.name") : null;
            var title = nameEl ? nameEl.textContent.replace(/\s*:\s*$/, "").trim() : "Breakdown Grid";

            // Рідна розмітка модуля: .toggle-wrap + .mod-header з .toggle-title (кнопка+заголовок).
            // Згортку малює й керує сама Jira — JIRA.ToggleBlock делеговано на document ловить кліки
            // по .toggle-title і перемикає .toggle-wrap (додає collapsed/expanded, ховає .mod-content).
            var mod = document.createElement("div");
            mod.className = "module toggle-wrap bdg-module";
            if (val.id) { mod.id = "bdg-" + val.id + "-module"; } // стабільний id → Jira памʼятає стан згортки
            var hdr = document.createElement("div");
            hdr.className = "mod-header";
            var btn = document.createElement("button");
            btn.className = "aui-button toggle-title";
            btn.setAttribute("type", "button");
            btn.setAttribute("aria-label", title);
            btn.innerHTML = TWIXI_SVG;
            var h = document.createElement("h4");
            h.className = "toggle-title";
            h.textContent = title;
            hdr.appendChild(btn);
            hdr.appendChild(h);
            var content = document.createElement("div");
            content.className = "mod-content";
            content.appendChild(val); // переносимо div.value з таблицею в тіло модуля
            mod.appendChild(hdr);
            mod.appendChild(content);

            if (anchor) { mainCol.insertBefore(mod, anchor); } else { mainCol.appendChild(mod); }
            if (li) { li.style.display = "none"; } // ховаємо порожній рядок у Details
        }
    }

    function ctxPath() {
        if (window.AJS && typeof AJS.contextPath === "function") { return AJS.contextPath(); }
        if (typeof window.contextPath === "string") { return window.contextPath; }
        return "";
    }

    function getJSON(url, cb) {
        try {
            var xhr = new XMLHttpRequest();
            xhr.open("GET", url, true);
            xhr.setRequestHeader("Accept", "application/json");
            xhr.onreadystatechange = function () {
                if (xhr.readyState === 4) {
                    if (xhr.status >= 200 && xhr.status < 300) {
                        try { cb(null, JSON.parse(xhr.responseText)); } catch (e) { cb(e); }
                    } else { cb(new Error("HTTP " + xhr.status)); }
                }
            };
            xhr.send();
        } catch (e) { cb(e); }
    }

    function closest(el, sel) {
        while (el && el.nodeType === 1) {
            if (el.matches ? el.matches(sel) : false) { return el; }
            el = el.parentNode;
        }
        return null;
    }
    function rootOf(el) { return closest(el, ".bdg-edit"); }
    function hasSource(col) { return !!(col.source && String(col.source).trim()); }
    function isNumericType(col) { return col.type === "int" || col.type === "decimal"; }
    function isRight(col) { return isNumericType(col); }
    function connected(root) { return root.getAttribute("data-connected") !== "0"; }

    function colByKey(root, key) { var cols = root._bdgCols || []; for (var i = 0; i < cols.length; i++) { if (cols[i].key === key) { return cols[i]; } } return null; }
    function hasDeps(col) { return !!(col.dependsOn && col.dependsOn.length); }
    function srcHasValue(col) { return hasSource(col) && String(col.source).indexOf("<value>") >= 0; }
    // Колонка потребує контексту рядка (per-row завантаження): має dependsOn або токен <value> у source.
    function needsCtx(col) { return hasSource(col) && (hasDeps(col) || srcHasValue(col)); }

    function postJSON(url, body, cb) {
        try {
            var xhr = new XMLHttpRequest();
            xhr.open("POST", url, true);
            xhr.setRequestHeader("Accept", "application/json");
            xhr.setRequestHeader("Content-Type", "application/json");
            xhr.onreadystatechange = function () {
                if (xhr.readyState === 4) {
                    if (xhr.status >= 200 && xhr.status < 300) {
                        try { cb(null, JSON.parse(xhr.responseText)); } catch (e) { cb(e); }
                    } else { cb(new Error("HTTP " + xhr.status)); }
                }
            };
            xhr.send(JSON.stringify(body || {}));
        } catch (e) { cb(e); }
    }

    var DEC_MAX = 9999999999999.99; // decimal(15,2)
    // Тихе округлення (варіант B): int → ціле; decimal → 2 знаки + клемп до 15 значущих.
    function normalizeNum(col, n) {
        if (col.type === "int") { return Math.round(n); }
        var d = Math.round(n * 100) / 100;
        if (d > DEC_MAX) { d = DEC_MAX; } else if (d < -DEC_MAX) { d = -DEC_MAX; }
        return d;
    }

    // Velocity у полі HTML-екранує вміст <script class="bdg-schema"> (лапки → &quot;),
    // тож перед JSON.parse декодуємо сутності. (На чистому JSON без '&' — no-op.)
    function decodeEntities(s) {
        if (!s || s.indexOf("&") < 0) { return s; }
        var ta = document.createElement("textarea");
        ta.innerHTML = s;
        return ta.value;
    }
    // Схема колонок — у data-атрибуті на .bdg-edit (а не у <script>, бо script вирізається при
    // AJAX-вставці inline Quick-Edit). getAttribute повертає вже декодоване значення.
    function parseSchema(root) {
        var s = root.getAttribute("data-schema");
        if (!s) { return []; }
        try { var a = JSON.parse(decodeEntities(s)); return (a && a.length !== undefined) ? a : []; }
        catch (e) { return []; }
    }
    function parseRows(root) {
        var hidden = root.querySelector(".bdg-json");
        if (!hidden || !hidden.value) { return []; }
        try { var o = JSON.parse(hidden.value); return (o && o.rows && o.rows.length !== undefined) ? o.rows : []; }
        catch (e) { return []; }
    }

    // ── Побудова комірки за типом ──────────────────────────────────────────────
    function buildCell(root, col, rowData) {
        var td = document.createElement("td");
        if (isRight(col)) { td.style.textAlign = "right"; }
        var val = rowData ? rowData[col.key] : undefined;

        if (hasSource(col)) {
            var sel = document.createElement("select");
            sel.className = "bdg-cell bdg-select select";
            sel.setAttribute("data-col", col.key);
            var name = (val === undefined || val === null) ? "" : String(val);
            var id = rowData ? (rowData[col.key + "Id"] || "") : "";
            sel.setAttribute("data-name", name);
            sel.setAttribute("data-id", id);
            if (name) {
                var op = document.createElement("option");
                op.value = name; op.textContent = name; op.selected = true; sel.appendChild(op);
            } else { sel.appendChild(document.createElement("option")); }
            if (!connected(root)) { sel.disabled = true; sel.title = "Source connection is not configured"; }
            td.appendChild(sel);
            return td;
        }
        if (isNumericType(col)) {
            var step = col.type === "int" ? "1" : "0.01";
            td.innerHTML = '<input type="number" step="' + step + '" class="bdg-cell bdg-num text" data-col="' + col.key + '"/>';
            td.firstChild.value = (val === undefined || val === null || val === "") ? "" : num(val);
            return td;
        }
        if (col.type === "date") {
            td.innerHTML = '<input type="date" class="bdg-cell bdg-date text" data-col="' + col.key + '"/>';
            td.firstChild.value = (val === undefined || val === null) ? "" : String(val);
            return td;
        }
        if (col.type === "boolean") {
            td.innerHTML = '<input type="checkbox" class="bdg-cell bdg-check" data-col="' + col.key + '"/>';
            td.firstChild.checked = (val === true || val === "true" || val === 1);
            return td;
        }
        td.innerHTML = '<input type="text" class="bdg-cell bdg-text text" data-col="' + col.key + '"/>';
        td.firstChild.value = (val === undefined || val === null) ? "" : String(val);
        return td;
    }

    function buildRow(root, rowData) {
        var cols = root._bdgCols || [];
        var tr = document.createElement("tr");
        for (var i = 0; i < cols.length; i++) { tr.appendChild(buildCell(root, cols[i], rowData)); }
        var tdDel = document.createElement("td");
        tdDel.innerHTML = '<button type="button" class="aui-button aui-button-link bdg-del" title="Delete row">✕</button>';
        tr.appendChild(tdDel);
        return tr;
    }

    // ── <select> джерела ───────────────────────────────────────────────────────
    function populateSelect(sel, opts) {
        var current = sel.value || sel.getAttribute("data-name") || "";
        sel.innerHTML = "";
        sel.appendChild(document.createElement("option"));
        var matched = false;
        for (var i = 0; i < opts.length; i++) {
            var o = opts[i];
            if (!o || !o.name) { continue; }
            var op = document.createElement("option");
            op.value = o.name; op.textContent = o.name;
            if (o.name === current) { op.selected = true; matched = true; }
            sel.appendChild(op);
        }
        if (current && !matched) {
            var stray = document.createElement("option");
            stray.value = current; stray.textContent = current + " (?)"; stray.selected = true;
            sel.appendChild(stray);
        }
        syncId(sel);
    }

    function loadColumnOptions(root, col) {
        if (!connected(root)) { return; }
        var url = ctxPath() + "/rest/breakdown-grid/1.0/options?ctx="
            + encodeURIComponent(root.getAttribute("data-ctx") || "") + "&col=" + encodeURIComponent(col.key);
        getJSON(url, function (err, data) {
            if (err || !data || data.length === undefined) { return; }
            root._bdgOpts[col.key] = data;
            root._bdgIdMap[col.key] = {};
            for (var i = 0; i < data.length; i++) {
                if (data[i] && data[i].name) { root._bdgIdMap[col.key][data[i].name] = data[i].id || ""; }
            }
            var sels = root.querySelectorAll('select.bdg-select[data-col="' + col.key + '"]');
            for (var j = 0; j < sels.length; j++) { populateSelect(sels[j], data); }
            serialize(root);
        });
    }

    // ── Per-row опції для колонок із dependsOn / <value> (каскадні списки) ──────
    function currentColVal(tr, col) {
        var el = tr.querySelector('.bdg-cell[data-col="' + col.key + '"]');
        if (!el) { return ""; }
        var id = el.getAttribute ? el.getAttribute("data-id") : "";
        if (id) { return id; } // id, якщо колонка source-backed і вже щось вибрано
        return (el.value || "").trim();
    }

    function loadRowOptions(root, tr, col) {
        if (!connected(root) || !tr) { return; }
        var sel = tr.querySelector('select.bdg-select[data-col="' + col.key + '"]');
        if (!sel) { return; }
        if (sel._optTimer) { clearTimeout(sel._optTimer); }
        sel._optTimer = setTimeout(function () { doLoadRowOptions(root, tr, col, sel); }, 180);
    }

    function doLoadRowOptions(root, tr, col, sel) {
        var url = ctxPath() + "/rest/breakdown-grid/1.0/options?ctx="
            + encodeURIComponent(root.getAttribute("data-ctx") || "") + "&col=" + encodeURIComponent(col.key);
        if (srcHasValue(col)) { url += "&val=" + encodeURIComponent(currentColVal(tr, col)); }
        function apply(err, data) {
            if (err || !data || data.length === undefined) { return; }
            sel._idMap = {};
            for (var i = 0; i < data.length; i++) { if (data[i] && data[i].name) { sel._idMap[data[i].name] = data[i].id || ""; } }
            populateSelect(sel, data);
            serialize(root);
        }
        if (hasDeps(col)) {
            var body = {};
            for (var d = 0; d < col.dependsOn.length; d++) {
                var dk = col.dependsOn[d];
                var dcol = colByKey(root, dk);
                var cell = tr.querySelector('.bdg-cell[data-col="' + dk + '"]');
                if (!cell) { continue; }
                body[dk] = (cell.type === "checkbox" && !(dcol && hasSource(dcol)))
                    ? (cell.checked ? "true" : "") : (cell.value || "").trim();
                var did = cell.getAttribute ? cell.getAttribute("data-id") : "";
                if (did) { body[dk + "Id"] = did; }
            }
            postJSON(url, body, apply);
        } else {
            getJSON(url, apply);
        }
    }

    function loadCtxAllRows(root) {
        var cols = root._bdgCols || [];
        var trs = root.querySelectorAll("tbody tr");
        for (var r = 0; r < trs.length; r++) {
            for (var c = 0; c < cols.length; c++) { if (needsCtx(cols[c])) { loadRowOptions(root, trs[r], cols[c]); } }
        }
    }

    // Перезавантажити залежні колонки рядка, коли змінилась колонка changedKey.
    function reloadDependents(root, tr, changedKey) {
        if (!tr) { return; }
        var cols = root._bdgCols || [];
        for (var c = 0; c < cols.length; c++) {
            var col = cols[c];
            if (!needsCtx(col)) { continue; }
            var dep = hasDeps(col) && col.dependsOn.indexOf(changedKey) >= 0;
            var own = srcHasValue(col) && col.key === changedKey;
            if (dep || own) { loadRowOptions(root, tr, col); }
        }
    }

    function syncId(sel) {
        var root = rootOf(sel);
        if (!root) { return; }
        var col = sel.getAttribute("data-col");
        var v = (sel.value || "").trim();
        var map = sel._idMap || root._bdgIdMap[col] || {};
        if (Object.prototype.hasOwnProperty.call(map, v)) { sel.setAttribute("data-id", map[v] || ""); }
        else if (v && v === (sel.getAttribute("data-name") || "")) { /* keep initial */ }
        else { sel.setAttribute("data-id", ""); }
    }

    // ── Значення комірки ───────────────────────────────────────────────────────
    function cellString(col, tr) { // рядкове представлення (для валідації/агрегації)
        var el = tr.querySelector('.bdg-cell[data-col="' + col.key + '"]');
        if (!el) { return ""; }
        if (col.type === "boolean" && !hasSource(col)) { return el.checked ? "true" : ""; }
        return (el.value || "").trim();
    }

    function serialize(root) {
        var cols = root._bdgCols || [];
        var rows = [];
        var trs = root.querySelectorAll("tbody tr");
        for (var i = 0; i < trs.length; i++) {
            var tr = trs[i], obj = {};
            for (var c = 0; c < cols.length; c++) {
                var col = cols[c];
                var el = tr.querySelector('.bdg-cell[data-col="' + col.key + '"]');
                if (hasSource(col)) {
                    obj[col.key] = el ? (el.value || "").trim() : "";
                    obj[col.key + "Id"] = el ? (el.getAttribute("data-id") || "") : "";
                } else if (isNumericType(col)) {
                    obj[col.key] = el && (el.value || "").trim() !== "" ? normalizeNum(col, num(el.value)) : 0;
                } else if (col.type === "boolean") {
                    obj[col.key] = el ? !!el.checked : false;
                } else {
                    obj[col.key] = el ? (el.value || "") : "";
                }
            }
            rows.push(obj);
        }
        var hidden = root.querySelector(".bdg-json");
        if (hidden) { hidden.value = JSON.stringify({ rows: rows }); }
    }

    function recalc(root) {
        var cols = root._bdgCols || [];
        for (var c = 0; c < cols.length; c++) {
            var col = cols[c];
            if (!col.sum) { continue; }
            var out = root.querySelector('.bdg-total[data-col="' + col.key + '"]');
            if (!out) { continue; }
            var trs = root.querySelectorAll("tbody tr"), i;
            if (isNumericType(col) && !hasSource(col)) {
                var total = 0;
                for (i = 0; i < trs.length; i++) {
                    var el = trs[i].querySelector('.bdg-cell[data-col="' + col.key + '"]');
                    if (el) { total += num(el.value); }
                }
                out.textContent = col.type === "int" ? String(Math.round(total)) : total.toFixed(2);
            } else {
                var seen = {}, n = 0;
                for (i = 0; i < trs.length; i++) {
                    var v = cellString(col, trs[i]);
                    if (v && !Object.prototype.hasOwnProperty.call(seen, v)) { seen[v] = 1; n++; }
                }
                out.textContent = String(n);
            }
        }
        serialize(root);
    }

    function addRow(root) {
        var tbody = root.querySelector("tbody");
        if (!tbody) { return; }
        var tr = buildRow(root, null);
        tbody.appendChild(tr);
        var cols = root._bdgCols || [];
        for (var c = 0; c < cols.length; c++) {
            if (needsCtx(cols[c])) { loadRowOptions(root, tr, cols[c]); }
            else if (hasSource(cols[c]) && root._bdgOpts[cols[c].key]) {
                var sel = tr.querySelector('select.bdg-select[data-col="' + cols[c].key + '"]');
                if (sel) { populateSelect(sel, root._bdgOpts[cols[c].key]); }
            }
        }
        var first = tr.querySelector(".bdg-cell");
        if (first && first.focus) { first.focus(); }
        recalc(root);
    }

    // ── Клієнтська валідація (шар «підлога») ───────────────────────────────────
    function validate(root) {
        var cols = root._bdgCols || [], errs = [];
        var trs = root.querySelectorAll("tbody tr");
        for (var i = 0; i < trs.length; i++) {
            for (var c = 0; c < cols.length; c++) {
                var col = cols[c];
                if (col.type === "boolean" && !hasSource(col)) { continue; }
                var v = cellString(col, trs[i]);
                if (!v) {
                    if (col.required) { errs.push("Row " + (i + 1) + ", «" + (col.label || col.key) + "»: required."); }
                    continue;
                }
                var bad = (isNumericType(col) && isNaN(parseFloat(v)))
                    || (col.type === "date" && !ISO_DATE.test(v));
                if (bad) { errs.push("Row " + (i + 1) + ", «" + (col.label || col.key) + "»: expected " + col.type + "."); }
            }
        }
        return errs;
    }

    function showError(root, msg) {
        var box = root.querySelector(".bdg-error");
        if (!box) {
            box = document.createElement("div");
            box.className = "aui-message aui-message-error error bdg-error";
            root.insertBefore(box, root.firstChild);
        }
        box.textContent = msg;
    }
    function clearError(root) {
        var box = root.querySelector(".bdg-error");
        if (box && box.parentNode) { box.parentNode.removeChild(box); }
    }

    // На edit-екрані вбудоване значення поля буває порожнім (Jira кешує «робочу копію» issue без
    // значення) — довантажуємо рядки з нашого REST за ключем issue (там читання авторитетне).
    function loadFromRest(root) {
        var key = root.getAttribute("data-issue-key") || "";
        var fid = root.getAttribute("data-field-id") || "";
        if (!key || !fid) { return; }
        var url = ctxPath() + "/rest/breakdown-grid/1.0/issue/"
            + encodeURIComponent(key) + "/field/" + encodeURIComponent(fid);
        getJSON(url, function (err, data) {
            if (err || !data || !data.rows || !data.rows.length) { return; }
            var tbody = root.querySelector("tbody");
            if (!tbody || tbody.querySelectorAll("tr").length > 0) { return; } // не чіпаємо, якщо вже є рядки
            for (var i = 0; i < data.rows.length; i++) { tbody.appendChild(buildRow(root, data.rows[i])); }
            var cols = root._bdgCols || [];
            for (var c = 0; c < cols.length; c++) {
                if (!needsCtx(cols[c]) && hasSource(cols[c]) && root._bdgOpts[cols[c].key]) {
                    var sels = tbody.querySelectorAll('select.bdg-select[data-col="' + cols[c].key + '"]');
                    for (var j = 0; j < sels.length; j++) { populateSelect(sels[j], root._bdgOpts[cols[c].key]); }
                }
            }
            loadCtxAllRows(root);
            recalc(root);
        });
    }

    // ── Рендер ─────────────────────────────────────────────────────────────────
    function render(root) {
        var cols = root._bdgCols;
        var mount = root.querySelector(".bdg-mount");
        if (!mount || cols.length === 0) { return; }

        var table = document.createElement("table");
        table.className = "bdg-edit-table";
        var thead = document.createElement("thead"), htr = document.createElement("tr");
        for (var i = 0; i < cols.length; i++) {
            var th = document.createElement("th");
            th.textContent = cols[i].label || cols[i].key;
            var desc = cols[i].description ? String(cols[i].description).trim() : "";
            if (desc) {
                th.title = desc;
                var qh = document.createElement("span");
                qh.className = "bdg-qh"; qh.textContent = "?"; qh.title = desc;
                th.appendChild(document.createTextNode(" ")); th.appendChild(qh);
            }
            if (isRight(cols[i])) { th.style.textAlign = "right"; }
            htr.appendChild(th);
        }
        htr.appendChild(document.createElement("th"));
        thead.appendChild(htr); table.appendChild(thead);

        var tbody = document.createElement("tbody");
        var data = parseRows(root);
        for (var r = 0; r < data.length; r++) { tbody.appendChild(buildRow(root, data[r])); }
        table.appendChild(tbody);

        // Один рядок футера: замість підпису «Total» — кнопка «+ Add row» (у першій колонці без sum),
        // sum-колонки показують свій підсумок.
        var tfoot = document.createElement("tfoot");
        var ftr = document.createElement("tr");
        var btnPlaced = false;
        for (var k = 0; k < cols.length; k++) {
            var fth = document.createElement("th");
            if (isRight(cols[k])) { fth.style.textAlign = "right"; }
            if (cols[k].sum) {
                var span = document.createElement("span");
                span.className = "bdg-total"; span.setAttribute("data-col", cols[k].key); span.textContent = "0";
                fth.appendChild(span);
            } else if (!btnPlaced) {
                fth.innerHTML = '<button type="button" class="aui-button aui-button-link bdg-add">+ Add row</button>';
                btnPlaced = true;
            }
            ftr.appendChild(fth);
        }
        var actTh = document.createElement("th");
        if (!btnPlaced) {
            actTh.innerHTML = '<button type="button" class="aui-button aui-button-link bdg-add">+ Add row</button>';
        }
        ftr.appendChild(actTh);
        tfoot.appendChild(ftr);
        table.appendChild(tfoot);

        mount.innerHTML = "";
        mount.appendChild(table);

        for (var c = 0; c < cols.length; c++) { if (hasSource(cols[c]) && !needsCtx(cols[c])) { loadColumnOptions(root, cols[c]); } }
        loadCtxAllRows(root);
        recalc(root);
        initTooltips();
        if (data.length === 0) { loadFromRest(root); }
    }

    function ensureInit(root) {
        if (!root || root.getAttribute("data-bdg-ready") === "1") { return; }
        root.setAttribute("data-bdg-ready", "1");
        root._bdgCols = parseSchema(root);
        root._bdgOpts = {};
        root._bdgIdMap = {};
        render(root);
    }
    function scan() {
        var grids = document.querySelectorAll(".bdg-edit");
        for (var i = 0; i < grids.length; i++) { ensureInit(grids[i]); }
    }

    var bound = false;
    function bindDocument() {
        if (bound) { return; }
        bound = true;

        document.addEventListener("click", function (e) {
            var addBtn = closest(e.target, ".bdg-add");
            if (addBtn) { var r1 = rootOf(addBtn); if (r1) { e.preventDefault(); ensureInit(r1); addRow(r1); } return; }
            var delBtn = closest(e.target, ".bdg-del");
            if (delBtn) {
                var r2 = rootOf(delBtn);
                if (r2) {
                    e.preventDefault();
                    var tr = closest(delBtn, "tr");
                    if (tr && tr.parentNode) { tr.parentNode.removeChild(tr); recalc(r2); }
                }
            }
        }, true);

        function onEdit(e) {
            var cell = closest(e.target, ".bdg-cell");
            if (!cell) { return; }
            var root = rootOf(cell);
            if (!root) { return; }
            if (cell.classList.contains("bdg-select")) { syncId(cell); }
            reloadDependents(root, closest(cell, "tr"), cell.getAttribute("data-col"));
            recalc(root);
        }
        document.addEventListener("change", onEdit, true);
        document.addEventListener("input", onEdit, true);

        // Клієнтська валідація перед сабмітом форми, що містить грід.
        document.addEventListener("submit", function (e) {
            var form = e.target;
            if (!form || !form.querySelector) { return; }
            var grids = form.querySelectorAll(".bdg-edit");
            for (var i = 0; i < grids.length; i++) {
                var root = grids[i];
                ensureInit(root);
                serialize(root);
                var errs = validate(root);
                if (errs.length) {
                    clearError(root);
                    showError(root, "Breakdown Grid — " + errs.join(" "));
                    e.preventDefault();
                    e.stopPropagation();
                    return;
                }
                clearError(root);
            }
        }, true);
    }

    function boot() { bindDocument(); scan(); relocateToModule(); initTooltips(); }

    if (document.readyState !== "loading") { boot(); }
    else { document.addEventListener("DOMContentLoaded", boot); }

    if (window.JIRA && JIRA.bind && JIRA.Events && JIRA.Events.NEW_CONTENT_ADDED) {
        JIRA.bind(JIRA.Events.NEW_CONTENT_ADDED, function () { boot(); });
    }
    if (window.MutationObserver) {
        var mo = new MutationObserver(function () { scan(); });
        try { mo.observe(document.body || document.documentElement, { childList: true, subtree: true }); }
        catch (e) { /* no-op */ }
    }
})();
