/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

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
 * Tool to add a visual ITEM (a {@code FormGroup} container or a {@code Decoration}
 * label, what {@link GetFormStructureTool} lists under {@code ## Items}) to an
 * existing managed form, optionally nested under an existing container item, via a
 * BM write transaction, then persist the change to the form's {@code Form.form} file
 * on disk.
 * <p>
 * This is the visual-ITEM sibling of {@link AddFormAttributeTool} (the form-ATTRIBUTE
 * writer) and {@link AddFormCommandTool} (the form-COMMAND writer) and reuses their
 * proven pattern EXACTLY: resolve the {@code BasicForm} via
 * {@link GetFormStructureTool#resolveMdForm}, capture its BM id, run all creation /
 * mutation inside a {@link BmTransactions#write} task, reach the editable {@code Form}
 * inside the task, create the concrete item EClass reflectively from the form EPackage,
 * set {@code name} + {@code title}, append it, and force-export the editable form's OWN
 * FQN to disk AFTER the commit (failing closed if the FQN was not captured). The
 * {@code title} EMap writer is the shared {@link AddFormAttributeTool#putTitle}, the
 * recursive parent resolver is the shared {@link SetFormItemPropertyTool#findItem}.
 * <p>
 * <b>Implemented itemTypes (v1):</b>
 * <ul>
 * <li>{@code group} - a {@code FormGroup} ({@code UsualGroup}). It is created with
 * {@code type = UsualGroup} and a default {@code UsualGroupExtInfo}, mirroring what the
 * platform's own {@code FormObjectFactory.newFormGroup} produces (sans the layout
 * defaults that the form-item type-management service fills in - those are filled when
 * the form is rebuilt). A group is itself a container, so later items can nest under
 * it via {@code parentId}.</li>
 * <li>{@code decoration} - a {@code Decoration} ({@code Label}). Created with
 * {@code type = Label} and a default {@code LabelDecorationExtInfo}, mirroring
 * {@code FormObjectFactory.newDecoration}.</li>
 * </ul>
 * Neither needs a data/command binding, so both are reflectively safe.
 * <p>
 * <b>Reserved / deferred itemTypes:</b> {@code field} and {@code button}. A
 * {@code FormField} requires a value-type AND a {@code dataPath} bound to a form
 * attribute (an {@code AbstractDataPath} containment chain, NOT a plain string); a
 * {@code Button} requires a command binding ({@code commandName} / a command-source
 * reference). Both are the same "complex containment binding" class
 * {@link AddFormCommandTool} defers for its {@code action}. Building them reflectively
 * is risky (it can corrupt the form), so they are REJECTED with a clear error that
 * points at the EDT form editor. The {@code attributeName} / {@code commandName}
 * parameters are accepted (declared + read for schema parity) but only apply to those
 * reserved types, so they currently have no effect.
 * <p>
 * The form model is mutated entirely through EMF reflection ({@code EObject} /
 * {@code EClass} / {@code EFactory} / {@code eSet}) so the bundle needs no compile-time
 * dependency on the {@code com._1c.g5.v8.dt.form.model} package.
 */
public class AddFormItemTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "add_form_item"; //$NON-NLS-1$

    /** EReference name holding child {@code FormItem}s on a {@code FormItemContainer}. */
    private static final String FEATURE_ITEMS = "items"; //$NON-NLS-1$
    /** EAttribute name carrying the programmatic name on a {@code NamedElement}. */
    private static final String FEATURE_NAME = "name"; //$NON-NLS-1$
    /** EAttribute name (plain EBoolean) carrying visibility on a {@code Visible}. */
    private static final String FEATURE_VISIBLE = "visible"; //$NON-NLS-1$
    /** EAttribute name (an enum) carrying the managed item subtype on a group/decoration. */
    private static final String FEATURE_TYPE = "type"; //$NON-NLS-1$
    /** EReference name carrying the per-subtype extra info on a group/decoration. */
    private static final String FEATURE_EXT_INFO = "extInfo"; //$NON-NLS-1$
    /** EAttribute name (int) carrying the form-unique item id on a {@code FormItem}. */
    private static final String FEATURE_ID = "id"; //$NON-NLS-1$

    /** itemType value: a {@code FormGroup} container. */
    private static final String ITEM_TYPE_GROUP = "group"; //$NON-NLS-1$
    /** itemType value: a {@code Decoration} label. */
    private static final String ITEM_TYPE_DECORATION = "decoration"; //$NON-NLS-1$
    /** itemType value: a {@code FormField} (RESERVED - needs a dataPath binding). */
    private static final String ITEM_TYPE_FIELD = "field"; //$NON-NLS-1$
    /** itemType value: a {@code Button} (RESERVED - needs a command binding). */
    private static final String ITEM_TYPE_BUTTON = "button"; //$NON-NLS-1$

    /** Concrete form-model EClass classifier name for a group. */
    private static final String ECLASS_FORM_GROUP = "FormGroup"; //$NON-NLS-1$
    /** Concrete form-model EClass classifier name for a decoration. */
    private static final String ECLASS_DECORATION = "Decoration"; //$NON-NLS-1$
    /** Abstract base EClass classifier for every visual form item (id space is form-wide). */
    private static final String ECLASS_FORM_ITEM = "FormItem"; //$NON-NLS-1$
    /** Default ext-info EClass for a group ({@code UsualGroupExtInfo}). */
    private static final String ECLASS_USUAL_GROUP_EXT_INFO = "UsualGroupExtInfo"; //$NON-NLS-1$
    /** Default ext-info EClass for a decoration ({@code LabelDecorationExtInfo}). */
    private static final String ECLASS_LABEL_DECORATION_EXT_INFO = "LabelDecorationExtInfo"; //$NON-NLS-1$

    /** Default {@code type} enum literal for a group ({@code ManagedFormGroupType.UsualGroup}). */
    private static final String TYPE_LITERAL_USUAL_GROUP = "UsualGroup"; //$NON-NLS-1$
    /** Default {@code type} enum literal for a decoration ({@code ManagedFormDecorationType.Label}). */
    private static final String TYPE_LITERAL_LABEL = "Label"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Add a visual ITEM (a group container or a decoration/label, what get_form_structure " + //$NON-NLS-1$
               "lists under ## Items) to an existing managed form, persisted to disk. Optionally nest " + //$NON-NLS-1$
               "it under an existing container via parentId. itemType is an enum: 'group' and " + //$NON-NLS-1$
               "'decoration' are supported; 'field' and 'button' are reserved (they need a data/" + //$NON-NLS-1$
               "command binding - edit those in EDT). To edit an existing item use " + //$NON-NLS-1$
               "set_form_item_property; to delete one use delete_form_item. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('add_form_item')."; //$NON-NLS-1$
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
            .stringProperty("name", //$NON-NLS-1$
                "Name for the new form item (required). A valid 1C identifier.", true) //$NON-NLS-1$
            .enumProperty("itemType", //$NON-NLS-1$
                "Kind of item to add (required). 'group' = a FormGroup container; 'decoration' = a " + //$NON-NLS-1$
                "Decoration label. 'field' and 'button' are RESERVED: they need a data/command " + //$NON-NLS-1$
                "binding and are rejected - add them in the EDT form editor.", true, //$NON-NLS-1$
                ITEM_TYPE_GROUP, ITEM_TYPE_DECORATION, ITEM_TYPE_FIELD, ITEM_TYPE_BUTTON)
            .stringProperty("parentId", //$NON-NLS-1$
                "Optional itemId (programmatic Name) of an existing CONTAINER item (group/table) to " + //$NON-NLS-1$
                "nest the new item under. Omit to add at the form root. The parent is searched across " + //$NON-NLS-1$
                "the whole nested items tree; a non-container parent is rejected.") //$NON-NLS-1$
            .stringProperty("title", //$NON-NLS-1$
                "Optional display title; written for 'language' or the config default language") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Optional language code for the title, e.g. 'ru'/'en' (default: config default)") //$NON-NLS-1$
            .stringProperty("attributeName", //$NON-NLS-1$
                "Optional. RESERVED: only applies to a 'field' (which is reserved). The form " + //$NON-NLS-1$
                "attribute a field would display; not wired by this version. See the guide.") //$NON-NLS-1$
            .stringProperty("commandName", //$NON-NLS-1$
                "Optional. RESERVED: only applies to a 'button' (which is reserved). The form " + //$NON-NLS-1$
                "command a button would invoke; not wired by this version. See the guide.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the form item was added", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("formPath", "Normalized FQN of the form") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("name", "Programmatic name of the added form item") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("itemType", "The itemType created ('group' or 'decoration')") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("parentId", "The parent container itemId, when the item was nested") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("persisted", "Whether the change was saved to the form file on disk") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("title", "Display title written, when a title was provided") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("language", "Language code the title was written for") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable confirmation message") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# add_form_item\n\n" //$NON-NLS-1$
            + "Adds one **visual ITEM** - a group container or a decoration/label - to an existing " //$NON-NLS-1$
            + "managed form (what `get_form_structure` lists under `## Items`) via a BM write " //$NON-NLS-1$
            + "transaction, optionally nested under an existing container item, then force-exports " //$NON-NLS-1$
            + "the form to its `Form.form` file on disk so the change survives a refresh / " //$NON-NLS-1$
            + "clean_project / EDT restart.\n\n" //$NON-NLS-1$
            + "## Supported vs reserved itemTypes\n\n" //$NON-NLS-1$
            + "- `group` (supported) - a `FormGroup` (a `UsualGroup`). It is a CONTAINER, so other " //$NON-NLS-1$
            + "items can later nest under it via `parentId`.\n" //$NON-NLS-1$
            + "- `decoration` (supported) - a `Decoration` (a `Label`).\n" //$NON-NLS-1$
            + "- `field` (RESERVED) - a `FormField` needs a value type AND a `dataPath` bound to a " //$NON-NLS-1$
            + "form attribute (a complex containment chain in the form model, not a plain string). " //$NON-NLS-1$
            + "Building it reflectively is risky, so it is REJECTED - add a field in the EDT form " //$NON-NLS-1$
            + "editor, then edit it with `set_form_item_property`.\n" //$NON-NLS-1$
            + "- `button` (RESERVED) - a `Button` needs a command binding (the same complex chain " //$NON-NLS-1$
            + "`add_form_command` defers for its action). REJECTED - add a button in EDT.\n\n" //$NON-NLS-1$
            + "## When to use\n\n" //$NON-NLS-1$
            + "Use to scaffold a form's layout: add a group to organize items, or a decoration for a " //$NON-NLS-1$
            + "static label. Read the form first with `get_form_structure` to see existing item names " //$NON-NLS-1$
            + "(a duplicate name anywhere in the items tree is rejected) and the container itemIds you " //$NON-NLS-1$
            + "can use as `parentId`. Ordinary/legacy (non-managed) forms have no editable model and " //$NON-NLS-1$
            + "are rejected.\n\n" //$NON-NLS-1$
            + "## Parameters\n\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `formPath` (required) - the form FQN. Two accepted shapes:\n" //$NON-NLS-1$
            + "  - `MetadataType.ObjectName.Forms.FormName` (e.g. `Catalog.Products.Forms.ItemForm`).\n" //$NON-NLS-1$
            + "  - `CommonForm.FormName` (e.g. `CommonForm.MyForm`).\n" //$NON-NLS-1$
            + "  The object/form names are the programmatic Name, not the synonym; only the TYPE " //$NON-NLS-1$
            // The escape spells the Russian Catalog token (Spravochnik).
            + "token (`Catalog`/`\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a`, ...) is dialect-aware.\n" //$NON-NLS-1$
            + "- `name` (required) - new item name. Must be a valid 1C identifier: start with a " //$NON-NLS-1$
            + "letter or `_`, then letters / digits / `_` only. Cyrillic letters are valid. A " //$NON-NLS-1$
            + "case-insensitive duplicate of any existing item (at any depth) is rejected.\n" //$NON-NLS-1$
            + "- `itemType` (required) - one of `group`, `decoration`, `field`, `button`. Only " //$NON-NLS-1$
            + "`group` and `decoration` are created; `field` / `button` are reserved (rejected).\n" //$NON-NLS-1$
            + "- `parentId` (optional) - the itemId (programmatic Name) of an existing CONTAINER item " //$NON-NLS-1$
            + "(a group or table) to nest the new item under, searched across the whole nested items " //$NON-NLS-1$
            + "tree. Omit to add at the form root. A non-existent parentId, or one that is not a " //$NON-NLS-1$
            + "container (no `items` collection), is rejected.\n" //$NON-NLS-1$
            + "- `title` (optional) - localized display title. Written for `language`, or the " //$NON-NLS-1$
            + "configuration default language when `language` is omitted.\n" //$NON-NLS-1$
            + "- `language` (optional) - language CODE for the title (`ru`, `en`, ...). Only " //$NON-NLS-1$
            + "consulted when `title` is supplied. Defaults to the configuration's first / default " //$NON-NLS-1$
            + "language code.\n" //$NON-NLS-1$
            + "- `attributeName` (optional) - RESERVED. Only meaningful for a `field` (itself " //$NON-NLS-1$
            + "reserved); the form attribute the field would display. Not wired by this version.\n" //$NON-NLS-1$
            + "- `commandName` (optional) - RESERVED. Only meaningful for a `button` (itself " //$NON-NLS-1$
            + "reserved); the form command the button would invoke. Not wired by this version.\n\n" //$NON-NLS-1$
            + "## Bilingual (ru/en) notes\n\n" //$NON-NLS-1$
            + "The item's title EMap is keyed by the language CODE (`ru`/`en`), never the language " //$NON-NLS-1$
            + "name. If you pass `language`, pass the code. The form and the parent are resolved by " //$NON-NLS-1$
            + "their programmatic Name; only the TYPE token in `formPath` is dialect-aware.\n\n" //$NON-NLS-1$
            + "## Transaction & persistence\n\n" //$NON-NLS-1$
            + "The mutation runs inside a BM write transaction: the form metadata object is re-fetched " //$NON-NLS-1$
            + "by its BM id inside the transaction, the editable `Form` is reached via its `getForm()` " //$NON-NLS-1$
            + "reference, the parent container is re-resolved by `parentId` on the tx-fetched form (or " //$NON-NLS-1$
            + "the form root when omitted), the new item is created and appended to that container's " //$NON-NLS-1$
            + "`items` collection. After the write commits, the editable form (its own top object) is " //$NON-NLS-1$
            + "force-exported to its `Form.form` file on disk.\n\n" //$NON-NLS-1$
            + "## Result\n\n" //$NON-NLS-1$
            + "JSON with `formPath`, `name`, `itemType`, `parentId` (when nested), `persisted` (true " //$NON-NLS-1$
            + "once the form file was exported to disk), and a `message`. When a title was written, " //$NON-NLS-1$
            + "`title` and the resolved `language` code are echoed back.\n\n" //$NON-NLS-1$
            + "## Examples\n\n" //$NON-NLS-1$
            + "A group at the form root: `{projectName: 'MyProject', formPath: 'CommonForm.MyForm', " //$NON-NLS-1$
            + "name: 'MainGroup', itemType: 'group'}`\n\n" //$NON-NLS-1$
            + "A decoration with a localized title: `{projectName: 'MyProject', " //$NON-NLS-1$
            + "formPath: 'CommonForm.MyForm', name: 'Hint', itemType: 'decoration', title: 'Note', " //$NON-NLS-1$
            + "language: 'en'}`\n\n" //$NON-NLS-1$
            + "A decoration nested under a group: `{projectName: 'MyProject', " //$NON-NLS-1$
            + "formPath: 'CommonForm.MyForm', name: 'Hint', itemType: 'decoration', " //$NON-NLS-1$
            + "parentId: 'MainGroup'}`\n\n" //$NON-NLS-1$
            + "## Gotchas\n\n" //$NON-NLS-1$
            + "- `formPath` not found / not a managed form -> error pointing at get_form_structure.\n" //$NON-NLS-1$
            + "- `itemType: 'field'` / `'button'` -> rejected (reserved); add those in the EDT editor.\n" //$NON-NLS-1$
            + "- `parentId` not found, or pointing at a non-container item -> error pointing at " //$NON-NLS-1$
            + "get_form_structure to list the container itemIds.\n" //$NON-NLS-1$
            + "- a duplicate item name (anywhere in the items tree) is rejected.\n" //$NON-NLS-1$
            + "- If `persisted` is false the in-memory model changed but the form-file write did not " //$NON-NLS-1$
            + "complete - re-check before relying on it on disk."; //$NON-NLS-1$
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        String name = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String itemType = JsonUtils.extractStringArgument(params, "itemType"); //$NON-NLS-1$
        String parentId = JsonUtils.extractStringArgument(params, "parentId"); //$NON-NLS-1$
        String title = JsonUtils.extractStringArgument(params, "title"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        // 'attributeName' / 'commandName' are reserved (see guide): read so they are
        // declared/parity-clean, but this version creates only group/decoration, which
        // take no data/command binding.
        String attributeName = JsonUtils.extractStringArgument(params, "attributeName"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$

        String err = JsonUtils.requireArgument(params, "projectName", //$NON-NLS-1$
            ". Usage: {projectName: 'MyProject', formPath: 'CommonForm.MyForm', name: 'MainGroup', " //$NON-NLS-1$
            + "itemType: 'group'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "formPath", //$NON-NLS-1$
            ". Examples: 'CommonForm.MyForm', 'Catalog.Products.Forms.ItemForm'. " //$NON-NLS-1$
            + "Usage: {formPath: 'CommonForm.MyForm', name: 'MainGroup', itemType: 'group'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "name", //$NON-NLS-1$
            ". Usage: {formPath: 'CommonForm.MyForm', name: 'MainGroup', itemType: 'group'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "itemType", //$NON-NLS-1$
            ". One of 'group', 'decoration'. " //$NON-NLS-1$
            + "Usage: {formPath: 'CommonForm.MyForm', name: 'MainGroup', itemType: 'group'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        if (!isValidIdentifier(name))
        {
            return ToolResult.error("Invalid form item name '" + name + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "A name must start with a letter or underscore and contain only letters, digits and underscores.").toJson(); //$NON-NLS-1$
        }

        // Normalize + validate the itemType enum BEFORE any model access (headless-testable).
        // A value outside the schema enum echoes the bad value + the accepted set; a reserved
        // (field/button) value is rejected with a steer to the EDT editor.
        String normalizedType = itemType.trim().toLowerCase(Locale.ROOT);
        if (!isKnownItemType(normalizedType))
        {
            return ToolResult.error("Invalid itemType '" + itemType + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "Accepted values: group, decoration, field, button " //$NON-NLS-1$
                + "(field and button are reserved).").toJson(); //$NON-NLS-1$
        }
        if (ITEM_TYPE_FIELD.equals(normalizedType) || ITEM_TYPE_BUTTON.equals(normalizedType))
        {
            return ToolResult.error("itemType '" + normalizedType + "' is reserved and not yet " //$NON-NLS-1$ //$NON-NLS-2$
                + "supported by add_form_item. A " + normalizedType + " needs a " //$NON-NLS-1$ //$NON-NLS-2$
                + (ITEM_TYPE_FIELD.equals(normalizedType) ? "data binding (dataPath to a form attribute)" //$NON-NLS-1$
                    : "command binding") //$NON-NLS-1$
                + " which is a complex containment chain in the form model. Add the " //$NON-NLS-1$
                + normalizedType + " in the EDT form editor, then edit it with " //$NON-NLS-1$
                + "set_form_item_property. Supported itemTypes here: group, decoration.").toJson(); //$NON-NLS-1$
        }

        return executeInternal(projectName, formPath, name, normalizedType, parentId, title,
            language, attributeName, commandName);
    }

    private String executeInternal(String projectName, String formPath, String name, String itemType,
        String parentId, String title, String language, String attributeName, String commandName)
    {
        // 'attributeName' / 'commandName' are reserved (only apply to field/button, which are
        // rejected above). Touch them so they are not flagged unused and the intent is logged.
        if ((attributeName != null && !attributeName.isEmpty())
            || (commandName != null && !commandName.isEmpty()))
        {
            Activator.logInfo("add_form_item: 'attributeName'/'commandName' are reserved for the " //$NON-NLS-1$
                + "field/button itemTypes (not supported yet); ignored for '" + name + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        }

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

        // Resolve the metadata form object (a BasicForm) from the FQN path. The TYPE
        // token may be en/ru; the object/form names are the programmatic Name. Reuses
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
        if (title != null && !title.isEmpty())
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

        final boolean hasParent = parentId != null && !parentId.isEmpty();
        final String fixedName = name;
        final String fixedType = itemType;
        final String fixedTitle = title;

        // Execute the write task. The form-model MUTATION runs ONLY here (CLAUDE.md
        // don't #1): re-fetch the BasicForm by bmId, reach its editable Form, re-resolve
        // the parent container by parentId (or use the form root), create the concrete
        // item EClass reflectively, set name/visible/type/extInfo/title, append it. The
        // editable Form's OWN FQN is captured for the post-commit export (a separate top
        // object serialized to Form.form - the BasicForm->Form reference is non-containment).
        String formFqn;
        try
        {
            formFqn = BmTransactions.<String>write(bmModel, "AddFormItem", (tx, pm) -> //$NON-NLS-1$
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

                // Resolve the container to append into: the named parent, or the form root.
                EObject container;
                if (hasParent)
                {
                    container = SetFormItemPropertyTool.findItem(formModel, parentId);
                    if (container == null)
                    {
                        throw new RuntimeException("Parent item not found: " + parentId); //$NON-NLS-1$
                    }
                    if (container.eClass().getEStructuralFeature(FEATURE_ITEMS) == null)
                    {
                        throw new RuntimeException("Parent item '" + parentId + "' (type " //$NON-NLS-1$ //$NON-NLS-2$
                            + container.eClass().getName() + ") is not a container - it has no 'items' " //$NON-NLS-1$
                            + "collection. Only a group or table can be a parent."); //$NON-NLS-1$
                    }
                }
                else
                {
                    container = formModel;
                }

                // A duplicate name anywhere in the items tree is rejected (the item Name is
                // the addressing id used by set_form_item_property / delete_form_item).
                if (SetFormItemPropertyTool.findItem(formModel, fixedName) != null)
                {
                    throw new RuntimeException("Form item already exists: " + fixedName); //$NON-NLS-1$
                }

                EObject newItem = createConcreteItem(formModel, fixedType);
                if (newItem == null)
                {
                    throw new RuntimeException("Cannot create a '" + fixedType //$NON-NLS-1$
                        + "' item for this form model"); //$NON-NLS-1$
                }
                setStringFeature(newItem, FEATURE_NAME, fixedName);
                setBooleanFeature(newItem, FEATURE_VISIBLE, true);
                // Assign a unique non-zero item id (max existing FormItem id + 1), mirroring
                // the platform's FormItemManagementService -> FormIdentifierService.getNextItemId.
                // Without it the item keeps id=0, which EDT's form-invalid-item-id check flags as
                // a MAJOR/ERROR marker, and a nested add would duplicate id 0. Computed over the
                // current tree BEFORE the new item is appended (it is not yet in the tree).
                setIntFeature(newItem, FEATURE_ID, nextItemId(formModel));
                initManagedItem(formModel, newItem, fixedType);
                if (titleLanguage != null)
                {
                    AddFormAttributeTool.putTitle(newItem, titleLanguage, fixedTitle);
                }
                addItem(container, newItem);

                // Capture the editable form's own FQN for the post-commit export.
                return (formModel instanceof IBmObject) ? ((IBmObject)formModel).bmGetFqn() : null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error adding form item", e); //$NON-NLS-1$
            return ToolResult.error("Failed to add form item: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // Persist the editable form (its OWN top object) to its Form.form on disk, AFTER
        // the write commit. A bare BM write only updates the in-memory model and enqueues
        // the async export, so without this the new item is lost on refresh / clean_project
        // / EDT restart. We persist ONLY when the form's own top-object FQN was captured;
        // exporting the BasicForm FQN instead would write the parent .mdo, NOT Form.form -
        // fail closed (persisted=false) rather than report a misleading success.
        boolean persisted = formFqn != null && !formFqn.isEmpty()
            && BmTransactions.forceExportToDisk(project, formFqn);

        ToolResult result = ToolResult.success()
            .put("formPath", normalizedFormPath) //$NON-NLS-1$
            .put("name", name) //$NON-NLS-1$
            .put("itemType", itemType) //$NON-NLS-1$
            .put("persisted", persisted); //$NON-NLS-1$
        if (hasParent)
        {
            result.put("parentId", parentId); //$NON-NLS-1$
        }
        if (titleLanguage != null)
        {
            result.put("title", title) //$NON-NLS-1$
                .put("language", titleLanguage); //$NON-NLS-1$
        }
        return result
            .put("message", "Form item '" + name + "' (" + itemType + ") added successfully to " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + normalizedFormPath + (hasParent ? " under '" + parentId + "'" : "")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
    }

    // ==================== form-model reflection (no compile-time dependency) ====================

    /**
     * Reads the editable form model ({@code com._1c.g5.v8.dt.form.model.Form}) from a
     * metadata form object ({@code BasicForm}) via its {@code getForm()} reference,
     * accessed reflectively to avoid a compile-time dependency on the form model
     * package. Only the managed-form model (which exposes the {@code items} feature) is
     * mutable here. Must be called inside the write transaction.
     * <p>
     * Mirrors the same helper in {@link AddFormAttributeTool} / {@link GetFormStructureTool}
     * / {@link SetFormItemPropertyTool} (private there); replicated here until a shared
     * form-model accessor is extracted.
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
     * Creates a new concrete form-item instance of the EClass matching {@code itemType}
     * ({@code FormGroup} for {@code group}, {@code Decoration} for {@code decoration}),
     * using the form model's REAL generated {@code EFactory}. The {@code items} EReference
     * is polymorphic (its EType is the abstract {@code FormItem}), so - unlike
     * {@link AddFormAttributeTool} which can use the collection's EType directly - the
     * concrete subtype EClass is looked up by classifier NAME on the form model's
     * {@code EPackage} (reached from {@code formModel.eClass().getEPackage()}). No new
     * bundle dependency is needed.
     *
     * @param formModel the editable form model EObject (used to reach the form EPackage)
     * @param itemType the (normalized) supported itemType: {@code group} or {@code decoration}
     * @return the new item EObject, or {@code null} if the EClass / factory cannot be resolved
     */
    private static EObject createConcreteItem(EObject formModel, String itemType)
    {
        String classifierName = ITEM_TYPE_GROUP.equals(itemType) ? ECLASS_FORM_GROUP : ECLASS_DECORATION;
        EClass itemClass = formEClass(formModel, classifierName);
        if (itemClass == null || itemClass.getEPackage() == null)
        {
            return null;
        }
        return itemClass.getEPackage().getEFactoryInstance().create(itemClass);
    }

    /**
     * Initializes the managed-item subtype the way the platform's {@code FormObjectFactory}
     * does for a fresh item: sets the {@code type} enum (e.g. {@code UsualGroup} /
     * {@code Label}) and attaches a default {@code extInfo} of the matching EClass
     * ({@code UsualGroupExtInfo} / {@code LabelDecorationExtInfo}). Both are reached
     * reflectively from the form EPackage; best-effort - a missing feature is skipped
     * (the item is still a valid, if barer, model element).
     *
     * @param formModel the editable form model EObject (to reach the form EPackage)
     * @param item the freshly created item EObject
     * @param itemType the (normalized) supported itemType
     */
    private static void initManagedItem(EObject formModel, EObject item, String itemType)
    {
        String typeLiteral = ITEM_TYPE_GROUP.equals(itemType) ? TYPE_LITERAL_USUAL_GROUP : TYPE_LITERAL_LABEL;
        String extInfoClassifier = ITEM_TYPE_GROUP.equals(itemType)
            ? ECLASS_USUAL_GROUP_EXT_INFO : ECLASS_LABEL_DECORATION_EXT_INFO;
        setEnumFeature(item, FEATURE_TYPE, typeLiteral);
        setExtInfo(formModel, item, extInfoClassifier);
    }

    /**
     * Looks up a concrete EClass by classifier name on the form model's EPackage.
     *
     * @return the EClass, or {@code null} if the package has no such concrete classifier
     */
    private static EClass formEClass(EObject formModel, String classifierName)
    {
        EPackage pkg = formModel.eClass().getEPackage();
        if (pkg == null)
        {
            return null;
        }
        EClassifier classifier = pkg.getEClassifier(classifierName);
        return (classifier instanceof EClass) ? (EClass)classifier : null;
    }

    /**
     * Sets an enum-valued EAttribute by its literal name (e.g. {@code "UsualGroup"}),
     * resolving the {@link EEnumLiteral} from the feature's {@link EEnum} type. Best-effort:
     * silently does nothing if the feature is absent or not an enum, or the literal is
     * unknown.
     */
    private static void setEnumFeature(EObject object, String featureName, String literal)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (!(feature instanceof EAttribute))
        {
            return;
        }
        EClassifier type = ((EAttribute)feature).getEAttributeType();
        if (!(type instanceof EEnum))
        {
            return;
        }
        EEnumLiteral enumLiteral = ((EEnum)type).getEEnumLiteralByLiteral(literal);
        if (enumLiteral != null)
        {
            object.eSet(feature, enumLiteral.getInstance());
        }
    }

    /**
     * Creates and attaches a default {@code extInfo} of the given concrete EClass to the
     * item via its {@code extInfo} EReference. Best-effort: skips when the item has no
     * {@code extInfo} feature or the EClass cannot be resolved.
     */
    private static void setExtInfo(EObject formModel, EObject item, String extInfoClassifier)
    {
        EStructuralFeature feature = item.eClass().getEStructuralFeature(FEATURE_EXT_INFO);
        if (!(feature instanceof EReference))
        {
            return;
        }
        EClass extInfoClass = formEClass(formModel, extInfoClassifier);
        if (extInfoClass == null || extInfoClass.getEPackage() == null)
        {
            return;
        }
        EObject extInfo = extInfoClass.getEPackage().getEFactoryInstance().create(extInfoClass);
        item.eSet(feature, extInfo);
    }

    private static void setStringFeature(EObject object, String featureName, String value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, value);
        }
    }

    private static void setBooleanFeature(EObject object, String featureName, boolean value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, Boolean.valueOf(value));
        }
    }

    private static void setIntFeature(EObject object, String featureName, int value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature != null)
        {
            object.eSet(feature, Integer.valueOf(value));
        }
    }

    /**
     * The next free form-item id = the max existing {@code FormItem} id across the WHOLE
     * form + 1, mirroring {@code FormIdentifierService.getNextItemId}/{@code getMaxId} (the
     * platform's add path always assigns this). A fresh reflectively-created item keeps
     * id=0, which EDT's {@code form-invalid-item-id} check flags as a MAJOR/ERROR marker,
     * and an id that duplicates ANY existing form item is flagged the same way.
     */
    private static int nextItemId(EObject formModel)
    {
        return maxItemId(formModel) + 1;
    }

    /**
     * Maximum existing {@code id} over EVERY {@code FormItem} in the whole form - NOT just
     * the {@code items} subtree. Form-item ids are unique form-WIDE: many {@code FormItem}s
     * live OUTSIDE {@code items} - an item's own {@code contextMenu} / {@code extendedTooltip},
     * the form {@code autoCommandBar}, table holders, additions - each carrying its own id.
     * Mirrors {@code FormIdentifierService.getMaxId}, which walks the form's full containment
     * tree ({@code EcoreUtil.getAllContents}) filtered to {@code FormItem}. Counting only
     * {@code items} under-counts and hands out an id that collides with, say, an existing
     * contextMenu/extendedTooltip - which EDT's {@code form-invalid-item-id} check then flags
     * as "Form item identifier is duplicated by another form item".
     * <p>
     * {@code FormAttribute}/{@code FormCommand} also carry an {@code id} but are NOT
     * {@code FormItem}s (separate id spaces), so the {@code FormItem} EClass filter excludes
     * them. If that EClass cannot be resolved the walk falls back to counting every object
     * that carries an {@code id} - which yields a higher-but-still-unique id, never a
     * colliding one.
     */
    private static int maxItemId(EObject formModel)
    {
        EClassifier formItemClassifier =
            formModel.eClass().getEPackage().getEClassifier(ECLASS_FORM_ITEM);
        boolean filterByFormItem = formItemClassifier instanceof EClass;
        int max = 0;
        for (TreeIterator<EObject> it = formModel.eAllContents(); it.hasNext();)
        {
            EObject obj = it.next();
            if (filterByFormItem && !((EClass)formItemClassifier).isInstance(obj))
            {
                continue;
            }
            EStructuralFeature idFeature = obj.eClass().getEStructuralFeature(FEATURE_ID);
            if (idFeature != null && obj.eGet(idFeature) instanceof Integer)
            {
                max = Math.max(max, ((Integer)obj.eGet(idFeature)).intValue());
            }
        }
        return max;
    }

    /**
     * Appends the new item to the container's {@code items} EList (a containment
     * reference), accessed reflectively. Throws when the feature is missing/not a list.
     */
    @SuppressWarnings("unchecked")
    private static void addItem(EObject container, EObject item)
    {
        EStructuralFeature feature = container.eClass().getEStructuralFeature(FEATURE_ITEMS);
        if (feature == null || !feature.isMany())
        {
            throw new RuntimeException("Container has no 'items' collection"); //$NON-NLS-1$
        }
        Object value = container.eGet(feature);
        if (value instanceof List<?>)
        {
            ((List<EObject>)value).add(item);
        }
        else
        {
            throw new RuntimeException("Container 'items' feature is not a list"); //$NON-NLS-1$
        }
    }

    /** @return {@code true} if the value is one of the four schema enum itemTypes. */
    private static boolean isKnownItemType(String itemType)
    {
        return ITEM_TYPE_GROUP.equals(itemType) || ITEM_TYPE_DECORATION.equals(itemType)
            || ITEM_TYPE_FIELD.equals(itemType) || ITEM_TYPE_BUTTON.equals(itemType);
    }

    /**
     * Checks that a name is a valid 1C identifier: starts with a letter or underscore
     * and contains only letters, digits and underscores. Delegates to the shared
     * {@link AddFormAttributeTool#isValidIdentifier} so all form-write tools share one
     * rule.
     *
     * @param name the candidate item name
     * @return {@code true} if the name is a valid identifier
     */
    static boolean isValidIdentifier(String name)
    {
        return AddFormAttributeTool.isValidIdentifier(name);
    }
}
