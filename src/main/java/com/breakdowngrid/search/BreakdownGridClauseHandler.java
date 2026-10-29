package com.breakdowngrid.search;

import com.atlassian.jira.JiraDataType;
import com.atlassian.jira.JiraDataTypes;
import com.atlassian.jira.issue.customfields.searchers.CustomFieldSearcherClauseHandler;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.jql.query.ClauseQueryFactory;
import com.atlassian.jira.jql.validator.ClauseValidator;
import com.atlassian.query.operator.Operator;

import java.util.EnumSet;
import java.util.Set;

/**
 * Звʼязує для JQL валідатор і фабрику запиту по гріду.
 * Підтримуваний оператор у JQL — {@code ~} (LIKE): значення містить вираз
 * {@code "колонка оператор значення"}. Тип даних — текст.
 */
public class BreakdownGridClauseHandler implements CustomFieldSearcherClauseHandler {

    private final ClauseValidator validator = new BreakdownGridClauseValidator();
    private final ClauseQueryFactory queryFactory;

    public BreakdownGridClauseHandler(final CustomField customField) {
        this.queryFactory = new BreakdownGridClauseQueryFactory(customField);
    }

    @Override
    public ClauseValidator getClauseValidator() {
        return validator;
    }

    @Override
    public ClauseQueryFactory getClauseQueryFactory() {
        return queryFactory;
    }

    @Override
    public Set<Operator> getSupportedOperators() {
        return EnumSet.of(Operator.LIKE, Operator.NOT_LIKE);
    }

    @Override
    public JiraDataType getDataType() {
        return JiraDataTypes.TEXT;
    }
}
