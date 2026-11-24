/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.moxel.SpreadsheetDocument;
import com._1c.g5.v8.dt.textdocument.model.FileAwareTextDocument;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.BmModelResolver;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.Refusals;
import com.ditrix.edt.mcp.server.utils.SpreadsheetTemplateWriter;
import com.ditrix.edt.mcp.server.utils.TemplateContentSupport;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Edits the file-backed text or spreadsheet content of an existing metadata template. */
public class TemplateContentTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "template_content";

    private static final String KEY_METHOD = "method";
    private static final String KEY_FQN = "fqn";
    private static final String KEY_TEMPLATE = "template";
    private static final String KEY_PERSISTED = "persisted";
    private static final String HELP = "help";
    private static final String MODIFY = "modify";
    private static final String VAL_MODIFIED = "modified";
    private static final String ERR_NO_BM_MANAGER = "IBmModelManager not available";
    private static final String ERR_NO_BM_MODEL = "BM model not available for project: ";

    /** A spreadsheet row / column index: a non-negative integer. */
    private static final String INDEX_SCHEMA = "{\"type\":\"integer\",\"minimum\":0}"; //$NON-NLS-1$

    /**
     * Input schema of {@link #KEY_TEMPLATE}: the TextDocument and SpreadsheetDocument members. Cell, column
     * and row items stay open so {@code SpreadsheetTemplateWriter} names a misplaced key itself; bold,
     * autoMarkIncomplete and autoWidthCalculation are untyped because the writer also reads "yes" / "1";
     * borders and the colours are untyped because each takes a string OR an object.
     */
    private static final String TEMPLATE_SCHEMA = "{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"text\":{\"type\":\"string\"},\"mode\":{\"type\":\"string\"},\"oldText\":{\"type\":\"string\"}," //$NON-NLS-1$
        + "\"expectedHash\":{\"type\":\"string\"},\"expectedSource\":{\"type\":\"string\"}," //$NON-NLS-1$
        + "\"overwrite\":{\"type\":\"boolean\"}," //$NON-NLS-1$
        + "\"cells\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"row\":" + INDEX_SCHEMA + ",\"col\":" + INDEX_SCHEMA + ",\"replace\":{\"type\":\"boolean\"}," //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        + "\"text\":{\"type\":\"string\"},\"parameter\":{\"type\":\"string\"},\"bold\":{}," //$NON-NLS-1$
        + "\"fontSize\":{\"type\":\"integer\",\"minimum\":1}," //$NON-NLS-1$
        + "\"hAlign\":{\"type\":\"string\",\"enum\":[\"Auto\",\"Left\",\"Right\",\"Center\",\"Width\"]}," //$NON-NLS-1$
        + "\"vAlign\":{\"type\":\"string\",\"enum\":[\"Center\",\"Top\",\"Bottom\"]}," //$NON-NLS-1$
        + "\"wrap\":{\"type\":[\"boolean\",\"string\"],\"enum\":[true,false,\"Auto\",\"Cut\",\"Block\",\"Wrap\"]}," //$NON-NLS-1$
        + "\"textOrientation\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":3600}," //$NON-NLS-1$
        + "\"autoIndent\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100},\"autoMarkIncomplete\":{}," //$NON-NLS-1$
        + "\"borders\":{},\"borderColor\":{},\"backColor\":{},\"textColor\":{}}," //$NON-NLS-1$
        + "\"required\":[\"row\",\"col\"]}}," //$NON-NLS-1$
        + "\"merges\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"fromRow\":" + INDEX_SCHEMA + ",\"fromCol\":" + INDEX_SCHEMA //$NON-NLS-1$ //$NON-NLS-2$
        + ",\"toRow\":" + INDEX_SCHEMA + ",\"toCol\":" + INDEX_SCHEMA //$NON-NLS-1$ //$NON-NLS-2$
        + "},\"required\":[\"fromRow\",\"fromCol\",\"toRow\",\"toCol\"],\"additionalProperties\":false}}," //$NON-NLS-1$
        + "\"areas\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"name\":{\"type\":\"string\"},\"type\":{\"type\":\"string\",\"enum\":[\"Rows\",\"Columns\",\"Rectangle\"]}," //$NON-NLS-1$
        + "\"fromRow\":" + INDEX_SCHEMA + ",\"fromCol\":" + INDEX_SCHEMA //$NON-NLS-1$ //$NON-NLS-2$
        + ",\"toRow\":" + INDEX_SCHEMA + ",\"toCol\":" + INDEX_SCHEMA //$NON-NLS-1$ //$NON-NLS-2$
        + "},\"required\":[\"name\"],\"additionalProperties\":false}}," //$NON-NLS-1$
        + "\"columnWidths\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"col\":" + INDEX_SCHEMA + ",\"width\":{\"type\":\"integer\",\"minimum\":1},\"autoWidthCalculation\":{}," //$NON-NLS-1$ //$NON-NLS-2$
        + "\"widthWeightFactor\":" + INDEX_SCHEMA + "},\"required\":[\"col\"]}}," //$NON-NLS-1$ //$NON-NLS-2$
        + "\"rowHeights\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"row\":" + INDEX_SCHEMA + ",\"height\":{\"type\":\"integer\",\"minimum\":1}}," //$NON-NLS-1$ //$NON-NLS-2$
        + "\"required\":[\"row\",\"height\"]}}," //$NON-NLS-1$
        + "\"unmerges\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"fromRow\":" + INDEX_SCHEMA + ",\"fromCol\":" + INDEX_SCHEMA //$NON-NLS-1$ //$NON-NLS-2$
        + ",\"toRow\":" + INDEX_SCHEMA + ",\"toCol\":" + INDEX_SCHEMA //$NON-NLS-1$ //$NON-NLS-2$
        + "},\"required\":[\"fromRow\",\"fromCol\",\"toRow\",\"toCol\"],\"additionalProperties\":false}}," //$NON-NLS-1$
        + "\"removeAreas\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}," //$NON-NLS-1$
        + "\"renameAreas\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"name\":{\"type\":\"string\"},\"newName\":{\"type\":\"string\"}}," //$NON-NLS-1$
        + "\"required\":[\"name\",\"newName\"],\"additionalProperties\":false}}}," //$NON-NLS-1$
        + "\"additionalProperties\":false}"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Edit an existing template's SpreadsheetDocument or TextDocument content. "
            + "Use method='help' for guidance and method='modify' to write. "
            + "Use metadata tools for the template object itself. "
            + "Full parameters and examples: call get_tool_guide('template_content').";
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .enumProperty(KEY_METHOD, "help: show usage; modify: edit an existing template.", true, HELP, MODIFY)
            .stringProperty(McpKeys.PROJECT_NAME, "Required for modify: EDT project name.")
            .stringProperty(KEY_FQN, "Required for modify: CommonTemplate.Name or Type.Owner.Template.Name.")
            .property(KEY_TEMPLATE,
                "Required for modify. TextDocument: {text, mode?, oldText?, " //$NON-NLS-1$
                + "expectedHash?, expectedSource?, overwrite?}; mode = 'searchReplace' (default, needs " //$NON-NLS-1$
                + "exactly one literal oldText match) / 'append' / 'replace' (the only mode that creates a " //$NON-NLS-1$
                + "missing file); replacing an existing file needs expectedHash or expectedSource from " //$NON-NLS-1$
                + "get_metadata_details, or overwrite=true. SpreadsheetDocument: {cells?, merges?, areas?, " //$NON-NLS-1$
                + "columnWidths?, rowHeights?, unmerges?, removeAreas?, renameAreas?}. cells [{row, col (both " //$NON-NLS-1$
                + "0-based, required), replace?, text? | parameter?, bold?, fontSize?, hAlign? " //$NON-NLS-1$
                + "(Left/Center/Right/Auto/Width), vAlign? (Top/Center/Bottom), wrap?, textOrientation? " //$NON-NLS-1$
                + "(TENTHS of a degree: 90 degrees = 900), autoIndent? (0..100), autoMarkIncomplete?, " //$NON-NLS-1$
                + "borders? ('Solid' or {style, width? 1..3}, or {left?, top?, right?, bottom?} of those), " //$NON-NLS-1$
                + "borderColor? / backColor? / textColor? ({red, green, blue}, 'Web.Red', 'Style.<Name>', " //$NON-NLS-1$
                + "'auto')}]; merges [{fromRow, " //$NON-NLS-1$
                + "fromCol, toRow, toCol}]; areas [{name, type?, fromRow?, fromCol?, toRow?, toCol?}]: " //$NON-NLS-1$
                + "Rectangle (default) needs all four indices; Rows needs only fromRow/toRow; Columns needs only " //$NON-NLS-1$
                + "fromCol/toCol; columnWidths " //$NON-NLS-1$
                + "[{col, width?, autoWidthCalculation?, widthWeightFactor? (only with " //$NON-NLS-1$
                + "autoWidthCalculation:true)}]; rowHeights [{row, height}]; unmerges [{fromRow, fromCol, " //$NON-NLS-1$
                + "toRow, toCol}] drops every merge touching the range; removeAreas [name]; renameAreas " //$NON-NLS-1$
                + "[{name, newName}]; these three run first. Omitted or null cell keys keep their values; replace:true " //$NON-NLS-1$
                + "resets them. Non-empty text clears parameter and vice versa; '' clears the named one. " //$NON-NLS-1$
                + "false / 0 on a format key overrides inheritance, but wrap:false restores inherited " //$NON-NLS-1$
                + "placement. Other cells, merges and areas are kept.", TEMPLATE_SCHEMA) //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return WrittenObjectMarkers.declareOutput(JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the call succeeded.", true)
            .stringProperty("error", "Actionable refusal on failure.")
            .enumProperty(KEY_METHOD, "Requested operation.", HELP, MODIFY)
            .stringProperty(HELP, "Markdown usage guide for method='help'.")
            .stringProperty(KEY_FQN, "Canonical FQN of the modified template.")
            .stringProperty(McpKeys.ACTION, "'modified' after a write.")
            .objectProperty(KEY_TEMPLATE, "For a template content change: the applied counts object " //$NON-NLS-1$
                + "{cells, merges, areas, columnWidths, rowHeights, unmerges, removedAreas, renamedAreas}, or " //$NON-NLS-1$
                + "{contentHash} for text") //$NON-NLS-1$
            .booleanProperty(KEY_PERSISTED, "Whether EDT accepted an export task for the write.")
            .stringProperty(McpKeys.MESSAGE, "Human-readable confirmation message.")
            .stringArrayProperty(WriteScope.RESULT_MEMBER, WriteScope.OUTPUT_SCHEMA_DESCRIPTION))
            .build();
    }

    @Override
    protected VendorSupportGuard.Intent writeIntent()
    {
        return VendorSupportGuard.Intent.MODIFY;
    }

    @Override
    protected boolean reportsWrittenObjectMarkers()
    {
        return true;
    }

    private static final Set<String> PARAMETERS = Set.of(KEY_METHOD, McpKeys.PROJECT_NAME, KEY_FQN, KEY_TEMPLATE);

    @Override
    protected String validateRequest(Map<String, String> params)
    {
        String method = JsonUtils.extractStringArgument(params, KEY_METHOD);
        if (HELP.equals(method))
        {
            if (params.keySet().stream().anyMatch(key -> !KEY_METHOD.equals(key)))
            {
                return ToolResult.error("method='help' takes no other parameters; use method='modify' to edit content.")
                    .toJson();
            }
            return null;
        }
        if (!MODIFY.equals(method))
        {
            return ToolResult.error("Specify method='help' or method='modify' for template_content.").toJson();
        }
        for (String key : params.keySet())
        {
            if (!PARAMETERS.contains(key))
            {
                return ToolResult.error("Unknown template_content argument '" + key + "'. "
                    + "Use template_content(method='help') for the accepted arguments.").toJson();
            }
        }
        String missing = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_FQN, KEY_TEMPLATE);
        return missing != null ? missing : parseTemplateSpec(params.get(KEY_TEMPLATE)) == null
            ? malformedTemplateError() : null;
    }

    private static JsonObject parseTemplateSpec(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return null;
        }
        try
        {
            JsonElement element = JsonParser.parseString(raw.trim());
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        if (HELP.equals(JsonUtils.extractStringArgument(params, KEY_METHOD)))
        {
            return ToolResult.success().put(KEY_METHOD, HELP).put(HELP, getGuide()).toJson();
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String normFqn = MetadataTypeUtils.normalizeFqn(JsonUtils.extractStringArgument(params, KEY_FQN));
        JsonObject templateSpec = parseTemplateSpec(params.get(KEY_TEMPLATE));
        ProjectContext ctx = resolveProjectAndScope(projectName, normFqn);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        TemplateWriteContext plan = readPreparation(ctx.project,
            () -> prepareTemplateWrite(ctx, normFqn, templateSpec));
        return modifyTemplateContent(ctx, plan, templateSpec);
    }

    private TemplateWriteContext prepareTemplateWrite(ProjectContext ctx, String normFqn, JsonObject templateSpec)
    {
        var scope = preparationScope(ctx);
        MetadataNodeResolver.ResolvedNode resolved = MetadataNodeResolver.resolveExistingWithYoFallback(scope, normFqn);
        TemplateWriteContext refused = new TemplateWriteContext();
        if (resolved.node == null || resolved.node.object == null)
        {
            refused.error = templateOnlyForTemplateFqnError(normFqn, "did not resolve to a template"
                + MetadataNodeResolver.yoNotFoundHint(normFqn) + scope.addressingHint(normFqn));
            return refused;
        }
        if (!(resolved.node.object instanceof BasicTemplate template))
        {
            refused.error = templateOnlyForTemplateFqnError(normFqn, "is a " + resolved.node.object.eClass().getName());
            return refused;
        }
        String address = resolved.yoFallback ? resolved.fqn : normFqn;
        boolean text = template.getTemplateType() == TemplateType.TEXT_DOCUMENT;
        refused.error = TemplateContentSupport.payloadError(template, templateSpec, address);
        if (refused.error == null && !text)
        {
            refused.error = nonSpreadsheetTemplateError(template, address);
        }
        if (refused.error != null)
        {
            return refused;
        }
        TemplateWriteContext plan = resolveTemplateWriteContext(ctx, template, address, !text);
        plan.externalObjects = scope.isExternalObjects();
        return plan;
    }

    private void requireWritable(ProjectContext ctx, IBmTransaction tx, BasicTemplate template,
        String normFqn, String exportFqn)
    {
        var scope = transactionScope(ctx, tx);
        String locked = vendorSupportRefusal(template, scope, normFqn, normFqn, "changed");
        EObject owner = template.eContainer();
        if (locked == null && owner instanceof MdObject && !(owner instanceof Configuration))
        {
            locked = vendorSupportRefusal(owner, scope, exportFqn, normFqn, "changed");
        }
        if (locked != null)
        {
            throw new TemplateWriteException(locked);
        }
    }

    private String modifyTemplateContent(ProjectContext ctx, TemplateWriteContext writeCtx, JsonObject templateSpec)
    {
        if (writeCtx.error != null)
        {
            return writeCtx.error;
        }

        String normFqn = writeCtx.normFqn;
        if (writeCtx.text)
        {
            return modifyTextTemplateContent(ctx, normFqn, writeCtx, templateSpec);
        }

        // Captured inside the write: the moxel content's OWN canonical FQN (a template's content IS a
        // distinct top BM object once attached - pre-existing on disk, or freshly attached below), so it is
        // force-exported alongside the template so the sibling .mxlx drains to disk.
        final String[] contentFqnHolder = {null};
        SpreadsheetTemplateWriter.Result result;
        try
        {
            result = BmTransactions.write(writeCtx.bmModel, "ModifyTemplateContent", //$NON-NLS-1$
                (tx, pm) -> applyTemplateSpec(ctx, tx, writeCtx, normFqn, templateSpec, contentFqnHolder));
        }
        catch (TemplateWriteException e)
        {
            return e.getErrorJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error modifying template content", e); //$NON-NLS-1$
            return ToolResult.error("Failed to modify template content: " //$NON-NLS-1$
                + unwrapCauseMessage(e)).toJson();
        }

        // The spreadsheet content serializes to the template's sibling .mxlx resource under the
        // template's OWN top-object .mdo, so force-exporting the template's canonical FQN drains it. If
        // EDT instead models that content as a distinct top BM object (the same shape as a Role's separate
        // Rights.rights sub-resource, which modifyRoleRights exports by its own FQN), the template FQN
        // alone would NOT drain it - so export the content resource's OWN FQN too, guarding against the
        // #239-class silent-false-success (persisted=true while the authored cells never reach disk).
        List<String> exportFqns = new ArrayList<>();
        exportFqns.add(writeCtx.exportFqn);
        String contentFqn = contentFqnHolder[0];
        if (contentFqn != null && !contentFqn.equals(writeCtx.exportFqn))
        {
            exportFqns.add(contentFqn);
        }
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, exportFqns);
        return buildTemplateResult(normFqn, result, persisted);
    }

    /** Write file-backed text inside a BM boundary with rollback for the file write. */
    private String modifyTextTemplateContent(ProjectContext ctx, String normFqn, TemplateWriteContext writeCtx,
        JsonObject templateSpec)
    {
        if (writeCtx.error != null)
        {
            return writeCtx.error;
        }
        final String hash;
        try (TemplateContentSupport.Context files = new TemplateContentSupport.Context(ctx.project))
        {
            hash = BmTransactions.write(writeCtx.bmModel, "ModifyTextTemplateContent", (tx, monitor) ->
            {
                Object object = tx.getObjectById(writeCtx.templateBmId);
                if (!(object instanceof BasicTemplate))
                {
                    throw new TemplateWriteException(ToolResult.error("Template '" + normFqn
                        + "' no longer exists; re-read get_metadata_details.").toJson());
                }
                BasicTemplate current = (BasicTemplate)object;
                requireWritable(ctx, tx, current, normFqn, writeCtx.exportFqn);
                String error = TemplateContentSupport.payloadError(current, templateSpec, normFqn);
                if (error != null)
                {
                    throw new TemplateWriteException(error);
                }
                EObject currentContent = current.getTemplate();
                if (!(currentContent instanceof FileAwareTextDocument)
                    && !TemplateContentSupport.isPlaceholder(currentContent))
                {
                    throw new TemplateWriteException(ToolResult.error("Template '" + normFqn //$NON-NLS-1$
                        + "' has incompatible content. Create a separate TextDocument template with create_metadata.") //$NON-NLS-1$
                        .toJson());
                }
                IFile file = TemplateContentSupport.contentFile(ctx.project, current);
                TemplateContentSupport.TextWriteResult written =
                    TemplateContentSupport.writeTextResult(current, file, templateSpec, files);
                if (written.error() != null)
                {
                    throw new TemplateWriteException(written.error());
                }
                TemplateContentSupport.initialize(current, TemplateType.TEXT_DOCUMENT, tx,
                    writeCtx.dtProject, writeCtx.fqnGenerator, file);
                return written.contentHash();
            });
            files.commit();
        }
        catch (TemplateWriteException error)
        {
            return error.getErrorJson();
        }
        catch (Exception error)
        {
            Refusals.log("Error modifying text template", error);
            return ToolResult.error("Failed to modify template text: " + unwrapCauseMessage(error)).toJson();
        }
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, writeCtx.exportFqn);
        JsonObject applied = new JsonObject();
        applied.addProperty("contentHash", hash);
        return ToolResult.success().put(KEY_METHOD, MODIFY).put(McpKeys.ACTION, VAL_MODIFIED)
            .put(KEY_FQN, MetadataTypeUtils.toCanonicalEnglishFqn(normFqn))
            .put(KEY_TEMPLATE, applied).put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Modified text template " + MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)).toJson();
    }

    /**
     * Everything the template-content write boundary needs (built by
     * {@link #resolveTemplateWriteContext}): the template's BM id for the in-tx re-fetch, the TOP
     * object's canonical FQN to force-export, the BM model, the (nullable) {@link IDtProject}
     * driving the canonical languageSettings block, and the external-property FQN generator (only
     * required for spreadsheet content). When
     * {@link #error} is non-null (a ready JSON error), the other fields must not be used.
     */
    private static final class TemplateWriteContext
    {
        boolean text;
        String normFqn;
        /** A ready {@link ToolResult#error} JSON when a service / export target is missing, else {@code null}. */
        String error;
        /** The template's stable BM id, re-fetched inside the write tx. */
        long templateBmId;
        /** The TOP object's canonical (all-English) FQN to force-export after the commit. */
        String exportFqn;
        IBmModel bmModel;
        /** Nullable: a null project still yields a templateMode=true document. */
        IDtProject dtProject;
        ITopObjectFqnGenerator fqnGenerator;
        /**
         * The project's 1C:Enterprise runtime version (the DT project manifest's {@code Runtime-Version},
         * which is what the moxel serializer gates its version-dependent members on). Nullable: a version
         * that cannot be resolved skips the version check rather than refusing a legal write.
         */
        Version projectVersion;
        /**
         * Whether the template lives in an external-objects project: its configuration is another
         * project's, so a cell colour may name platform colours only (as a form item's there).
         */
        boolean externalObjects;
    }

    /**
     * Resolves everything the template-content write needs up front: the template's BM id, the TOP
     * object's canonical FQN to force-export, the project's BM model, the (nullable)
     * {@link IDtProject} and, when {@code attachSpreadsheet} is true, the external-property FQN
     * generator needed to attach spreadsheet content. Returns a
     * {@link TemplateWriteContext} whose non-null {@code error} (a ready JSON error) reports a
     * missing service / unresolvable export target. Extracted verbatim from
     * {@link #modifyTemplateContent}.
     */
    private static TemplateWriteContext resolveTemplateWriteContext(ProjectContext ctx,
        BasicTemplate template, String normFqn, boolean attachSpreadsheet)
    {
        TemplateWriteContext writeCtx = new TemplateWriteContext();
        writeCtx.normFqn = normFqn;
        writeCtx.text = template.getTemplateType() == TemplateType.TEXT_DOCUMENT;

        // A common template (Template.X / CommonTemplate.X) is its OWN top BM object; an object-owned
        // template (Catalog.Y.Template.Z) is INLINE in its owner's .mdo, so it is NOT a top object. Its
        // stable BM id still re-fetches inside the tx (getObjectById resolves any managed object, not only
        // top ones), but the force-export target must be the TOP object's canonical (all-English) FQN - the
        // template itself when it is top, else the OWNER top object (bmGetTopObject) whose .mdo + sibling
        // .mxlx carry the content. Mirrors RoleRightsWriter's top climb - self when already top, else
        // bmGetTopObject. A bmGetFqn read is legal only on a top object, so it happens on `topObject`, never on a non-top
        // template. A null top (should not happen for a resolved template) fails LOUD, nothing written.
        IBmObject templateBm = (IBmObject)template;
        writeCtx.templateBmId = templateBm.bmGetId();
        IBmObject topObject = templateBm.bmIsTop() ? templateBm : templateBm.bmGetTopObject();
        if (topObject == null)
        {
            writeCtx.error = ToolResult.error("Cannot resolve the on-disk file to export for template '" + normFqn //$NON-NLS-1$
                + "': its owning top-level object could not be found; report it with the template FQN.") //$NON-NLS-1$
                    .toJson();
            return writeCtx;
        }
        writeCtx.exportFqn = topObject.bmGetFqn();

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            writeCtx.error = ToolResult.error(ERR_NO_BM_MANAGER).toJson();
            return writeCtx;
        }
        writeCtx.bmModel = BmModelResolver.resolve(ctx.project, bmModelManager).getModel();
        if (writeCtx.bmModel == null)
        {
            writeCtx.error = ToolResult.error(ERR_NO_BM_MODEL
                + ctx.project.getName()).toJson();
            return writeCtx;
        }

        // Drives the canonical (project-aware) <languageSettings> block when an EMPTY template's content
        // is materialized inside the tx (a freshly created template has getTemplate()==null). Resolved
        // best-effort with the SAME manager the force-export below uses; a null project still yields a
        // templateMode=true document (only the languageSettings block is skipped, which the platform's
        // moxel reader null-guards). Carried on the write context captured by the write lambda.
        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        writeCtx.dtProject =
            dtProjectManager == null ? null : dtProjectManager.getDtProject(ctx.project);

        // The moxel serializer gates autoWidthCalculation / widthWeightFactor on the project's RUNTIME
        // version (the DT manifest's Runtime-Version, which is exactly what IV8Project.getVersion()
        // returns) - not on the configuration's compatibilityMode. Below 8.3.10 it writes neither, so the
        // writer refuses them there instead of reporting success for a dropped value.
        writeCtx.projectVersion = com.ditrix.edt.mcp.server.utils.ProjectContext.platformVersion(ctx.project);

        // The moxel content is a transient @ExternalProperty of the template - its own .mxlx resource, NOT
        // an inline BM reference. A freshly-materialized content doc must be ATTACHED as a BM top object
        // under its generated external-property FQN (the same machinery FormElementWriter uses for a form's
        // content), else committing the write fails with "Failed to persist reference value". Carried on
        // the write context captured by the write lambda.
        if (attachSpreadsheet)
        {
            writeCtx.fqnGenerator = Activator.getDefault().getTopObjectFqnGenerator();
            if (writeCtx.fqnGenerator == null)
            {
                writeCtx.error = ToolResult.error("ITopObjectFqnGenerator not available").toJson(); //$NON-NLS-1$
                return writeCtx;
            }
        }
        return writeCtx;
    }

    /**
     * The template-content write-transaction body: re-fetches the template by its BM id, resolves
     * (or materializes + attaches) its content {@link SpreadsheetDocument}, records the content
     * resource's OWN export FQN into {@code contentFqnHolder}, and applies the payload via
     * {@link SpreadsheetTemplateWriter}. Throws a {@link TemplateWriteException} carrying a ready
     * JSON error on a resolution / validation failure, so the surrounding tx rolls back with no
     * partial mutation. Extracted verbatim from the write lambda of {@link #modifyTemplateContent}.
     */
    private SpreadsheetTemplateWriter.Result applyTemplateSpec(ProjectContext ctx, IBmTransaction tx,
        TemplateWriteContext writeCtx, String normFqn, JsonObject templateSpec,
        String[] contentFqnHolder)
    {
        Object inTx = tx.getObjectById(writeCtx.templateBmId);
        if (!(inTx instanceof BasicTemplate))
        {
            throw new TemplateWriteException(ToolResult.error("The template could not be " //$NON-NLS-1$
                + "resolved inside the transaction.").toJson());
        }
        BasicTemplate txTemplate = (BasicTemplate)inTx;
        requireWritable(ctx, tx, txTemplate, normFqn, writeCtx.exportFqn);
        String payloadError = TemplateContentSupport.payloadError(txTemplate, templateSpec, normFqn);
        if (payloadError == null)
        {
            payloadError = nonSpreadsheetTemplateError(txTemplate, normFqn);
        }
        if (payloadError != null)
        {
            throw new TemplateWriteException(payloadError);
        }
        SpreadsheetDocument doc =
            resolveSpreadsheetContent(txTemplate, tx, writeCtx.dtProject, writeCtx.fqnGenerator, normFqn);
        // The content doc is now an attached BM top object (pre-existing on disk, or freshly
        // materialized + attachTopObject'd inside resolveSpreadsheetContent), so its own resource
        // FQN resolves and is force-exported alongside the template so the sibling .mxlx drains.
        contentFqnHolder[0] = contentResourceExportFqn(doc);
        // A named cell colour resolves as EDT's spreadsheet reader does: the platform colour first, then
        // the configuration's style / palette item read in THIS transaction (the writer binds the
        // reference to it); an external-objects project offers platform colours only.
        SpreadsheetTemplateWriter.Result applied = SpreadsheetTemplateWriter.apply(doc, templateSpec,
            writeCtx.projectVersion, SpreadsheetTemplateWriter.projectColors(writeCtx.externalObjects ? null
                : () -> (Configuration)tx.getTopObjectByFqn("Configuration"), writeCtx.projectVersion)); //$NON-NLS-1$
        if (applied.hasError())
        {
            // Roll the whole write back so a validation failure leaves nothing on disk.
            throw new TemplateWriteException(applied.error);
        }
        return applied;
    }

    /**
     * A template's external-property moxel {@link SpreadsheetDocument}'s OWN canonical top-object FQN
     * when EDT models it as a DISTINCT top BM object (so force-exporting the template's FQN alone would
     * NOT drain the sibling {@code .mxlx}, the same shape as a Role's separate
     * {@code Rights.rights} sub-resource), else
     * {@code null} when the content is a contained child that the template's own export already serializes
     * (the export list is then unchanged). MUST run inside the write boundary:
     * {@code bmGetFqn()} is legal only on a top object, so the call is guarded by {@code bmIsTop()}.
     */
    private static String contentResourceExportFqn(EObject content)
    {
        if (content instanceof IBmObject)
        {
            IBmObject contentBm = (IBmObject)content;
            if (contentBm.bmIsTop())
            {
                return contentBm.bmGetFqn();
            }
        }
        return null;
    }

    /**
     * The up-front refusal for a {@code template} payload aimed at a real {@link BasicTemplate} whose type
     * is NOT {@link TemplateType#SPREADSHEET_DOCUMENT} (a text / binary-data / DCS / graphical template
     * cannot host cells): a ready {@link ToolResult#error} JSON naming the FQN and the template's ACTUAL
     * type (or {@code "unset"} for a null type - no NPE), pointing at the only template kind {@code template}
     * can author. Returns {@code null} when the template IS a SpreadsheetDocument (the write may proceed).
     * Pure (no model mutation, no transaction) so the guard is unit-testable headlessly; called fail-fast
     * before any BM write, mirrored inside the tx by {@link #resolveSpreadsheetContent}'s content re-check.
     */
    static String nonSpreadsheetTemplateError(BasicTemplate template, String normFqn)
    {
        if (template.getTemplateType() == TemplateType.SPREADSHEET_DOCUMENT)
        {
            return null;
        }
        String actual = template.getTemplateType() == null
            ? "unset" : template.getTemplateType().getName(); //$NON-NLS-1$
        return ToolResult.error("Template '" + normFqn + "' is not a SpreadsheetDocument template " //$NON-NLS-1$ //$NON-NLS-2$
            + "(its type is '" + actual + "'). Only a SpreadsheetDocument (print form) " //$NON-NLS-1$ //$NON-NLS-2$
            + "template can be authored with template_content.").toJson(); //$NON-NLS-1$
    }

    /**
     * Reuses spreadsheet content or initializes an empty placeholder through EDT's creation helper.
     * Real foreign content is refused. Must run inside the BM write boundary.
     */
    private static SpreadsheetDocument resolveSpreadsheetContent(BasicTemplate txTemplate, IBmTransaction tx,
        IDtProject dtProject, ITopObjectFqnGenerator fqnGenerator, String normFqn)
    {
        EObject contentObj = txTemplate.getTemplate();
        if (contentObj instanceof SpreadsheetDocument)
        {
            return (SpreadsheetDocument)contentObj;
        }
        if (!TemplateContentSupport.isPlaceholder(contentObj))
        {
            throw new TemplateWriteException(ToolResult.error("Template '" + normFqn
                + "' has incompatible content. Create a separate SpreadsheetDocument template with create_metadata.")
                .toJson());
        }
        TemplateContentSupport.initialize(txTemplate, TemplateType.SPREADSHEET_DOCUMENT,
            tx, dtProject, fqnGenerator, null);
        return (SpreadsheetDocument)txTemplate.getTemplate();
    }

    /**
     * Builds the success JSON for a completed template content change: the {@code template} counts object
     * ({@code cells} / {@code merges} / {@code areas} / {@code columnWidths} / {@code rowHeights} /
     * {@code unmerges} / {@code removedAreas} / {@code renamedAreas}) plus
     * {@code persisted} and a confirmation message. Pure helper.
     */
    private static String buildTemplateResult(String normFqn, SpreadsheetTemplateWriter.Result result,
        boolean persisted)
    {
        JsonObject applied = new JsonObject();
        applied.addProperty("cells", result.cells); //$NON-NLS-1$
        applied.addProperty("merges", result.merges); //$NON-NLS-1$
        applied.addProperty("areas", result.areas); //$NON-NLS-1$
        applied.addProperty("columnWidths", result.columnWidths); //$NON-NLS-1$
        applied.addProperty("rowHeights", result.rowHeights); //$NON-NLS-1$
        applied.addProperty("unmerges", result.unmerges); //$NON-NLS-1$
        applied.addProperty("removedAreas", result.removedAreas); //$NON-NLS-1$
        applied.addProperty("renamedAreas", result.renamedAreas); //$NON-NLS-1$
        return ToolResult.success()
            .put(KEY_METHOD, MODIFY)
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put(KEY_FQN, MetadataTypeUtils.toCanonicalEnglishFqn(normFqn)) //$NON-NLS-1$
            .put(KEY_TEMPLATE, applied)
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Modified template " + MetadataTypeUtils.toCanonicalEnglishFqn(normFqn) + " content (cells: " + result.cells //$NON-NLS-1$ //$NON-NLS-2$
                + ", merges: " + result.merges + ", areas: " + result.areas + ", columnWidths: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + result.columnWidths + ", rowHeights: " + result.rowHeights + ", merges removed: " //$NON-NLS-1$ //$NON-NLS-2$
                + result.unmerges + ", areas removed: " + result.removedAreas + ", areas renamed: " //$NON-NLS-1$ //$NON-NLS-2$
                + result.renamedAreas + ")") //$NON-NLS-1$
            .toJson();
    }

    /**
     * The actionable error for a {@code template} payload addressed to a FQN that is not a
     * SpreadsheetDocument template (a form member, a subsystem, or any other non-template object): names
     * the offending FQN + what it is, and points at the valid template FQN shapes. {@code isClause}
     * describes the resolved target (e.g. {@code "is a Catalog"} or {@code "addresses a FORM member"}).
     * Package-visible for tests.
     */
    static String templateOnlyForTemplateFqnError(String normFqn, String isClause)
    {
        return ToolResult.error("'template' is only valid for a template FQN (a " //$NON-NLS-1$
            + "common template 'CommonTemplate.<Name>' or an object-owned template " //$NON-NLS-1$
            + "'<Type>.<Owner>.Template.<Name>'); '" + normFqn + "' " + isClause + ". 'template' " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "edits template content; use modify_metadata with 'properties' for a generic " //$NON-NLS-1$
            + "property change, or address a template FQN.").toJson(); //$NON-NLS-1$
    }

    /**
     * Carries a ready JSON error out of the template write transaction (a validation / resolution failure)
     * so {@link #modifyTemplateContent} returns it verbatim AND the throw rolls the write back (no partial
     * mutation persists). Unchecked so it crosses the BM task boundary; the message is a validated
     * {@link ToolResult#error} JSON string. Mirrors {@code ReferenceMembershipWriter.ContentWriteException}.
     */
    private static final class TemplateWriteException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        private final transient String errorJson;

        TemplateWriteException(String errorJson)
        {
            super(errorJson);
            this.errorJson = errorJson;
        }

        String getErrorJson()
        {
            return errorJson;
        }
    }

    private static String malformedTemplateError()
    {
        return ToolResult.error("'template' must be a JSON object, e.g. " //$NON-NLS-1$
            + "{cells:[{row:0,col:0,text:'Total'}]}. It authors a SpreadsheetDocument template's cells / " //$NON-NLS-1$
            + "merges / areas / column & row sizes on a template FQN. For a TextDocument use " //$NON-NLS-1$
            + "{text:'...'} with optional expectedHash from get_metadata_details.").toJson(); //$NON-NLS-1$
    }

}
