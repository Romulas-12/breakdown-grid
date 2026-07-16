package com.breakdowngrid.schema;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.project.Project;

/**
 * Визначає контекстний ключ, за яким для issue береться набір колонок.
 *
 * Наразі контекст = ключ проекту Jira. Це ЄДИНА точка, яку треба міняти, щоб зробити
 * резолвинг складнішим (напр. привʼязати до значення довільного поля). Тип поля, REST і UI
 * цього не знають — вони оперують уже готовим контекстним ключем.
 */
public final class SchemaResolver {

    private SchemaResolver() {
    }

    public static String contextKeyFor(final Issue issue) {
        if (issue == null) {
            return null;
        }
        final Project project = issue.getProjectObject();
        return contextKey(project == null ? null : project.getKey());
    }

    /** Той самий резолвинг, але від «сирих» атрибутів — для валідації, де є лише параметри запиту. */
    public static String contextKey(final String projectKey) {
        if (projectKey == null || projectKey.trim().isEmpty()) {
            return null;
        }
        return projectKey;
    }
}
