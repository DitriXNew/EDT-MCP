/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.metadata.dbview.DbViewElement;
import com._1c.g5.v8.dt.metadata.dbview.Table;
import com._1c.g5.v8.dt.ql.dcs.resource.QlDcsResource;
import com._1c.g5.v8.dt.ql.model.AbstractQuerySchemaTable;
import com._1c.g5.v8.dt.ql.model.AbstractQuerySchemaSource;
import com._1c.g5.v8.dt.ql.model.CommonExpression;
import com._1c.g5.v8.dt.ql.model.MultiPartCommonExpression;
import com._1c.g5.v8.dt.ql.model.QuerySchemaOperator;
import com._1c.g5.v8.dt.ql.model.QuerySchemaTable;
import com._1c.g5.v8.dt.ql.model.QuerySchemaNestedQuery;
import com._1c.g5.v8.dt.ql.model.QuerySchemaTempTableDescription;
import com._1c.g5.v8.dt.ql.model.UnresolvedDbView;
import com._1c.g5.v8.dt.ql.typesystem.IDynamicDbViewFieldComputer;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/** Native QL validation shared by the read tool and query-bearing writers. */
public class QueryValidationService
{
    private static final URI LOOKUP = URI.createURI("/nopr/mcp_validation.qldcs"); //$NON-NLS-1$

    public enum State { VALID, INVALID, PARTIAL }

    /** Dispatch before opening any writer transaction; native QL reads run in a BM read boundary. */
    public Result validate(IProject project, String queryText, boolean dcsMode)
    {
        AtomicReference<Result> result = new AtomicReference<>();
        Runnable work = () ->
        {
            try
            {
                var manager = Activator.getDefault().getBmModelManager();
                IV8Project v8Project = ExtensionOriginUtils.resolveV8Project(project);
                IBmModel model = manager == null || v8Project == null
                    ? null : manager.getModel(v8Project.getDtProject());
                result.set(model == null
                    ? Result.unavailable(dcsMode, "BM model is unavailable for project '" + project.getName()
                        + "'. Check list_projects, wait for project loading and retry.")
                    : BmTransactions.read(model, "ValidateQuery", (tx, monitor) ->
                        validateOnDisplay(project, v8Project, queryText, dcsMode)));
            }
            catch (RuntimeException e)
            {
                result.set(Result.unavailable(dcsMode, "Native query validation failed: "
                    + e.getMessage() + ". Wait for project loading and retry validate_query."));
            }
        };
        try
        {
            dispatch(work);
        }
        catch (RuntimeException e)
        {
            return Result.unavailable(dcsMode, "Could not dispatch native query validation: "
                + e.getMessage() + ". Reopen the EDT workbench and retry.");
        }
        return result.get();
    }

    /** Dispatch precedes the BM read boundary; headless tests substitute only the display. */
    protected void dispatch(Runnable work)
    {
        ReadToolExecution.dispatchOnCurrentOrDefaultDisplay(work);
    }

    private static String validationScope(QlDcsResource resource, IV8Project v8Project)
    {
        String scope = "Project '" + resource.getDtProject().getName() + "'";
        if (v8Project instanceof IExtensionProject extension)
        {
            if (!resource.isParentScopeAware()) return scope + " (extension; parent scope disabled)";
            // EDT's parent-scope flag and parent handle do not certify loaded base metadata.
            IProject base = extension.getParentProject();
            return scope + (base == null ? " (extension; base scope unavailable)"
                : " (extension; base configuration '" + base.getName()
                    + "' requested, availability not verified)");
        }
        return scope;
    }

