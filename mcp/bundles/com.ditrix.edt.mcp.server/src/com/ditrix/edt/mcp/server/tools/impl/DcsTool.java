/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.DcsAddress;
import com.ditrix.edt.mcp.server.utils.DcsHash;
import com.ditrix.edt.mcp.server.utils.DcsReadProjection;
import com.ditrix.edt.mcp.server.utils.DcsRootReader;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/** Reads and, in later stages, mutates DCS schemas and form dynamic lists. */
public class DcsTool implements IMcpTool
{
    public static final String NAME = "dcs"; //$NON-NLS-1$

    private static final String KEY_FQN = "fqn"; //$NON-NLS-1$
    private static final String KEY_ACTION = "action"; //$NON-NLS-1$
    private static final String KEY_TYPE = "type"; //$NON-NLS-1$
    private static final String KEY_BODY = "body"; //$NON-NLS-1$
    private static final String KEY_EXPECTED_HASH = "expectedHash"; //$NON-NLS-1$
    private static final String KEY_LANGUAGE = "language"; //$NON-NLS-1$
    private static final String KEY_OFFSET = "offset"; //$NON-NLS-1$

    private static final String ACTION_GET = "get"; //$NON-NLS-1$
    private static final String ACTION_UPSERT = "upsert"; //$NON-NLS-1$
    private static final String ACTION_UPDATE = "update"; //$NON-NLS-1$
    private static final String ACTION_REPLACE = "replace"; //$NON-NLS-1$
    private static final String ACTION_REMOVE = "remove"; //$NON-NLS-1$

    private static final String[] ACTIONS = {
        ACTION_GET, ACTION_UPSERT, ACTION_UPDATE, ACTION_REPLACE, ACTION_REMOVE
    };

    private static final String[] TYPES = {
        "schema", "dynamicList", //$NON-NLS-1$ //$NON-NLS-2$
        "dataSource", "dataSet", "field", "parameter", "calculatedField", "totalField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "variant", "grouping", "selection", "filter", "dataParameter", "order", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "conditionalAppearance", "table", "userField", "outputParameter", "userSettings" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    };

    private static final Set<String> ACTION_SET = new LinkedHashSet<>(Arrays.asList(ACTIONS));
    private static final Set<String> TYPE_SET = new LinkedHashSet<>(Arrays.asList(TYPES));

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Inspect 1C DCS schemas and form dynamic lists; mutation actions are reserved but not " //$NON-NLS-1$
            + "implemented yet. Call action='get' first, pass its hash as expectedHash for " //$NON-NLS-1$
            + "index-addressed mutations, and call get_tool_guide('dcs') for body shapes."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name.", true) //$NON-NLS-1$
            .stringProperty(KEY_FQN, "DCS root FQN, optionally followed by an RFC-6901 '#/...' pointer.", true) //$NON-NLS-1$
            .enumProperty(KEY_ACTION, "Operation; this stage implements get only.", true, ACTIONS) //$NON-NLS-1$
            .enumProperty(KEY_TYPE, "Target kind; body shapes are in get_tool_guide('dcs').", true, TYPES) //$NON-NLS-1$
            .objectProperty(KEY_BODY, "Mutation body; forbidden for get/remove and required by the other mutations.") //$NON-NLS-1$
            .stringProperty(KEY_EXPECTED_HASH, "Hash from get; conditionally required for mutation actions.") //$NON-NLS-1$
            .stringProperty(KEY_LANGUAGE, "Optional declared configuration language code for presentations.") //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT, "Collection page size; clamped to 1..1000 for get.") //$NON-NLS-1$
            .integerProperty(KEY_OFFSET, "Zero-based collection offset for get.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        return new ToolAnnotations(null, Boolean.FALSE, Boolean.TRUE, null, Boolean.FALSE);
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Keep all nine reads explicit: SchemaExecuteParamParityTest checks both directions.
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String rawFqn = JsonUtils.extractStringArgument(params, KEY_FQN);
        String action = JsonUtils.extractStringArgument(params, KEY_ACTION);
        String type = JsonUtils.extractStringArgument(params, KEY_TYPE);
        String body = JsonUtils.extractStringArgument(params, KEY_BODY);
        String expectedHash = JsonUtils.extractStringArgument(params, KEY_EXPECTED_HASH);
        String language = JsonUtils.extractStringArgument(params, KEY_LANGUAGE);
        int rawLimit = JsonUtils.extractIntArgument(params, McpKeys.LIMIT, Pagination.DEFAULT_LIMIT);
        int offset = JsonUtils.extractIntArgument(params, KEY_OFFSET, 0);

        String required = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_FQN,
            KEY_ACTION, KEY_TYPE);
        if (required != null)
        {
            return required;
        }
        if (!ACTION_SET.contains(action))
        {
            return ToolResult.error("Unknown action '" + action + "'. Use one of: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", ACTION_SET) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!TYPE_SET.contains(type))
        {
            return ToolResult.error("Unknown type '" + type + "'. Use one of: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", TYPE_SET) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String integerError = validateIntegerArguments(params, offset);
        if (integerError != null)
        {
            return integerError;
        }
        if (language != null && !language.equals(language.trim()))
        {
            return ToolResult.error("language '" + language //$NON-NLS-1$
                + "' contains leading or trailing whitespace. Pass the exact declared language code.").toJson(); //$NON-NLS-1$
        }

        DcsAddress.ParseResult parsed = DcsAddress.parse(rawFqn);
        if (!parsed.isSuccess())
        {
            return ToolResult.error(parsed.failure().message()).toJson();
        }
        String shapeError = validateActionShape(params, action, body, expectedHash, parsed.address());
        if (shapeError != null)
        {
            return shapeError;
        }
        if (!ACTION_GET.equals(action))
        {
            return ToolResult.error("Action '" + action //$NON-NLS-1$
                + "' is not implemented yet. This Stage 1b tool supports action='get'; no model changes were made.") //$NON-NLS-1$
                .toJson();
        }

        int limit = Pagination.clampLimit(rawLimit, Pagination.MAX_LIMIT);
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            return ToolResult.error("EDT workbench display is not available for dcs target '" + rawFqn //$NON-NLS-1$
                + "'. Open the project in EDT and retry action='get'.").toJson(); //$NON-NLS-1$
        }
        AtomicReference<String> result = new AtomicReference<>();
        display.syncExec(() -> result.set(executeGet(projectName, parsed.address(), type,
            language, limit, offset)));
        return result.get();
    }

