package com.breakdowngrid.search;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.customfields.searchers.CustomFieldSearcherClauseHandler;
import com.atlassian.jira.issue.customfields.searchers.TextSearcher;
import com.atlassian.jira.issue.customfields.searchers.transformer.CustomFieldInputHelper;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.index.indexers.FieldIndexer;
import com.atlassian.jira.issue.search.searchers.SearcherGroupType;
import com.atlassian.jira.issue.search.searchers.information.SearcherInformation;
import com.atlassian.jira.jql.operand.JqlOperandResolver;
import com.atlassian.jira.web.FieldVisibilityManager;

import java.util.Collections;
import java.util.List;

/**
 * Searcher гріда: успадковує текстовий (готові рендерер пошукової форми та трансформер
 * вводу), але підмінює дві речі —
 *   • {@link #getSearchInformation()} повертає наш {@link BreakdownGridIndexer}
 *     (індексація по колонках замість одного тексту всього значення);
 *   • {@link #getCustomFieldSearcherClauseHandler()} — наш обробник JQL
 *     {@code "Grid" ~ "колонка оператор значення"}.
 *
 * Залежності базового TextSearcher дістаємо через ComponentAccessor — Jira інстанціює
 * searcher конструктором без аргументів.
 */
public class BreakdownGridSearcher extends TextSearcher {

    public BreakdownGridSearcher() {
        super(ComponentAccessor.getComponent(FieldVisibilityManager.class),
              ComponentAccessor.getComponent(JqlOperandResolver.class),
              ComponentAccessor.getComponent(CustomFieldInputHelper.class));
    }

    @Override
    public SearcherInformation<CustomField> getSearchInformation() {
        final SearcherInformation<CustomField> base = super.getSearchInformation();
        return new SearcherInformation<CustomField>() {
            @Override public String getId() { return base.getId(); }
            @Override public String getNameKey() { return base.getNameKey(); }
            @Override public CustomField getField() { return base.getField(); }
            @Override public SearcherGroupType getSearcherGroupType() { return base.getSearcherGroupType(); }
            @Override public List<FieldIndexer> getRelatedIndexers() {
                final CustomField cf = base.getField();
                if (cf == null) {
                    return Collections.emptyList();
                }
                return Collections.<FieldIndexer>singletonList(
                        new BreakdownGridIndexer(
                                ComponentAccessor.getComponent(FieldVisibilityManager.class), cf));
            }
        };
    }

    @Override
    public CustomFieldSearcherClauseHandler getCustomFieldSearcherClauseHandler() {
        return new BreakdownGridClauseHandler(getSearchInformation().getField());
    }
}