    private Result validateOnDisplay(IProject project, IV8Project v8Project, String text, boolean dcsMode)
    {
        XtextResource resource = null;
        try
        {
            IResourceServiceProvider provider =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(LOOKUP);
            if (provider == null)
                return Result.unavailable(dcsMode, "QL language support is unavailable. Install the EDT QL plugin.");
            IResourceSetProvider sets = provider.get(IResourceSetProvider.class);
            IResourceValidator validator = provider.get(IResourceValidator.class);
            IDynamicDbViewFieldComputer computer = provider.get(IDynamicDbViewFieldComputer.class);
            if (sets == null || validator == null || computer == null)
                return Result.unavailable(dcsMode, "Native QL services are unavailable. Reopen the project and retry.");
            ResourceSet set = sets.get(project);
            URI uri = URI.createPlatformResourceURI("/" + project.getName()
                + "/mcp_validate_query_" + UUID.randomUUID() + ".qldcs", true);
            resource = (XtextResource)set.createResource(uri);
            if (!(resource instanceof QlDcsResource))
                return Result.unavailable(dcsMode, "EDT did not create a QlDcs resource. Install QL language support.");
            QlDcsResource ql = (QlDcsResource)resource;
            // Bind the own index separately from the parent-scope union, before parsing.
            ql.setDtProject(v8Project.getDtProject());
            ql.setParentScopeAware(true);
            ql.addOptions(QlDcsResource.DCS_VALIDATION_MODE_OPTION, dcsMode);
            ql.setPreComputeAnnounceAlias(dcsMode);
            String scope = validationScope(ql, v8Project);
            try (var input = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))
            {
                resource.load(input, null);
            }
            return new Result(inspect(resource, validator, computer, dcsMode), scope);
        }
        catch (Exception e)
        {
            return Result.unavailable(dcsMode, "Native query validation failed: " + e.getMessage()
                + ". Reopen the project and retry validate_query.");
        }
        finally
        {
            if (resource != null)
            {
                ResourceSet set = resource.getResourceSet();
                resource.unload();
                if (set != null) set.getResources().remove(resource);
            }
        }
    }

    /** Keep parser diagnostics and native validator issues separately observable. */
    static Result inspect(XtextResource resource, IResourceValidator validator,
        IDynamicDbViewFieldComputer computer, boolean dcsMode)
    {
        List<Problem> problems = new ArrayList<>();
        // Validation installs derived state before the source census.
        List<Issue> nativeIssues = validator.validate(resource, CheckMode.ALL, CancelIndicator.NullImpl);
        for (Resource.Diagnostic diagnostic : resource.getErrors())
            problems.add(new Problem("ERROR", diagnostic.getMessage(), diagnostic.getLine(),
                diagnostic.getColumn(), -1, "resource", null, false));
        for (Resource.Diagnostic diagnostic : resource.getWarnings())
            problems.add(new Problem("WARNING", diagnostic.getMessage(), diagnostic.getLine(),
                diagnostic.getColumn(), -1, "resource", null, false));

        List<Source> sources = new ArrayList<>();
        var all = resource.getAllContents();
        while (all.hasNext())
        {
            EObject object = all.next();
            if (object instanceof QuerySchemaTable)
                sources.add(inspectSource((QuerySchemaTable)object, computer));
            else if (object instanceof QuerySchemaTempTableDescription parameter)
                sources.add(new Source(parameter,
                    parameter.getTable() == null ? "<missing parameter>" : "&" + parameter.getTable().getName(),
                    "External table parameter has no statically known field schema.", List.of(), null));
            else if (object instanceof QuerySchemaNestedQuery nested)
            {
                var view = nested.getQuery() == null ? null : nested.getQuery().getDbViewByThis();
                sources.add(snapshotSource(nested, "<subquery> " + nested.getAlias(), view,
                    view == null ? null : new ArrayList<>(view.getFields())));
            }
        }
        for (Issue issue : nativeIssues)
        {
            EObject object = issue.getUriToProblem() == null ? null
                : resource.getEObject(issue.getUriToProblem().fragment());
            boolean incomplete = !issue.isSyntaxError()
                && dependsOnUnresolvedSource(object, issue.getCode(), sources);
            problems.add(new Problem(issue.getSeverity().name(), issue.getMessage(),
                issue.getLineNumber() == null ? -1 : issue.getLineNumber(),
                issue.getColumn() == null ? -1 : issue.getColumn(),
                issue.getOffset() == null ? -1 : issue.getOffset(), "validator", issue.getCode(), incomplete));
        }
        return new Result(dcsMode, problems, sources, null);
    }

    private static Source inspectSource(QuerySchemaTable source, IDynamicDbViewFieldComputer computer)
    {
        AbstractQuerySchemaTable table = source.getTable();
        String name = table == null ? "<missing source>" : table.getFullTableName();
        DbViewElement view = null;
        try
        {
            view = table == null ? null : computer.computeDbView(table);
            if (view == null || view.eIsProxy() || view instanceof UnresolvedDbView || !(view instanceof Table))
                return new Source(source, name, "EDT could not resolve a native table DBView.", List.of(), null);
            List<DbViewElement> fields = computer.computeFieldsBySemanticObject(table);
            return snapshotSource(source, name, view, fields);
        }
        catch (RuntimeException e)
        {
            return new Source(source, name, "Native DBView computation failed: " + e.getMessage(), List.of(),
                view == null ? null : view.eClass().getName());
        }
    }

    private static Source snapshotSource(AbstractQuerySchemaSource source, String name,
        DbViewElement view, List<DbViewElement> fields)
    {
        String kind = view == null ? null : view.eClass().getName();
        if (fields == null || fields.isEmpty())
            return new Source(source, name, "EDT did not compute the available DBView fields.", List.of(), kind);
        List<Map<String, String>> names = new ArrayList<>();
        for (DbViewElement field : fields)
        {
            if (field == null || field.eIsProxy() || field instanceof UnresolvedDbView)
                return new Source(source, name, "EDT returned an unresolved DBView field.", names, kind);
            Map<String, String> pair = new LinkedHashMap<>();
            pair.put("name", field.getName());
            pair.put("nameRu", field.getNameRu());
            names.add(pair);
        }
        return new Source(source, name, null, names, kind);
    }

    /** Only native lookup diagnostics with a proven dependency lose their error verdict. */
    static boolean dependsOnUnresolvedSource(EObject object, String code, List<Source> sources)
    {
        if (!("Table not found".equals(code) || "Field not found".equals(code))) return false;
        for (Source source : sources)
        {
            if (source.reason == null || source.dbView != null) continue;
            if (object instanceof AbstractQuerySchemaTable
                && EcoreUtil2.isAncestor(source.semantic, object)) return true;
            if (!(object instanceof CommonExpression)) continue;
            QuerySchemaOperator operator = EcoreUtil2.getContainerOfType(object, QuerySchemaOperator.class);
            if (operator == null || operator != EcoreUtil2.getContainerOfType(source.semantic, QuerySchemaOperator.class))
                continue;
            CommonExpression expression = (CommonExpression)object;
            if (expression instanceof MultiPartCommonExpression)
            {
                CommonExpression root = expression;
                while (root instanceof MultiPartCommonExpression)
                    root = ((MultiPartCommonExpression)root).getSourceTable();
                String qualifier = root.getContent();
                String tableName = source.semantic instanceof QuerySchemaTable table
                    && table.getTable() != null ? table.getTable().getTableName() : null;
                if (qualifier != null && (qualifier.equalsIgnoreCase(source.semantic.getAlias())
                    || qualifier.equalsIgnoreCase(tableName)
                    || expression.getFullContent().regionMatches(true, 0, source.name + ".", 0,
                        source.name.length() + 1))) return true;
            }
            else
            {
                // An unqualified field may belong to the unresolved source, even in a join.
                return true;
            }
        }
        return false;
    }

    public record Problem(String severity, String message, int line, int column, int offset,
        String origin, String code, boolean incomplete)
    {
        Map<String, Object> wire()
        {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("severity", incomplete ? "WARNING" : severity);
            value.put("message", message);
            value.put("origin", origin);
            if (code != null) value.put("code", code);
            if (line > 0) value.put("line", line);
            if (column > 0) value.put("column", column);
            if (offset >= 0) value.put("offset", offset);
            if (incomplete)
            {
                value.put("validationIncomplete", true);
                value.put("nativeSeverity", severity);
            }
            return value;
        }
    }

    static final class Source
    {
        final AbstractQuerySchemaSource semantic;
        final String name;
        final String reason;
        final List<Map<String, String>> fields;
        final String dbView;

        Source(AbstractQuerySchemaSource semantic, String name, String reason, List<Map<String, String>> fields,
            String dbView)
        {
            this.semantic = semantic;
            this.name = name;
            this.reason = reason;
            this.fields = fields;
            this.dbView = dbView;
        }

        Map<String, Object> wire()
        {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("source", name);
            value.put("resolution", reason == null ? "RESOLVED" : "UNRESOLVED");
            if (reason != null) value.put("reason", reason);
            if (dbView != null) value.put("dbView", dbView);
            value.put("availableFields", fields);
            var node = semantic == null ? null : NodeModelUtils.getNode(semantic);
            if (node != null) value.put("offset", node.getOffset());
            return value;
        }
    }

    public static final class Result
    {
        private final boolean dcsMode;
        private final List<Problem> issues;
        private final List<Map<String, Object>> sources;
        private final List<Map<String, Object>> unresolved;
        private final String failure;
        private final String validationScope;

        private Result(Result result, String validationScope)
        {
            this.dcsMode = result.dcsMode;
            this.issues = result.issues;
            this.sources = result.sources;
            this.unresolved = result.unresolved;
            this.failure = result.failure;
            this.validationScope = validationScope;
        }

        Result(boolean dcsMode, List<Problem> issues, List<Source> sources, String failure)
        {
            this.dcsMode = dcsMode;
            this.issues = List.copyOf(issues);
            this.sources = sources.stream().map(Source::wire).toList();
            this.unresolved = sources.stream().filter(s -> s.reason != null).map(Source::wire).toList();
            this.failure = failure;
            this.validationScope = null;
        }

        static Result unavailable(boolean mode, String reason)
        {
            return new Result(mode, List.of(), List.of(), reason);
        }

        public State state()
        {
            if (issues.stream().anyMatch(i -> !i.incomplete && "ERROR".equals(i.severity))) return State.INVALID;
            return complete() ? State.VALID : State.PARTIAL;
        }

        public boolean complete() { return failure == null && unresolved.isEmpty(); }
        public boolean available() { return failure == null; }

        public Map<String, Object> values()
        {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", state() == State.VALID);
            result.put("validationState", state().name());
            result.put("validationComplete", complete());
            result.put("dcsMode", dcsMode);
            if (validationScope != null) result.put("validationScope", validationScope);
            result.put("errorCount", issues.stream().filter(i -> !i.incomplete && "ERROR".equals(i.severity)).count());
            result.put("warningCount", issues.stream().filter(i -> i.incomplete || "WARNING".equals(i.severity)).count());
            result.put("infoCount", issues.stream().filter(i -> !i.incomplete && "INFO".equals(i.severity)).count());
            result.put("issues", issues.stream().map(Problem::wire).toList());
            result.put("sources", sources);
            result.put("unresolvedSources", unresolved);
            if (failure != null) result.put("incompleteReason", failure);
            return result;
        }

        public String toJson()
        {
            ToolResult result = failure == null ? ToolResult.success() : ToolResult.error(failure);
            values().forEach(result::put);
            return result.toJson();
        }
    }
}
