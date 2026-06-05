/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.refactoring.core.CleanReferenceProblem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Deletes a metadata node (a top-level object or a subordinate member) addressed by a 1C full-name
 * FQN, cascading the cleanup of every reference (BSL code, forms, other metadata) via EDT's
 * md-refactoring service. Two-phase: a bare call previews the affected references; {@code confirm=true}
 * performs the delete. Replaces the former {@code delete_metadata_object}.
 */
public class DeleteMetadataTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "delete_metadata"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Delete a metadata node (object or member, including a FORM member - item / attribute / " //$NON-NLS-1$
            + "command / handler) addressed by a 1C full-name FQN, cascading the cleanup of all " //$NON-NLS-1$
            + "references in BSL code, forms and other metadata. Two-phase: call without confirm to " //$NON-NLS-1$
            + "preview what would be removed, then confirm=true to apply (deletion is hard to reverse). " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('delete_metadata')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty("fqn", //$NON-NLS-1$
                "Full-name FQN of the node to delete (required), e.g. 'Catalog.Products' or " //$NON-NLS-1$
                + "'Document.SalesOrder.Attribute.Amount' (type / kind tokens may be English or " //$NON-NLS-1$
                + "Russian; the Name parts are the programmatic Name, not the synonym).", true) //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = execute the deletion; default false = preview only.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the request succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "Either 'preview' or 'executed'") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("fqn", "FQN of the node targeted for deletion") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("refactoringTitle", "Title of the delete refactoring (preview)") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("items", "Metadata items the deletion would remove (preview)") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("affectedReferences", "References that would be affected (preview)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("affectedReferencesCount", "Count of affected references (preview)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable description of the result") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# delete_metadata\n\n" //$NON-NLS-1$
            + "Deletes one metadata node (a top-level object or one of its members) addressed by a 1C " //$NON-NLS-1$
            + "full-name FQN, and cascades the cleanup to every reference across the configuration: " //$NON-NLS-1$
            + "BSL code, forms and other metadata. Backed by EDT's md-refactoring service, so the same " //$NON-NLS-1$
            + "reference cleanup EDT computes for the IDE delete is what gets applied. The target's " //$NON-NLS-1$
            + "identity is its programmatic Name (not its synonym). Replaces the former " //$NON-NLS-1$
            + "delete_metadata_object.\n\n" //$NON-NLS-1$
            + "## Think twice\n" //$NON-NLS-1$
            + "This is a CASCADING, hard-to-reverse deletion: a wrong target can mass-edit BSL, forms " //$NON-NLS-1$
            + "and metadata across the whole configuration. Always preview first, run it on a " //$NON-NLS-1$
            + "configuration you can revert (version control), and do not execute without an explicit " //$NON-NLS-1$
            + "request. After execute, verify with get_project_errors.\n\n" //$NON-NLS-1$
            + "## When to use\n" //$NON-NLS-1$
            + "Use to remove an existing node and have all references cleaned automatically. To rename " //$NON-NLS-1$
            + "instead use rename_metadata_object; to create use create_metadata.\n\n" //$NON-NLS-1$
            + "## Two-phase workflow\n" //$NON-NLS-1$
            + "1. Preview (confirm omitted / false): returns the refactoring title, the refactoring " //$NON-NLS-1$
            + "items, and the affected references (referencingObject, reference feature, targetObject " //$NON-NLS-1$
            + "FQN) plus a count. Nothing is modified.\n" //$NON-NLS-1$
            + "2. Execute (confirm=true): performs the delete refactoring. Returns action='executed'.\n\n" //$NON-NLS-1$
            + "## Parameters\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `fqn` (required) - the delete target. Top object: 'Type.Name' (e.g. 'Catalog.Products' " //$NON-NLS-1$
            + "deletes the whole catalog). Member: 'Type.Name.Kind.Name', including a NESTED member " //$NON-NLS-1$
            + "(e.g. 'Catalog.X.TabularSection.T.Attribute.A'). Any node create_metadata can address - " //$NON-NLS-1$
            + "an attribute / tabular section / dimension / resource / enum value / command / template " //$NON-NLS-1$
            + "/ recalculation / type-specific child - can be deleted by its FQN.\n" //$NON-NLS-1$
            + "- `confirm` (optional, default false) - false previews, true applies.\n\n" //$NON-NLS-1$
            + "## Form members\n" //$NON-NLS-1$
            + "A FORM member is addressed like its create/modify FQN: `Catalog.X.Form.F.<Kind>.Name` " //$NON-NLS-1$
            + "(or `CommonForm.F.<Kind>.Name`), Kind = Attribute / Command / Field / Button / Group / " //$NON-NLS-1$
            + "Decoration / Table, or an event Handler (`...Form.F.Handler.Event`, item-level " //$NON-NLS-1$
            + "`...Field.Item.Handler.Event`). The same two-phase preview/confirm applies; deleting a " //$NON-NLS-1$
            + "Group / Table cascades its contained subtree. Unlike the mdclass path there is NO " //$NON-NLS-1$
            + "reference cascade for forms: a cross-reference to the removed member (a field's " //$NON-NLS-1$
            + "dataPath, a button's command) is NOT rewritten - re-check with get_form_structure / " //$NON-NLS-1$
            + "get_metadata_details afterwards. The change persists to the form's `Form.form` on disk. " //$NON-NLS-1$
            + "For a form member the preview's `items` list the removed element + its contained " //$NON-NLS-1$
            + "descendants as `{name, type}` and `affectedReferencesCount` is 0 (no cascade is " //$NON-NLS-1$
            + "computed for forms).\n\n" //$NON-NLS-1$
            + "## Bilingual (ru/en)\n" //$NON-NLS-1$
            + "Resolves by the programmatic Name; only the leading TYPE token and the child KIND tokens " //$NON-NLS-1$
            + "are dialect-aware (English or Russian). The synonym is never used to locate the target.\n\n" //$NON-NLS-1$
            + "## Examples\n" //$NON-NLS-1$
            + "- Preview: `{projectName: 'P', fqn: 'Catalog.Products'}`\n" //$NON-NLS-1$
            + "- Execute: `{projectName: 'P', fqn: 'Catalog.Products', confirm: true}`\n" //$NON-NLS-1$
            + "- Delete one attribute: `{projectName: 'P', fqn: 'Document.SalesOrder.Attribute.Amount', " //$NON-NLS-1$
            + "confirm: true}`\n\n" //$NON-NLS-1$
            + "## Gotchas\n" //$NON-NLS-1$
            + "- A malformed nested FQN with an odd trailing token (e.g. 'Catalog.Products.Attribute') " //$NON-NLS-1$
            + "is rejected as not found, so a nested delete never silently falls back to the parent.\n" //$NON-NLS-1$
            + "- Deletion targets the programmatic Name; passing a synonym will not resolve."; //$NON-NLS-1$
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, "projectName", "fqn"); //$NON-NLS-1$ //$NON-NLS-2$
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String fqn = JsonUtils.extractStringArgument(params, "fqn"); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        Configuration config = ctx.config;

        IMdRefactoringService refactoringService = Activator.getDefault().getMdRefactoringService();
        if (refactoringService == null)
        {
            return ToolResult.error("IMdRefactoringService not available").toJson(); //$NON-NLS-1$
        }

        String normFqn = MetadataTypeUtils.normalizeFqn(fqn);

        // A FQN addressing a FORM member (item / attribute / command / handler) is handled by a
        // dedicated branch: form members live on the editable Form content model (a cross-model hop),
        // not the mdclass tree, and are removed directly (the md-refactoring service is mdclass-only).
        FormElementWriter.FormMemberRef formRef = FormElementWriter.parse(normFqn);
        if (formRef != null)
        {
            return deleteFormMember(ctx, normFqn, formRef, confirm);
        }

        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, normFqn);
        if (node == null)
        {
            return ToolResult.error("Node not found: " + fqn + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Check the FQN: 'Type.Name' for a top object (e.g. 'Catalog.Products'), " //$NON-NLS-1$
                + "'Type.Name.Kind.Name' for a member (e.g. 'Document.Order.Attribute.Amount'). " //$NON-NLS-1$
                + "Any node create_metadata can address can be deleted; see " //$NON-NLS-1$
                + "get_tool_guide('create_metadata') for the kinds. " //$NON-NLS-1$
                + "Use get_metadata_objects to find an object's FQN.").toJson(); //$NON-NLS-1$
        }

        IRefactoring refactoring = refactoringService.createMdObjectDeleteRefactoring(
            Collections.singletonList(node.object));
        if (refactoring == null)
        {
            return ToolResult.error("Failed to create delete refactoring for: " + normFqn).toJson(); //$NON-NLS-1$
        }

        return confirm ? performDelete(normFqn, refactoring) : buildPreview(normFqn, refactoring);
    }

    private String buildPreview(String fqn, IRefactoring refactoring)
    {
        List<Map<String, Object>> allItems = new ArrayList<>();
        List<Map<String, Object>> allProblems = new ArrayList<>();

        String title = refactoring.getTitle();

        Collection<IRefactoringItem> items = refactoring.getItems();
        if (items != null)
        {
            for (IRefactoringItem item : items)
            {
                Map<String, Object> itemMap = new java.util.LinkedHashMap<>();
                itemMap.put("name", item.getName()); //$NON-NLS-1$
                itemMap.put("optional", item.isOptional()); //$NON-NLS-1$
                itemMap.put("checked", item.isChecked()); //$NON-NLS-1$
                allItems.add(itemMap);
            }
        }

        RefactoringStatus status = refactoring.getStatus();
        if (status != null)
        {
            Collection<IRefactoringProblem> problems = status.getProblems();
            if (problems != null)
            {
                for (IRefactoringProblem problem : problems)
                {
                    Map<String, Object> problemMap = new java.util.LinkedHashMap<>();
                    if (problem instanceof CleanReferenceProblem crp)
                    {
                        EObject refObj = crp.getReferencingObject();
                        if (refObj instanceof IBmObject bmObj)
                        {
                            problemMap.put("referencingObject", bmObj.bmGetFqn()); //$NON-NLS-1$
                        }
                        EStructuralFeature feat = crp.getReference();
                        if (feat != null)
                        {
                            problemMap.put("reference", feat.getName()); //$NON-NLS-1$
                        }
                    }
                    EObject obj = problem.getObject();
                    if (obj instanceof IBmObject bmObj)
                    {
                        problemMap.put("targetObject", bmObj.bmGetFqn()); //$NON-NLS-1$
                    }
                    if (!problemMap.isEmpty())
                    {
                        allProblems.add(problemMap);
                    }
                }
            }
        }

        return ToolResult.success()
            .put("action", "preview") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fqn", fqn) //$NON-NLS-1$
            .put("refactoringTitle", title) //$NON-NLS-1$
            .put("items", allItems) //$NON-NLS-1$
            .put("affectedReferences", allProblems) //$NON-NLS-1$
            .put("affectedReferencesCount", allProblems.size()) //$NON-NLS-1$
            .put("message", "Preview of delete refactoring. References listed above will be cleaned " //$NON-NLS-1$ //$NON-NLS-2$
                + "up. Call with confirm=true to apply.") //$NON-NLS-1$
            .toJson();
    }

    private String performDelete(String fqn, IRefactoring refactoring)
    {
        try
        {
            refactoring.perform();
            return ToolResult.success()
                .put("action", "executed") //$NON-NLS-1$ //$NON-NLS-2$
                .put("fqn", fqn) //$NON-NLS-1$
                .put("message", "Delete refactoring completed successfully.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error performing delete refactoring", e); //$NON-NLS-1$
            return ToolResult.error("Delete failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    // ==================== FORM members (cross-model hop) ====================

    /**
     * Deletes a FORM member (item / attribute / command / handler) addressed by a form FQN. The member
     * lives on the editable Form content model, so it is removed directly with {@link EcoreUtil#remove}
     * (a Group / Table cascades its contained subtree because {@code items} is containment) - the
     * md-refactoring service that cascades mdclass references does NOT apply here, so a cross-reference
     * to the removed member (a field's dataPath, a button's command) is NOT rewritten; the caller
     * should re-read the form afterwards. Two-phase like the mdclass path: {@code confirm=false}
     * previews what would be removed (no write transaction), {@code confirm=true} removes it and
     * force-exports the content form to {@code Form.form}.
     */
    private String deleteFormMember(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, boolean confirm)
    {
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
            return ToolResult.error("BM model not available").toJson(); //$NON-NLS-1$
        }

        MdObject mdForm = GetFormStructureTool.resolveMdForm(config, ref.formPath);
        if (mdForm == null)
        {
            return ToolResult.error("Form not found for '" + normFqn + "'. Address a form member as " //$NON-NLS-1$ //$NON-NLS-2$
                + "'Type.Object.Form.FormName.<Kind>.Name' or 'CommonForm.FormName.<Kind>.Name' " //$NON-NLS-1$
                + "(Kind = Attribute / Command / Field / Button / Group / Decoration / Table / " //$NON-NLS-1$
                + "Handler).").toJson(); //$NON-NLS-1$
        }
        if (!(mdForm instanceof IBmObject))
        {
            return ToolResult.error("Form is not a BM object").toJson(); //$NON-NLS-1$
        }

        final long mdFormBmId = ((IBmObject)mdForm).bmGetId();
        final boolean handler = FormElementWriter.isHandlerToken(ref.kindToken);

        return confirm
            ? performFormDelete(project, bmModel, mdFormBmId, normFqn, ref, handler)
            : buildFormDeletePreview(bmModel, mdFormBmId, normFqn, ref, handler);
    }

    /** Resolves the delete target: a handler (form/item container) or a member (attribute/command/item). */
    private static EObject resolveFormTarget(EObject formModel, FormElementWriter.FormMemberRef ref,
        boolean handler)
    {
        if (handler)
        {
            EObject container = ref.isItemLevel()
                ? FormElementWriter.findFormItem(formModel, ref.itemName) : formModel;
            return container == null ? null : FormElementWriter.findFormHandler(container, ref.name);
        }
        return FormElementWriter.resolveFormMember(formModel, ref);
    }

    private static String formMemberNotFound(FormElementWriter.FormMemberRef ref, boolean handler)
    {
        if (handler)
        {
            return ToolResult.error("No event handler for '" + ref.name + "' on " //$NON-NLS-1$ //$NON-NLS-2$
                + (ref.isItemLevel() ? ref.formPath + "." + ref.itemName : ref.formPath) //$NON-NLS-1$
                + ". Use get_form_structure to list the handlers.").toJson(); //$NON-NLS-1$
        }
        return ToolResult.error("Form member not found: " + ref.name + " (kind '" + ref.kindToken //$NON-NLS-1$ //$NON-NLS-2$
            + "') on " + ref.formPath + ". Use get_form_structure to list the members.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Preview inside a READ transaction (no mutation): capture the target type + item descendants. */
    private String buildFormDeletePreview(IBmModel bmModel, long mdFormBmId, String normFqn,
        FormElementWriter.FormMemberRef ref, boolean handler)
    {
        FormDeletePreview data = BmTransactions.read(bmModel, "DeleteFormMemberPreview", (tx, pm) -> //$NON-NLS-1$
        {
            EObject txMdForm = tx.getObjectById(mdFormBmId);
            if (txMdForm == null)
            {
                return FormDeletePreview.notManaged();
            }
            EObject formModel = FormElementWriter.getEditableForm(txMdForm);
            if (formModel == null)
            {
                return FormDeletePreview.notManaged();
            }
            EObject target = resolveFormTarget(formModel, ref, handler);
            if (target == null)
            {
                return new FormDeletePreview(); // found stays false
            }
            FormDeletePreview d = new FormDeletePreview();
            d.found = true;
            d.type = target.eClass().getName();
            if (!handler)
            {
                collectItemDescendants(target, d.descendants);
            }
            return d;
        });

        if (data == null || data.notManaged)
        {
            return ToolResult.error("the form has no editable content model (it may be empty, an " //$NON-NLS-1$
                + "ordinary/legacy form, or not yet built): " + ref.formPath).toJson(); //$NON-NLS-1$
        }
        if (!data.found)
        {
            return formMemberNotFound(ref, handler);
        }

        List<Map<String, Object>> removed = new ArrayList<>();
        Map<String, Object> head = new java.util.LinkedHashMap<>();
        head.put("name", ref.name); //$NON-NLS-1$
        head.put("type", data.type); //$NON-NLS-1$
        removed.add(head);
        removed.addAll(data.descendants);

        return ToolResult.success()
            .put("action", "preview") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("refactoringTitle", "Delete form " + (handler ? "handler" : "member") + " " + ref.name) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .put("items", removed) //$NON-NLS-1$
            .put("affectedReferences", Collections.emptyList()) //$NON-NLS-1$
            .put("affectedReferencesCount", 0) //$NON-NLS-1$
            .put("message", "Preview: deleting '" + ref.name + "' (" + data.type + ") from " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ref.formPath + " would remove " //$NON-NLS-1$
                + (data.descendants.isEmpty()
                    ? "the " + (handler ? "handler" : "member") + " itself." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    : "it and its " + data.descendants.size() + " contained item(s).") //$NON-NLS-1$ //$NON-NLS-2$
                + " Cross-references to it (a field's dataPath, a button's command) are NOT rewritten - " //$NON-NLS-1$
                + "re-check with get_form_structure / get_metadata_details afterwards. Call confirm=true " //$NON-NLS-1$
                + "to apply.") //$NON-NLS-1$
            .toJson();
    }

    /** Delete inside a WRITE transaction: EcoreUtil.remove the target, then export the content form. */
    private String performFormDelete(IProject project, IBmModel bmModel, long mdFormBmId, String normFqn,
        FormElementWriter.FormMemberRef ref, boolean handler)
    {
        final String[] capturedType = new String[1];
        final boolean[] missing = new boolean[1];
        String formFqn;
        try
        {
            formFqn = BmTransactions.<String>write(bmModel, "DeleteFormMember", (tx, pm) -> //$NON-NLS-1$
            {
                EObject txMdForm = tx.getObjectById(mdFormBmId);
                if (txMdForm == null)
                {
                    throw new RuntimeException("Form object not found in transaction"); //$NON-NLS-1$
                }
                EObject formModel = FormElementWriter.getEditableForm(txMdForm);
                if (formModel == null)
                {
                    throw new RuntimeException("the form has no editable content model"); //$NON-NLS-1$
                }
                EObject target = resolveFormTarget(formModel, ref, handler);
                if (target == null)
                {
                    missing[0] = true;
                    return null;
                }
                capturedType[0] = target.eClass().getName();
                // items is containment, so removing a Group/Table cascades its contained subtree.
                EcoreUtil.remove(target);
                return (formModel instanceof IBmObject) ? ((IBmObject)formModel).bmGetFqn() : null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error deleting form member", e); //$NON-NLS-1$
            return ToolResult.error("Failed to delete form member: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }
        if (missing[0])
        {
            return formMemberNotFound(ref, handler);
        }

        boolean persisted = formFqn != null && !formFqn.isEmpty()
            && BmTransactions.forceExportToDisk(project, formFqn);

        return ToolResult.success()
            .put("action", "executed") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("message", "Deleted form " + (handler ? "handler" : "member") + " '" + ref.name //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + "' (" + capturedType[0] + ") from " + ref.formPath //$NON-NLS-1$ //$NON-NLS-2$
                + (persisted ? " and persisted to disk." //$NON-NLS-1$
                    : " (in-memory only; on-disk write did not complete - re-check before relying on " //$NON-NLS-1$
                        + "it).")) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Walks the item's contained {@code items} subtree depth-first, appending each descendant as a
     * {name, type} map (the same {@code getReferenceList} / {@code nameOf} walk the form reader uses),
     * so the preview lists what a container delete cascades. The item ITSELF is not added.
     */
    private static void collectItemDescendants(EObject item, List<Map<String, Object>> out)
    {
        for (EObject child : GetFormStructureTool.getReferenceList(item, "items")) //$NON-NLS-1$
        {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("name", GetFormStructureTool.nameOf(child)); //$NON-NLS-1$
            entry.put("type", child.eClass().getName()); //$NON-NLS-1$
            out.add(entry);
            collectItemDescendants(child, out);
        }
    }

    /** Mutable carrier for the form-delete preview read task so tx-bound EObjects never escape. */
    private static final class FormDeletePreview
    {
        boolean found;
        boolean notManaged;
        String type;
        final List<Map<String, Object>> descendants = new ArrayList<>();

        static FormDeletePreview notManaged()
        {
            FormDeletePreview d = new FormDeletePreview();
            d.notManaged = true;
            return d;
        }
    }
}
