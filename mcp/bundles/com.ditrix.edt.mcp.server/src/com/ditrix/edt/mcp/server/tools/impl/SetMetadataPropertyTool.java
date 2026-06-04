/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to set the Comment and/or Synonym of an existing metadata object, or one
 * of its attributes, via a BM write transaction.
 * <p>
 * Scope is deliberately narrow: only the two free-text properties that
 * get_metadata_details already SHOWS but nothing could WRITE (closing part of
 * the read/write asymmetry). Type/flag editing is out of scope - the export -&gt;
 * edit XML -&gt; import path (export_configuration_to_xml /
 * import_configuration_from_xml) covers those.
 */
public class SetMetadataPropertyTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "set_metadata_property"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Set the Comment and/or Synonym of an existing metadata object (or one of its " + //$NON-NLS-1$
               "attributes via attributeName), persisted to disk. The objectFqn TYPE token may be " + //$NON-NLS-1$
               "English or Russian; the synonym is keyed by language code. To change OTHER " + //$NON-NLS-1$
               "properties (type, flags, ...) use the export_configuration_to_xml -> edit XML -> " + //$NON-NLS-1$
               "import_configuration_from_xml path. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('set_metadata_property')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("objectFqn", //$NON-NLS-1$
                "FQN of the object to edit, e.g. 'Catalog.Products' (required; TYPE token may be " + //$NON-NLS-1$
                "en/ru; object part is the programmatic Name, not the synonym). Canonical FQN " + //$NON-NLS-1$
                "parameter, shared with find_references / delete_metadata_object / " + //$NON-NLS-1$
                "rename_metadata_object / add_metadata_attribute.", true) //$NON-NLS-1$
            .stringProperty("attributeName", //$NON-NLS-1$
                "Optional name of an attribute of objectFqn to edit instead of the object itself; " + //$NON-NLS-1$
                "when omitted, the object itself is the target") //$NON-NLS-1$
            .stringProperty("comment", //$NON-NLS-1$
                "Optional new Comment (plain text). Provide comment and/or synonym (at least one)") //$NON-NLS-1$
            .stringProperty("synonym", //$NON-NLS-1$
                "Optional new Synonym (display name), written for 'language' or the config default") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Optional language code for the synonym, e.g. 'ru'/'en' (default: config default)") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the property was set", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("objectFqn", "Normalized FQN of the object (canonical)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("attributeName", "Name of the edited attribute, when one was targeted") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("comment", "Comment written, when a comment was provided") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("synonym", "Synonym written, when a synonym was provided") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("language", "Language code the synonym was written for") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("persisted", "Whether the change was saved to disk") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable confirmation message") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# set_metadata_property\n\n" //$NON-NLS-1$
            + "Sets the **Comment** and/or **Synonym** of an existing metadata object (or one of " //$NON-NLS-1$
            + "its attributes) via a BM write transaction, then force-exports the TOP object to " //$NON-NLS-1$
            + "its `.mdo` on disk so the change survives a refresh / clean_project / EDT restart. " //$NON-NLS-1$
            + "This closes part of the read/write asymmetry: get_metadata_details SHOWS Comment and " //$NON-NLS-1$
            + "Synonym, and this tool WRITES them.\n\n" //$NON-NLS-1$
            + "## Scope\n\n" //$NON-NLS-1$
            + "Only Comment and Synonym. Editing the type, flags, or any other property reflectively " //$NON-NLS-1$
            + "is OUT OF SCOPE (too risky). For those, export the object to XML, edit it, and import " //$NON-NLS-1$
            + "it back: export_configuration_to_xml -> edit the `.xml` -> import_configuration_from_xml.\n\n" //$NON-NLS-1$
            + "## Parameters\n\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `objectFqn` (required) - FQN of the object as `Type.Name`. The TYPE token may be " //$NON-NLS-1$
            + "English or Russian (e.g. `Catalog.Products` or the Russian Catalog token " //$NON-NLS-1$
            // The escape below spells the Russian Catalog token (Spravochnik).
            + "`\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.Products`); the object " //$NON-NLS-1$
            + "part is the programmatic Name, NOT the synonym / display name. `objectFqn` is the " //$NON-NLS-1$
            + "canonical FQN parameter shared with find_references / delete_metadata_object / " //$NON-NLS-1$
            + "rename_metadata_object / add_metadata_attribute.\n" //$NON-NLS-1$
            + "- `attributeName` (optional) - name of an attribute of `objectFqn` to edit INSTEAD of " //$NON-NLS-1$
            + "the object itself. Matched case-insensitively by its programmatic Name. When omitted, " //$NON-NLS-1$
            + "the object itself is the target.\n" //$NON-NLS-1$
            + "- `comment` (optional) - new Comment (plain text). An empty string is treated as " //$NON-NLS-1$
            + "'not provided', not as a clear.\n" //$NON-NLS-1$
            + "- `synonym` (optional) - new Synonym (localized display name). Written for `language`, " //$NON-NLS-1$
            + "or the configuration default language when `language` is omitted.\n" //$NON-NLS-1$
            + "- `language` (optional) - language CODE for the synonym (`ru`, `en`, ...). Only " //$NON-NLS-1$
            + "consulted when `synonym` is supplied. Defaults to the configuration's first / default " //$NON-NLS-1$
            + "language code.\n\n" //$NON-NLS-1$
            + "**At least one of `comment` / `synonym` must be provided** - a call with neither is " //$NON-NLS-1$
            + "rejected.\n\n" //$NON-NLS-1$
            + "## Bilingual (ru/en) notes\n\n" //$NON-NLS-1$
            + "The synonym EMap is keyed by the language CODE (`ru`/`en`), never the language name. " //$NON-NLS-1$
            + "Setting a synonym for one language does NOT remove the synonym already stored for " //$NON-NLS-1$
            + "another language - each language code is an independent map entry. The object and " //$NON-NLS-1$
            + "attribute are resolved by their programmatic Name; only the TYPE token in `objectFqn` " //$NON-NLS-1$
            + "is dialect-aware.\n\n" //$NON-NLS-1$
            + "## Transaction\n\n" //$NON-NLS-1$
            + "The mutation runs inside a BM write transaction (the target is re-fetched by its BM id " //$NON-NLS-1$
            + "inside the transaction; an attribute is re-found by Name on the transaction-fetched " //$NON-NLS-1$
            + "parent). After the write commits, the TOP object's `.mdo` is force-exported to disk.\n\n" //$NON-NLS-1$
            + "## Result\n\n" //$NON-NLS-1$
            + "JSON with `objectFqn`, the optional `attributeName`, the `comment` / `synonym` actually " //$NON-NLS-1$
            + "written (and the resolved `language` code for the synonym), and `persisted` (true once " //$NON-NLS-1$
            + "the `.mdo` was exported to disk), plus a `message`.\n\n" //$NON-NLS-1$
            + "## Examples\n\n" //$NON-NLS-1$
            + "Set an object Comment: `{projectName: 'MyProject', objectFqn: 'Catalog.Products', " //$NON-NLS-1$
            + "comment: 'Master catalog of goods'}`\n\n" //$NON-NLS-1$
            + "Set an English Synonym: `{projectName: 'MyProject', objectFqn: 'Catalog.Products', " //$NON-NLS-1$
            + "synonym: 'Products', language: 'en'}`\n\n" //$NON-NLS-1$
            + "Set a Russian Synonym (bilingual): `{projectName: 'MyProject', " //$NON-NLS-1$
            + "objectFqn: 'Catalog.Products', synonym: 'Tovary', language: 'ru'}`\n\n" //$NON-NLS-1$
            + "Edit an attribute's Comment: `{projectName: 'MyProject', objectFqn: 'Catalog.Products', " //$NON-NLS-1$
            + "attributeName: 'Weight', comment: 'Net weight in kg'}`\n\n" //$NON-NLS-1$
            + "## Gotchas\n\n" //$NON-NLS-1$
            + "- `objectFqn` not found -> error pointing to get_metadata_objects; check `Type.Name` " //$NON-NLS-1$
            + "and that you used the Name, not the synonym.\n" //$NON-NLS-1$
            + "- `attributeName` not found on the object -> error listing how to discover attributes " //$NON-NLS-1$
            + "(get_metadata_details).\n" //$NON-NLS-1$
            + "- To change anything OTHER than Comment / Synonym, use the export/import XML path.\n" //$NON-NLS-1$
            + "- If `persisted` is false the in-memory model changed but the `.mdo` write did not " //$NON-NLS-1$
            + "complete - re-check before relying on it on disk."; //$NON-NLS-1$
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String objectFqn = JsonUtils.extractStringArgument(params, "objectFqn"); //$NON-NLS-1$
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        String comment = JsonUtils.extractStringArgument(params, "comment"); //$NON-NLS-1$
        String synonym = JsonUtils.extractStringArgument(params, "synonym"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        String err = JsonUtils.requireArgument(params, "projectName", //$NON-NLS-1$
            ". Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products', comment: 'A note'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "objectFqn", //$NON-NLS-1$
            ". Examples: 'Catalog.Products', 'Document.SalesOrder'. " //$NON-NLS-1$
            + "Usage: {objectFqn: 'Catalog.Products', synonym: 'Products', language: 'en'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        // At least one editable property must be supplied. An empty string is treated
        // as "not provided" (NOT as a clear): the mutations below are gated on these
        // flags, so an empty comment/synonym never reaches setComment()/getSynonym().put.
        // This matches the schema, guide and README — do NOT relax the guard to "clear
        // on empty" without also documenting a deliberate destructive-clear path.
        boolean hasComment = comment != null && !comment.isEmpty();
        boolean hasSynonym = synonym != null && !synonym.isEmpty();
        if (!hasComment && !hasSynonym)
        {
            return ToolResult.error("Nothing to set: provide at least one of 'comment' or 'synonym'. " //$NON-NLS-1$
                + "Usage: {objectFqn: 'Catalog.Products', comment: 'A note'} " //$NON-NLS-1$
                + "or {objectFqn: 'Catalog.Products', synonym: 'Products', language: 'en'}. " //$NON-NLS-1$
                + "To change other properties (type, flags, ...) use export_configuration_to_xml " //$NON-NLS-1$
                + "-> edit XML -> import_configuration_from_xml.").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, objectFqn, attributeName, comment, synonym, language,
            hasComment, hasSynonym);
    }

    private String executeInternal(String projectName, String objectFqn, String attributeName,
        String comment, String synonym, String language, boolean hasComment, boolean hasSynonym)
    {
        // Get project and configuration
        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

        // Get BM model
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        // Normalize and find the object
        String normalizedFqn = MetadataTypeUtils.normalizeFqn(objectFqn);
        String[] parts = normalizedFqn.split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ToolResult.error("Invalid FQN: " + normalizedFqn + ". Expected 'Type.Name', " //$NON-NLS-1$ //$NON-NLS-2$
                + "e.g. 'Catalog.Products'.").toJson(); //$NON-NLS-1$
        }
        MdObject object = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
        if (object == null)
        {
            return ToolResult.error("Object not found: " + normalizedFqn + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Check the FQN format: 'Type.Name' (e.g. 'Catalog.Products', 'Document.SalesOrder'). " //$NON-NLS-1$
                + "Use get_metadata_objects tool to list available objects.").toJson(); //$NON-NLS-1$
        }

        // When an attribute is targeted, verify it exists up front so the error is a
        // clear not-found rather than a transaction failure (the authoritative re-find
        // still runs inside the write task on the tx-fetched parent).
        boolean targetingAttribute = attributeName != null && !attributeName.isEmpty();
        if (targetingAttribute && findAttribute(object, attributeName) == null)
        {
            return ToolResult.error("Attribute not found: '" + attributeName + "' on " + normalizedFqn //$NON-NLS-1$ //$NON-NLS-2$
                + ". Use get_metadata_details to list the object's attributes by Name " //$NON-NLS-1$
                + "(the Name, not the synonym).").toJson(); //$NON-NLS-1$
        }

        // Get bmId of the TOP object for the BM task (the attribute, when targeted, is
        // re-found by Name on the tx-fetched parent - avoids a stale child handle).
        if (!(object instanceof IBmObject))
        {
            return ToolResult.error("Object is not a BM object").toJson(); //$NON-NLS-1$
        }
        long objectBmId = ((IBmObject) object).bmGetId();

        // Resolve synonym language (only required when a synonym is supplied).
        // Keyed by the language CODE, never the language NAME (see MetadataLanguageUtils).
        final String synonymLanguage;
        if (hasSynonym)
        {
            synonymLanguage = MetadataLanguageUtils.resolveLanguageCode(config, language);
            if (synonymLanguage == null)
            {
                return ToolResult.error("Cannot determine a language code for the synonym " //$NON-NLS-1$
                    + "in this configuration. Specify 'language' explicitly (e.g. 'en' or 'ru').").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            synonymLanguage = null;
        }

        // Execute write task. Mutation MUST run inside the write transaction
        // (CLAUDE.md don't #1): re-fetch the top object by bmId, re-find the attribute
        // by Name on it, then set comment / synonym.
        final String attrName = targetingAttribute ? attributeName : null;
        try
        {
            BmTransactions.<Void>write(bmModel, "SetMetadataProperty", (tx, pm) -> //$NON-NLS-1$
            {
                MdObject top = (MdObject) tx.getObjectById(objectBmId);
                if (top == null)
                {
                    throw new RuntimeException("Object not found in transaction"); //$NON-NLS-1$
                }

                MdObject target = top;
                if (attrName != null)
                {
                    target = findAttribute(top, attrName);
                    if (target == null)
                    {
                        throw new RuntimeException("Attribute not found: " + attrName); //$NON-NLS-1$
                    }
                }

                if (hasComment)
                {
                    target.setComment(comment);
                }
                if (hasSynonym)
                {
                    target.getSynonym().put(synonymLanguage, synonym);
                }
                return null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error setting metadata property", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set property: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // Persist the mutated TOP object to its .mdo on disk. A bare BM write only
        // updates the in-memory model and enqueues the async export, so without this
        // the change is lost on refresh / clean_project / EDT restart. Runs AFTER the
        // write commit. The attribute is nested, so the TOP object's FQN is exported.
        boolean persisted = BmTransactions.forceExportToDisk(project, normalizedFqn);

        ToolResult result = ToolResult.success()
            .put("objectFqn", normalizedFqn) //$NON-NLS-1$
            .put("persisted", persisted); //$NON-NLS-1$
        if (targetingAttribute)
        {
            result.put("attributeName", attributeName); //$NON-NLS-1$
        }
        if (hasComment)
        {
            result.put("comment", comment); //$NON-NLS-1$
        }
        // Echo back the synonym actually written so callers can confirm the localized
        // name without a second get. synonymLanguage is the resolved language CODE.
        if (hasSynonym)
        {
            result.put("synonym", synonym) //$NON-NLS-1$
                .put("language", synonymLanguage); //$NON-NLS-1$
        }
        String targetDesc = targetingAttribute
            ? ("attribute '" + attributeName + "' of " + normalizedFqn) //$NON-NLS-1$ //$NON-NLS-2$
            : normalizedFqn;
        return result
            .put("message", "Properties set successfully on " + targetDesc) //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Finds an attribute of the given object by its programmatic Name
     * (case-insensitive), using the {@code getAttributes()} reflection that the
     * attribute-bearing metadata types share (none of them declare it on a common
     * interface). Returns {@code null} if the type has no attribute collection or
     * no attribute matches.
     *
     * @param object the parent metadata object
     * @param name the attribute Name to match
     * @return the matching attribute, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static MdObject findAttribute(MdObject object, String name)
    {
        try
        {
            java.lang.reflect.Method method = object.getClass().getMethod("getAttributes"); //$NON-NLS-1$
            Object result = method.invoke(object);
            if (result instanceof EList)
            {
                EList<? extends MdObject> attrs = (EList<? extends MdObject>) result;
                for (MdObject attr : attrs)
                {
                    if (name.equalsIgnoreCase(attr.getName()))
                    {
                        return attr;
                    }
                }
            }
        }
        catch (Exception e)
        {
            // Type may not have getAttributes
        }
        return null;
    }
}
