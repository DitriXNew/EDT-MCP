/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.swt.widgets.Display;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.resource.IResourceSetProvider;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;

import com._1c.g5.v8.dt.ql.dcs.resource.QlDcsResource;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to validate 1C:Enterprise query language (QL) text in the context of a project.
 * Uses Xtext QL infrastructure to parse and validate query text, returning syntax and
 * semantic errors with line numbers and messages.
 */
public class ValidateQueryTool implements IMcpTool
{
    public static final String NAME = "validate_query"; //$NON-NLS-1$
    
    /** URI used to look up the QlDcs language IResourceServiceProvider */
    private static final URI QLDCS_LOOKUP_URI = URI.createURI("/nopr/querywizard_validate.qldcs"); //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Validate 1C:Enterprise query language (QL) text against a project, returning " + //$NON-NLS-1$
               "syntax and semantic errors with line numbers. Use to check a query before " + //$NON-NLS-1$
               "embedding it in BSL; resolves table/field names against the project's metadata. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('validate_query')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty("queryText", //$NON-NLS-1$
                "Full 1C query text to validate (required), e.g. 'SELECT Ref FROM Catalog.Products'.", //$NON-NLS-1$
                true)
            .booleanProperty("dcsMode", //$NON-NLS-1$
                "true for Data Composition System queries (allows DCS-specific syntax). Default: false.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("valid", "true when the query has zero issues") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("dcsMode", "Whether DCS validation mode was used") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("errorCount", "Number of ERROR-severity issues") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("warningCount", "Number of WARNING-severity issues") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("infoCount", "Number of INFO-severity issues") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("issues", //$NON-NLS-1$
                "Validation issues; each has severity, message, optional line/column/offset") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "Validates 1C:Enterprise query language (QL) text in the context of a project, " //$NON-NLS-1$
            + "using EDT's Xtext QL infrastructure. It parses the query and runs full semantic " //$NON-NLS-1$
            + "validation, so it catches both grammar errors and references to tables/fields that " //$NON-NLS-1$
            + "do not exist in the project's metadata. The query is validated only; nothing is " //$NON-NLS-1$
            + "written to the model or executed.\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- Before pasting a query string into a BSL module, to confirm it parses and all " //$NON-NLS-1$
            + "referenced metadata resolves.\n" //$NON-NLS-1$
            + "- To diagnose a query that fails at runtime: the returned line/column points at the " //$NON-NLS-1$
            + "offending token.\n" //$NON-NLS-1$
            + "- For queries that drive a Data Composition Schema (set `dcsMode=true`).\n\n" //$NON-NLS-1$

            + "## Parameter details\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name; the query is resolved against this " //$NON-NLS-1$
            + "project's metadata, so table and field names must exist there.\n" //$NON-NLS-1$
            + "- `queryText` (required) - the complete query text. Query parameters " //$NON-NLS-1$
            + "(`&SearchString`) are allowed and need not be bound. Example: " //$NON-NLS-1$
            + "`SELECT Ref FROM Catalog.Products WHERE Description LIKE &SearchString`.\n" //$NON-NLS-1$
            + "- `dcsMode` - default `false`. Set `true` only for queries used inside a Data " //$NON-NLS-1$
            + "Composition Schema; this enables DCS-specific syntax (e.g. `{...}` braces, dataset " //$NON-NLS-1$
            + "fields) that a plain query would reject, and is echoed back as `dcsMode` in the " //$NON-NLS-1$
            + "result.\n\n" //$NON-NLS-1$

            + "## Bilingual (ru/en) notes\n" //$NON-NLS-1$
            + "The QL parser is dialect-aware: both the English and Russian forms of keywords and " //$NON-NLS-1$
            + "table-type tokens are accepted (e.g. `SELECT ... FROM Catalog.Products` and " //$NON-NLS-1$
            + "`\u0412\u042B\u0411\u0420\u0410\u0422\u042C ... \u0418\u0417 " //$NON-NLS-1$
            + "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A." //$NON-NLS-1$
            + "\u041F\u0440\u043E\u0434\u0443\u043A\u0442\u044B`). Do not assume a single dialect. " //$NON-NLS-1$
            + "Metadata objects are referenced by their programmatic `Name` (not by synonym); only " //$NON-NLS-1$
            + "the type token (Catalog / \u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A) " //$NON-NLS-1$
            + "may be in either language.\n\n" //$NON-NLS-1$

            + "## Result shape\n" //$NON-NLS-1$
            + "JSON with `valid` (true when there are zero issues), `dcsMode`, `errorCount`, " //$NON-NLS-1$
            + "`warningCount`, `infoCount`, and an `issues` array. Each issue has `severity` " //$NON-NLS-1$
            + "(ERROR / WARNING / INFO), `message`, and, when located, `line`, `column` and " //$NON-NLS-1$
            + "`offset` (1-based line/column; non-positive locations are omitted).\n\n" //$NON-NLS-1$

            + "## Examples\n" //$NON-NLS-1$
            + "- Plain query: `{ \"projectName\": \"MyProj\", \"queryText\": " //$NON-NLS-1$
            + "\"SELECT Ref FROM Catalog.Products\" }`.\n" //$NON-NLS-1$
            + "- DCS query: add `\"dcsMode\": true` for a query backing a data composition " //$NON-NLS-1$
            + "schema.\n\n" //$NON-NLS-1$

            + "## Gotchas\n" //$NON-NLS-1$
            + "- `success:true` means the tool ran; check `valid` for whether the query itself is " //$NON-NLS-1$
            + "error-free. A successful run can still report `valid:false` with issues.\n" //$NON-NLS-1$
            + "- A field/table that exists in the platform but not in THIS project resolves as a " //$NON-NLS-1$
            + "semantic error - pass the project that actually owns the metadata.\n" //$NON-NLS-1$
            + "- If QL language support is unavailable the tool returns an error rather than a " //$NON-NLS-1$
            + "validation result.\n"; //$NON-NLS-1$
    }
    
    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String queryText = JsonUtils.extractStringArgument(params, "queryText"); //$NON-NLS-1$
        boolean dcsMode = JsonUtils.extractBooleanArgument(params, "dcsMode", false); //$NON-NLS-1$
        
        String err = JsonUtils.requireArguments(params, "projectName", "queryText"); //$NON-NLS-1$ //$NON-NLS-2$
        if (err != null)
        {
            return err;
        }
        
        // Find the project
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        if (!ctx.isOpen())
        {
            return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
        }

        return validateQuery(ctx.project(), queryText, dcsMode);
    }
    
    /**
     * Validates query text against the project context.
     * Must run on the UI thread for proper Xtext resource access.
     * 
     * @param project the project context
     * @param queryText the query text to validate
     * @param dcsMode whether to use DCS validation mode
     * @return JSON result with validation errors
     */
    private String validateQuery(IProject project, String queryText, boolean dcsMode)
    {
        final AtomicReference<String> resultRef = new AtomicReference<>();
        
        Display display = Display.getDefault();
        if (Display.getCurrent() != null)
        {
            // Already on UI thread
            resultRef.set(doValidateQuery(project, queryText, dcsMode));
        }
        else
        {
            display.syncExec(() -> resultRef.set(doValidateQuery(project, queryText, dcsMode)));
        }
        
        return resultRef.get();
    }
    
    /**
     * Performs the actual validation. Executed on UI thread.
     * 
     * @param project the project context
     * @param queryText the query text to validate
     * @param dcsMode whether to use DCS validation mode
     * @return JSON result with validation errors
     */
    private String doValidateQuery(IProject project, String queryText, boolean dcsMode)
    {
        XtextResource resource = null;
        try
        {
            // Get the QlDcs language resource service provider
            IResourceServiceProvider resourceServiceProvider =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(QLDCS_LOOKUP_URI);
            
            if (resourceServiceProvider == null)
            {
                return ToolResult.error("QlDcs language support not available. " + //$NON-NLS-1$
                    "Please ensure the QL plugin is installed.").toJson(); //$NON-NLS-1$
            }
            
            // Get IResourceSetProvider for project context
            IResourceSetProvider resourceSetProvider =
                resourceServiceProvider.get(IResourceSetProvider.class);
            
            if (resourceSetProvider == null)
            {
                return ToolResult.error("Failed to get resource set provider").toJson(); //$NON-NLS-1$
            }
            
            // Create resource set bound to the project
            ResourceSet resourceSet = resourceSetProvider.get(project);
            
            // Create a unique URI for this validation resource
            URI resourceUri = URI.createPlatformResourceURI(
                "/" + project.getName() + "/mcp_validate_query_" + System.currentTimeMillis() + ".qldcs", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                true);
            
            // Create the Xtext resource
            resource = (XtextResource) resourceSet.createResource(resourceUri);
            
            // Configure QlDcsResource if applicable
            if (resource instanceof QlDcsResource)
            {
                QlDcsResource qlResource = (QlDcsResource) resource;
                qlResource.addOptions("DcsValidationModeOption", dcsMode); //$NON-NLS-1$
                qlResource.setPreComputeAnnounceAlias(dcsMode);
            }
            
            // Load the query text into the resource
            try (InputStream inputStream = new ByteArrayInputStream(
                queryText.getBytes(StandardCharsets.UTF_8)))
            {
                resource.load(inputStream, null);
            }
            
            // Get the resource validator
            IResourceValidator validator = resourceServiceProvider.get(IResourceValidator.class);
            if (validator == null)
            {
                return ToolResult.error("Failed to get resource validator").toJson(); //$NON-NLS-1$
            }
            
            // Collect syntax errors from resource diagnostics
            List<QueryIssue> issues = new ArrayList<>();
            
            for (Resource.Diagnostic error : resource.getErrors())
            {
                issues.add(new QueryIssue(
                    "ERROR", //$NON-NLS-1$
                    error.getMessage(),
                    error.getLine(),
                    error.getColumn(),
                    -1
                ));
            }
            
            for (Resource.Diagnostic warning : resource.getWarnings())
            {
                issues.add(new QueryIssue(
                    "WARNING", //$NON-NLS-1$
                    warning.getMessage(),
                    warning.getLine(),
                    warning.getColumn(),
                    -1
                ));
            }
            
            // Run full validation (semantic checks)
            List<Issue> validationIssues = validator.validate(resource, CheckMode.ALL, CancelIndicator.NullImpl);
            
            for (Issue issue : validationIssues)
            {
                String severity;
                switch (issue.getSeverity())
                {
                    case ERROR:
                        severity = "ERROR"; //$NON-NLS-1$
                        break;
                    case WARNING:
                        severity = "WARNING"; //$NON-NLS-1$
                        break;
                    case INFO:
                        severity = "INFO"; //$NON-NLS-1$
                        break;
                    default:
                        severity = "WARNING"; //$NON-NLS-1$
                        break;
                }
                
                issues.add(new QueryIssue(
                    severity,
                    issue.getMessage(),
                    issue.getLineNumber() != null ? issue.getLineNumber() : -1,
                    issue.getColumn() != null ? issue.getColumn() : -1,
                    issue.getOffset() != null ? issue.getOffset() : -1
                ));
            }
            
            // Build the result
            return buildResult(issues, dcsMode);
        }
        catch (IOException e)
        {
            Activator.logError("Error loading query text into resource", e); //$NON-NLS-1$
            return ToolResult.error("Failed to load query text: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error validating query", e); //$NON-NLS-1$
            return ToolResult.error("Validation error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        finally
        {
            // Clean up the resource
            if (resource != null)
            {
                try
                {
                    ResourceSet resourceSet = resource.getResourceSet();
                    resource.unload();
                    if (resourceSet != null)
                    {
                        resourceSet.getResources().remove(resource);
                    }
                }
                catch (Exception e)
                {
                    Activator.logError("Error cleaning up validation resource", e); //$NON-NLS-1$
                }
            }
        }
    }
    
    /**
     * Builds the JSON result from validation issues, via the shared
     * {@link ToolResult} builder (consistent with the tool's error paths and
     * the rest of the server).
     *
     * @param issues list of validation issues
     * @param dcsMode whether DCS mode was used
     * @return JSON result string
     */
    static String buildResult(List<QueryIssue> issues, boolean dcsMode)
    {
        List<Map<String, Object>> issueList = new ArrayList<>();
        for (QueryIssue issue : issues)
        {
            // LinkedHashMap to keep a stable, readable field order per issue.
            Map<String, Object> issueObj = new LinkedHashMap<>();
            issueObj.put("severity", issue.severity); //$NON-NLS-1$
            issueObj.put("message", issue.message); //$NON-NLS-1$
            if (issue.line > 0)
            {
                issueObj.put("line", issue.line); //$NON-NLS-1$
            }
            if (issue.column > 0)
            {
                issueObj.put("column", issue.column); //$NON-NLS-1$
            }
            if (issue.offset >= 0)
            {
                issueObj.put("offset", issue.offset); //$NON-NLS-1$
            }
            issueList.add(issueObj);
        }

        return ToolResult.success()
            .put("valid", issues.isEmpty()) //$NON-NLS-1$
            .put("dcsMode", dcsMode) //$NON-NLS-1$
            .put("errorCount", //$NON-NLS-1$
                issues.stream().filter(i -> "ERROR".equals(i.severity)).count()) //$NON-NLS-1$
            .put("warningCount", //$NON-NLS-1$
                issues.stream().filter(i -> "WARNING".equals(i.severity)).count()) //$NON-NLS-1$
            .put("infoCount", //$NON-NLS-1$
                issues.stream().filter(i -> "INFO".equals(i.severity)).count()) //$NON-NLS-1$
            .put("issues", issueList) //$NON-NLS-1$
            .toJson();
    }
    
    /**
     * Internal representation of a validation issue.
     */
    static class QueryIssue
    {
        final String severity;
        final String message;
        final int line;
        final int column;
        final int offset;
        
        QueryIssue(String severity, String message, int line, int column, int offset)
        {
            this.severity = severity;
            this.message = message;
            this.line = line;
            this.column = column;
            this.offset = offset;
        }
    }
}
