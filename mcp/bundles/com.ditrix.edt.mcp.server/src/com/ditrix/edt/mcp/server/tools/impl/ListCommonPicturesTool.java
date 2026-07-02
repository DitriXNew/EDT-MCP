/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EMap;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.CommonPictureContentReader;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to list a 1C configuration's CommonPicture objects together with the raster/vector
 * variants each one carries in its {@code Picture.zip} manifest (DPI bucket, theme, interface
 * variant, template flag, glyph size, picture direction, byte size).
 * <p>
 * This is the metadata-only, no-image companion of {@code export_common_picture}: it renders a
 * Markdown overview so the caller can see, at a glance, which pictures exist and how their
 * variants are shaped - the quick way to spot a mixed-icon mistake (e.g. one picture that only
 * ships a legacy 8.2 raster while its siblings ship an 8.5 vector template). No image bytes are
 * emitted; use {@code export_common_picture} for the PNG of a single picture.
 * <p>
 * The Picture.zip access is entirely owned by {@link CommonPictureContentReader}; this tool only
 * enumerates {@link Configuration#getCommonPictures()} inside a BM read boundary, asks the reader
 * for each picture's variants, and formats the table.
 * <p>
 * <b>Attribution:</b> the CommonPicture Picture.zip manifest model this feature reads was mapped
 * from e1c's edt-bridge {@code edt_picture_export} tool (Apache-2.0); no source was copied.
 */
public class ListCommonPicturesTool implements IMcpTool
{
    public static final String NAME = "list_common_pictures"; //$NON-NLS-1$

    /** Input param: synonym language code (optional; default = configuration default language). */
    private static final String KEY_LANGUAGE = "language"; //$NON-NLS-1$

    /** Input param: maximum number of pictures rendered (optional; default from preferences). */
    private static final String KEY_LIMIT = "limit"; //$NON-NLS-1$

    /** Default page size when the caller supplies no {@code limit} (overridable via preferences). */
    private static final int DEFAULT_LIMIT = 100;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List a 1C configuration's CommonPicture objects and the variants each carries in its " //$NON-NLS-1$
            + "Picture.zip (DPI, theme, interface variant, template flag, glyph size, picture direction, " //$NON-NLS-1$
            + "byte size) as a Markdown overview. Use it to see which pictures exist and to spot " //$NON-NLS-1$
            + "mixed-icon mistakes; no image bytes are returned - for the PNG of one picture use " //$NON-NLS-1$
            + "export_common_picture. Full parameters and examples: " //$NON-NLS-1$
            + "call get_tool_guide('list_common_pictures')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty(KEY_LANGUAGE,
                "Synonym language code, e.g. 'en'/'ru' (default: configuration default)") //$NON-NLS-1$
            .integerProperty(KEY_LIMIT,
                "Max pictures listed (default from preferences: 100, max 1000)") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        if (projectName != null && !projectName.isEmpty())
        {
            return "common-pictures-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "common-pictures.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Display-free early validation FIRST (unit-testable): the required-argument guard returns a
        // ready error payload before any workspace / BM access.
        String argErr = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (argErr != null)
        {
            return argErr;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String language = JsonUtils.extractStringArgument(params, KEY_LANGUAGE);

        int defaultLimit = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, KEY_LIMIT, DEFAULT_LIMIT);
        int limit = Pagination.clampLimit(
            JsonUtils.extractIntArgument(params, KEY_LIMIT, defaultLimit), Pagination.MAX_LIMIT);

        // The model access happens inside BmTransactions.read (a read-only BM task, which does not
        // require the UI thread) and the reader's rasterization is pure AWT, so this runs directly
        // on the calling thread - no SWT Display, mirroring export_common_picture (unattended-safe:
        // no UI-thread dependency).
        try
        {
            return listInternal(projectName, language, limit);
        }
        catch (Exception e) // NOSONAR any reader/model failure must surface as an actionable tool error
        {
            Activator.logError("Error listing common pictures", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    /**
     * Resolves the project + configuration, then enumerates the CommonPictures and their variants
     * inside a read-only BM transaction and formats the Markdown. Runs on the calling thread (no
     * UI-thread requirement).
     *
     * @param projectName the EDT project name
     * @param language the requested synonym language code (may be {@code null})
     * @param limit the max number of pictures to render
     * @return the Markdown document, or a {@link ToolResult#error} JSON payload on failure
     */
    private String listInternal(String projectName, String language, int limit)
    {
        // Resolve the project + its live configuration (not-found / provider-missing errors are
        // value-naming and reached before any BM access).
        ProjectContext.ConfigurationResult resolved = ProjectContext.resolveConfiguration(projectName);
        if (!resolved.ok())
        {
            return resolved.errorJson();
        }
        IProject project = resolved.project();
        Configuration config = resolved.configuration();

        // The synonym map is keyed by the language CODE (e.g. "ru"/"en"), never by the Language
        // object's name; resolve the effective code once (may be null on a language-less config).
        String effectiveLanguage = MetadataLanguageUtils.resolveLanguageCode(config, language);

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager not available. The EDT platform may not be ready.").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Model reads happen ONLY inside the BM read boundary (CLAUDE.md hard rule): enumerate the
        // pictures and read each one's variants there. The reader touches the picture content API;
        // the resolved model object is handed to it already inside this transaction.
        List<PictureInfo> pictures = BmTransactions.read(bmModel, "ListCommonPictures", (tx, monitor) -> //$NON-NLS-1$
            collectPictures(config, effectiveLanguage));
        return formatOutput(projectName, pictures, limit);
    }

    /**
     * Enumerates every {@link CommonPicture} of the configuration and, for each, reads its variants
     * through the shared {@link CommonPictureContentReader}. Runs inside the BM read boundary.
     * <p>
     * The shared {@link CommonPictureContentReader} is built once up front; its constructor is
     * declared {@code throws Exception} (it resolves the MdPlugin Guice injector), so it is created
     * inside a try/catch. If the reader cannot be built at all (a global condition - the injector /
     * plugin is not available), every picture degrades to a {@code null} variant list rather than
     * aborting the whole listing; the failure is logged.
     * <p>
     * A single picture whose variants cannot be read (a missing/corrupt Picture.zip - the reader's
     * {@code listVariants} is declared {@code throws Exception}) is likewise degraded to an empty
     * variant list (rendered as a "No variants" note) rather than aborting the whole listing: one
     * broken picture must not hide every other picture. Catching the checked exceptions here also
     * keeps them out of the {@code BmTransactions.read} lambda (whose functional signature does not
     * declare {@code throws}).
     *
     * @param config the resolved configuration
     * @param language the resolved synonym language code (may be {@code null})
     * @return one {@link PictureInfo} per common picture, in configuration order
     */
    private static List<PictureInfo> collectPictures(Configuration config, String language)
    {
        CommonPictureContentReader reader;
        try
        {
            reader = new CommonPictureContentReader();
        }
        catch (Exception e) // NOSONAR a missing injector/plugin degrades every picture to "no variants", never aborts
        {
            Activator.logError("Could not create the common-picture content reader", e); //$NON-NLS-1$
            reader = null;
        }

        List<PictureInfo> pictures = new ArrayList<>();
        for (CommonPicture picture : config.getCommonPictures())
        {
            PictureInfo info = new PictureInfo();
            info.name = picture.getName();
            info.synonym = synonymForLanguage(picture, language);
            if (reader != null)
            {
                try
                {
                    info.variants = reader.listVariants(picture);
                }
                catch (Exception e) // NOSONAR one unreadable Picture.zip is surfaced per-picture, never aborts the listing
                {
                    Activator.logError("Could not read variants of common picture " + info.name, e); //$NON-NLS-1$
                    info.variants = null;
                    // Surface the failure in the report (this tool is meant to FIND picture problems) —
                    // do not mask a corrupt/unreadable Picture.zip as an ordinary single-image picture.
                    info.error = e.getMessage() != null ? e.getMessage() : e.toString();
                }
            }
            pictures.add(info);
        }
        return pictures;
    }

    /**
     * Reads a picture's synonym for the given language CODE out of its code-keyed synonym map,
     * delegating to the shared resolver (first the requested code, then any non-empty value).
     *
     * @param picture the common picture
     * @param language the preferred language code (may be {@code null})
     * @return the resolved synonym, or {@code ""} when none is available
     */
    private static String synonymForLanguage(CommonPicture picture, String language)
    {
        EMap<String, String> synonym = picture.getSynonym();
        return MetadataLanguageUtils.getSynonymForLanguage(synonym == null ? null : synonym.map(), language);
    }

    /**
     * Formats the collected pictures as Markdown: a header + count/truncation notice, then, per
     * picture (up to {@code limit}), a sub-heading and a variant table. Every variant-table cell is
     * escaped ({@link MarkdownUtils#tableRow}) so a value containing {@code |} cannot break the table.
     *
     * @param projectName the project name for the header
     * @param pictures every common picture collected
     * @param limit the max number of pictures to render
     * @return the Markdown document
     */
    private static String formatOutput(String projectName, List<PictureInfo> pictures, int limit)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Common Pictures: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        int total = pictures.size();
        int shown = Math.min(total, limit);
        sb.append("**Total:** ").append(total).append(" pictures"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(Pagination.truncationNotice(shown, total));
        sb.append("\n\n"); //$NON-NLS-1$

        if (pictures.isEmpty())
        {
            sb.append("No common pictures found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        int count = 0;
        for (PictureInfo info : pictures)
        {
            if (count >= limit)
            {
                break;
            }
            appendPicture(sb, info);
            count++;
        }
        return sb.toString();
    }

    /**
     * Appends one picture: a sub-heading (Name plus its synonym when present) and a variant table
     * (or a note, for a picture with no multi-variant Picture.zip - an ordinary single-image
     * picture, or one whose Picture.zip is empty/unreadable).
     *
     * @param sb the buffer to append to
     * @param info the picture to render
     */
    private static void appendPicture(StringBuilder sb, PictureInfo info)
    {
        sb.append("### ").append(info.name != null ? info.name : ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (info.synonym != null && !info.synonym.isEmpty())
        {
            sb.append(" (").append(info.synonym).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        if (info.error != null)
        {
            // A read failure is a real problem to flag, NOT a plain single-image picture.
            sb.append("**Could not read this picture's Picture.zip:** ") //$NON-NLS-1$
                .append(MarkdownUtils.escapeForTable(info.error)).append("\n\n"); //$NON-NLS-1$
            return;
        }

        if (info.variants == null || info.variants.isEmpty())
        {
            sb.append("No multi-variant Picture.zip (single-image picture, or its Picture.zip is empty); " //$NON-NLS-1$
                + "export of single-image pictures is not yet supported.\n\n"); //$NON-NLS-1$
            return;
        }

        // MarkdownUtils.tableHeader / tableRow run every cell through escapeForTable internally, so a
        // name/synonym/direction containing '|' or a newline cannot break the table (CLAUDE.md don't #9).
        // Pass RAW values here - pre-escaping would double-escape ('|' -> '\\|').
        sb.append(MarkdownUtils.tableHeader(
            "Name", "Dpi", "Theme", "InterfaceVariant", "Template", "Glyph", "PictureDirection", "Size")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        for (CommonPictureContentReader.PictureVariantInfo variant : info.variants)
        {
            sb.append(MarkdownUtils.tableRow(
                variant.name,
                variant.dpi,
                variant.theme,
                variant.interfaceVariant,
                variant.template ? "Yes" : "-", //$NON-NLS-1$ //$NON-NLS-2$
                glyph(variant),
                variant.pictureDirection,
                Long.toString(variant.sizeBytes)));
        }
        sb.append('\n');
    }

    /**
     * Renders a variant's glyph size as {@code WxH}, or {@code "-"} when both dimensions are 0
     * (a picture with no declared glyph size).
     *
     * @param variant the variant
     * @return the glyph cell text
     */
    private static String glyph(CommonPictureContentReader.PictureVariantInfo variant)
    {
        if (variant.glyphWidth == 0 && variant.glyphHeight == 0)
        {
            return "-"; //$NON-NLS-1$
        }
        return variant.glyphWidth + "x" + variant.glyphHeight; //$NON-NLS-1$
    }

    /**
     * Detached per-picture data carried out of the BM read boundary: the programmatic name, the
     * chosen-language synonym and the reader's variant list. Nothing here holds a live model handle,
     * so it is safe to format after the transaction closes.
     */
    private static final class PictureInfo
    {
        String name;
        String synonym;
        List<CommonPictureContentReader.PictureVariantInfo> variants;
        /** Set when this picture's Picture.zip could not be read (surfaced in the report). */
        String error;
    }
}
