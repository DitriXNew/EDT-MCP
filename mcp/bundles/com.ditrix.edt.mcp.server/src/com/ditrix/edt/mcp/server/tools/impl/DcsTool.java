/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.DynamicListExtInfo;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DcsAddress;
import com.ditrix.edt.mcp.server.utils.DcsDynamicListContent;
import com.ditrix.edt.mcp.server.utils.DcsDynamicListWriter;
import com.ditrix.edt.mcp.server.utils.DcsHash;
import com.ditrix.edt.mcp.server.utils.DcsMutationGuard;
import com.ditrix.edt.mcp.server.utils.DcsPresentationParser;
import com.ditrix.edt.mcp.server.utils.DcsReadProjection;
import com.ditrix.edt.mcp.server.utils.DcsRootReader;
import com.ditrix.edt.mcp.server.utils.DcsSchemaContent;
import com.ditrix.edt.mcp.server.utils.DcsSchemaWriter;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver;
import com.ditrix.edt.mcp.server.utils.DcsWriter;
import com.ditrix.edt.mcp.server.utils.DcsXmlCodec;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormValidationException;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Reads and authors DCS schemas, shared settings, and form dynamic lists. */
public class DcsTool implements IMcpTool
{
    public static final String NAME = "dcs"; //$NON-NLS-1$

    private static final String KEY_FQN = "fqn"; //$NON-NLS-1$
    private static final String KEY_ACTION = "action"; //$NON-NLS-1$
    private static final String KEY_TYPE = "type"; //$NON-NLS-1$
    private static final String KEY_BODY = "body"; //$NON-NLS-1$
    private static final String KEY_EXPECTED_HASH = "expectedHash"; //$NON-NLS-1$
    private static final String KEY_LANGUAGE = "language"; //$NON-NLS-1$
    private static final String KEY_FORMAT = "format"; //$NON-NLS-1$
    private static final String KEY_OFFSET = "offset"; //$NON-NLS-1$