    private static String validateIntegerArguments(Map<String, String> params, int offset)
    {
        String rawLimit = JsonUtils.extractStringArgument(params, McpKeys.LIMIT);
        if (rawLimit != null && !isInteger(rawLimit))
        {
            return ToolResult.error("limit '" + rawLimit //$NON-NLS-1$
                + "' is not an integer. Pass a whole number; get clamps it to 1..1000.").toJson(); //$NON-NLS-1$
        }
        String rawOffset = JsonUtils.extractStringArgument(params, KEY_OFFSET);
        if (rawOffset != null && !isInteger(rawOffset))
        {
            return ToolResult.error("offset '" + rawOffset //$NON-NLS-1$
                + "' is not an integer. Pass a zero-based whole-number offset.").toJson(); //$NON-NLS-1$
        }
        if (offset < 0)
        {
            return ToolResult.error("offset '" + offset //$NON-NLS-1$
                + "' is negative. Pass offset=0 or a later zero-based collection offset.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    private static boolean isInteger(String raw)
    {
        try
        {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) && value == Math.floor(value)
                && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }

    private static String validateActionShape(Map<String, String> params, String action, String body,
        String expectedHash, DcsAddress address)
    {
        boolean hasBody = params.containsKey(KEY_BODY);
        boolean hasHash = params.containsKey(KEY_EXPECTED_HASH);
        boolean hasLimit = params.containsKey(McpKeys.LIMIT);
        boolean hasOffset = params.containsKey(KEY_OFFSET);
        if (hasBody && (body == null || !isJsonObject(body)))
        {
            return ToolResult.error("body '" + body //$NON-NLS-1$
                + "' is not a JSON object. Pass one object matching the selected type's guide shape.").toJson(); //$NON-NLS-1$
        }
        if (hasHash && (expectedHash == null || !expectedHash.matches("[0-9a-f]{20}"))) //$NON-NLS-1$
        {
            return ToolResult.error("expectedHash '" + expectedHash //$NON-NLS-1$
                + "' is invalid. Re-run dcs action='get' and copy its 20-character lowercase hash.").toJson(); //$NON-NLS-1$
        }
        if (ACTION_GET.equals(action))
        {
            if (hasBody)
            {
                return ToolResult.error("body is not allowed for action='get'. Omit body and use fqn/type to select the read target.").toJson(); //$NON-NLS-1$
            }
            if (hasHash)
            {
                return ToolResult.error("expectedHash is not accepted by action='get'. Omit it; get returns the current hash.").toJson(); //$NON-NLS-1$
            }
            return null;
        }
        if (hasLimit || hasOffset)
        {
            return ToolResult.error("limit/offset apply only to action='get'. Omit them from action='" //$NON-NLS-1$
                + action + "'.").toJson(); //$NON-NLS-1$
        }
        if (ACTION_REMOVE.equals(action))
        {
            if (hasBody)
            {
                return ToolResult.error("body is not allowed for action='remove'. Omit body and address exactly one node with fqn '#/...'.").toJson(); //$NON-NLS-1$
            }
            if (!address.hasPointer())
            {
                return ToolResult.error("action='remove' refuses bare root '" + address //$NON-NLS-1$
                    + "'. Append the exact '#/...' node pointer returned by get.").toJson(); //$NON-NLS-1$
            }
        }
        else if (!hasBody)
        {
            return ToolResult.error("body is required for action='" + action //$NON-NLS-1$
                + "'. Pass one object matching type='" + JsonUtils.extractStringArgument(params, KEY_TYPE) //$NON-NLS-1$
                + "' from get_tool_guide('dcs').").toJson(); //$NON-NLS-1$
        }
        boolean hashRequired = ACTION_REPLACE.equals(action) || ACTION_REMOVE.equals(action)
            || address.isIndexAddressed();
        if (hashRequired && !hasHash)
        {
            return ToolResult.error("expectedHash is required for action='" + action //$NON-NLS-1$
                + "' at '" + address + "'. Re-run dcs action='get' and pass its current hash.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    private static boolean isJsonObject(String body)
    {
        try
        {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject();
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private static String executeGet(String projectName, DcsAddress address, String type,
        String language, int limit, int offset)
    {
        try
        {
            ProjectContext.ConfigurationResult context = ProjectContext.resolveMetadataRoot(projectName);
            if (!context.ok())
            {
                return context.errorJson();
            }
            String effectiveLanguage = resolveLanguage(context, language);
            if (effectiveLanguage != null && effectiveLanguage.startsWith("ERROR:")) //$NON-NLS-1$
            {
                return ToolResult.error(effectiveLanguage.substring("ERROR:".length())).toJson(); //$NON-NLS-1$
            }
            IBmModelManager manager = Activator.getDefault().getBmModelManager();
            IBmModel model = manager == null ? null : manager.getModel(context.project());
            if (model == null)
            {
                return ToolResult.error("BM model is not available for project '" + projectName //$NON-NLS-1$
                    + "'. Wait for EDT to finish opening the project, then retry dcs action='get'.").toJson(); //$NON-NLS-1$
            }
            DcsTargetResolver.Resolution resolution = DcsTargetResolver.resolve(context, model, address);
            if (!resolution.isSuccess())
            {
                return ToolResult.error(resolution.failure().message()).toJson();
            }
            DcsTargetResolver.Target target = resolution.target();
            return BmTransactions.executeAndRollback(model, "DcsGet", (tx, monitor) -> //$NON-NLS-1$
            {
                DcsRootReader.Result read = DcsRootReader.read(tx, target);
                if (!read.isSuccess())
                {
                    return ToolResult.error(read.error()).toJson();
                }
                String hash = DcsHash.compute(read.root());
                DcsReadProjection.Result projection = DcsReadProjection.render(
                    target.normalizedRootFqn(), target.kind(), read.root(), address, type,
                    effectiveLanguage, limit, offset);
                if (!projection.isSuccess())
                {
                    return ToolResult.error(projection.error()).toJson();
                }
                return "**Hash:** `" + hash + "`\n\n" + projection.markdown(); //$NON-NLS-1$ //$NON-NLS-2$
            });
        }
        catch (RuntimeException e)
        {
            Activator.logError("Error reading DCS target " + address, e); //$NON-NLS-1$
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.error("Could not read DCS target '" + address + "': " + message //$NON-NLS-1$ //$NON-NLS-2$
                + ". Re-open or clean the project, then retry action='get'.").toJson(); //$NON-NLS-1$
        }
    }

    private static String resolveLanguage(ProjectContext.ConfigurationResult context, String requested)
    {
        List<String> declared = context.scope().declaredLanguageCodes();
        if (requested != null && !requested.isEmpty() && !declared.isEmpty())
        {
            for (String code : declared)
            {
                if (code.equalsIgnoreCase(requested))
                {
                    return code;
                }
            }
            return "ERROR:Unknown language '" + requested + "'. This project declares: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", declared) //$NON-NLS-1$
                + ". Pass one of those codes, or omit language to use the default."; //$NON-NLS-1$
        }
        return context.scope().resolveLanguageCode(requested);
    }
}
