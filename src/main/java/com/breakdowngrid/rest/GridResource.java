package com.breakdowngrid.rest;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.ModifiedValue;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder;
import com.atlassian.jira.permission.ProjectPermissions;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.user.ApplicationUser;
import com.breakdowngrid.BreakdownGridCFType;
import com.breakdowngrid.schema.GridColumn;
import com.breakdowngrid.schema.GridNormalizer;
import com.breakdowngrid.schema.GridSchema;
import com.breakdowngrid.schema.GridValidator;
import com.breakdowngrid.schema.SchemaResolver;
import com.breakdowngrid.schema.SchemaStore;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * REST гріда — для зовнішньої системи.
 *
 *   GET  /rest/breakdown-grid/1.0/issue/{issueKey}/field/{fieldId}  -> поточний JSON гріда
 *   PUT  /rest/breakdown-grid/1.0/issue/{issueKey}/field/{fieldId}  -> ПОВНА заміна (тіло = JSON)
 *
 * fieldId: "customfield_10500", "10500" або назва поля.
 * PUT валідує тіло проти схеми проекту issue (required + типи); source НЕ звіряється;
 * атомарно — при будь-якій помилці не пишемо нічого й повертаємо 400 з переліком.
 * Права: GET — BROWSE_PROJECTS, PUT — EDIT_ISSUES.
 */
@Path("/issue")
public class GridResource {

    @GET
    @Path("/{issueKey}/field/{fieldId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getGrid(@PathParam("issueKey") final String issueKey,
                            @PathParam("fieldId") final String fieldId) {

        final ApplicationUser user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        if (user == null) {
            return error(Response.Status.UNAUTHORIZED, "Not authenticated");
        }
        final MutableIssue issue = ComponentAccessor.getIssueManager().getIssueObject(issueKey);
        if (issue == null) {
            return error(Response.Status.NOT_FOUND, "Issue not found: " + issueKey);
        }
        final PermissionManager pm = ComponentAccessor.getPermissionManager();
        if (!pm.hasPermission(ProjectPermissions.BROWSE_PROJECTS, issue, user)) {
            return error(Response.Status.FORBIDDEN, "No browse permission");
        }
        final CustomField cf = resolveField(fieldId);
        if (cf == null || !(cf.getCustomFieldType() instanceof BreakdownGridCFType)) {
            return error(Response.Status.NOT_FOUND, "Not a Breakdown Grid field: " + fieldId);
        }

        final Object value = issue.getCustomFieldValue(cf);
        final String json = (value instanceof String && !((String) value).trim().isEmpty())
                ? (String) value
                : "{\"rows\":[]}";
        return Response.ok(json).build();
    }

    @PUT
    @Path("/{issueKey}/field/{fieldId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response putGrid(@PathParam("issueKey") final String issueKey,
                            @PathParam("fieldId") final String fieldId,
                            final String body) {

        final ApplicationUser user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
        if (user == null) {
            return error(Response.Status.UNAUTHORIZED, "Not authenticated");
        }
        final MutableIssue issue = ComponentAccessor.getIssueManager().getIssueObject(issueKey);
        if (issue == null) {
            return error(Response.Status.NOT_FOUND, "Issue not found: " + issueKey);
        }
        final PermissionManager pm = ComponentAccessor.getPermissionManager();
        if (!pm.hasPermission(ProjectPermissions.EDIT_ISSUES, issue, user)) {
            return error(Response.Status.FORBIDDEN, "No edit permission");
        }
        final CustomField cf = resolveField(fieldId);
        if (cf == null || !(cf.getCustomFieldType() instanceof BreakdownGridCFType)) {
            return error(Response.Status.NOT_FOUND, "Not a Breakdown Grid field: " + fieldId);
        }

        final String newValue = body == null ? "" : body;

        // Валідація проти схеми проекту issue — атомарно.
        final GridSchema schema = SchemaStore.load();
        final String ctx = SchemaResolver.contextKeyFor(issue);
        final List<GridColumn> columns = schema.columnsFor(ctx);
        final List<String> errors = GridValidator.validate(newValue, columns);
        if (!errors.isEmpty()) {
            return error(Response.Status.BAD_REQUEST, String.join(" ", errors));
        }

        // Тихо нормалізуємо числові (int → ціле, decimal → 15,2) вже після успішної валідації.
        final String stored = GridNormalizer.normalize(newValue, columns);

        final Object oldValue = issue.getCustomFieldValue(cf);
        issue.setCustomFieldValue(cf, stored);
        cf.updateValue(null, issue, new ModifiedValue(oldValue, stored), new DefaultIssueChangeHolder());
        return Response.ok(stored).build();
    }

    private CustomField resolveField(final String fieldId) {
        if (fieldId == null || fieldId.trim().isEmpty()) {
            return null;
        }
        final CustomFieldManager cfm = ComponentAccessor.getCustomFieldManager();
        CustomField cf = null;
        if (fieldId.startsWith("customfield_")) {
            cf = cfm.getCustomFieldObject(fieldId);
        } else if (fieldId.matches("\\d+")) {
            cf = cfm.getCustomFieldObject("customfield_" + fieldId);
        }
        if (cf == null) {
            cf = cfm.getCustomFieldObjectByName(fieldId);
        }
        return cf;
    }

    private Response error(final Response.Status status, final String message) {
        final String json = "{\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return Response.status(status).entity(json).type(MediaType.APPLICATION_JSON).build();
    }
}
