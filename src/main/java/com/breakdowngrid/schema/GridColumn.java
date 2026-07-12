package com.breakdowngrid.schema;

/**
 * Опис однієї колонки гріда (елемент схеми, задається в адмінці).
 *
 *   key      — внутрішній ідентифікатор колонки (унікальний у межах проекту).
 *   label    — назва для користувача.
 *   description — довільний опис колонки; показується як тултип на заголовку колонки у гріді (view+edit).
 *   type     — примітивний тип: string | int | decimal | date | boolean.
 *              decimal = DECIMAL(15,2): значення тихо округлюється до 2 знаків і обрізається до 15 значущих.
 *              int     = ціле (значення тихо округлюється до цілого).
 *   source    — шлях від baseUrl (напр. "tools/all"); якщо задано — ввід лише зі списку джерела,
 *               а в рядку зʼявляється супутник "<key>Id" (opaque, довжина не фіксована).
 *               Токен "<value>" у шляху → підставляється значення ЦІЄЇ колонки (id якщо є, інакше name), GET.
 *   dependsOn — ключі інших колонок, від яких залежать опції; якщо задано → опції вантажаться per-row
 *               через POST, тіло = значення цих колонок (name+id). Каскадні (залежні) списки.
 *   sum       — агрегат у футері: number → сума; інші типи → кількість унікальних значень.
 *   required  — заборонити порожнє значення.
 *
 * Поля public — Gson наповнює їх напряму.
 */
public class GridColumn {

    public String key;
    public String label;
    public String description;
    public String type;
    public String source;
    public java.util.List<String> dependsOn;
    public boolean sum;
    public boolean required;

    public boolean hasDependsOn() { return dependsOn != null && !dependsOn.isEmpty(); }

    public boolean isInt()      { return "int".equals(type); }
    public boolean isDecimal()  { return "decimal".equals(type); }
    public boolean isNumeric()  { return isInt() || isDecimal(); }
    public boolean isDate()     { return "date".equals(type); }
    public boolean isBoolean()  { return "boolean".equals(type); }
    public boolean isString()   { return type == null || "string".equals(type); }

    public boolean hasSource()  { return source != null && !source.trim().isEmpty(); }

    public boolean hasDescription() { return description != null && !description.trim().isEmpty(); }

    public String keySafe()    { return key == null ? "" : key.trim(); }
    public String labelSafe()  { return (label == null || label.trim().isEmpty()) ? keySafe() : label; }
    public String descriptionSafe() { return description == null ? "" : description.trim(); }
    public String sourceSafe() { return source == null ? "" : source.trim(); }
    public String typeSafe()   { return (type == null || type.trim().isEmpty()) ? "string" : type; }
}
