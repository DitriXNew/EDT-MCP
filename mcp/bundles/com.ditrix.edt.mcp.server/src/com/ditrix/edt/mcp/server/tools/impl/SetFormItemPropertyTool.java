/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

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
 * Tool to set the Title (bilingual), Visible flag and/or ReadOnly flag of an
 * existing ITEM (a field / group / button / decoration / table) of a managed form,
 * addressed by its programmatic {@code itemId} (the {@code name} that
 * {@link GetFormStructureTool} lists), via a BM write transaction, then persist the
 * change to the form's {@code Form.form} file on disk.
 * <p>
 * Scope is deliberately narrow: only the three properties {@code title} /
 * {@code visible} / {@code readOnly}. Other properties (data binding, type, layout,
 * ...) are OUT OF SCOPE - edit those in the EDT form editor or via the
 * export_configuration_to_xml -&gt; edit XML -&gt; import_configuration_from_xml path.
 * <p>
 * The form model is mutated entirely through EMF reflection ({@code EObject} /
 * {@code EClass} / {@code eGet} / {@code eSet}) so the bundle needs no compile-time
 * dependency on the {@code com._1c.g5.v8.dt.form.model} package - mirroring
 * {@link AddFormAttributeTool} (the proven form WRITE path) and
 * {@link GetFormStructureTool} (the form READER). The item is addressed by the SAME
 * programmatic name the reader reports, searched recursively across the WHOLE nested
 * {@code items} tree (groups / tables nest further items).
 * <p>
 * The form model layering (confirmed against the EDT form model):
 * <ul>
 * <li>{@code title} is an {@code EMap<String,String>} keyed by the language CODE on
 * the {@code Titled} supertype (same map form attributes / commands use) - written
 * via {@link AddFormAttributeTool#putTitle};</li>
 * <li>{@code visible} is a plain {@code EBoolean} on the {@code Visible} supertype
 * (NOT the {@code AdjustableBoolean} {@code userVisible} - that is a separate
 * feature) - set with {@code eSet} of a {@link Boolean};</li>
 * <li>{@code readOnly} is a plain {@code EBoolean} declared on {@code FormField},
 * {@code Group} and {@code Table} - it is NOT present on every item type (e.g. a
 * {@code Decoration} has no {@code readOnly}), so setting it on an item that lacks
 * the feature fails closed with a clear error rather than silently no-op'ing.</li>
 * </ul>
 */
public class SetFormItemPropertyTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "set_form_item_property"; //$NON-NLS-1$

    /** EReference name holding child {@code FormItem}s on a {@code FormItemContainer}. */
    private static final String FEATURE_ITEMS = "items"; //$NON-NLS-1$
    /** EAttribute name (plain EBoolean) carrying visibility on a {@code Visible}. */
    private static final String FEATURE_VISIBLE = "visible"; //$NON-NLS-1$
    /** EAttribute name (plain EBoolean) carrying read-only on {@code FormField}/{@code Group}/{@code Table}. */
    private static final String FEATURE_READ_ONLY = "readOnly"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Set the Title (bilingual), Visible flag and/or ReadOnly flag of an existing ITEM " + //$NON-NLS-1$
               "(field / group / button / decoration / table) of a managed form, persisted to disk. " + //$NON-NLS-1$
               "Address the item by its itemId - the programmatic name get_form_structure lists. " + //$NON-NLS-1$
               "At least one of title / visible / readOnly must be supplied. Other properties (data " + //$NON-NLS-1$
               "binding, type, layout, ...) are out of scope - edit those in EDT or via the export/" + //$NON-NLS-1$
               "import XML path. Full parameters and examples: call get_tool_guide('set_form_item_property')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("formPath", //$NON-NLS-1$
                "Form FQN: 'MetadataType.ObjectName.Forms.FormName' or 'CommonForm.FormName' " + //$NON-NLS-1$
                "(e.g. 'Catalog.Products.Forms.ItemForm'). Names are the programmatic Name, " + //$NON-NLS-1$
                "not the synonym; the TYPE token may be en/ru. (required)", true) //$NON-NLS-1$
            .stringProperty("itemId", //$NON-NLS-1$
                "Programmatic name of the form ITEM to edit - the item Name get_form_structure " + //$NON-NLS-1$
                "lists (searched across the whole nested items tree). (required)", true) //$NON-NLS-1$
            .stringProperty("title", //$NON-NLS-1$
                "Optional new display Title, written for 'language' or the config default language. " + //$NON-NLS-1$
                "Provide at least one of title / visible / readOnly") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Optional language code for the title, e.g. 'ru'/'en' (default: config default). " + //$NON-NLS-1$
                "Only consulted when 'title' is supplied") //$NON-NLS-1$
            .booleanProperty("visible", //$NON-NLS-1$
                "Optional new Visible flag (true/false). Acted on only when the key is present, so " + //$NON-NLS-1$
                "visible=false differs from 'visible omitted'") //$NON-NLS-1$
            .booleanProperty("readOnly", //$NON-NLS-1$
                "Optional new ReadOnly flag (true/false). Only fields / groups / tables have it; " + //$NON-NLS-1$
                "setting it on an item without the property (e.g. a decoration) is rejected") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the item property was set", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("formPath", "Normalized FQN of the form") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("itemId", "Programmatic name of the edited form item") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("title", "Title written, when a title was provided") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("language", "Language code the title was written for") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("visible", "Visible flag written, when one was provided") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("readOnly", "ReadOnly flag written, when one was provided") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("persisted", "Whether the change was saved to the form file on disk") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable confirmation message") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# set_form_item_property\n\n" //$NON-NLS-1$
            + "Sets the **Title** (bilingual), **Visible** flag and/or **ReadOnly** flag of an " //$NON-NLS-1$
            + "existing ITEM of a managed form (a field / group / button / decoration / table), " //$NON-NLS-1$
            + "addressed by its `itemId`, via a BM write transaction, then force-exports the form to " //$NON-NLS-1$
            + "its `Form.form` file on disk so the change survives a refresh / clean_project / EDT " //$NON-NLS-1$
            + "restart.\n\n" //$NON-NLS-1$
            + "## Scope\n\n" //$NON-NLS-1$
            + "Only `title`, `visible` and `readOnly`. Editing an item's data binding, type, layout " //$NON-NLS-1$
            + "or any other property is OUT OF SCOPE - do that in the EDT form editor, or export the " //$NON-NLS-1$
            + "form with export_configuration_to_xml, edit the XML, and re-import it with " //$NON-NLS-1$
            + "import_configuration_from_xml. Adding a NEW item is also out of scope (this tool only " //$NON-NLS-1$
            + "edits an EXISTING one); to add a form ATTRIBUTE use add_form_attribute.\n\n" //$NON-NLS-1$
            + "## Addressing the item (itemId)\n\n" //$NON-NLS-1$
            + "`itemId` is the item's programmatic **Name** - exactly what `get_form_structure` lists " //$NON-NLS-1$
            + "for each item (the Name, NOT the integer id and NOT the title). The item is searched " //$NON-NLS-1$
            + "across the WHOLE nested `items` tree, so a field deep inside a group / table resolves " //$NON-NLS-1$
            + "too. Read the form first with `get_form_structure` to get the exact item names.\n\n" //$NON-NLS-1$
            + "## Parameters\n\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `formPath` (required) - the form FQN. Two accepted shapes:\n" //$NON-NLS-1$
            + "  - `MetadataType.ObjectName.Forms.FormName` (e.g. `Catalog.Products.Forms.ItemForm`).\n" //$NON-NLS-1$
            + "  - `CommonForm.FormName` (e.g. `CommonForm.MyForm`).\n" //$NON-NLS-1$
            + "  The object/form names are the programmatic Name, not the synonym; only the TYPE " //$NON-NLS-1$
            // The escape spells the Russian Catalog token (Spravochnik).
            + "token (`Catalog`/`\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a`, ...) is dialect-aware.\n" //$NON-NLS-1$
            + "- `itemId` (required) - the item's programmatic Name (see above). Matched " //$NON-NLS-1$
            + "case-insensitively.\n" //$NON-NLS-1$
            + "- `title` (optional) - new display title, written for `language` or the configuration " //$NON-NLS-1$
            + "default language. An empty string is treated as 'not provided', not as a clear.\n" //$NON-NLS-1$
            + "- `language` (optional) - language CODE for the title (`ru`, `en`, ...). Only consulted " //$NON-NLS-1$
            + "when `title` is supplied. Defaults to the configuration's first / default language code.\n" //$NON-NLS-1$
            + "- `visible` (optional) - new Visible flag (`true`/`false`). Acted on ONLY when the key " //$NON-NLS-1$
            + "is present in the call, so `visible: false` is a real set, distinct from omitting it.\n" //$NON-NLS-1$
            + "- `readOnly` (optional) - new ReadOnly flag (`true`/`false`). Same present-key rule. " //$NON-NLS-1$
            + "Only fields, groups and tables carry `readOnly`; setting it on an item that has no " //$NON-NLS-1$
            + "such property (e.g. a decoration) is rejected with a clear error.\n\n" //$NON-NLS-1$
            + "**At least one of `title` / `visible` / `readOnly` must be provided** - a call with " //$NON-NLS-1$
            + "none of them is rejected.\n\n" //$NON-NLS-1$
            + "## Bilingual (ru/en) notes\n\n" //$NON-NLS-1$
            + "The item's title EMap is keyed by the language CODE (`ru`/`en`), never the language " //$NON-NLS-1$
            + "name. Setting the title for one language does NOT remove the title already stored for " //$NON-NLS-1$
            + "another - each language code is an independent map entry. The item is resolved by its " //$NON-NLS-1$
            + "programmatic Name; only the TYPE token in `formPath` is dialect-aware.\n\n" //$NON-NLS-1$
            + "## Transaction & persistence\n\n" //$NON-NLS-1$
            + "The mutation runs inside a BM write transaction: the form metadata object is re-fetched " //$NON-NLS-1$
            + "by its BM id inside the transaction, the editable `Form` is reached via its `getForm()` " //$NON-NLS-1$
            + "reference, the item is re-found by Name on the transaction-fetched form, then its " //$NON-NLS-1$
            + "title / visible / readOnly are set. After the write commits, the editable form (its own " //$NON-NLS-1$
            + "top object) is force-exported to its `Form.form` file on disk.\n\n" //$NON-NLS-1$
            + "## Result\n\n" //$NON-NLS-1$
            + "JSON with `formPath`, `itemId`, the `title` / `visible` / `readOnly` actually written " //$NON-NLS-1$
            + "(and the resolved `language` code for the title), `persisted` (true once the form file " //$NON-NLS-1$
            + "was exported to disk), and a `message`.\n\n" //$NON-NLS-1$
            + "## Examples\n\n" //$NON-NLS-1$
            + "Set a title: `{projectName: 'MyProject', formPath: 'CommonForm.MyForm', " //$NON-NLS-1$
            + "itemId: 'Total', title: 'Grand total'}`\n\n" //$NON-NLS-1$
            + "Hide an item: `{projectName: 'MyProject', formPath: 'CommonForm.MyForm', " //$NON-NLS-1$
            + "itemId: 'Total', visible: false}`\n\n" //$NON-NLS-1$
            + "Make a field read-only with a Russian title: `{projectName: 'MyProject', " //$NON-NLS-1$
            + "formPath: 'Catalog.Products.Forms.ItemForm', itemId: 'Price', readOnly: true, " //$NON-NLS-1$
            + "title: '\u0426\u0435\u043d\u0430', language: 'ru'}`\n\n" //$NON-NLS-1$
            + "## Gotchas\n\n" //$NON-NLS-1$
            + "- `formPath` not found / not a managed form -> error pointing at get_form_structure.\n" //$NON-NLS-1$
            + "- `itemId` not found -> error pointing at get_form_structure to list the item ids.\n" //$NON-NLS-1$
            + "- `readOnly` on an item type that has no such property (e.g. a decoration) -> error.\n" //$NON-NLS-1$
            + "- If `persisted` is false the in-memory model changed but the form-file write did not " //$NON-NLS-1$
            + "complete - re-check before relying on it on disk."; //$NON-NLS-1$
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        String itemId = JsonUtils.extractStringArgument(params, "itemId"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        // The boolean flags use a PRESENCE sentinel (params.containsKey) so an explicit
        // 'false' is distinguishable from 'omitted' (a plain default-false would lose
        // that distinction). extractBooleanArgument supplies the value only when present;
        // both reads keep the schema/execute parity check satisfied for these keys.
        boolean hasVisible = params.containsKey("visible"); //$NON-NLS-1$
        boolean visible = JsonUtils.extractBooleanArgument(params, "visible", false); //$NON-NLS-1$
        boolean hasReadOnly = params.containsKey("readOnly"); //$NON-NLS-1$
        boolean readOnly = JsonUtils.extractBooleanArgument(params, "readOnly", false); //$NON-NLS-1$

        String err = JsonUtils.requireArgument(params, "projectName", //$NON-NLS-1$
            ". Usage: {projectName: 'MyProject', formPath: 'CommonForm.MyForm', itemId: 'Total', " //$NON-NLS-1$
            + "title: 'Grand total'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "formPath", //$NON-NLS-1$
            ". Examples: 'CommonForm.MyForm', 'Catalog.Products.Forms.ItemForm'. " //$NON-NLS-1$
            + "Usage: {formPath: 'CommonForm.MyForm', itemId: 'Total', visible: false}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "itemId", //$NON-NLS-1$
            ". The itemId is the item's programmatic Name from get_form_structure. " //$NON-NLS-1$
            + "Usage: {formPath: 'CommonForm.MyForm', itemId: 'Total', visible: false}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        // At least one editable property must be supplied. An empty title is treated as
        // "not provided" (NOT as a clear). The boolean flags count only when their key is
        // present. This guard runs BEFORE any model access (headless-testable).
        boolean hasTitle = title != null && !title.isEmpty();
        if (!hasTitle && !hasVisible && !hasReadOnly)
        {
            return ToolResult.error("Nothing to set: provide at least one of 'title', 'visible' or " //$NON-NLS-1$
                + "'readOnly'. Usage: {formPath: 'CommonForm.MyForm', itemId: 'Total', " //$NON-NLS-1$
                + "title: 'Grand total'} or {..., visible: false} or {..., readOnly: true}. " //$NON-NLS-1$
                + "To change other item properties (data binding, type, layout, ...) edit the form " //$NON-NLS-1$
                + "in EDT or use export_configuration_to_xml -> edit XML -> " //$NON-NLS-1$
                + "import_configuration_from_xml.").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, formPath, itemId, title, language, hasTitle,
            hasVisible, visible, hasReadOnly, readOnly);
    }

    private String executeInternal(String projectName, String formPath, String itemId, String title,
        String language, boolean hasTitle, boolean hasVisible, boolean visible, boolean hasReadOnly,
        boolean readOnly)
    {
        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        IProject project = ctx.project;
        Configuration config = ctx.config;

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

        // Resolve the metadata form object (a BasicForm) from the FQN path, reusing
        // get_form_structure's resolver so addressing stays identical across read/write.
        MdObject mdForm = GetFormStructureTool.resolveMdForm(config, formPath);
        if (mdForm == null)
        {
            return ToolResult.error("Form not found: " + formPath + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Expected 'MetadataType.ObjectName.Forms.FormName' or 'CommonForm.FormName'. " + //$NON-NLS-1$
                "Names are the programmatic Name, not the synonym. " + //$NON-NLS-1$
                "Use get_form_structure to inspect the form.").toJson(); //$NON-NLS-1$
        }
        if (!(mdForm instanceof IBmObject))
        {
            return ToolResult.error("Form object is not a BM object: " + formPath).toJson(); //$NON-NLS-1$
        }

        final long mdFormBmId = ((IBmObject)mdForm).bmGetId();
        final String normalizedFormPath = MetadataTypeUtils.normalizeFqn(formPath);

        // Resolve title language (only required when a title is supplied). Keyed by the
        // language CODE, never the language NAME (see MetadataLanguageUtils).
        final String titleLanguage;
        if (hasTitle)
        {
            titleLanguage = MetadataLanguageUtils.resolveLanguageCode(config, language);
            if (titleLanguage == null)
            {
                return ToolResult.error("Cannot determine a language code for the title " + //$NON-NLS-1$
                    "in this configuration. Specify 'language' explicitly (e.g. 'en' or 'ru').").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            titleLanguage = null;
        }

        // Execute the write task. The form-model MUTATION runs ONLY here (CLAUDE.md
        // don't #1): re-fetch the BasicForm by bmId, reach its editable Form, re-find the
        // item by Name on the tx-fetched form, then set the requested properties. The
        // editable Form's OWN FQN is captured for the post-commit export (it is a separate
        // top object serialized to Form.form - the BasicForm->Form reference is
        // non-containment, so exporting the BasicForm FQN would NOT persist the change).
        String formFqn;
        try
        {
            formFqn = BmTransactions.<String>write(bmModel, "SetFormItemProperty", (tx, pm) -> //$NON-NLS-1$
            {
                EObject txMdForm = tx.getObjectById(mdFormBmId);
                if (txMdForm == null)
                {
                    throw new RuntimeException("Form object not found in transaction"); //$NON-NLS-1$
                }
                EObject formModel = getEditableForm(txMdForm);
                if (formModel == null)
                {
                    throw new RuntimeException("Form has no editable model (the form may be empty, " //$NON-NLS-1$
                        + "an ordinary/legacy form, or not yet built)"); //$NON-NLS-1$
                }

                EObject item = findItem(formModel, itemId);
                if (item == null)
                {
                    throw new RuntimeException("Item not found: " + itemId); //$NON-NLS-1$
                }

                // readOnly is NOT modeled on every item type. Fail closed with a clear
                // message rather than silently no-op'ing when the feature is absent.
                if (hasReadOnly && item.eClass().getEStructuralFeature(FEATURE_READ_ONLY) == null)
                {
                    throw new RuntimeException("Item '" + itemId + "' (type " //$NON-NLS-1$ //$NON-NLS-2$
                        + item.eClass().getName() + ") has no 'readOnly' property. Only fields, " //$NON-NLS-1$
                        + "groups and tables are read-only-able."); //$NON-NLS-1$
                }
                // visible is on the Visible supertype shared by data/visual items, but a
                // defensive guard keeps the failure clear if an exotic item type lacks it.
                if (hasVisible && item.eClass().getEStructuralFeature(FEATURE_VISIBLE) == null)
                {
                    throw new RuntimeException("Item '" + itemId + "' (type " //$NON-NLS-1$ //$NON-NLS-2$
                        + item.eClass().getName() + ") has no 'visible' property."); //$NON-NLS-1$
                }

                if (hasTitle)
                {
                    AddFormAttributeTool.putTitle(item, titleLanguage, title);
                }
                if (hasVisible)
                {
                    item.eSet(item.eClass().getEStructuralFeature(FEATURE_VISIBLE), Boolean.valueOf(visible));
                }
                if (hasReadOnly)
                {
                    item.eSet(item.eClass().getEStructuralFeature(FEATURE_READ_ONLY), Boolean.valueOf(readOnly));
                }

                // Capture the editable form's own FQN for the post-commit export.
                return (formModel instanceof IBmObject) ? ((IBmObject)formModel).bmGetFqn() : null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error setting form item property", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set form item property: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // Persist the editable form (its OWN top object) to its Form.form on disk, AFTER
        // the write commit. A bare BM write only updates the in-memory model and enqueues
        // the async export, so without this the change is lost on refresh / clean_project /
        // EDT restart. We persist ONLY when the form's own top-object FQN was captured;
        // exporting the BasicForm FQN instead would write the parent .mdo, NOT Form.form -
        // fail closed (persisted=false) rather than report a misleading success.
        boolean persisted = formFqn != null && !formFqn.isEmpty()
            && BmTransactions.forceExportToDisk(project, formFqn);

        ToolResult result = ToolResult.success()
            .put("formPath", normalizedFormPath) //$NON-NLS-1$
            .put("itemId", itemId) //$NON-NLS-1$
            .put("persisted", persisted); //$NON-NLS-1$
        if (hasTitle)
        {
            result.put("title", title) //$NON-NLS-1$
                .put("language", titleLanguage); //$NON-NLS-1$
        }
        if (hasVisible)
        {
            result.put("visible", visible); //$NON-NLS-1$
        }
        if (hasReadOnly)
        {
            result.put("readOnly", readOnly); //$NON-NLS-1$
        }
        return result
            .put("message", "Form item '" + itemId + "' updated successfully on " + normalizedFormPath) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
    }

    // ==================== form-model reflection (no compile-time dependency) ====================

    /**
     * Reads the editable form model ({@code com._1c.g5.v8.dt.form.model.Form}) from a
     * metadata form object ({@code BasicForm}) via its {@code getForm()} reference,
     * accessed reflectively to avoid a compile-time dependency on the form model
     * package. Only the managed-form model (which exposes the {@code items} feature) is
     * editable here. Must be called inside the write transaction.
     * <p>
     * Mirrors the same helper in {@link AddFormAttributeTool} / {@link GetFormStructureTool}
     * (private there); replicated here until a shared form-model accessor is extracted.
     *
     * @param txMdForm the transaction-bound {@code BasicForm} EObject
     * @return the editable form model EObject, or {@code null} if absent / not managed
     */
    private static EObject getEditableForm(EObject txMdForm)
    {
        try
        {
            Method getForm = txMdForm.getClass().getMethod("getForm"); //$NON-NLS-1$
            Object form = getForm.invoke(txMdForm);
            if (form instanceof EObject
                && ((EObject)form).eClass().getEStructuralFeature(FEATURE_ITEMS) != null)
            {
                return (EObject)form;
            }
        }
        catch (ReflectiveOperationException e)
        {
            // No getForm() / inaccessible - treated as "no editable model".
        }
        return null;
    }

    /**
     * Finds a form item by its programmatic {@code name} (case-insensitive),
     * searching the WHOLE nested {@code items} tree depth-first - groups and tables
     * nest further items, and an item can be addressed at any depth. Uses the SAME
     * {@code items} feature and {@code name} accessor {@link GetFormStructureTool} uses
     * to RENDER the tree, so an item the reader lists is addressable here by that id.
     *
     * @param container the form model (or a nested container) to search
     * @param itemId the item Name to match
     * @return the matching item EObject, or {@code null} if none matches
     */
    static EObject findItem(EObject container, String itemId)
    {
        for (EObject item : GetFormStructureTool.getReferenceList(container, FEATURE_ITEMS))
        {
            if (itemId.equalsIgnoreCase(GetFormStructureTool.nameOf(item)))
            {
                return item;
            }
            EObject nested = findItem(item, itemId);
            if (nested != null)
            {
                return nested;
            }
        }
        return null;
    }
}
