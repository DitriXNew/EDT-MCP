/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.SearchDependenciesResult;

/**
 * Tool to find all references to a metadata object.
 * Returns all places where the object is used: in other metadata objects and BSL code.
 */
@SuppressWarnings("restriction")
public class FindReferencesTool implements IMcpTool
{
    public static final String NAME = "find_references"; //$NON-NLS-1$

    /** Fixed, shared budget for the pure-query search-scope settle before entering the UI thread. */
    static final long SEARCH_SCOPE_SETTLE_TIMEOUT_MS = 10_000L;

    /** The bounded pre-scan readiness wait; production is {@link ProjectStateChecker#awaitSearchScopeReady}. */
    interface SearchScopeSettler
    {
        /**
         * @return the last snapshot the wait saw; the scan ignores it and captures its own fresh one,
         *     the value exists so the production wiring is observable
         */
        SearchDependenciesResult settle(String projectName, long budgetMs);
    }

    private final SearchScopeSettler searchScopeSettler;
    private final Consumer<Runnable> uiRunner;

    public FindReferencesTool()
    {
        this(ProjectStateChecker::awaitSearchScopeReady, runnable -> {
            Display display = PlatformUI.getWorkbench().getDisplay();
            display.syncExec(runnable);
        });
    }

    FindReferencesTool(SearchScopeSettler searchScopeSettler, Consumer<Runnable> uiRunner)
    {
        this.searchScopeSettler = searchScopeSettler;
        this.uiRunner = uiRunner;
    }

    /** Package-visible so a headless test can prove what the public constructor wired. */
    SearchScopeSettler searchScopeSettler()
    {
        return searchScopeSettler;
    }

    /** Input param: FQN of the metadata object to find references to. */
    private static final String KEY_OBJECT_FQN = "objectFqn"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Discover where a metadata object is used throughout the configuration and BSL code. " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('find_references')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty(KEY_OBJECT_FQN,
                "FQN of the object to search for, e.g. 'Catalog.Products' " + //$NON-NLS-1$
                "(type token may be English or Russian) (required)", true) //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Result-size hint (default 100, max 500); caps the overall reference count " //$NON-NLS-1$
                + "(at limit*10), not per category.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String objectFqn = JsonUtils.extractStringArgument(params, KEY_OBJECT_FQN);
        if (objectFqn != null && !objectFqn.isEmpty())
        {
            String safeName = objectFqn.replace(".", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            return "references-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "references.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Validate required parameters via the shared guard (canonical reference
        // for the broader required-guard migration).
        String missing = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (missing != null)
        {
            return missing;
        }
        missing = JsonUtils.requireArgument(params, KEY_OBJECT_FQN);
        if (missing != null)
        {
            return missing;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String objectFqn = JsonUtils.extractStringArgument(params, KEY_OBJECT_FQN);

        // Shared typed accessor (handles the "42.0" form and invalid/missing -> default),
        // replacing the inline Double.parseDouble. Default 100, upper clamp 500 preserved.
        int limit = Math.min(JsonUtils.extractIntArgument(params, "limit", 100), 500); //$NON-NLS-1$

        // EDT can need the UI thread while settling. The scan captures its own fresh snapshot.
        searchScopeSettler.settle(projectName, SEARCH_SCOPE_SETTLE_TIMEOUT_MS);

        // Execute on UI thread
        AtomicReference<String> resultRef = new AtomicReference<>();
        final int maxResults = limit;

        uiRunner.accept(() -> {
            try
            {
                String result = new MetadataReferenceService().findReferences(projectName, objectFqn, maxResults);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error finding references", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });

        return resultRef.get();
    }
}