    private static final String FORMAT_MD = "md"; //$NON-NLS-1$
    private static final String FORMAT_XML = "xml"; //$NON-NLS-1$

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
    private static final Set<String> FORMAT_SET = new LinkedHashSet<>(Arrays.asList(FORMAT_MD, FORMAT_XML));

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read, author, and losslessly XML-round-trip 1C DCS schemas, settings variants, " //$NON-NLS-1$
            + "and form dynamic lists. " //$NON-NLS-1$
            + "Call action='get' first; replace, remove and any index-addressed edit require its " //$NON-NLS-1$
            + "hash as expectedHash. Call get_tool_guide('dcs') for body shapes."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name.", true) //$NON-NLS-1$
            .stringProperty(KEY_FQN, "DCS root FQN, optionally followed by an RFC-6901 '#/...' pointer.", true) //$NON-NLS-1$
            .enumProperty(KEY_ACTION, "Operation; replace resets omitted members, remove deletes one node.", //$NON-NLS-1$
                true, ACTIONS)
            .enumProperty(KEY_TYPE, "Target kind; body shapes are in get_tool_guide('dcs').", true, TYPES) //$NON-NLS-1$
            .objectProperty(KEY_BODY, "Mutation body; forbidden for get/remove and required by the other mutations.") //$NON-NLS-1$
            .stringProperty(KEY_EXPECTED_HASH, "Hash from get; conditionally required for mutation actions.") //$NON-NLS-1$
            .stringProperty(KEY_LANGUAGE, "Optional declared configuration language code for presentations.") //$NON-NLS-1$
            .enumProperty(KEY_FORMAT, "Read output; defaults to md. xml is only for a bare schema get.", //$NON-NLS-1$
                false, FORMAT_MD, FORMAT_XML)
            .integerProperty(McpKeys.LIMIT, "Markdown collection page size, or scalar/XML chunk characters.") //$NON-NLS-1$
            .integerProperty(KEY_OFFSET, "Zero-based collection or scalar/XML character offset for get.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public ResponseType getResponseType(Map<String, String> params)
    {
        return FORMAT_XML.equals(JsonUtils.extractStringArgument(params, KEY_FORMAT))
            ? ResponseType.JSON : getResponseType();
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        return new ToolAnnotations(null, Boolean.FALSE, Boolean.TRUE, null, Boolean.FALSE);
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Keep all ten reads explicit: SchemaExecuteParamParityTest checks both directions.
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String rawFqn = JsonUtils.extractStringArgument(params, KEY_FQN);
        String action = JsonUtils.extractStringArgument(params, KEY_ACTION);
        String type = JsonUtils.extractStringArgument(params, KEY_TYPE);
        String body = JsonUtils.extractStringArgument(params, KEY_BODY);
        String expectedHash = JsonUtils.extractStringArgument(params, KEY_EXPECTED_HASH);
        String language = JsonUtils.extractStringArgument(params, KEY_LANGUAGE);
        String rawFormat = JsonUtils.extractStringArgument(params, KEY_FORMAT);
        String format = rawFormat == null ? FORMAT_MD : rawFormat;
        int defaultLimit = FORMAT_XML.equals(format) ? DcsXmlCodec.DEFAULT_CHUNK_CHARS
            : Pagination.DEFAULT_LIMIT;
        int rawLimit = JsonUtils.extractIntArgument(params, McpKeys.LIMIT, defaultLimit);
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
        if (!FORMAT_SET.contains(format))
        {
            return ToolResult.error("Unknown format '" + format + "'. Use 'md' (default) or 'xml'.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
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
        String formatError = validateFormat(format, action, type, parsed.address());
        if (formatError != null)
        {
            return formatError;
        }
        String shapeError = validateActionShape(params, action, type, body, expectedHash, parsed.address());
        if (shapeError != null)
        {
            return shapeError;
        }
        int limit = FORMAT_XML.equals(format) ? Math.max(1, rawLimit)
            : Pagination.clampLimit(rawLimit, Pagination.MAX_LIMIT);
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            return ToolResult.error("EDT workbench display is not available for dcs target '" + rawFqn //$NON-NLS-1$
                + "'. Open the project in EDT and retry action='" + action + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        AtomicReference<String> result = new AtomicReference<>();
        if (ACTION_GET.equals(action))
        {
            display.syncExec(() -> result.set(executeGet(projectName, parsed.address(), type,
                language, format, limit, offset)));
        }
        else if (ACTION_UPSERT.equals(action) || ACTION_UPDATE.equals(action)
            || ACTION_REPLACE.equals(action) || ACTION_REMOVE.equals(action))
        {
            JsonObject parsedBody = ACTION_REMOVE.equals(action) ? null
                : JsonParser.parseString(body).getAsJsonObject();
            display.syncExec(() -> result.set(executeWrite(projectName, parsed.address(), action, type,
                parsedBody, expectedHash, language)));
        }
        else
        {
            // Unreachable: ACTION_SET is validated above, and every member of it is dispatched
            // by one of the branches. Kept so a future action added to ACTIONS without a branch
            // fails loudly instead of silently returning nothing.
            return ToolResult.error("Action '" + action //$NON-NLS-1$
                + "' has no handler. Use one of: " + String.join(", ", ACTION_SET) //$NON-NLS-1$ //$NON-NLS-2$
                + "; no model changes were made.").toJson(); //$NON-NLS-1$
        }
        return result.get();
    }

    private static String validateIntegerArguments(Map<String, String> params, int offset)
    {
        String rawLimit = JsonUtils.extractStringArgument(params, McpKeys.LIMIT);
        if (rawLimit != null && !isInteger(rawLimit))
        {
            return ToolResult.error("limit '" + rawLimit //$NON-NLS-1$
                + "' is not an integer. Pass a whole number; Markdown gets clamp it to 1..1000, " //$NON-NLS-1$
                + "while XML gets use it as the requested character chunk size.").toJson(); //$NON-NLS-1$
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

    private static String validateActionShape(Map<String, String> params, String action, String type, String body,
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
        if (hasBody)
        {
            String xmlBodyError = validateXmlBody(action, type, address,
                JsonParser.parseString(body).getAsJsonObject());
            if (xmlBodyError != null)
            {
                return xmlBodyError;
            }
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

    private static String validateFormat(String format, String action, String type, DcsAddress address)
    {
        if (!FORMAT_XML.equals(format))
        {
            return null;
        }
        if (ACTION_GET.equals(action) && "schema".equals(type) && !address.hasPointer()) //$NON-NLS-1$
        {
            return null;
        }
        return ToolResult.error("format='xml' is not allowed with action='" + action + "', type='" //$NON-NLS-1$ //$NON-NLS-2$
            + type + "', fqn='" + address + "'. Use format='xml' only with action='get', " //$NON-NLS-1$ //$NON-NLS-2$
            + "type='schema', and a bare root FQN without a '#/...' fragment.").toJson(); //$NON-NLS-1$
    }

    private static String validateXmlBody(String action, String type, DcsAddress address, JsonObject body)
    {
        if (!body.has(FORMAT_XML))
        {
            return null;
        }
        if (body.size() != 1)
        {
            Set<String> otherMembers = new LinkedHashSet<>(body.keySet());
            otherMembers.remove(FORMAT_XML);
            return ToolResult.error("body.xml is mutually exclusive with every other schema body member, " //$NON-NLS-1$
                + "but the body also contains: " + String.join(", ", otherMembers) //$NON-NLS-1$
                + ". Pass either body={\"xml\":\"...\"} or the structured schema members, not both.").toJson(); //$NON-NLS-1$
        }
        if (!ACTION_REPLACE.equals(action) || !"schema".equals(type) || address.hasPointer()) //$NON-NLS-1$
        {
            return ToolResult.error("body.xml is not allowed with action='" + action + "', type='" //$NON-NLS-1$ //$NON-NLS-2$
                + type + "', fqn='" + address + "'. Use body.xml only with action='replace', " //$NON-NLS-1$ //$NON-NLS-2$
                + "type='schema', and a bare root FQN without a '#/...' fragment.").toJson(); //$NON-NLS-1$
        }
        JsonElement xml = body.get(FORMAT_XML);
        if (!xml.isJsonPrimitive() || !xml.getAsJsonPrimitive().isString()
            || xml.getAsString().trim().isEmpty())
        {
            return ToolResult.error("body.xml must be a non-empty string containing the complete " //$NON-NLS-1$
                + "DataCompositionSchema XML returned by dcs format='xml'.").toJson(); //$NON-NLS-1$
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
        String language, String format, int limit, int offset)
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
            DcsXmlCodec codec = null;
            if (FORMAT_XML.equals(format))
            {
                if (target.kind() == DcsTargetResolver.TargetKind.DYNAMIC_LIST)
                {
                    return ToolResult.error("format='xml' with type='schema' cannot read dynamic-list root '" //$NON-NLS-1$
                        + target.normalizedRootFqn() + "'. Use format='md' with type='dynamicList'; " //$NON-NLS-1$ //$NON-NLS-2$
                        + "XML is only available for a DataCompositionSchema root.").toJson(); //$NON-NLS-1$
                }
                DcsXmlCodec.ResolveResult codecResult = DcsXmlCodec.resolve(context.project());
                if (!codecResult.isSuccess())
                {
                    return ToolResult.error(codecResult.error()).toJson();
                }
                codec = codecResult.codec();
            }
            DcsXmlCodec resolvedCodec = codec;
            return BmTransactions.executeAndRollback(model, "DcsGet", (tx, monitor) -> //$NON-NLS-1$
            {
                DcsRootReader.Result read = DcsRootReader.read(tx, target);
                if (!read.isSuccess())
                {
                    return ToolResult.error(read.error()).toJson();
                }
                if (FORMAT_XML.equals(format))
                {
                    if (!(read.root() instanceof com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema))
                    {
                        return ToolResult.error("format='xml' requires a non-empty DataCompositionSchema at '" //$NON-NLS-1$
                            + target.normalizedRootFqn() + "'. Create schema content with dcs upsert, " //$NON-NLS-1$ //$NON-NLS-2$
                            + "then retry the bare schema get.").toJson(); //$NON-NLS-1$
                    }
                    DcsXmlCodec.XmlResult xml = resolvedCodec.serialize(
                        (com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema)read.root());
                    if (!xml.isSuccess())
                    {
                        return ToolResult.error(xml.error()).toJson();
                    }
                    String hash = DcsHash.compute(read.root());
                    return DcsXmlCodec.serializePageEnvelope(xml.xml(), hash, offset, limit);
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

    private static String executeWrite(String projectName, DcsAddress address, String action, String type,
        JsonObject body, String expectedHash, String language)
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
                    + "'. Wait for EDT to finish opening the project, then retry action='" //$NON-NLS-1$
                    + action + "'.").toJson(); //$NON-NLS-1$
            }
            DcsTargetResolver.Resolution resolution = DcsTargetResolver.resolveForWrite(context, model, address);
            if (!resolution.isSuccess())
            {
                return ToolResult.error(resolution.failure().message()).toJson();
            }
            DcsTargetResolver.Target target = resolution.target();
            DcsPresentationParser.LanguageContext languages =
                new DcsPresentationParser.LanguageContext(context.scope().declaredLanguageCodes());
            DcsSchemaContent.Services services = DcsSchemaContent.resolveServices(context, model);
            if (!services.isSuccess())
            {
                return ToolResult.error(services.error()).toJson();
            }
            DcsWriter.TypeResolver typeResolver =
                DcsWriter.typeResolver(context.configuration(), services.version());
            if (target.kind() == DcsTargetResolver.TargetKind.DYNAMIC_LIST)
            {
                if (body != null && body.has(FORMAT_XML))
                {
                    return ToolResult.error("body.xml with type='schema' cannot replace dynamic-list root '" //$NON-NLS-1$
                        + target.normalizedRootFqn() + "'. Use a bare DataCompositionSchema root, or " //$NON-NLS-1$ //$NON-NLS-2$
                        + "author this dynamic list with its structured body and type='dynamicList'.").toJson(); //$NON-NLS-1$
                }
                return executeDynamicListWrite(context, target, address, action, type, body,
                    expectedHash, languages, typeResolver, services.version());
            }

            com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema importedSchema = null;
            if (body != null && body.has(FORMAT_XML))
            {
                DcsXmlCodec.ResolveResult codecResult = DcsXmlCodec.resolve(context.project());
                if (!codecResult.isSuccess())
                {
                    return ToolResult.error(codecResult.error()).toJson();
                }
                DcsXmlCodec.SchemaResult decoded = codecResult.codec().deserialize(body.get(FORMAT_XML).getAsString());
                if (!decoded.isSuccess())
                {
                    return ToolResult.error(decoded.error()).toJson();
                }
                importedSchema = decoded.schema();
            }
            com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema detachedImport = importedSchema;
            String schemaMembersError = detachedImport == null && "schema".equals(type) && body != null //$NON-NLS-1$
                ? schemaRootMembersError(body) : null;
            if (schemaMembersError != null)
            {
                return ToolResult.error(schemaMembersError).toJson();
            }
            boolean settingsWrite = detachedImport == null && (DcsSettingsWriter.supports(type)
                || "schema".equals(type) && body != null //$NON-NLS-1$
                    && (DcsSettingsWriter.schemaMembers(body).size() > 0
                        || ACTION_REPLACE.equals(action)));
            JsonObject schemaBody = detachedImport == null && "schema".equals(type) && body != null //$NON-NLS-1$
                ? schemaLayerMembers(body) : body;
            DcsSchemaWriter.PrepareResult prepared = detachedImport != null ? null
                : DcsSettingsWriter.supports(type)
                || "schema".equals(type) && schemaBody != null && schemaBody.size() == 0 //$NON-NLS-1$
                    && !ACTION_REPLACE.equals(action) ? null
                : DcsSchemaWriter.prepare(action, type, address, schemaBody, languages);
            if (prepared != null && !prepared.isSuccess())
            {
                return ToolResult.error(prepared.error()).toJson();
            }

            WriteOutcome outcome;
            try
            {
                outcome = BmTransactions.write(model, "DcsSchemaWrite", (tx, monitor) -> //$NON-NLS-1$
                {
                    DcsRootReader.Result current = DcsRootReader.read(tx, target);
                    if (!current.isSuccess())
                    {
                        throw DcsWriteFailure.message(current.error());
                    }
                    if (current.root() != null
                        && !(current.root() instanceof com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema))
                    {
                        throw DcsWriteFailure.message("DCS root '" + target.normalizedRootFqn() //$NON-NLS-1$
                            + "' is no longer a DataCompositionSchema. Re-run dcs action='get'."); //$NON-NLS-1$
                    }
                    String currentHash = DcsHash.compute(current.root());
                    String hashError = validateExpectedHash(expectedHash, currentHash, address);
                    if (hashError != null)
                    {
                        throw DcsWriteFailure.message(hashError);
                    }

                    DcsSchemaContent.ResolveResult content = DcsSchemaContent.resolve(tx, target, services);
                    if (!content.isSuccess())
                    {
                        throw DcsWriteFailure.message(content.error());
                    }
                    if (detachedImport != null)
                    {
                        DcsXmlCodec.replaceContent(content.schema(), detachedImport);
                        return new WriteOutcome(DcsHash.compute(content.schema()), content.contentFqn(),
                            null, false, true);
                    }
                    DcsSettingsWriter.SchemaResult settings = null;
                    if (settingsWrite)
                    {
                        if (ACTION_REPLACE.equals(action))
                        {
                            String refusal = replaceRefusal(content.schema(),
                                content.schema().getDefaultSettings(), type, address);
                            if (refusal != null) throw DcsWriteFailure.message(refusal);
                        }
                        JsonObject settingsBody = "schema".equals(type) //$NON-NLS-1$
                            ? DcsSettingsWriter.schemaMembers(body) : body;
                        settings = DcsSettingsWriter.planSchema(content.schema(), action, type,
                            address, settingsBody, languages, services.version());
                        if (!settings.isSuccess())
                        {
                            throw DcsWriteFailure.message(settings.error());
                        }
                    }
                    DcsWriter.Result counts = null;
                    if (prepared != null)
                    {
                        DcsSchemaWriter.Result applied =
                            DcsSchemaWriter.apply(content.schema(), prepared.request(), typeResolver);
                        if (!applied.isSuccess())
                        {
                            throw applied.isErrorJson() ? DcsWriteFailure.json(applied.error())
                                : DcsWriteFailure.message(applied.error());
                        }
                        counts = applied.applied();
                    }
                    if (settings != null)
                    {
                        settings.plan().commit(content.schema());
                    }
                    return new WriteOutcome(DcsHash.compute(content.schema()), content.contentFqn(),
                        counts, settingsWrite, false);
                });
            }
            catch (DcsWriteFailure e)
            {
                return e.errorJson;
            }

            List<String> exports = new ArrayList<>(target.forceExportFqns());
            if (outcome.contentFqn != null && !outcome.contentFqn.isEmpty()
                && !exports.contains(outcome.contentFqn))
            {
                exports.add(outcome.contentFqn);
            }
            WriteScope.recordWrite(context.project());
            boolean persisted = !exports.isEmpty()
                && BmTransactions.forceExportToDisk(context.project(), exports);
            if (!persisted)
            {
                return ToolResult.error("DCS action='" + action + "' committed in EDT memory for '" //$NON-NLS-1$ //$NON-NLS-2$
                    + address + "', but force-export could not be scheduled for " + exports //$NON-NLS-1$
                    + ". Save or resync the project before refreshing it, then verify with dcs " //$NON-NLS-1$
                    + "action='get'.").toJson(); //$NON-NLS-1$
            }
            DcsWriter.Result counts = outcome.applied;
            String countsText = outcome.xmlReplaced ? "xml=wholesale" //$NON-NLS-1$
                : counts == null ? "none" //$NON-NLS-1$
                : "dataSources=" + counts.dataSources + ", dataSets=" + counts.dataSets //$NON-NLS-1$ //$NON-NLS-2$
                    + ", fields=" + counts.fields + ", parameters=" + counts.parameters //$NON-NLS-1$ //$NON-NLS-2$
                    + ", calculatedFields=" + counts.calculatedFields + ", totalFields=" //$NON-NLS-1$ //$NON-NLS-2$
                    + counts.totalFields;
            return "**Action:** `" + action + "`\n\n**Target:** `" + address //$NON-NLS-1$ //$NON-NLS-2$
                + "`\n\n**Hash:** `" + outcome.hash + "`\n\n**Export scheduled:** `true`" //$NON-NLS-1$ //$NON-NLS-2$
                + "\n\n**Settings applied:** `" + outcome.settingsApplied + "`" //$NON-NLS-1$ //$NON-NLS-2$
                + "\n\n**Schema applied:** " + countsText //$NON-NLS-1$
                + localeUnusedNote(context, languages);
        }
        catch (RuntimeException e)
        {
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Activator.logError("Error writing DCS target " + address, e); //$NON-NLS-1$
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.error("Could not write DCS target '" + address + "': " + message //$NON-NLS-1$ //$NON-NLS-2$
                + ". Re-open or clean the project, run dcs action='get', then retry.").toJson(); //$NON-NLS-1$
        }
    }

    private static String executeDynamicListWrite(ProjectContext.ConfigurationResult context,
        DcsTargetResolver.Target target, DcsAddress address, String action, String type, JsonObject body,
        String expectedHash, DcsPresentationParser.LanguageContext languages,
        DcsWriter.TypeResolver typeResolver, com._1c.g5.v8.dt.platform.version.Version version)
    {
        FormElementWriter.FormMemberRef ref = target.formMemberRef();
        FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(context.project(),
            context.scope(), ref.formPath, "Form for dynamic-list target '" //$NON-NLS-1$
                + target.normalizedRootFqn() + "' was not found. Verify the form FQN and retry."); //$NON-NLS-1$

        String verdict = FormElementWriter.readEditableForm(fctx, "DcsDynamicListPreflight", //$NON-NLS-1$
            (formModel, tx) ->
            {
                EObject member = FormElementWriter.resolveFormMember(formModel, ref);
                if (member == null)
                {
                    return ToolResult.error("Form attribute '" + target.normalizedRootFqn() //$NON-NLS-1$
                        + "' was not found. Re-run dcs action='get' and copy the current FQN.").toJson(); //$NON-NLS-1$
                }
                DynamicListExtInfo extInfo = dynamicListExtInfo(member);
                DcsDynamicListWriter.Result planned = DcsDynamicListWriter.plan(extInfo, action,
                    type, address, body, typeResolver, languages, version);
                if (!planned.isSuccess())
                {
                    return dynamicPlanError(planned);
                }
                if (extInfo == null && !planned.plan().canConvertPlainAttribute())
                {
                    return ToolResult.error("Form attribute '" + target.normalizedRootFqn() //$NON-NLS-1$
                        + "' is not a dynamic list. Include a non-empty 'queryText' or 'mainTable' " //$NON-NLS-1$
                        + "in a type='dynamicList' body to request the guarded conversion.").toJson(); //$NON-NLS-1$
                }
                if (!planned.plan().canConvertPlainAttribute())
                {
                    return ""; //$NON-NLS-1$
                }
                return ModifyMetadataTool.dynamicListRetypeVerdict(context.configuration(), version,
                    formModel, member, planned.plan().mainTable());
            });
        if (verdict != null && !verdict.isEmpty())
        {
            return verdict;
        }
        if (verdict == null)
        {
            ConsentPreview preview = new ConsentPreview(
                "Convert a form attribute into a dynamic list", //$NON-NLS-1$
                "This replaces the attribute's data type with DynamicList. Any value the form held " //$NON-NLS-1$
                    + "through it is dropped on the next database update.", //$NON-NLS-1$
                1, List.of(target.normalizedRootFqn()));
            ConsentDecision decision = DestructiveConsentGate.getInstance().requireConsent(NAME, preview);
            if (decision != ConsentDecision.ALLOW)
            {
                return ToolResult.error(
                    DestructiveConsentGate.consentDeniedMessage(decision, NAME)).toJson();
            }
        }

        AtomicReference<DynamicWriteOutcome> outcome = new AtomicReference<>();
        boolean formPersisted = FormElementWriter.writeEditableForm(fctx, "DcsDynamicListWrite", //$NON-NLS-1$
            (formModel, tx) ->
            {
                EObject member = FormElementWriter.resolveFormMember(formModel, ref);
                if (member == null)
                {
                    throw new FormValidationException(ToolResult.error("Form attribute '" //$NON-NLS-1$
                        + target.normalizedRootFqn()
                        + "' disappeared before the write. Re-run dcs action='get'.").toJson()); //$NON-NLS-1$
                }
                DynamicListExtInfo extInfo = dynamicListExtInfo(member);
                String currentHash = DcsHash.compute(extInfo);
                String hashError = validateExpectedHash(expectedHash, currentHash, address);
                if (hashError != null)
                {
                    throw new FormValidationException(ToolResult.error(hashError).toJson());
                }
                // The same refusal the schema path applies, now that replace actually reaches the
                // settings writer here: an authoritative replacement must not silently discard
                // content this writer cannot reproduce (a chart, a nested schema, an area template)
                // that still lives under the target. A dynamic list's listSettings is the same
                // settings model a report variant uses, so it can hold exactly those subtypes.
                if (ACTION_REPLACE.equals(action))
                {
                    String refusal = replaceRefusal(extInfo,
                        extInfo == null ? null : extInfo.getListSettings(), type, address);
                    if (refusal != null)
                    {
                        throw new FormValidationException(ToolResult.error(refusal).toJson());
                    }
                }
                DcsDynamicListWriter.Result planned = DcsDynamicListWriter.plan(extInfo, action,
                    type, address, body, typeResolver, languages, version);
                if (!planned.isSuccess())
                {
                    throw new FormValidationException(dynamicPlanError(planned));
                }
                String mainTableError = FormElementWriter.mainTableResolutionError(
                    context.configuration(), planned.plan().mainTable());
                if (mainTableError != null)
                {
                    throw new FormValidationException(mainTableError);
                }
                if (extInfo == null && planned.plan().canConvertPlainAttribute())
                {
                    String recheck = ModifyMetadataTool.dynamicListRetypeVerdict(
                        context.configuration(), version, formModel, member,
                        planned.plan().mainTable());
                    if (recheck != null && !recheck.isEmpty())
                    {
                        throw new FormValidationException(recheck);
                    }
                }
                List<String> applied = planned.plan().commit(formModel, member, extInfo,
                    context.configuration(), version);
                DynamicListExtInfo effective = dynamicListExtInfo(member);
                String settingsFqn = null;
                if (planned.plan().settingsTouched())
                {
                    DcsDynamicListContent.Result settings =
                        DcsDynamicListContent.ensureAttached(tx, effective);
                    if (!settings.isSuccess())
                    {
                        throw new FormValidationException(ToolResult.error(settings.error()).toJson());
                    }
                    settingsFqn = settings.fqn();
                }
                outcome.set(new DynamicWriteOutcome(DcsHash.compute(effective), settingsFqn,
                    applied));
            });
        DynamicWriteOutcome written = outcome.get();
        boolean settingsPersisted = written != null && (written.settingsFqn == null
            || BmTransactions.forceExportToDisk(context.project(), written.settingsFqn));
        if (!formPersisted || !settingsPersisted)
        {
            return ToolResult.error("DCS action='" + action + "' committed in EDT memory for '" //$NON-NLS-1$ //$NON-NLS-2$
                + address + "', but force-export could not be scheduled for " //$NON-NLS-1$
                + (!formPersisted ? "Form.form" : written.settingsFqn) //$NON-NLS-1$
                + ". Save or resync the project, then verify with dcs action='get'.").toJson(); //$NON-NLS-1$
        }
        return "**Action:** `" + action + "`\n\n**Target:** `" + address //$NON-NLS-1$ //$NON-NLS-2$
            + "`\n\n**Hash:** `" + written.hash + "`\n\n**Form.form export scheduled:** `true`" //$NON-NLS-1$ //$NON-NLS-2$
            + "\n\n**ListSettings.dcss export scheduled:** `" //$NON-NLS-1$
            + (written.settingsFqn != null) + "`\n\n**Applied:** " //$NON-NLS-1$
            + (written.applied.isEmpty() ? "none" : String.join(", ", written.applied)) //$NON-NLS-1$ //$NON-NLS-2$
            + localeUnusedNote(context, languages);
    }

    /**
     * The "you just wrote into a language nothing else uses" prompt, carrying the same rule as the
     * {@code localeUnusedInConfiguration} flag {@code create_metadata} / {@code modify_metadata}
     * return - this tool answers in Markdown, so it says it in prose instead of a JSON field.
     * <p>
     * NOT an error: the language IS declared, so the text will display. It is a prompt to ASK the
     * user, because the configuration's own synonym has no text in that language - it may be a
     * single-language build, or a language this configuration does not support yet. A payload
     * writes many titles at once, so this reports the languages rather than a per-property list.
     *
     * @param context the resolved project/configuration
     * @param languages the parse context, whose used codes are the ones this call actually wrote
     * @return the Markdown note, or an empty string when every written language is in use
     */
    private static String localeUnusedNote(ProjectContext.ConfigurationResult context,
        DcsPresentationParser.LanguageContext languages)
    {
        if (context == null || languages == null)
        {
            return ""; //$NON-NLS-1$
        }
        List<String> unused = new ArrayList<>();
        for (String code : languages.usedCodes())
        {
            if (MetadataLanguageUtils.isDeclaredButUnused(context.configuration(), code))
            {
                unused.add(code);
            }
        }
        if (unused.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        Collections.sort(unused);
        return "\n\n**localeUnusedInConfiguration:** `true` (" + String.join(", ", unused) //$NON-NLS-1$ //$NON-NLS-2$
            + "). The text will display - the language is declared - but the configuration's own " //$NON-NLS-1$
            + "synonym has no text in it. Ask the user before translating further."; //$NON-NLS-1$
    }

    private static DynamicListExtInfo dynamicListExtInfo(EObject member)
    {
        if (member == null || member.eClass().getEStructuralFeature("extInfo") == null) //$NON-NLS-1$
        {
            return null;
        }
        Object value = member.eGet(member.eClass().getEStructuralFeature("extInfo")); //$NON-NLS-1$
        return value instanceof DynamicListExtInfo ? (DynamicListExtInfo)value : null;
    }

    private static String dynamicPlanError(DcsDynamicListWriter.Result result)
    {
        return result.isErrorJson() ? result.error() : ToolResult.error(result.error()).toJson();
    }

    static String expectedHashError(String expected, String current, DcsAddress address)
    {
        return "expectedHash '" + expected + "' does not match current hash '" + current //$NON-NLS-1$ //$NON-NLS-2$
            + "' for '" + address + "'. Re-run dcs action='get' and pass the new expectedHash."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    static String validateExpectedHash(String expected, String current, DcsAddress address)
    {
        return expected == null || expected.equals(current) ? null
            : expectedHashError(expected, current, address);
    }

    /**
     * The unmodellable-content refusal for a replace, scoped to the node the planner will actually
     * rewrite.
     *
     * <p>A concrete settings type addressed at a BARE root (action='replace', type='selection')
     * resolves to that type's default settings path, but the address itself still carries no
     * pointer - so checking it against the whole document treated every unmodellable node anywhere
     * as a descendant, and a chart in an unrelated variant or data set blocked a selection-only
     * replacement it could not have removed. Scope to the settings root and the type's own default
     * path in that case; an explicitly pointered address already says what it means.</p>
     *
     * @param whole the whole schema or ext-info, used when the address carries a pointer
     * @param settingsRoot the settings the bare-root form resolves into, possibly {@code null}
     * @param type the requested type token
     * @param address the caller's address
     * @return the refusal message, or {@code null} when nothing blocks the replacement
     */
    private static String replaceRefusal(EObject whole, EObject settingsRoot, String type,
        DcsAddress address)
    {
        if (!address.hasPointer() && settingsRoot != null)
        {
            List<String> scoped = DcsSettingsWriter.defaultSettingsPath(type);
            if (!scoped.isEmpty())
            {
                DcsAddress.ParseResult parsed =
                    DcsAddress.parse(DcsAddress.render(address.rootFqn(), scoped));
                if (parsed.isSuccess())
                {
                    return DcsMutationGuard.replaceError(settingsRoot, parsed.address());
                }
            }
        }
        return DcsMutationGuard.replaceError(whole, address);
    }

    private static JsonObject schemaLayerMembers(JsonObject body)
    {
        JsonObject result = new JsonObject();
        // dataSetLinks belongs here: a schema-root replace CLEARS the links
        // (DcsSchemaWriter.clearReplaceTarget), and DcsWriter can author them, so omitting the
        // member from the forwarded body made every accepted root replacement destroy every join
        // with no way for the caller to restate it.
        for (String member : List.of("dataSources", "dataSets", "dataSetLinks", "parameters", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "calculatedFields", "totalFields")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            if (body.has(member)) result.add(member, body.get(member).deepCopy());
        }
        return result;
    }

    private static String schemaRootMembersError(JsonObject body)
    {
        Set<String> allowed = new LinkedHashSet<>(List.of("dataSources", "dataSets", "dataSetLinks", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "parameters", "calculatedFields", "totalFields", "defaultSettings", "variants")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        for (String member : body.keySet())
        {
            if (!allowed.contains(member))
            {
                return "Unknown schema body member '" + member + "'. Use one of: " //$NON-NLS-1$ //$NON-NLS-2$
                    + String.join(", ", allowed) + "."; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return null;
    }

    private static final class WriteOutcome
    {
        final String hash;
        final String contentFqn;
        final DcsWriter.Result applied;
        final boolean settingsApplied;
        final boolean xmlReplaced;

        WriteOutcome(String hash, String contentFqn, DcsWriter.Result applied,
            boolean settingsApplied, boolean xmlReplaced)
        {
            this.hash = hash;
            this.contentFqn = contentFqn;
            this.applied = applied;
            this.settingsApplied = settingsApplied;
            this.xmlReplaced = xmlReplaced;
        }
    }

    private static final class DynamicWriteOutcome
    {
        final String hash;
        final String settingsFqn;
        final List<String> applied;

        DynamicWriteOutcome(String hash, String settingsFqn, List<String> applied)
        {
            this.hash = hash;
            this.settingsFqn = settingsFqn;
            this.applied = applied;
        }
    }

    /** Runtime failure forces the BM write transaction to roll back. */
    private static final class DcsWriteFailure extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        final String errorJson;

        private DcsWriteFailure(String errorJson)
        {
            super(errorJson);
            this.errorJson = errorJson;
        }

        static DcsWriteFailure message(String message)
        {
            return new DcsWriteFailure(ToolResult.error(message).toJson());
        }

        static DcsWriteFailure json(String errorJson)
        {
            return new DcsWriteFailure(errorJson);
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
