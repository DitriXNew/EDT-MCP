/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.CommonPictureContentReader;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tool to export a 1C {@code CommonPicture} (общая картинка) as PNG plus its picture-variant
 * inventory. The picture content (both raster variants and SVG) lives inside a {@code Picture.zip}
 * container in the model; this tool enumerates every variant (dpi / theme / interface variant /
 * direction / template flag / glyph size) and, when a {@code variant} is requested, decodes the
 * chosen variant to PNG and returns it as base64 so an AI can see the actual image.
 * <p>
 * The response is JSON (see {@link #getOutputSchema()}): {@code fqn}, a {@code variants} array, and
 * an optional {@code selected} object (present only when a {@code variant} was requested and
 * resolved). Omitting {@code variant} returns the inventory with NO base64 payload.
 * <p>
 * The picture bytes are read through {@link CommonPictureContentReader}, the single class that
 * touches the picture API. The zip-content decode/rasterize approach is informed by the public,
 * Apache-2.0-licensed {@code edt_picture_export} in the edt-bridge project (no source copied).
 */
public class ExportCommonPictureTool implements IMcpTool
{
    public static final String NAME = "export_common_picture"; //$NON-NLS-1$

    /** Input param: the CommonPicture FQN to export (e.g. {@code CommonPicture.МояКартинка}). */
    private static final String KEY_FQN = "fqn"; //$NON-NLS-1$
    /** Input param: which variant to decode to PNG ({@code svg} / {@code best} / an exact entry name). */
    private static final String KEY_VARIANT = "variant"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Export a 1C CommonPicture (общая картинка) as PNG and list its picture variants " + //$NON-NLS-1$
            "(dpi, theme, interface variant, direction, template flag, glyph size). Resolves the " + //$NON-NLS-1$
            "picture by FQN 'CommonPicture.<Name>' (Russian token ОбщаяКартинка accepted). Omit " + //$NON-NLS-1$
            "'variant' to get the inventory only (no image bytes); pass variant='best'/'svg'/an " + //$NON-NLS-1$
            "exact variant name to also get that variant decoded to PNG as base64. SVG variants are " + //$NON-NLS-1$
            "rasterized to PNG. Full parameters and examples: call " + //$NON-NLS-1$
            "get_tool_guide('export_common_picture')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty(KEY_FQN,
                "CommonPicture FQN, e.g. 'CommonPicture.МояКартинка' (Russian type token " + //$NON-NLS-1$
                "ОбщаяКартинка accepted). Required.", //$NON-NLS-1$
                true)
            .stringProperty(KEY_VARIANT,
                "Optional. Which variant to decode to PNG: 'best' (the best raster variant), 'svg' " + //$NON-NLS-1$
                "(the vector variant, rasterized), or an exact variant/entry name from the " + //$NON-NLS-1$
                "'variants' list. Omit it to return the inventory only (no image bytes).") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        // Pure read tool: reads picture content inside a BM read transaction and never mutates the
        // model. Advertise readOnly + idempotent so MCP clients don't gate it behind write-confirmation
        // — the 'export_' name prefix would otherwise classify it as a non-destructive write (that prefix
        // is shared with the file-writing export_configuration_to_xml).
        return new ToolAnnotations(null, Boolean.TRUE, null, Boolean.TRUE, Boolean.FALSE);
    }

    @Override
    public String getOutputSchema()
    {
        // Permissive schema (no additionalProperties:false): 'selected' is present only when a
        // variant was requested and resolved.
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_FQN, "The resolved CommonPicture FQN.") //$NON-NLS-1$
            .objectArrayProperty("variants", //$NON-NLS-1$
                "All picture variants: each has name, dpi, theme, interfaceVariant, pictureDirection, " + //$NON-NLS-1$
                "template (boolean), glyphWidth, glyphHeight, contentType and sizeBytes. Nullable 1C " + //$NON-NLS-1$
                "enums are rendered as stable literals or '-'.") //$NON-NLS-1$
            .objectProperty("selected", //$NON-NLS-1$
                "Present only when 'variant' was requested and resolved: the chosen variant decoded " + //$NON-NLS-1$
                "to PNG - name, contentType ('image/png'), sizeBytes and pngBase64.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Display-free early validation up front so a bad call fails fast (and is unit-testable)
        // before any workspace / BM access. Both guards carry an actionable discovery hint.
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, KEY_FQN);
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String fqn = JsonUtils.extractStringArgument(params, KEY_FQN);
        String variant = JsonUtils.extractStringArgument(params, KEY_VARIANT);

        // The model access already happens inside BmTransactions.read (a read-only BM task, which
        // does not require the UI thread) and the reader's rasterization is pure AWT, so this runs
        // directly on the calling thread - no SWT Display, exactly like ListCommonPicturesTool so
        // the two sibling picture tools access the model identically (unattended-safe: no UI-thread
        // dependency).
        try
        {
            return exportInternal(projectName, fqn, variant);
        }
        catch (Exception e) // NOSONAR any reader/model failure must surface as an actionable tool error
        {
            Activator.logError("Error exporting common picture", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    /**
     * Resolves the project + configuration, then reads the CommonPicture inside a read-only BM
     * transaction and emits the E3 JSON. Runs on the calling thread (no UI-thread requirement).
     */
    private String exportInternal(String projectName, String fqn, String variant)
    {
        ProjectContext.ConfigurationResult resolved = ProjectContext.resolveConfiguration(projectName);
        if (!resolved.ok())
        {
            return resolved.errorJson();
        }
        IProject project = resolved.project();
        Configuration config = resolved.configuration();

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        IBmModel bmModel = bmModelManager != null ? bmModelManager.getModel(project) : null;
        if (bmModel == null)
        {
            return ToolResult.error("BM model is not available for project '" + projectName //$NON-NLS-1$
                + "'. Open the project in EDT first.").toJson(); //$NON-NLS-1$
        }

        // Resolve the CommonPicture and read its content strictly inside a read boundary; the
        // picture-content read navigates the model, so it must not run outside a transaction.
        return BmTransactions.read(bmModel, "ExportCommonPicture", (tx, monitor) -> { //$NON-NLS-1$
            MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, fqn);
            if (node == null || node.object == null)
            {
                return ToolResult.error("Cannot resolve CommonPicture '" + fqn //$NON-NLS-1$
                    + "'. Expected a CommonPicture FQN like 'CommonPicture.<Name>' " //$NON-NLS-1$
                    + "(Russian token ОбщаяКартинка accepted). Use get_metadata_objects " //$NON-NLS-1$
                    + "to find the exact name.").toJson(); //$NON-NLS-1$
            }
            // Guard the resolved type. The resolver already keys off the bilingual CommonPicture
            // type token, so a non-picture object cannot reach here through a CommonPicture FQN; // NOSONAR explanatory comment, not commented-out code
            // this defensive check keeps the error clear if that ever changes.
            String eClassName = node.object.eClass().getName();
            if (!MetadataTypeUtils.MetadataTypeInfo.COMMON_PICTURE.getEnglishSingular()
                .equalsIgnoreCase(eClassName))
            {
                return ToolResult.error("'" + fqn + "' is not a CommonPicture (it resolves to a " //$NON-NLS-1$ //$NON-NLS-2$
                    + eClassName + "). Use a CommonPicture FQN.").toJson(); //$NON-NLS-1$
            }

            try
            {
                CommonPictureContentReader reader = new CommonPictureContentReader();
                List<CommonPictureContentReader.PictureVariantInfo> variants =
                    reader.listVariants(node.object);
                CommonPictureContentReader.PngResult selected = reader.exportPng(node.object, variant);
                // A non-blank variant was requested but did not resolve to a decodable entry, while
                // the picture DOES expose variants: that is an unknown-variant request, not a silent
                // no-op. Fail actionably - name the bad value and the valid entry names so the caller
                // can pick one (the frozen error-shape rule: name the bad value + the fix).
                if (selected == null && variant != null && !variant.trim().isEmpty() && variants != null
                    && !variants.isEmpty())
                {
                    return ToolResult.error("Unknown variant '" + variant.trim() //$NON-NLS-1$
                        + "' for CommonPicture '" + fqn + "'. Available: " + availableNames(variants) //$NON-NLS-1$ //$NON-NLS-2$
                        + " (or the keywords 'best'/'svg').").toJson(); //$NON-NLS-1$
                }
                return buildResultJson(fqn, variants, selected);
            }
            catch (Exception e)
            {
                Activator.logError("Error reading common picture content: " + fqn, e); //$NON-NLS-1$
                return ToolResult.error("Failed to read CommonPicture content for '" + fqn + "': " //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage()).toJson();
            }
        });
    }

    /**
     * Renders the available variant names as a comma-separated, quoted list for an
     * unknown-variant error message (e.g. {@code 'a', 'b'}). Never {@code null}; entries with a
     * {@code null} name are skipped.
     *
     * @param variants the picture's variant inventory (non-{@code null}, non-empty)
     * @return the quoted, comma-separated variant names
     */
    private static String availableNames(List<CommonPictureContentReader.PictureVariantInfo> variants)
    {
        StringBuilder sb = new StringBuilder();
        for (CommonPictureContentReader.PictureVariantInfo v : variants)
        {
            if (v == null || v.name == null)
            {
                continue;
            }
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append('\'').append(v.name).append('\'');
        }
        return sb.toString();
    }

    /**
     * Builds the E3 result JSON: {@code { fqn, variants:[...], selected?:{...} }}. The {@code selected}
     * key is omitted entirely when no variant was requested/resolved (so no base64 is emitted).
     */
    private static String buildResultJson(String fqn,
        List<CommonPictureContentReader.PictureVariantInfo> variants,
        CommonPictureContentReader.PngResult selected)
    {
        JsonObject root = new JsonObject();
        // JSON tools emit the success envelope (matches getOutputSchema + the ToolResult.success contract).
        root.addProperty("success", true); //$NON-NLS-1$
        root.addProperty(KEY_FQN, fqn);

        JsonArray variantsArray = new JsonArray();
        if (variants != null)
        {
            for (CommonPictureContentReader.PictureVariantInfo v : variants)
            {
                JsonObject vo = new JsonObject();
                vo.addProperty("name", v.name); //$NON-NLS-1$
                vo.addProperty("dpi", v.dpi); //$NON-NLS-1$
                vo.addProperty("theme", v.theme); //$NON-NLS-1$
                vo.addProperty("interfaceVariant", v.interfaceVariant); //$NON-NLS-1$
                vo.addProperty("pictureDirection", v.pictureDirection); //$NON-NLS-1$
                vo.addProperty("template", v.template); //$NON-NLS-1$
                vo.addProperty("glyphWidth", v.glyphWidth); //$NON-NLS-1$
                vo.addProperty("glyphHeight", v.glyphHeight); //$NON-NLS-1$
                vo.addProperty("contentType", v.contentType); //$NON-NLS-1$
                vo.addProperty("sizeBytes", v.sizeBytes); //$NON-NLS-1$
                variantsArray.add(vo);
            }
        }
        root.add("variants", variantsArray); //$NON-NLS-1$

        if (selected != null)
        {
            JsonObject sel = new JsonObject();
            sel.addProperty("name", selected.name); //$NON-NLS-1$
            sel.addProperty("contentType", selected.contentType); //$NON-NLS-1$
            sel.addProperty("sizeBytes", selected.sizeBytes); //$NON-NLS-1$
            sel.addProperty("pngBase64", selected.base64); //$NON-NLS-1$
            root.add("selected", sel); //$NON-NLS-1$
        }

        return GsonProvider.toJson(root);
    }
}
