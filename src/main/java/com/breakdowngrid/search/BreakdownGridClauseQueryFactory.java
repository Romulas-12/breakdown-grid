package com.breakdowngrid.search;

import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.jql.query.ClauseQueryFactory;
import com.atlassian.jira.jql.query.QueryCreationContext;
import com.atlassian.jira.jql.query.QueryFactoryResult;
import com.atlassian.query.clause.TerminalClause;
import com.atlassian.query.operand.Operand;
import com.atlassian.query.operand.SingleValueOperand;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Будує Lucene-запит з JQL виду {@code "Grid" ~ "колонка оператор значення"}.
 *
 * Значення clause — вираз {@code <колонка> <оп> <значення>}:
 *   =   точний збіг  (текст exact «_e», АБО число «_n», АБО дата «_d» — що збіглося);
 *   !=  заперечення точного збігу;
 *   ~   слово міститься (аналізований текст «_t»);
 *   &gt; &gt;= &lt; &lt;=  діапазон — по числу «_n» або, якщо значення у форматі yyyy-MM-dd, по даті «_d».
 * Match, якщо БУДЬ-ЯКИЙ рядок гріда задовольняє (терми багатозначні).
 */
public class BreakdownGridClauseQueryFactory implements ClauseQueryFactory {

    private final CustomField customField;

    // "колонка  оп  значення"; оп: >= <= != = ~ > <  (двосимвольні — першими)
    private static final Pattern EXPR =
            Pattern.compile("^\\s*([A-Za-z0-9_]+)\\s*(>=|<=|!=|=|~|>|<)\\s*(.+?)\\s*$");

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    public BreakdownGridClauseQueryFactory(final CustomField customField) {
        this.customField = customField;
    }

    @Override
    public QueryFactoryResult getQuery(final QueryCreationContext context, final TerminalClause clause) {
        final String expr = rawValue(clause);
        if (expr == null) {
            return QueryFactoryResult.createFalseResult();
        }
        final Matcher m = EXPR.matcher(expr);
        if (!m.matches()) {
            return QueryFactoryResult.createFalseResult();
        }
        final String col = m.group(1);
        final String op = m.group(2);
        final String val = m.group(3).trim();
        if (val.isEmpty()) {
            return QueryFactoryResult.createFalseResult();
        }

        final String p = customField.getId() + "_" + col; // префікс терма колонки

        if ("~".equals(op)) {
            return new QueryFactoryResult(new TermQuery(new Term(p + "_t", val.toLowerCase())));
        }
        if ("=".equals(op)) {
            return new QueryFactoryResult(equalsQuery(p, val));
        }
        if ("!=".equals(op)) {
            final BooleanQuery.Builder b = new BooleanQuery.Builder();
            b.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
            b.add(equalsQuery(p, val), BooleanClause.Occur.MUST_NOT);
            return new QueryFactoryResult(b.build());
        }
        // Діапазони: > >= < <=
        final Query range = rangeQuery(p, op, val);
        return range == null ? QueryFactoryResult.createFalseResult() : new QueryFactoryResult(range);
    }

    /** «=»: збіг по тексту АБО числу АБО даті (що релевантне для значення). */
    private Query equalsQuery(final String p, final String val) {
        final BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(new TermQuery(new Term(p + "_e", val.toLowerCase())), BooleanClause.Occur.SHOULD);
        final Double d = asDouble(val);
        if (d != null) {
            b.add(DoublePoint.newExactQuery(p + "_n", d), BooleanClause.Occur.SHOULD);
        }
        final Long day = asEpochDay(val);
        if (day != null) {
            b.add(LongPoint.newExactQuery(p + "_d", day), BooleanClause.Occur.SHOULD);
        }
        return b.build();
    }

    /** Діапазон по числу або (для yyyy-MM-dd) по даті. */
    private Query rangeQuery(final String p, final String op, final String val) {
        final Long day = asEpochDay(val);
        if (day != null) {
            final long lo;
            final long hi;
            switch (op) {
                case ">":  lo = day + 1;           hi = Long.MAX_VALUE; break;
                case ">=": lo = day;               hi = Long.MAX_VALUE; break;
                case "<":  lo = Long.MIN_VALUE;    hi = day - 1;        break;
                default:   lo = Long.MIN_VALUE;    hi = day;            break; // <=
            }
            return LongPoint.newRangeQuery(p + "_d", lo, hi);
        }
        final Double d = asDouble(val);
        if (d == null) {
            return null; // діапазон має сенс лише для числа/дати
        }
        final double lo;
        final double hi;
        switch (op) {
            case ">":  lo = Math.nextUp(d);              hi = Double.POSITIVE_INFINITY; break;
            case ">=": lo = d;                           hi = Double.POSITIVE_INFINITY; break;
            case "<":  lo = Double.NEGATIVE_INFINITY;    hi = Math.nextDown(d);         break;
            default:   lo = Double.NEGATIVE_INFINITY;    hi = d;                        break; // <=
        }
        return DoublePoint.newRangeQuery(p + "_n", lo, hi);
    }

    private static Double asDouble(final String s) {
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asEpochDay(final String s) {
        if (!ISO_DATE.matcher(s).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(s).toEpochDay();
        } catch (Exception e) {
            return null;
        }
    }

    /** Витягує рядок значення з операнда clause. */
    private static String rawValue(final TerminalClause clause) {
        final Operand operand = clause.getOperand();
        if (operand instanceof SingleValueOperand) {
            final SingleValueOperand svo = (SingleValueOperand) operand;
            return svo.getStringValue() != null ? svo.getStringValue()
                    : (svo.getLongValue() != null ? String.valueOf(svo.getLongValue()) : null);
        }
        return null;
    }
}
