/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

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
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to DELETE a visual ITEM (a field / group / table / button / decoration)
 * from an existing managed form, addressed by its programmatic {@code itemId} (the
 * {@code name} that {@link GetFormStructureTool} lists), via a BM write
 * transaction, then persist the change to the form's {@code Form.form} file on
 * disk.
 * <p>
 * This is a DESTRUCTIVE form-model write: deleting a CONTAINER item (a
 * {@code Group} or {@code Table}, which are themselves {@code FormItemContainer}s)
 * removes its WHOLE subtree of descendant items, because {@code items} is a
 * CONTAINMENT reference and {@link EcoreUtil#remove(EObject)} detaches the item
 * and cascades the removal to everything it contains. Cross-references from
 * elsewhere (e.g. a command bound to a removed button) are NOT rewritten here -
 * the caller should re-read the form with {@code get_form_structure} afterwards.
 * <p>
 * A mandatory confirm-preview guards the deletion (mirroring
 * {@link DeleteMetadataObjectTool}): when {@code confirm} is not {@code true} the
 * tool returns a PREVIEW of exactly what WOULD be removed - the item, its type,
 * and its contained descendant items (name + type) with a count - and makes NO
 * model change at all (it opens NO write transaction). Only {@code confirm:true}
 * performs the delete.
 * <p>
 * The form model is read/mutated entirely through EMF reflection ({@code EObject}
 * / {@code EClass} / {@code eGet}) so the bundle needs no compile-time dependency
 * on the {@code com._1c.g5.v8.dt.form.model} package - mirroring
 * {@link SetFormItemPropertyTool} (the proven form WRITE sibling, whose
 * {@link SetFormItemPropertyTool#findItem(EObject, String)} recursive resolver is
 * reused) and {@link GetFormStructureTool} (the form READER, whose
 * {@code getReferenceList} / {@code nameOf} item-walk is reused so the preview
 * lists descendants exactly the way the reader renders them).
 * <p>
 * Scope: this tool deletes a VISUAL item only. Deleting a form ATTRIBUTE or a form
 * COMMAND is out of scope.
 */
public class DeleteFormItemTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "delete_form_item"; //$NON-NLS-1$

    /** EReference name holding child {@code FormItem}s on a {@code FormItemContainer}. */
    private static final String FEATURE_ITEMS = "items"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Delete a visual ITEM (field / group / table / button / decoration) from an existing " + //$NON-NLS-1$
               "managed form, persisted to disk. Address the item by its itemId - the programmatic name " + //$NON-NLS-1$
               "get_form_structure lists. DESTRUCTIVE: deleting a group/table removes its whole subtree. " + //$NON-NLS-1$
               "Use the two-phase workflow: call WITHOUT confirm to preview what would be removed, review, " + //$NON-NLS-1$
               "then call with confirm=true to apply. Deleting form attributes/commands is out of scope. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('delete_form_item')."; //$NON-NLS-1$
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
                "Programmatic name of the form ITEM to delete - the item Name get_form_structure " + //$NON-NLS-1$
                "lists (searched across the whole nested items tree). (required)", true) //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = execute the deletion; default false = preview only (what would be removed, " + //$NON-NLS-1$
                "including any contained descendant items for a group/table).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        // Two success branches: action="preview" (rich, confirmationRequired=true) and
        // action="deleted" (terse). Both are success=true. The schema is the union; only
        // success is always present (preview is NOT an error).
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the request succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "Either 'preview' or 'deleted'") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("confirmationRequired", //$NON-NLS-1$
                "true on a preview (no change made); absent/false once deleted") //$NON-NLS-1$
            .stringProperty("formPath", "Normalized FQN of the form") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("itemId", "Programmatic name of the targeted form item") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("itemType", "EClass type of the targeted item (e.g. Decoration, FormGroup)") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("descendants", //$NON-NLS-1$
                "Contained descendant items that would be removed with a group/table (preview)") //$NON-NLS-1$
            .integerProperty("descendantCount", "Count of contained descendant items (preview)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("persisted", "Whether the change was saved to the form file on disk") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable description of the result") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# delete_form_item\n\n" //$NON-NLS-1$
            + "Deletes one **visual ITEM** of a managed form (a field / group / table / button / " //$NON-NLS-1$
            + "decoration), addressed by its `itemId`, via a BM write transaction, then force-exports " //$NON-NLS-1$
            + "the form to its `Form.form` file on disk so the deletion survives a refresh / " //$NON-NLS-1$
            + "clean_project / EDT restart.\n\n" //$NON-NLS-1$
            + "## Think twice (destructive)\n\n" //$NON-NLS-1$
            + "This REMOVES a form item. Deleting a CONTAINER item (a group or a table) removes its " //$NON-NLS-1$
            + "WHOLE subtree of contained items - `items` is a containment reference, so the removal " //$NON-NLS-1$
            + "cascades to every descendant. Cross-references from elsewhere are NOT rewritten: e.g. a " //$NON-NLS-1$
            + "command that was bound to a deleted button is left dangling. Always read the form with " //$NON-NLS-1$
            + "`get_form_structure` first, preview the deletion (see below), and re-read the form with " //$NON-NLS-1$
            + "`get_form_structure` AFTER to check for anything left referring to the removed item.\n\n" //$NON-NLS-1$
            + "## Scope\n\n" //$NON-NLS-1$
            + "Deletes a VISUAL item only (something `get_form_structure` lists under `## Items`). " //$NON-NLS-1$
            + "Deleting a form ATTRIBUTE or a form COMMAND is OUT OF SCOPE. To EDIT an item instead of " //$NON-NLS-1$
            + "removing it use `set_form_item_property`; to add one use the EDT form editor.\n\n" //$NON-NLS-1$
            + "## Two-phase workflow (confirm-preview)\n\n" //$NON-NLS-1$
            + "1. **Preview** (`confirm` omitted / false, the default): returns `action='preview'` and " //$NON-NLS-1$
            + "`confirmationRequired=true` with the target item's `itemId` + `itemType`, the list of " //$NON-NLS-1$
            + "`descendants` (name + type) that would be removed with it, and a `descendantCount`. " //$NON-NLS-1$
            + "NOTHING is modified - no write transaction is opened.\n" //$NON-NLS-1$
            + "2. **Delete** (`confirm=true`): performs the deletion (the item and its whole contained " //$NON-NLS-1$
            + "subtree), then force-exports the form to disk. Returns `action='deleted'` with " //$NON-NLS-1$
            + "`persisted` true once the form file was written.\n\n" //$NON-NLS-1$
            + "## Addressing the item (itemId)\n\n" //$NON-NLS-1$
            + "`itemId` is the item's programmatic **Name** - exactly what `get_form_structure` lists " //$NON-NLS-1$
            + "for each item (the Name, NOT the integer id and NOT the title). The item is searched " //$NON-NLS-1$
            + "across the WHOLE nested `items` tree, so a field deep inside a group / table resolves " //$NON-NLS-1$
            + "too. Matched case-insensitively.\n\n" //$NON-NLS-1$
            + "## Parameters\n\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `formPath` (required) - the form FQN. Two accepted shapes:\n" //$NON-NLS-1$
            + "  - `MetadataType.ObjectName.Forms.FormName` (e.g. `Catalog.Products.Forms.ItemForm`).\n" //$NON-NLS-1$
            + "  - `CommonForm.FormName` (e.g. `CommonForm.MyForm`).\n" //$NON-NLS-1$
            + "  The object/form names are the programmatic Name, not the synonym; only the TYPE " //$NON-NLS-1$
            // The escape spells the Russian Catalog token (Spravochnik).
            + "token (`Catalog`/`\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a`, ...) is dialect-aware.\n" //$NON-NLS-1$
            + "- `itemId` (required) - the item's programmatic Name (see above).\n" //$NON-NLS-1$
            + "- `confirm` (optional, default false): false previews, true applies.\n\n" //$NON-NLS-1$
            + "## Transaction & persistence\n\n" //$NON-NLS-1$
            + "On `confirm=true` the deletion runs inside a BM write transaction: the form metadata " //$NON-NLS-1$
            + "object is re-fetched by its BM id inside the transaction, the editable `Form` is reached " //$NON-NLS-1$
            + "via its `getForm()` reference, the item is re-found by Name on the transaction-fetched " //$NON-NLS-1$
            + "form, then removed from its parent container's `items` collection with " //$NON-NLS-1$
            + "`EcoreUtil.remove` (which cascades the contained subtree). After the write commits, the " //$NON-NLS-1$
            + "editable form (its own top object) is force-exported to its `Form.form` file on disk.\n\n" //$NON-NLS-1$
            + "## Examples\n\n" //$NON-NLS-1$
            + "Preview: `{projectName: 'MyProject', formPath: 'CommonForm.MyForm', itemId: 'Total'}`\n\n" //$NON-NLS-1$
            + "Delete it: `{projectName: 'MyProject', formPath: 'CommonForm.MyForm', itemId: 'Total', " //$NON-NLS-1$
            + "confirm: true}`\n\n" //$NON-NLS-1$
            + "Delete a group (and its whole subtree): `{projectName: 'MyProject', " //$NON-NLS-1$
            + "formPath: 'Catalog.Products.Forms.ItemForm', itemId: 'MainGroup', confirm: true}`\n\n" //$NON-NLS-1$
            + "## Gotchas\n\n" //$NON-NLS-1$
            + "- `formPath` not found / not a managed form -> error pointing at get_form_structure.\n" //$NON-NLS-1$
            + "- `itemId` not found -> error pointing at get_form_structure to list the item ids.\n" //$NON-NLS-1$
            + "- Deleting the form's ONLY item leaves an empty form; that is allowed, but re-validate " //$NON-NLS-1$
            + "with get_project_errors afterwards.\n" //$NON-NLS-1$
            + "- If `persisted` is false the in-memory model changed but the form-file write did not " //$NON-NLS-1$
            + "complete - re-check before relying on it on disk."; //$NON-NLS-1$
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String formPath = JsonUtils.extractStringArgument(params, "formPath"); //$NON-NLS-1$
        String itemId = JsonUtils.extractStringArgument(params, "itemId"); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$

        String err = JsonUtils.requireArgument(params, "projectName", //$NON-NLS-1$
            ". Usage: {projectName: 'MyProject', formPath: 'CommonForm.MyForm', itemId: 'Total'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "formPath", //$NON-NLS-1$
            ". Examples: 'CommonForm.MyForm', 'Catalog.Products.Forms.ItemForm'. " //$NON-NLS-1$
            + "Usage: {formPath: 'CommonForm.MyForm', itemId: 'Total'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, "itemId", //$NON-NLS-1$
            ". The itemId is the item's programmatic Name from get_form_structure. " //$NON-NLS-1$
            + "Usage: {formPath: 'CommonForm.MyForm', itemId: 'Total'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        return executeInternal(projectName, formPath, itemId, confirm);
    }

    private String executeInternal(String projectName, String formPath, String itemId, boolean confirm)
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

        return confirm
            ? performDelete(project, bmModel, mdFormBmId, normalizedFormPath, itemId)
            : buildPreview(bmModel, mdFormBmId, normalizedFormPath, itemId);
    }

    // ==================== preview (confirm != true): NO write transaction ====================

    /**
     * Builds the preview of what a delete WOULD remove. Reads the model ONLY inside a
     * READ transaction (CLAUDE.md don't #1) and makes NO change: it re-fetches the
     * form by bmId, re-finds the item by Name, captures its type and walks its
     * contained {@code items} subtree (the same {@code getReferenceList} / {@code nameOf}
     * walk {@link GetFormStructureTool} uses to render) into a flat descendant list
     * BEFORE the read transaction closes. The model is never mutated on a preview - a
     * preview that mutates would be a critical bug.
     */
    private String buildPreview(IBmModel bmModel, long mdFormBmId, String normalizedFormPath, String itemId)
    {
        // Carry the not-found / not-managed outcomes out of the read task as a sentinel so
        // the (transaction-bound) EObjects never escape; the rendered preview is a String.
        PreviewData data = BmTransactions.read(bmModel, "DeleteFormItemPreview", (tx, monitor) -> //$NON-NLS-1$
        {
            EObject txMdForm = tx.getObjectById(mdFormBmId);
            if (txMdForm == null)
            {
                return PreviewData.formMissing();
            }
            EObject formModel = getEditableForm(txMdForm);
            if (formModel == null)
            {
                return PreviewData.notManaged();
            }
            EObject item = SetFormItemPropertyTool.findItem(formModel, itemId);
            if (item == null)
            {
                return PreviewData.itemMissing();
            }
            PreviewData found = new PreviewData();
            found.found = true;
            found.itemType = item.eClass().getName();
            collectDescendants(item, found.descendants);
            return found;
        });

        if (data == null || data.formNotManaged)
        {
            return ToolResult.error("Form has no editable model (the form may be empty, " + //$NON-NLS-1$
                "an ordinary/legacy form, or not yet built): " + normalizedFormPath).toJson(); //$NON-NLS-1$
        }
        if (!data.found)
        {
            return ToolResult.error("Item not found: " + itemId + " on " + normalizedFormPath + ". " + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "The itemId is the item's programmatic Name. " + //$NON-NLS-1$
                "Use get_form_structure to list the form's item ids.").toJson(); //$NON-NLS-1$
        }

        ToolResult result = ToolResult.success()
            .put("action", "preview") //$NON-NLS-1$ //$NON-NLS-2$
            .put("confirmationRequired", true) //$NON-NLS-1$
            .put("formPath", normalizedFormPath) //$NON-NLS-1$
            .put("itemId", itemId) //$NON-NLS-1$
            .put("itemType", data.itemType) //$NON-NLS-1$
            .put("descendants", data.descendants) //$NON-NLS-1$
            .put("descendantCount", data.descendants.size()); //$NON-NLS-1$

        String message = "Preview: deleting item '" + itemId + "' (type " + data.itemType + ") from " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + normalizedFormPath + " would remove " //$NON-NLS-1$
            + (data.descendants.isEmpty()
                ? "the item itself (no contained items)." //$NON-NLS-1$
                : "the item and its " + data.descendants.size() + " contained item(s).") //$NON-NLS-1$ //$NON-NLS-2$
            + " NOTHING was changed. Re-call with confirm:true to delete. " //$NON-NLS-1$
            + "After deleting, re-check the form with get_form_structure (cross-references to the " //$NON-NLS-1$
            + "removed item are not rewritten)."; //$NON-NLS-1$
        return result.put("message", message).toJson(); //$NON-NLS-1$
    }

    /**
     * Walks the item's contained {@code items} subtree depth-first and appends each
     * descendant as a {name, type} map - exactly the way {@link GetFormStructureTool}
     * reads the tree (same {@code getReferenceList} feature + {@code nameOf} accessor),
     * so the preview lists descendants identically to the reader. The item ITSELF is
     * not added (it is reported separately as the target).
     */
    private static void collectDescendants(EObject item, List<Map<String, Object>> out)
    {
        for (EObject child : GetFormStructureTool.getReferenceList(item, FEATURE_ITEMS))
        {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("name", GetFormStructureTool.nameOf(child)); //$NON-NLS-1$
            entry.put("type", child.eClass().getName()); //$NON-NLS-1$
            out.add(entry);
            collectDescendants(child, out);
        }
    }

    /** Mutable carrier for the read-task outcome so transaction-bound EObjects never escape. */
    private static final class PreviewData
    {
        boolean found;
        boolean formNotManaged;
        String itemType;
        final List<Map<String, Object>> descendants = new ArrayList<>();

        static PreviewData formMissing()
        {
            // No txMdForm: treated as "not managed" (same caller-facing outcome).
            return notManaged();
        }

        static PreviewData notManaged()
        {
            PreviewData d = new PreviewData();
            d.formNotManaged = true;
            return d;
        }

        static PreviewData itemMissing()
        {
            return new PreviewData(); // found stays false
        }
    }

    // ==================== delete (confirm == true): write transaction ====================

    /**
     * Performs the deletion inside a BM write transaction (the form-model MUTATION runs
     * ONLY here, CLAUDE.md don't #1): re-fetch the BasicForm by bmId, reach its editable
     * Form, re-find the item by Name on the tx-fetched form, then remove it from its
     * parent container's {@code items} collection via {@link EcoreUtil#remove(EObject)}
     * - which detaches the item and cascades to its contained subtree (containment). The
     * editable Form's OWN FQN is captured for the post-commit export (it is a separate
     * top object serialized to Form.form - the BasicForm-&gt;Form reference is
     * non-containment, so exporting the BasicForm FQN would NOT persist the change).
     */
    private String performDelete(IProject project, IBmModel bmModel, long mdFormBmId,
        String normalizedFormPath, String itemId)
    {
        String formFqn;
        String itemType;
        try
        {
            String[] capturedType = new String[1];
            formFqn = BmTransactions.<String>write(bmModel, "DeleteFormItem", (tx, pm) -> //$NON-NLS-1$
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

                EObject item = SetFormItemPropertyTool.findItem(formModel, itemId);
                if (item == null)
                {
                    throw new RuntimeException("Item not found: " + itemId); //$NON-NLS-1$
                }
                capturedType[0] = item.eClass().getName();

                // Remove from the parent container's `items` (the parent is the Form or a
                // Group/Table). EcoreUtil.remove detaches the item from its containing
                // feature and, because `items` is containment, the contained subtree is
                // removed with it.
                EcoreUtil.remove(item);

                // Capture the editable form's own FQN for the post-commit export.
                return (formModel instanceof IBmObject) ? ((IBmObject)formModel).bmGetFqn() : null;
            });
            itemType = capturedType[0];
        }
        catch (Exception e)
        {
            Activator.logError("Error deleting form item", e); //$NON-NLS-1$
            return ToolResult.error("Failed to delete form item: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // Persist the editable form (its OWN top object) to its Form.form on disk, AFTER
        // the write commit. A bare BM write only updates the in-memory model and enqueues
        // the async export, so without this the deletion is lost on refresh / clean_project
        // / EDT restart. Persist ONLY when the form's own top-object FQN was captured;
        // exporting the BasicForm FQN instead would write the parent .mdo, NOT Form.form -
        // fail closed (persisted=false) rather than report a misleading success.
        boolean persisted = formFqn != null && !formFqn.isEmpty()
            && BmTransactions.forceExportToDisk(project, formFqn);

        return ToolResult.success()
            .put("action", "deleted") //$NON-NLS-1$ //$NON-NLS-2$
            .put("formPath", normalizedFormPath) //$NON-NLS-1$
            .put("itemId", itemId) //$NON-NLS-1$
            .put("itemType", itemType) //$NON-NLS-1$
            .put("persisted", persisted) //$NON-NLS-1$
            .put("message", "Form item '" + itemId + "' deleted from " + normalizedFormPath //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (persisted ? " and persisted to disk." : " (in-memory only; on-disk write did not " //$NON-NLS-1$ //$NON-NLS-2$
                    + "complete - re-check before relying on it).")) //$NON-NLS-1$
            .toJson();
    }

    // ==================== form-model reflection (no compile-time dependency) ====================

    /**
     * Reads the editable form model ({@code com._1c.g5.v8.dt.form.model.Form}) from a
     * metadata form object ({@code BasicForm}) via its {@code getForm()} reference,
     * accessed reflectively to avoid a compile-time dependency on the form model
     * package. Only the managed-form model (which exposes the {@code items} feature) is
     * editable here. Must be called inside the transaction.
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
}
