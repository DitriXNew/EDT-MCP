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

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
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
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.FormValidationException;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataPathResolver;
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
        return "Delete a metadata node (object or member, including a FORM object " //$NON-NLS-1$
            + "'Type.Object.Form.Name' or a FORM member - item / attribute / " //$NON-NLS-1$
            + "command / handler) addressed by a 1C full-name FQN, cascading the cleanup of all " //$NON-NLS-1$
            + "references in BSL code, forms and other metadata. Two-phase: call without confirm to " //$NON-NLS-1$
            + "preview what would be removed, then confirm=true to apply (deletion is hard to reverse). " //$NON-NLS-1$
            + "If the node is still referenced by metadata the refactoring cannot auto-clean, a " //$NON-NLS-1$
            + "confirm=true delete is BLOCKED and the referencing objects are listed; pass force=true " //$NON-NLS-1$
            + "to delete anyway (those references are left dangling). " //$NON-NLS-1$
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
            .booleanProperty("force", //$NON-NLS-1$
                "true = delete even when the node is still referenced by other metadata that the " //$NON-NLS-1$
                + "refactoring cannot auto-clean (those incoming references are left dangling). " //$NON-NLS-1$
                + "Default false = on confirm=true the deletion is BLOCKED and the referencing " //$NON-NLS-1$
                + "objects are listed (independent of 'confirm', which is the preview gate).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the request succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "Either 'preview', 'executed' or 'blocked'") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("fqn", "FQN of the node targeted for deletion") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("refactoringTitle", "Title of the delete refactoring (preview)") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("items", "Metadata items the deletion would remove (preview)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("blocking", "Whether the listed blockingReferences BLOCK the delete (the " //$NON-NLS-1$ //$NON-NLS-2$
                + "refactoring cannot auto-clean them; a confirm=true delete is refused unless force=true)") //$NON-NLS-1$
            .objectArrayProperty("blockingReferences", "Incoming references the refactoring cannot " //$NON-NLS-1$ //$NON-NLS-2$
                + "auto-clean: listed in the preview, the reason a delete is refused " //$NON-NLS-1$
                + "(action='blocked'), or left dangling when force=true (action='executed')") //$NON-NLS-1$
            .integerProperty("blockingReferencesCount", "Count of blocking references") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("forced", "Whether the delete was forced past blocking references") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable description of the result") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
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
        boolean force = JsonUtils.extractBooleanArgument(params, "force", false); //$NON-NLS-1$

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

        // A 4-part form FQN (Type.Object.Form.FormName) addresses the FORM OBJECT itself. create_metadata
        // accepts this FQN to CREATE an owned form; to stay symmetric, delete it the same way: an
        // owned BasicForm is removed by cascade through its owner's 'forms' collection, not by the
        // md-refactoring service (it is not a top object, so resolveExisting / the delete refactoring see
        // nothing here). A CommonForm (2 parts) is NOT matched - it is a real top object handled below.
        FormElementWriter.FormObjectRef formObjectRef = FormElementWriter.parseFormObjectCreate(normFqn);
        if (formObjectRef != null)
        {
            return deleteFormObject(ctx, normFqn, formObjectRef, confirm);
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

        return confirm ? performDelete(normFqn, refactoring, force) : buildPreview(normFqn, refactoring);
    }

    private String buildPreview(String fqn, IRefactoring refactoring)
    {
        List<Map<String, Object>> allItems = new ArrayList<>();

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

        // Incoming references EDT could not clean automatically — these BLOCK a confirm=true delete
        // unless force=true is also passed (mirrors the EDT/Configurator UI's pre-delete check).
        List<Map<String, Object>> blocking = collectBlockingProblems(refactoring);
        boolean hasBlocking = !blocking.isEmpty();

        String message = hasBlocking
            ? "Preview of delete refactoring. This node is referenced by " + blocking.size() //$NON-NLS-1$
                + " object(s) the refactoring CANNOT auto-clean: a confirm=true delete will be BLOCKED " //$NON-NLS-1$
                + "unless force=true is also passed (force leaves these references dangling)." //$NON-NLS-1$
            : "Preview of delete refactoring. References listed above will be cleaned up. " //$NON-NLS-1$
                + "Call with confirm=true to apply."; //$NON-NLS-1$

        // The preview's "affected" references ARE exactly the blocking set, so the list is
        // serialized ONCE under the blocking* fields shared with action='blocked' / 'executed'.
        return ToolResult.success()
            .put("action", "preview") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fqn", fqn) //$NON-NLS-1$
            .put("refactoringTitle", title) //$NON-NLS-1$
            .put("items", allItems) //$NON-NLS-1$
            .put("blocking", hasBlocking) //$NON-NLS-1$
            .put("blockingReferences", blocking) //$NON-NLS-1$
            .put("blockingReferencesCount", blocking.size()) //$NON-NLS-1$
            .put("message", message) //$NON-NLS-1$
            .toJson();
    }

    private String performDelete(String fqn, IRefactoring refactoring, boolean force)
    {
        // EDT's own reference check: if the node is still referenced by metadata the refactoring
        // cannot auto-clean and the caller did not force, refuse the delete and report the
        // referencing objects (mirrors the UI). 'confirm' is the preview gate; 'force' overrides
        // this reference block — the two are intentionally distinct.
        List<Map<String, Object>> blocking = collectBlockingProblems(refactoring);
        if (!blocking.isEmpty() && !force)
        {
            return ToolResult.error("Cannot delete '" + fqn + "': it is still referenced by " //$NON-NLS-1$ //$NON-NLS-2$
                    + blocking.size() + " object(s) that the refactoring cannot auto-clean. Remove the " //$NON-NLS-1$
                    + "references first, or call again with force=true to delete anyway (the references " //$NON-NLS-1$
                    + "will be left dangling).") //$NON-NLS-1$
                .put("action", "blocked") //$NON-NLS-1$ //$NON-NLS-2$
                .put("fqn", fqn) //$NON-NLS-1$
                .put("blocking", true) //$NON-NLS-1$
                .put("blockingReferences", blocking) //$NON-NLS-1$
                .put("blockingReferencesCount", blocking.size()) //$NON-NLS-1$
                .toJson();
        }

        try
        {
            refactoring.perform();
            ToolResult result = ToolResult.success()
                .put("action", "executed") //$NON-NLS-1$ //$NON-NLS-2$
                .put("fqn", fqn) //$NON-NLS-1$
                .put("forced", force); //$NON-NLS-1$
            if (!blocking.isEmpty())
            {
                result.put("blockingReferences", blocking) //$NON-NLS-1$
                    .put("blockingReferencesCount", blocking.size()) //$NON-NLS-1$
                    .put("message", "Delete refactoring completed (forced). " + blocking.size() //$NON-NLS-1$ //$NON-NLS-2$
                        + " incoming reference(s) were left dangling."); //$NON-NLS-1$
            }
            else
            {
                result.put("message", "Delete refactoring completed successfully."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return result.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error performing delete refactoring", e); //$NON-NLS-1$
            return ToolResult.error("Delete failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Collects the refactoring's BLOCKING problems — the incoming references EDT could not resolve
     * automatically. This is the same set the EDT/Configurator UI renders before a delete. A
     * {@link CleanReferenceProblem} carries the referencing object and the feature through which it
     * points at the node being deleted; other problem kinds only carry the target object. A non-empty
     * result means the deletion is unsafe without force. Never throws on a single odd problem.
     */
    private static List<Map<String, Object>> collectBlockingProblems(IRefactoring refactoring)
    {
        List<Map<String, Object>> result = new ArrayList<>();

        RefactoringStatus status = refactoring.getStatus();
        if (status == null)
        {
            return result;
        }
        Collection<IRefactoringProblem> problems = status.getProblems();
        if (problems == null)
        {
            return result;
        }

        for (IRefactoringProblem problem : problems)
        {
            Map<String, Object> problemMap = new java.util.LinkedHashMap<>();
            problemMap.put("problemType", problem.getClass().getSimpleName()); //$NON-NLS-1$
            // Best-effort description; never let a single odd problem abort the whole check.
            try
            {
                if (problem instanceof CleanReferenceProblem crp)
                {
                    EObject refObj = crp.getReferencingObject();
                    if (refObj instanceof IBmObject bmObj)
                    {
                        String refFqn = bmFqnSafe(bmObj);
                        if (refFqn != null)
                        {
                            problemMap.put("referencingObject", refFqn); //$NON-NLS-1$
                        }
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
                    String tgtFqn = bmFqnSafe(bmObj);
                    if (tgtFqn != null)
                    {
                        problemMap.put("targetObject", tgtFqn); //$NON-NLS-1$
                    }
                }
            }
            catch (Exception e)
            {
                Activator.logError("Error describing refactoring problem", e); //$NON-NLS-1$
            }
            result.add(problemMap);
        }
        return result;
    }

    /**
     * Returns a human-readable FQN for a BM object. {@code bmGetFqn()} is only legal on top objects,
     * so for a nested object (e.g. a register dimension or a type item that holds the reference) we
     * climb to the owning top object and append the nested element's name when one is available.
     * Never throws.
     */
    private static String bmFqnSafe(IBmObject obj)
    {
        if (obj == null)
        {
            return null;
        }
        try
        {
            if (obj.bmIsTop())
            {
                return obj.bmGetFqn();
            }
        }
        catch (Exception e)
        {
            // fall through to top-object resolution
        }

        String localName = null;
        if (obj instanceof MdObject mdo)
        {
            localName = mdo.getName();
        }
        else if (obj instanceof org.eclipse.emf.ecore.ENamedElement ene)
        {
            localName = ene.getName();
        }

        try
        {
            IBmObject top = obj.bmGetTopObject();
            if (top != null && top != obj)
            {
                String topFqn = top.bmGetFqn();
                if (topFqn != null)
                {
                    return (localName != null && !localName.isEmpty())
                        ? topFqn + " (" + localName + ")" //$NON-NLS-1$ //$NON-NLS-2$
                        : topFqn;
                }
            }
        }
        catch (Exception e)
        {
            // ignore — fall back to the local name (or null)
        }
        return localName;
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
        final boolean handler = FormElementWriter.isHandlerToken(ref.kindToken);
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.config, ref.formPath,
                "Form not found for '" + normFqn + "'. Address a form member as " //$NON-NLS-1$ //$NON-NLS-2$
                    + "'Type.Object.Form.FormName.<Kind>.Name' or 'CommonForm.FormName.<Kind>.Name' " //$NON-NLS-1$
                    + "(Kind = Attribute / Command / Field / Button / Group / Decoration / Table / " //$NON-NLS-1$
                    + "Handler)."); //$NON-NLS-1$
            return confirm
                ? performFormDelete(fctx, normFqn, ref, handler)
                : buildFormDeletePreview(fctx, normFqn, ref, handler);
        }
        catch (Exception e)
        {
            String ready = FormValidationException.jsonOf(e);
            if (ready != null)
            {
                return ready;
            }
            Activator.logError("Error deleting form member", e); //$NON-NLS-1$
            return ToolResult.error("Failed to delete form member: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }
    }

    /** Resolves the delete target: a handler (form/item container) or a member (attribute/command/item). */
    private static EObject resolveFormTarget(EObject formModel, FormElementWriter.FormMemberRef ref,
        boolean handler)
    {
        if (handler)
        {
            // The container is the form root, a form ITEM, or a form COMMAND (whose single Action
            // handler is its contained action - removing it clears the binding).
            EObject container = FormElementWriter.resolveHandlerContainer(formModel, ref);
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
                + ". Use get_metadata_details to list the handlers.").toJson(); //$NON-NLS-1$
        }
        return ToolResult.error("Form member not found: " + ref.name + " (kind '" + ref.kindToken //$NON-NLS-1$ //$NON-NLS-2$
            + "') on " + ref.formPath + ". Use get_metadata_details to list the members.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Preview inside a READ transaction (no mutation): capture the target type + item descendants. */
    private String buildFormDeletePreview(FormElementWriter.FormEditContext fctx, String normFqn,
        FormElementWriter.FormMemberRef ref, boolean handler)
    {
        FormDeletePreview data = FormElementWriter.readEditableForm(fctx, "DeleteFormMemberPreview", //$NON-NLS-1$
            (formModel, tx) ->
            {
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
            .put("blocking", false) //$NON-NLS-1$
            .put("blockingReferences", Collections.emptyList()) //$NON-NLS-1$
            .put("blockingReferencesCount", 0) //$NON-NLS-1$
            .put("message", "Preview: deleting '" + ref.name + "' (" + data.type + ") from " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ref.formPath + " would remove " //$NON-NLS-1$
                + (data.descendants.isEmpty()
                    ? "the " + (handler ? "handler" : "member") + " itself." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    : "it and its " + data.descendants.size() + " contained item(s).") //$NON-NLS-1$ //$NON-NLS-2$
                + " Cross-references to it (a field's dataPath, a button's command) are NOT rewritten - " //$NON-NLS-1$
                + "re-check with get_metadata_details afterwards. Call confirm=true " //$NON-NLS-1$
                + "to apply.") //$NON-NLS-1$
            .toJson();
    }

    /** Delete inside a WRITE transaction: EcoreUtil.remove the target, then export the content form. */
    private String performFormDelete(FormElementWriter.FormEditContext fctx, String normFqn,
        FormElementWriter.FormMemberRef ref, boolean handler)
    {
        final String[] capturedType = new String[1];
        boolean persisted = FormElementWriter.writeEditableForm(fctx, "DeleteFormMember", //$NON-NLS-1$
            (formModel, tx) ->
            {
                EObject target = resolveFormTarget(formModel, ref, handler);
                if (target == null)
                {
                    // Thrown (not flagged): rolls the unchanged tx back and skips the export.
                    throw new FormValidationException(formMemberNotFound(ref, handler));
                }
                capturedType[0] = target.eClass().getName();
                // items is containment, so removing a Group/Table cascades its contained subtree.
                EcoreUtil.remove(target);
            });

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

    // ==================== FORM object (owned BasicForm, symmetric with create) ====================

    /**
     * Deletes an OWNED form OBJECT addressed by a 4-part form FQN ({@code Type.Object.Form.FormName}) -
     * the symmetric counterpart of {@code create_metadata}'s {@link FormElementWriter#createForm}. An
     * owned form is not a top object (it lives on its owner's {@code forms} collection), so the
     * md-refactoring service cannot see it; it is removed directly by re-fetching the owner inside a
     * write transaction, detaching the content {@code Form} top object (the store created at attach), and
     * removing the {@code BasicForm} from the {@code forms} collection while clearing any default-form
     * reference the owner held to it (so no dangling {@code defaultObjectForm} / {@code defaultListForm}
     * ref is left behind). Two-phase like the rest of the tool: {@code confirm=false} previews (no
     * mutation), {@code confirm=true} removes it and force-exports the owner {@code .mdo}.
     */
    private String deleteFormObject(ProjectContext ctx, String normFqn,
        FormElementWriter.FormObjectRef ref, boolean confirm)
    {
        IProject project = ctx.project;
        Configuration config = ctx.config;

        // Reuse create_metadata's owner + owned-form resolution so create/delete address the SAME object. The
        // resolver expects the 'forms' shape: Type.Object.forms.FormName.
        String formPath = ref.ownerType + "." + ref.ownerName + ".forms." + ref.formName; //$NON-NLS-1$ //$NON-NLS-2$
        MdObject mdForm = FormStructureReader.resolveMdForm(config, formPath);
        if (mdForm == null)
        {
            // Distinguish a missing owner from a missing form for a sharper message.
            MdObject owner = MetadataTypeUtils.findObject(config, ref.ownerType, ref.ownerName);
            if (owner == null)
            {
                return ToolResult.error("Owner object not found: " + ref.ownerFqn() + ". " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Use get_metadata_objects to list available objects.").toJson(); //$NON-NLS-1$
            }
            return ToolResult.error("Form '" + ref.formName + "' not found on " + ref.ownerFqn() //$NON-NLS-1$ //$NON-NLS-2$
                + ". Use get_metadata_details to list the object's forms.").toJson(); //$NON-NLS-1$
        }
        if (!(mdForm instanceof IBmObject))
        {
            return ToolResult.error("Form is not a BM object").toJson(); //$NON-NLS-1$
        }

        if (!confirm)
        {
            return ToolResult.success()
                .put("action", "preview") //$NON-NLS-1$ //$NON-NLS-2$
                .put("fqn", normFqn) //$NON-NLS-1$
                .put("refactoringTitle", "Delete form " + ref.formName) //$NON-NLS-1$ //$NON-NLS-2$
                .put("items", Collections.singletonList(formItem(ref.formName, mdForm.eClass().getName()))) //$NON-NLS-1$
                .put("blocking", false) //$NON-NLS-1$
                .put("blockingReferences", Collections.emptyList()) //$NON-NLS-1$
                .put("blockingReferencesCount", 0) //$NON-NLS-1$
                .put("message", "Preview: deleting form '" + ref.formName + "' from " + ref.ownerFqn() //$NON-NLS-1$ //$NON-NLS-2$
                    + " would remove the form and its content Form.form. Cross-references to it " //$NON-NLS-1$
                    + "(a default-form setting) are cleared on the owner. Call confirm=true to apply.") //$NON-NLS-1$
                .toJson();
        }

        // The owner is a top object whose .mdo registers the form; force-export it after the removal so
        // the <forms> entry (and any cleared default-form ref) lands on disk. eContainer() is the owner.
        EObject ownerObj = mdForm.eContainer();
        final String ownerFqn = (ownerObj instanceof IBmObject) ? ((IBmObject)ownerObj).bmGetFqn()
            : ref.ownerFqn();
        // Capture the RESOLVED names BEFORE the delete: the model lookup is case-INsensitive while the
        // workspace folder path is case-sensitive, so the folder cleanup must address the names the
        // model actually carries, not the user-typed FQN segments (which may differ in case).
        String resolvedFormName = mdForm.getName();
        final String formNameOnDisk =
            (resolvedFormName == null || resolvedFormName.isEmpty()) ? ref.formName : resolvedFormName;
        String resolvedOwnerName = (ownerObj instanceof MdObject) ? ((MdObject)ownerObj).getName() : null;
        final String ownerNameOnDisk =
            (resolvedOwnerName == null || resolvedOwnerName.isEmpty()) ? ref.ownerName : resolvedOwnerName;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.editContextFor(project, mdForm);
            FormElementWriter.writeMdForm(fctx, "DeleteFormObject", (txMdForm, tx) -> //$NON-NLS-1$
            {
                EObject owner = txMdForm.eContainer();
                // Detach the content Form top object (the BM store the attach created) before removing the
                // MD-form, so no store-less top object is left orphaned in the namespace.
                EObject content = FormElementWriter.getEditableForm(txMdForm);
                if (content instanceof IBmObject)
                {
                    tx.detachTopObject((IBmObject)content);
                }
                // Clear any single-valued default-form reference on the owner that points at this form
                // (defaultObjectForm / defaultListForm / ...), so removing the form leaves no dangling ref.
                if (owner != null)
                {
                    clearReferencesTo(owner, txMdForm);
                }
                // Remove the MD-form from the owner's 'forms' containment list.
                EcoreUtil.remove(txMdForm);
            });
        }
        catch (Exception e)
        {
            String ready = FormValidationException.jsonOf(e);
            if (ready != null)
            {
                return ready;
            }
            Activator.logError("Error deleting form object", e); //$NON-NLS-1$
            return ToolResult.error("Failed to delete form: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        boolean persisted = ownerFqn != null && !ownerFqn.isEmpty()
            && BmTransactions.forceExportToDisk(project, ownerFqn);

        // The BM-model delete + owner force-export drop the <forms> entry from the owner .mdo, but the
        // form's own resource folder on disk (src/<TypeDir>/<Owner>/Forms/<FormName>/, holding Form.form
        // and any sub-files) is NOT touched by the export - it would survive as an orphan that still
        // resolves the form FQN ("no editable content model") and clutters a fresh checkout / XML import.
        // Remove it physically through the workspace API (best-effort: never fail the delete the model
        // already committed). Only this EXACT form folder is removed, never the parent Forms/ (siblings)
        // or the owner folder. The path is built from the RESOLVED names captured above.
        FolderCleanup folderCleanup =
            deleteFormResourceFolder(project, ref.ownerType, ownerNameOnDisk, formNameOnDisk);

        return ToolResult.success()
            .put("action", "executed") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("message", "Deleted form '" + ref.formName + "' from " + ref.ownerFqn() //$NON-NLS-1$ //$NON-NLS-2$
                + (persisted ? " and persisted to disk." //$NON-NLS-1$
                    : " (in-memory only; on-disk write did not complete - re-check before relying on " //$NON-NLS-1$
                        + "it).") //$NON-NLS-1$
                + folderCleanupMessage(folderCleanup))
            .toJson();
    }

    /** Outcome of the orphan form-folder cleanup - never conflate "not found" with "removed". */
    private enum FolderCleanup
    {
        /** The folder existed and was deleted. */
        REMOVED,
        /** No folder at the resolved path (nothing was removed). */
        NOT_FOUND,
        /** The path could not be resolved or the delete attempt failed. */
        FAILED
    }

    /** The message fragment describing the folder-cleanup outcome (leading space included). */
    private static String folderCleanupMessage(FolderCleanup cleanup)
    {
        switch (cleanup)
        {
        case REMOVED:
            return " The form resource folder was removed from disk."; //$NON-NLS-1$
        case NOT_FOUND:
            return " The form resource folder was not found on disk (nothing was removed)."; //$NON-NLS-1$
        case FAILED:
        default:
            return " (the form resource folder could not be removed - check it manually)."; //$NON-NLS-1$
        }
    }

    /**
     * Physically removes an owned form's resource folder
     * ({@code src/<TypeDir>/<Owner>/Forms/<FormName>/}, containing {@code Form.form} and any sub-files)
     * through the Eclipse workspace API so the workspace stays in sync. The path is built from the
     * RESOLVED owner / form names (the names the model actually carries), NOT the user-typed FQN
     * segments: the model lookup is case-insensitive while the workspace path is case-sensitive, so a
     * case-variant FQN would otherwise miss the real folder and leave the orphan behind. Best-effort: a
     * delete failure is logged but never propagated - the BM-model delete already committed, so the
     * orphan-folder cleanup must not turn a successful delete into an error. A folder that does not
     * exist is reported as {@link FolderCleanup#NOT_FOUND}, never claimed as removed. Only the EXACT
     * {@code Forms/<FormName>} folder is targeted, never the parent {@code Forms/} directory (which may
     * hold sibling forms) or the owner folder.
     *
     * @param project the owning workspace project
     * @param ownerType the owner metadata TYPE token (English or Russian, as supplied)
     * @param resolvedOwnerName the owner object Name AS RESOLVED on the model
     * @param resolvedFormName the form Name AS RESOLVED on the model
     * @return the cleanup outcome (removed / not found on disk / failed)
     */
    private static FolderCleanup deleteFormResourceFolder(IProject project, String ownerType,
        String resolvedOwnerName, String resolvedFormName)
    {
        String folderRel = formResourceFolderPath(ownerType, resolvedOwnerName, resolvedFormName);
        if (folderRel == null)
        {
            Activator.logError("Could not resolve the form resource folder for " + ownerType + "." //$NON-NLS-1$ //$NON-NLS-2$
                + resolvedOwnerName + ".Form." + resolvedFormName + "; leaving any on-disk Forms/" //$NON-NLS-1$ //$NON-NLS-2$
                + resolvedFormName + " folder in place.", null); //$NON-NLS-1$
            return FolderCleanup.FAILED;
        }
        try
        {
            IFolder folder = project.getFolder(new Path(folderRel));
            if (!folder.exists())
            {
                // Nothing on disk at the resolved path (e.g. the form had no rendered content yet).
                // Reported as NOT_FOUND - never claimed as a removal.
                return FolderCleanup.NOT_FOUND;
            }
            // delete(true, monitor): force-delete the folder and its contents, keeping the workspace
            // resource tree in sync with disk. DEPTH is implicitly infinite for a container.
            folder.delete(true, new NullProgressMonitor());
            return FolderCleanup.REMOVED;
        }
        catch (Exception e)
        {
            Activator.logError("Failed to remove the form resource folder " + folderRel //$NON-NLS-1$
                + " (the model delete already succeeded; remove it manually if it persists).", e); //$NON-NLS-1$
            return FolderCleanup.FAILED;
        }
    }

    /**
     * The project-relative resource folder of an owned form
     * ({@code src/<TypeDir>/<Owner>/Forms/<FormName>}), built from the RESOLVED owner / form names via
     * the shared {@link MetadataPathResolver} mapping (same disk layout create_metadata writes), or
     * {@code null} when the type token is unknown. Pure; package-visible for tests.
     */
    static String formResourceFolderPath(String ownerType, String resolvedOwnerName,
        String resolvedFormName)
    {
        return MetadataPathResolver.resolveFormFolderPath(
            ownerType + "." + resolvedOwnerName + ".forms." + resolvedFormName); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A {name, type} preview entry for the form object being removed. */
    private static Map<String, Object> formItem(String name, String type)
    {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("name", name); //$NON-NLS-1$
        entry.put("type", type); //$NON-NLS-1$
        return entry;
    }

    /**
     * Nulls out every single-valued (non-containment) reference on {@code holder} whose value is
     * {@code target}. For a form owner these are the {@code defaultObjectForm} / {@code defaultListForm}
     * / {@code defaultChoiceForm} / ... settings - all declared on the direct owner pointing at one of
     * its own {@code BasicForm}s - so checking the owner's own features is sufficient to avoid a dangling
     * reference once the form is removed. Containment / many-valued references (the {@code forms} list
     * itself) are left to {@link EcoreUtil#remove}.
     */
    private static void clearReferencesTo(EObject holder, EObject target)
    {
        for (EReference reference : holder.eClass().getEAllReferences())
        {
            if (reference.isContainment() || reference.isMany() || !reference.isChangeable())
            {
                continue;
            }
            if (holder.eGet(reference) == target)
            {
                holder.eUnset(reference);
            }
        }
    }

    /**
     * Walks the item's contained {@code items} subtree depth-first, appending each descendant as a
     * {name, type} map (the same {@code getReferenceList} / {@code nameOf} walk the form reader uses),
     * so the preview lists what a container delete cascades. The item ITSELF is not added.
     */
    private static void collectItemDescendants(EObject item, List<Map<String, Object>> out)
    {
        for (EObject child : FormStructureReader.getReferenceList(item, "items")) //$NON-NLS-1$
        {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("name", FormStructureReader.nameOf(child)); //$NON-NLS-1$
            entry.put("type", child.eClass().getName()); //$NON-NLS-1$
            out.add(entry);
            collectItemDescendants(child, out);
        }
    }

    /** Mutable carrier for the form-delete preview read task so tx-bound EObjects never escape. */
    private static final class FormDeletePreview
    {
        boolean found;
        String type;
        final List<Map<String, Object>> descendants = new ArrayList<>();
    }
}
