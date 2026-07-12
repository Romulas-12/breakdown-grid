package com.breakdowngrid.schema;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.project.Project;

/**
 * Визначає контекстний ключ, за яким для issue береться набір колонок.
 *
 * ЄДИНА точка, яку треба міняти, щоб перейти з «тільки проект» на щось інше
 * (проект+тип задачі, значення реквізиту тощо). Тип поля, REST і UI цього не знають —
 * вони оперують уже готовим контекстним ключем.
 */
public final class SchemaResolver {

    private SchemaResolver() {
    }

    public static String contextKeyFor(final GridSchema schema, final Issue issue) {
        if (issue == null) {
            return null;
        }
        final Project project = issue.getProjectObject();
        final String projectKey = project == null ? null : project.getKey();
        final String issueType = issue.getIssueType() == null ? "" : issue.getIssueType().getName();
        return contextKey(schema, projectKey, issueType);
    }

    /** Той самий резолвинг, але від «сирих» атрибутів — для валідації, де є лише параметри запиту. */
    public static String contextKey(final GridSchema schema, final String projectKey, final String issueType) {
        if (projectKey == null || projectKey.trim().isEmpty()) {
            return null;
        }
        final String mode = (schema == null || schema.resolveBy == null) ? "project" : schema.resolveBy;
        if ("project+issuetype".equals(mode)) {
            return projectKey + "/" + (issueType == null ? "" : issueType);
        }
        return projectKey;
    }
}
