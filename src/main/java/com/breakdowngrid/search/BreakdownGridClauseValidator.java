package com.breakdowngrid.search;

import com.atlassian.jira.jql.validator.ClauseValidator;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.MessageSet;
import com.atlassian.jira.util.MessageSetImpl;
import com.atlassian.query.clause.TerminalClause;

/**
 * Валідатор clause пошуку по гріду. Формат значення перевіряє сам QueryFactory
 * (невідповідний вираз → порожній результат), тож тут помилок не додаємо.
 */
public class BreakdownGridClauseValidator implements ClauseValidator {

    @Override
    public MessageSet validate(final ApplicationUser searcher, final TerminalClause terminalClause) {
        return new MessageSetImpl();
    }
}
