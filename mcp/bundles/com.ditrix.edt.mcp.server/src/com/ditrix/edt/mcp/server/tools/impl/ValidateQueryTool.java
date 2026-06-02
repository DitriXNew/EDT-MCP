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
        return "Validate 1C:Enterprise query language (QL) text in the context of a project. " + //$NON-NLS-1$
               "Parses query text and returns syntax and semantic errors with line numbers. " + //$NON-NLS-1$
               "Supports both regular queries and DCS (Data Composition System) queries."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("queryText", //$NON-NLS-1$
                "Query text to validate (required). The full text of the 1C query, e.g. " + //$NON-NLS-1$
                "'SELECT Ref FROM Catalog.Products WHERE Description LIKE &SearchString'", true) //$NON-NLS-1$
            .booleanProperty("dcsMode", //$NON-NLS-1$
                "DCS (Data Composition System) mode. Set to true for queries used in " + //$NON-NLS-1$
                "data composition schemas. Allows additional DCS-specific syntax. Default: false") //$NON-NLS-1$
            .build();
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
