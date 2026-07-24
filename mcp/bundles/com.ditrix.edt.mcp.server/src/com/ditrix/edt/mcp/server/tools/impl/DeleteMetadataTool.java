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
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.md.refactoring.core.IMdRefactoringService;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com._1c.g5.v8.dt.refactoring.core.CleanReferenceProblem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoring;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringItem;
import com._1c.g5.v8.dt.refactoring.core.IRefactoringProblem;
import com._1c.g5.v8.dt.refactoring.core.RefactoringStatus;
import com._1c.g5.v8.dt.xdto.model.ObjectType;
import com._1c.g5.v8.dt.xdto.model.Package;
import com._1c.g5.v8.dt.xdto.model.Property;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.FormValidationException;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataPathResolver;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;
import com.ditrix.edt.mcp.server.utils.XdtoWriteException;
import com.ditrix.edt.mcp.server.utils.XdtoWriter;

/**
 * Deletes a metadata node (a top-level object or a subordinate member) addressed by a 1C full-name
 * FQN, cascading the cleanup of every reference (BSL code, forms, other metadata) via EDT's
 * md-refactoring service. Two-phase: a bare call previews the affected references; {@code confirm=true}
 * performs the delete. Replaces the former {@code delete_metadata_object}.
 */
public class DeleteMetadataTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "delete_metadata"; //$NON-NLS-1$

    /** Output key: title of the delete refactoring (preview). */
    private static final String KEY_REFACTORING_TITLE = "refactoringTitle"; //$NON-NLS-1$

    /** Output key: metadata items the deletion would remove (preview). */
    private static final String KEY_ITEMS = "items"; //$NON-NLS-1$

    /** Output key: whether the listed blocking references block the delete. */
    private static final String KEY_BLOCKING = "blocking"; //$NON-NLS-1$

    /** Output value of 'action' for a preview-only response. */
    private static final String VAL_PREVIEW = "preview"; //$NON-NLS-1$

    /** Output value of 'action' for an executed (performed) deletion. */
    private static final String VAL_EXECUTED = "executed"; //$NON-NLS-1$

    /** FQN kind token / label for a form event handler. */
    private static final String KEY_HANDLER = "handler"; //$NON-NLS-1$

    /** Label for a form member (non-handler). */
    private static final String KEY_MEMBER = "member"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Delete a metadata node (object or member, including a FORM object " //$NON-NLS-1$
            + "'Type.Object.Form.Name', a FORM member - item / attribute / command / handler - an XDTO " //$NON-NLS-1$
            + "package member 'XDTOPackage.<Package>.ObjectType.<Name>' / '...Property.<Name>' / " //$NON-NLS-1$
            + "'...ObjectType.<Type>.Property.<Name>', or a PREDEFINED item " //$NON-NLS-1$
            + "'Catalog.X.Predefined.ItemName' / 'ChartOfCharacteristicTypes.X.Predefined.ItemName') " //$NON-NLS-1$
            + "addressed by a 1C full-name FQN, cascading the cleanup of all " //$NON-NLS-1$
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
            .stringProperty(McpKeys.PROJECT_NAME,
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
            .stringProperty(McpKeys.ACTION, "Either 'preview', 'executed' or 'blocked'") //$NON-NLS-1$
            .stringProperty("fqn", "FQN of the node targeted for deletion") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_REFACTORING_TITLE, "Title of the delete refactoring (preview)") //$NON-NLS-1$
            .objectArrayProperty(KEY_ITEMS, "Metadata items the deletion would remove (preview)") //$NON-NLS-1$
            .booleanProperty(KEY_BLOCKING, "Whether the listed blockingReferences BLOCK the delete (the " //$NON-NLS-1$
                + "refactoring cannot auto-clean them; a confirm=true delete is refused unless force=true)") //$NON-NLS-1$
            .objectArrayProperty("blockingReferences", "Incoming references the refactoring cannot " //$NON-NLS-1$ //$NON-NLS-2$
                + "auto-clean: listed in the preview, the reason a delete is refused " //$NON-NLS-1$
                + "(action='blocked'), or left dangling when force=true (action='executed')") //$NON-NLS-1$
            .integerProperty("blockingReferencesCount", "Count of blocking references") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("affectedReferences", "Deprecated alias of blockingReferences (the " //$NON-NLS-1$ //$NON-NLS-2$
                + "same list), kept for one release for wire compatibility") //$NON-NLS-1$
            .integerProperty("affectedReferencesCount", "Deprecated alias of blockingReferencesCount " //$NON-NLS-1$ //$NON-NLS-2$
                + "(the same count), kept for one release for wire compatibility") //$NON-NLS-1$
            .booleanProperty("forced", "Whether the delete was forced past blocking references") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.MESSAGE, "Human-readable description of the result") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, "fqn"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String fqn = JsonUtils.extractStringArgument(params, "fqn"); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$
        boolean force = JsonUtils.extractBooleanArgument(params, "force", false); //$NON-NLS-1$

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        Configuration config = ctx.config;

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

        // A FQN addressing an XDTO PACKAGE MEMBER (an ObjectType or a Property - issue #183 stream 1)
        // is handled by a dedicated branch too: it lives on the package's lazily materialized
        // xdto.model content (a cross-model hop), not the mdclass tree, so the md-refactoring service
        // (mdclass-only) cannot see it - removed directly instead, mirroring the form-member delete's
        // two-phase preview/confirm shape.
        XdtoWriter.MemberRef xdtoRef = XdtoWriter.parseMemberRef(normFqn);
        if (xdtoRef != null)
        {
            return deleteXdtoMember(ctx, normFqn, xdtoRef, confirm);
        }

        // A FQN addressing a PREDEFINED item (Catalog/ChartOfCharacteristicTypes.Name.Predefined.Item)
        // is handled by a dedicated branch too: the predefined content is a plain EMF containment on
        // the owner, not a top object the md-refactoring service can see (issue #293).
        PredefinedWriter.PredefinedRef predefinedRef = PredefinedWriter.parseRef(normFqn);
        if (predefinedRef != null)
        {
            return deletePredefinedItem(ctx, normFqn, predefinedRef, confirm, force);
        }

        // The md-refactoring service is needed ONLY by the generic mdclass path below - the
        // form-member / form-object / XDTO-member / predefined-item branches above delete directly
        // through their own content models, so its unavailability (e.g. during startup) must not block
        // them.
        IMdRefactoringService refactoringService = Activator.getDefault().getMdRefactoringService();
        if (refactoringService == null)
        {
            return ToolResult.error("IMdRefactoringService not available").toJson(); //$NON-NLS-1$
        }

        // Exact-first resolve with the yo-addressing fallback: create_metadata normalizes
        // 'yo'->'ye' in names by default, so a caller re-typing the original yo spelling
        // would miss the stored name — the resolver retries the normalized FQN.
        MetadataNodeResolver.ResolvedNode resolved =
            MetadataNodeResolver.resolveExistingWithYoFallback(config, normFqn);
        MetadataNodeResolver.MetadataNode node = resolved.node;
        if (node == null)
        {
            return ToolResult.error("Node not found: " + fqn + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Check the FQN: 'Type.Name' for a top object (e.g. 'Catalog.Products'), " //$NON-NLS-1$
                + "'Type.Name.Kind.Name' for a member (e.g. 'Document.Order.Attribute.Amount'). " //$NON-NLS-1$
                + "Any node create_metadata can address can be deleted; see " //$NON-NLS-1$
                + "get_tool_guide('create_metadata') for the kinds. " //$NON-NLS-1$
                + "Use get_metadata_objects to find an object's FQN." //$NON-NLS-1$
                + MetadataNodeResolver.yoNotFoundHint(normFqn)).toJson();
        }
        if (resolved.yoFallback)
        {
            Activator.logInfo("delete_metadata: '" + normFqn //$NON-NLS-1$
                + "' did not resolve exactly; proceeding with its yo-normalized form '" //$NON-NLS-1$
                + resolved.fqn + "'"); //$NON-NLS-1$
            normFqn = resolved.fqn;
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

        // The preview's "affected" references ARE exactly the blocking set, so the list is built ONCE
        // and emitted under the blocking* fields (and their legacy affected* aliases) shared with
        // action='blocked' / 'executed'.
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_PREVIEW)
            .put("fqn", fqn) //$NON-NLS-1$
            .put(KEY_REFACTORING_TITLE, title)
            .put(KEY_ITEMS, allItems)
            .put(KEY_BLOCKING, hasBlocking);
        return putBlockingReferences(result, blocking)
            .put(McpKeys.MESSAGE, message)
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
            ToolResult blocked = ToolResult.error("Cannot delete '" + fqn + "': it is still referenced by " //$NON-NLS-1$ //$NON-NLS-2$
                    + blocking.size() + " object(s) that the refactoring cannot auto-clean. Remove the " //$NON-NLS-1$
                    + "references first, or call again with force=true to delete anyway (the references " //$NON-NLS-1$
                    + "will be left dangling).") //$NON-NLS-1$
                .put(McpKeys.ACTION, "blocked") //$NON-NLS-1$
                .put("fqn", fqn) //$NON-NLS-1$
                .put(KEY_BLOCKING, true);
            return putBlockingReferences(blocked, blocking).toJson();
        }

        // Destructive-operation consent gate: the LAST check before the model mutation. Built from the
        // ref list the tool already computed; on ALLOW the behaviour is byte-identical, on REJECT the
        // caller returns an error and NOTHING is mutated. Headless / env-bypass / non-ASK never block.
        // The count/name line names the ACTUAL deletion target (count 1, its FQN) — like the other five
        // gated tools — so the common case (no blocking refs) reads "1 object: <fqn>" rather than a
        // misleading "0 objects:". Any incoming references the delete leaves dangling (force=true) are
        // described in the subtitle, where the count reflects the references, not the deletion.
        String subtitle = blocking.isEmpty()
            ? "This deletes '" + fqn + "' and cascades reference cleanup (BSL, forms, metadata)." //$NON-NLS-1$ //$NON-NLS-2$
            : "This deletes '" + fqn + "' and cascades reference cleanup (BSL, forms, metadata); " //$NON-NLS-1$ //$NON-NLS-2$
                + blocking.size() + " incoming reference(s) the refactoring cannot auto-clean will be " //$NON-NLS-1$
                + "left dangling."; //$NON-NLS-1$
        ConsentPreview preview = new ConsentPreview(
            "Delete metadata node", //$NON-NLS-1$
            subtitle, 1, Collections.singletonList(fqn));
        DestructiveConsentGate.ConsentDecision consentDecision =
            DestructiveConsentGate.getInstance().requireConsent(NAME, preview);
        if (consentDecision != DestructiveConsentGate.ConsentDecision.ALLOW)
        {
            return ToolResult.error(DestructiveConsentGate.consentDeniedMessage(consentDecision, NAME)).toJson();
        }

        try
        {
            refactoring.perform();
            ToolResult result = ToolResult.success()
                .put(McpKeys.ACTION, VAL_EXECUTED)
                .put("fqn", fqn) //$NON-NLS-1$
                .put("forced", force); //$NON-NLS-1$
            if (!blocking.isEmpty())
            {
                putBlockingReferences(result, blocking)
                    .put(McpKeys.MESSAGE, "Delete refactoring completed (forced). " + blocking.size() //$NON-NLS-1$
                        + " incoming reference(s) were left dangling."); //$NON-NLS-1$
            }
            else
            {
                result.put(McpKeys.MESSAGE, "Delete refactoring completed successfully."); //$NON-NLS-1$
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
     * Puts the blocking-reference list and count onto {@code result} — the SINGLE place every response
     * branch (preview / blocked / forced execute / form previews) emits them, so the legacy aliases
     * below can never drift from the canonical keys. Package-visible for tests.
     */
    static ToolResult putBlockingReferences(ToolResult result, List<Map<String, Object>> blocking)
    {
        return result
            .put("blockingReferences", blocking) //$NON-NLS-1$
            .put("blockingReferencesCount", blocking.size()) //$NON-NLS-1$
            // legacy aliases of blockingReferences*, kept for one release for wire compatibility (upstream review)
            .put("affectedReferences", blocking) //$NON-NLS-1$
            .put("affectedReferencesCount", blocking.size()); //$NON-NLS-1$
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
            result.add(describeProblem(problem));
        }
        return result;
    }

    /**
     * Describes a single refactoring {@link IRefactoringProblem} as a JSON-ready map: the problem type
     * plus, best-effort, the referencing object / feature (for a {@link CleanReferenceProblem}) and the
     * target object. Mirrors what the EDT/Configurator UI shows per blocking reference. Never throws on
     * a single odd problem — a description failure is logged and the partial map is still returned.
     */
    private static Map<String, Object> describeProblem(IRefactoringProblem problem)
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
        return problemMap;
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

        String memberWord = handler ? KEY_HANDLER : KEY_MEMBER;
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_PREVIEW)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_REFACTORING_TITLE, "Delete form " + memberWord + " " + ref.name) //$NON-NLS-1$ //$NON-NLS-2$
            .put(KEY_ITEMS, removed)
            .put(KEY_BLOCKING, false);
        return putBlockingReferences(result, Collections.emptyList())
            .put(McpKeys.MESSAGE, "Preview: deleting '" + ref.name + "' (" + data.type + ") from " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ref.formPath + " would remove " //$NON-NLS-1$
                + (data.descendants.isEmpty()
                    ? "the " + memberWord + " itself." //$NON-NLS-1$ //$NON-NLS-2$
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
            .put(McpKeys.ACTION, VAL_EXECUTED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(McpKeys.MESSAGE, "Deleted form " + (handler ? KEY_HANDLER : KEY_MEMBER) + " '" + ref.name //$NON-NLS-1$ //$NON-NLS-2$
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
        // resolver expects the 'forms' shape: Type.Object.forms.FormName (FormElementWriter owns it).
        String formPath = FormElementWriter.formPathOf(ref.ownerType, ref.ownerName, ref.formName);
        MdObject mdForm = FormStructureReader.resolveMdForm(config, formPath);
        if (mdForm == null)
        {
            return formObjectNotFoundError(config, ref);
        }
        if (!(mdForm instanceof IBmObject))
        {
            return ToolResult.error("Form is not a BM object").toJson(); //$NON-NLS-1$
        }

        if (!confirm)
        {
            // blocking is hardcoded false: an owned form is removed by cascade (not through the
            // md-refactoring service), so unlike top-object previews NO incoming-reference scan
            // runs here — the message says so to keep the preview honest (deep scan is follow-up).
            ToolResult preview = ToolResult.success()
                .put(McpKeys.ACTION, VAL_PREVIEW)
                .put("fqn", normFqn) //$NON-NLS-1$
                .put(KEY_REFACTORING_TITLE, "Delete form " + ref.formName) //$NON-NLS-1$
                .put(KEY_ITEMS, Collections.singletonList(formItem(ref.formName, mdForm.eClass().getName())))
                .put(KEY_BLOCKING, false);
            return putBlockingReferences(preview, Collections.emptyList())
                .put(McpKeys.MESSAGE, "Preview: deleting form '" + ref.formName + "' from " + ref.ownerFqn() //$NON-NLS-1$ //$NON-NLS-2$
                    + " would remove the form and its content Form.form. Cross-references to it " //$NON-NLS-1$
                    + "(a default-form setting) are cleared on the owner. Note: incoming references " //$NON-NLS-1$
                    + "from OTHER top objects (e.g. BSL code opening this form by name) are NOT " //$NON-NLS-1$
                    + "checked for owned forms — verify with find_references if unsure. " //$NON-NLS-1$
                    + "Call confirm=true to apply.") //$NON-NLS-1$
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
            FormElementWriter.writeMdForm(fctx, "DeleteFormObject", //$NON-NLS-1$
                DeleteMetadataTool::removeFormObjectInTx);
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
            .put(McpKeys.ACTION, VAL_EXECUTED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(McpKeys.MESSAGE, "Deleted form '" + ref.formName + "' from " + ref.ownerFqn() //$NON-NLS-1$ //$NON-NLS-2$
                + (persisted ? " and persisted to disk." //$NON-NLS-1$
                    : " (in-memory only; on-disk write did not complete - re-check before relying on " //$NON-NLS-1$
                        + "it).") //$NON-NLS-1$
                + folderCleanupMessage(folderCleanup))
            .toJson();
    }

    /**
     * Builds the "form object not found" error for {@link #deleteFormObject}: distinguishes a missing
     * owner from a missing form (the form lookup failed) for a sharper message. Pure message selection,
     * no mutation.
     */
    private static String formObjectNotFoundError(Configuration config, FormElementWriter.FormObjectRef ref)
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

    /**
     * The {@code confirm=true} write-transaction body for {@link #deleteFormObject}: detaches the
     * content {@code Form} top object, clears any default-form reference the owner held to this form,
     * and removes the MD-form from the owner's {@code forms} containment list. Runs inside the BM write
     * transaction supplied by {@link FormElementWriter#writeMdForm}.
     */
    private static void removeFormObjectInTx(EObject txMdForm, IBmTransaction tx)
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
    }

    // ==================== PREDEFINED item (a plain EMF containment on the owner) ====================

    /**
     * Deletes a PREDEFINED item addressed by {@code Type.Owner.Predefined.ItemName}. Two-phase like
     * the rest of this tool: {@code confirm=false} previews (a FOLDER's preview reports how many
     * nested items the delete would cascade), {@code confirm=true} removes it from its ACTUAL
     * containing list and force-exports the OWNER's canonical FQN (the predefined content is a plain
     * EMF containment - there is no separate top object to detach, unlike an owned form).
     */
    private String deletePredefinedItem(ProjectContext ctx, String normFqn,
        PredefinedWriter.PredefinedRef ref, boolean confirm, boolean force)
    {
        String ownerTypeErr = PredefinedWriter.unsupportedOwnerTypeError(ref.ownerType);
        if (ownerTypeErr != null)
        {
            return ToolResult.error(ownerTypeErr).toJson();
        }

        MetadataNodeResolver.ResolvedNode ownerResolved =
            MetadataNodeResolver.resolveExistingWithYoFallback(ctx.config, ref.ownerFqn());
        if (ownerResolved.node == null)
        {
            return ToolResult.error("Owner object not found: " + ref.ownerFqn() + ". " //$NON-NLS-1$ //$NON-NLS-2$
                + "Use get_metadata_objects to list available objects." //$NON-NLS-1$
                + MetadataNodeResolver.yoNotFoundHint(ref.ownerFqn())).toJson();
        }
        MdObject owner = ownerResolved.node.object;
        if (!(owner instanceof IBmObject))
        {
            return ToolResult.error("Owner object is not a BM object").toJson(); //$NON-NLS-1$
        }

        PredefinedWriter.DeletePreview preview = PredefinedWriter.preview(owner, ref.itemName);
        if (!preview.found)
        {
            return ToolResult.error("Predefined item not found: '" + ref.itemName + "' on " //$NON-NLS-1$ //$NON-NLS-2$
                + ref.ownerFqn() + ". Use get_metadata_details to list the owner's predefined items.") //$NON-NLS-1$
                .toJson();
        }

        // Incoming-reference check (issue #296 P1): a predefined item CAN be referenced elsewhere in
        // the model (e.g. a DynamicList filter, another object's default value referencing this
        // item), so deleting it unconditionally could silently leave a dangling reference. Mirrors
        // the generic-node delete path above (collectBlockingProblems / force), reusing the SAME
        // back-reference mechanism find_references' MetadataReferenceService uses.
        //
        // FAIL-CLOSED (P1 fix): the scan can fail to run to completion (no BM model/manager, a missing
        // owner/item inside the transaction, or a per-item getBackReferences exception) - that is NOT
        // the same as "genuinely zero references" and must never be silently treated as safe. See
        // PredefinedRefScan#completed.
        PredefinedRefScan refScan = collectPredefinedItemBlockingReferences(ctx.project, (IBmObject)owner, ref);

        if (!confirm)
        {
            return buildPredefinedItemDeletePreview(normFqn, ref, preview, refScan);
        }

        if (predefinedDeleteWouldBlock(refScan, force))
        {
            // Distinct message for the two block reasons: an UNVERIFIED scan vs. actual references.
            String reason = !refScan.completed
                ? "Could not verify incoming references to predefined item '" + ref.itemName + "' on " //$NON-NLS-1$ //$NON-NLS-2$
                    + ref.ownerFqn() + " (the project may still be building or the reference index is " //$NON-NLS-1$
                    + "unavailable). Retry when the project is ready, or pass force=true to delete " //$NON-NLS-1$
                    + "without the reference check." //$NON-NLS-1$
                : "Cannot delete predefined item '" + ref.itemName + "' on " + ref.ownerFqn() //$NON-NLS-1$ //$NON-NLS-2$
                    + ": it is still referenced by " + refScan.refs.size() + " place(s). Remove the " //$NON-NLS-1$ //$NON-NLS-2$
                    + "references first, or call again with force=true to delete anyway (those " //$NON-NLS-1$
                    + "references will be left dangling)."; //$NON-NLS-1$
            ToolResult blocked = ToolResult.error(reason)
                .put(McpKeys.ACTION, "blocked") //$NON-NLS-1$
                .put("fqn", normFqn) //$NON-NLS-1$
                .put(KEY_BLOCKING, true);
            return putBlockingReferences(blocked, refScan.refs).toJson();
        }

        // Destructive-operation consent gate: the LAST check before the mutation, mirroring the
        // generic-node path above. delete_metadata is a gated tool, and a FOLDER delete cascades its
        // whole content tree - so an interactive session that requires confirmation must get the
        // dialog here too. On ALLOW the behaviour is unchanged; headless / env-bypass never block.
        // Reached only when the reference check completed with nothing blocking, OR force=true bypasses
        // either an incomplete check or a non-empty blocking set.
        int cascadeTotal = 1 + preview.descendantCount;
        // Cascade wording follows the real containment-descendant count, not isFolder: a
        // ChartOfAccounts parent account (isFolder=false) still cascades its childItems, so the
        // subtitle must report the count. The "(a folder)" label stays gated on isFolder.
        StringBuilder consentSubtitle = new StringBuilder(preview.descendantCount > 0
            ? "This deletes predefined item '" + ref.itemName + "'" //$NON-NLS-1$ //$NON-NLS-2$
                + (preview.isFolder ? " (a folder)" : "") + " and its " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + preview.descendantCount + " nested item(s) from " + ref.ownerFqn() + "." //$NON-NLS-1$ //$NON-NLS-2$
            : "This deletes predefined item '" + ref.itemName + "' from " + ref.ownerFqn() + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!refScan.completed)
        {
            consentSubtitle.append(' ').append("The incoming-reference check did not complete " //$NON-NLS-1$
                + "(force=true bypasses it); any references to this item are UNVERIFIED and may be " //$NON-NLS-1$
                + "left dangling."); //$NON-NLS-1$
        }
        else if (!refScan.refs.isEmpty())
        {
            consentSubtitle.append(' ').append(refScan.refs.size())
                .append(" incoming reference(s) will be left dangling."); //$NON-NLS-1$
        }
        ConsentPreview consentPreview = new ConsentPreview(
            "Delete predefined item", //$NON-NLS-1$
            consentSubtitle.toString(), cascadeTotal, Collections.singletonList(normFqn));
        DestructiveConsentGate.ConsentDecision consentDecision =
            DestructiveConsentGate.getInstance().requireConsent(NAME, consentPreview);
        if (consentDecision != DestructiveConsentGate.ConsentDecision.ALLOW)
        {
            return ToolResult.error(DestructiveConsentGate.consentDeniedMessage(consentDecision, NAME)).toJson();
        }

        return performPredefinedItemDelete(ctx.project, owner, normFqn, ref, refScan, force);
    }

    /**
     * Preview (no mutation): a {name, type} row for the item itself AND one per cascaded descendant
     * (a folder delete removes its whole content tree - the structured {@code items} must list
     * everything the confirm would remove, like the owned-form preview does), plus a message noting
     * a folder's cascade count AND (issue #296 P1) the incoming-reference count - or, when the scan
     * did NOT run to completion, that the check could not be completed (so a confirm=true may still
     * be blocked) - so a caller sees the block coming before ever calling confirm=true.
     */
    private String buildPredefinedItemDeletePreview(String normFqn, PredefinedWriter.PredefinedRef ref,
        PredefinedWriter.DeletePreview preview, PredefinedRefScan refScan)
    {
        Map<String, Object> head = new java.util.LinkedHashMap<>();
        head.put("name", ref.itemName); //$NON-NLS-1$
        head.put("type", preview.kind); //$NON-NLS-1$
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(head);
        for (String[] descendant : preview.descendants)
        {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", descendant[0]); //$NON-NLS-1$
            row.put("type", descendant[1]); //$NON-NLS-1$
            items.add(row);
        }

        // A cascade is driven by CONTAINMENT descendants, NOT by isFolder: a ChartOfAccounts parent
        // account is NOT a folder yet its childItems DO cascade (isFolder=false, descendantCount>0).
        // The "(a FOLDER)" label stays gated on isFolder so a non-folder cascading owner is not
        // mislabelled, but the cascade wording itself follows the real descendant count.
        boolean cascades = preview.descendantCount > 0;
        // A confirm=true delete WITHOUT force would block when the scan found references OR did not
        // complete - the SAME decision the confirm path makes - so the preview's blocking flag never
        // contradicts what a subsequent confirm actually does.
        boolean wouldBlock = predefinedDeleteWouldBlock(refScan, false);
        boolean hasBlocking = refScan.completed && !refScan.refs.isEmpty();
        StringBuilder message = new StringBuilder(cascades
            ? "Preview: deleting predefined item '" + ref.itemName + "'" //$NON-NLS-1$ //$NON-NLS-2$
                + (preview.isFolder ? " (a FOLDER)" : "") + " from " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ref.ownerFqn() + " would remove it AND its " + preview.descendantCount //$NON-NLS-1$
                + " nested item(s)." //$NON-NLS-1$
            : "Preview: deleting predefined item '" + ref.itemName + "' from " + ref.ownerFqn() //$NON-NLS-1$ //$NON-NLS-2$
                + " would remove the item itself."); //$NON-NLS-1$
        if (!refScan.completed)
        {
            message.append(" The incoming-reference check could NOT be completed (the project may " //$NON-NLS-1$
                + "still be building or the reference index is unavailable): a confirm=true delete may " //$NON-NLS-1$
                + "be BLOCKED unless force=true is also passed."); //$NON-NLS-1$
        }
        else if (hasBlocking)
        {
            message.append(" It is referenced by ").append(refScan.refs.size()) //$NON-NLS-1$
                .append(" place(s) that cannot be auto-cleaned: a confirm=true delete will be BLOCKED " //$NON-NLS-1$
                    + "unless force=true is also passed (force leaves these references dangling)."); //$NON-NLS-1$
        }
        message.append(" Call confirm=true to apply."); //$NON-NLS-1$

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_PREVIEW)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_REFACTORING_TITLE, "Delete predefined item " + ref.itemName) //$NON-NLS-1$
            .put(KEY_ITEMS, items)
            .put(KEY_BLOCKING, wouldBlock);
        return putBlockingReferences(result, refScan.refs)
            .put(McpKeys.MESSAGE, message.toString())
            .toJson();
    }

    /**
     * Delete inside a WRITE transaction: re-fetch the owner, remove the item, export the owner FQN.
     * {@code refScan} / {@code force} are used only to compose the result message (the actual
     * block/force decision already ran in the caller before this is reached).
     */
    private String performPredefinedItemDelete(IProject project, MdObject owner,
        String normFqn, PredefinedWriter.PredefinedRef ref, PredefinedRefScan refScan,
        boolean force)
    {
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + project.getName()).toJson(); //$NON-NLS-1$
        }

        final long ownerBmId = ((IBmObject)owner).bmGetId();
        final String itemName = ref.itemName;
        // Force-export must target the owner's CANONICAL FQN (its own bmGetFqn()), never the
        // caller's spelling; and the cascade count in the result message is re-taken INSIDE the
        // write transaction (the pre-confirm preview may be stale by the time confirm runs).
        final String[] canonicalOwnerFqnHolder = new String[1];
        final PredefinedWriter.DeletePreview[] txPreviewHolder = new PredefinedWriter.DeletePreview[1];

        try
        {
            BmTransactions.<Void>write(bmModel, "DeletePredefinedItem", (tx, pm) -> //$NON-NLS-1$
            {
                EObject txOwner = (EObject)tx.getObjectById(ownerBmId);
                if (txOwner == null)
                {
                    throw new RuntimeException("Owner object not found in transaction"); //$NON-NLS-1$
                }
                canonicalOwnerFqnHolder[0] = ((IBmObject)txOwner).bmGetFqn();
                txPreviewHolder[0] = PredefinedWriter.preview(txOwner, itemName);
                PredefinedWriter.WriteResult result = PredefinedWriter.delete(txOwner, itemName);
                if (result.isError())
                {
                    throw new IllegalStateException(result.error);
                }
                return null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error deleting predefined item", e); //$NON-NLS-1$
            return ToolResult.error("Delete failed: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        boolean persisted = BmTransactions.forceExportToDisk(project, canonicalOwnerFqnHolder[0]);
        PredefinedWriter.DeletePreview txPreview = txPreviewHolder[0];
        // Cascade is driven by the CONTAINMENT descendant count, not isFolder: a ChartOfAccounts
        // parent account (isFolder=false) still cascades its childItems, so the executed-result
        // message must report the nested count too (the "(with its N nested item(s))" clause below is
        // already folder-agnostic, so Catalog/CCT output stays byte-identical - their non-folders have
        // zero containment descendants).
        boolean cascaded = txPreview != null && txPreview.descendantCount > 0;

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_EXECUTED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("forced", force); //$NON-NLS-1$
        StringBuilder message =
            new StringBuilder("Deleted predefined item '" + itemName + "' from " + ref.ownerFqn()); //$NON-NLS-1$ //$NON-NLS-2$
        if (cascaded)
        {
            message.append(" (with its ").append(txPreview.descendantCount).append(" nested item(s))"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        message.append(persisted ? " and persisted to disk." //$NON-NLS-1$
            : " (in-memory only; on-disk write did not complete - re-check before relying on it)."); //$NON-NLS-1$
        if (!refScan.completed)
        {
            message.append(' ').append("The incoming-reference check did not complete before this " //$NON-NLS-1$
                + "delete; any references to this item are UNVERIFIED and may be left dangling."); //$NON-NLS-1$
            if (!refScan.refs.isEmpty())
            {
                putBlockingReferences(result, refScan.refs);
            }
        }
        else if (!refScan.refs.isEmpty())
        {
            message.append(' ').append(refScan.refs.size())
                .append(" incoming reference(s) were left dangling."); //$NON-NLS-1$
            putBlockingReferences(result, refScan.refs);
        }
        return result.put(McpKeys.MESSAGE, message.toString()).toJson();
    }

    /**
     * Outcome of {@link #collectPredefinedItemBlockingReferences} (issue #296 P1 fix): the
     * blocking-reference rows gathered so far, AND whether the scan ran to completion.
     * {@code completed=false} means the incoming-reference state is UNVERIFIED - a null BM model /
     * model manager, a missing owner/item once re-fetched inside the transaction, a per-item
     * {@code getBackReferences} failure, or any other exception - and must NEVER be read as "genuinely
     * zero references": {@code refs} may still carry a partial list gathered before the failure, but
     * callers must fail CLOSED (block unless {@code force=true}), never silently proceed. See
     * {@link #deletePredefinedItem}.
     */
    /**
     * Result of the predefined-item incoming-reference scan: the collected blocking-reference rows,
     * and whether the scan RAN TO COMPLETION. {@code completed=false} (a partial/failed scan) is NOT
     * the same as "genuinely zero references" - it means the reference state is UNVERIFIED, which
     * fail-closes a non-forced delete (see {@link #predefinedDeleteWouldBlock}). Package-visible so the
     * pure block-decision it feeds is unit-testable.
     */
    static final class PredefinedRefScan
    {
        final List<Map<String, Object>> refs;
        final boolean completed;

        PredefinedRefScan(List<Map<String, Object>> refs, boolean completed)
        {
            this.refs = refs;
            this.completed = completed;
        }
    }

    /**
     * The SINGLE fail-closed decision for a predefined-item delete: whether a {@code confirm=true}
     * delete would be BLOCKED for this reference scan. A non-forced delete blocks when the scan did NOT
     * complete (the reference state is UNVERIFIED - safer to refuse than to delete blind) OR when it
     * completed and found at least one blocking reference. {@code force=true} bypasses both. Both the
     * confirm path and the preview's {@code blocking} flag route through this ONE method, so the
     * behaviour cannot drift between them and a regression that inverts it fails the unit test.
     * Package-visible for testing.
     *
     * @param scan the reference scan result
     * @param force the caller's {@code force} flag
     * @return {@code true} when a non-forced delete must be blocked
     */
    static boolean predefinedDeleteWouldBlock(PredefinedRefScan scan, boolean force)
    {
        if (force)
        {
            return false;
        }
        return !scan.completed || !scan.refs.isEmpty();
    }

    /**
     * Result-size hint passed to {@link MetadataReferenceService#collectReferencesForObjectStrict} for a
     * predefined item's incoming-reference scan - generous enough that a real config never truncates it
     * (the collector's own internal cap is {@code limit * 10} per category), matching find_references'
     * own default ({@code FindReferencesTool}'s {@code limit} parameter default).
     */
    private static final int PREDEFINED_REF_SCAN_LIMIT = 100;

    /**
     * Collects incoming references to the predefined item {@code ref.itemName} on {@code owner} AND -
     * when it is a FOLDER - every descendant it would cascade (issue #296 P1), REUSING the exact same
     * reference-collection engine {@code find_references} uses ({@link
     * MetadataReferenceService#collectReferencesForObjectStrict}, issue #293) rather than a hand-rolled
     * subset of it. This closes two gaps the former hand-rolled scan had: (1) it now ALSO covers BSL
     * code references - the SEPARATE Xtext-indexed mechanism the shared service wires in as its 5th
     * collection step ({@code collectBslReferences}), which a plain {@code IBmEngine.getBackReferences}
     * scan can never see; and (2) it no longer false-positives on the item's OWN owner showing up as a
     * "reference" through the derived predefined-data-source linkage (the EXACT {@code "source"}
     * feature, narrowed - a same-owner reference through any OTHER feature is a real dependency and
     * still blocks) - see {@link #isOwnerSelfReference}. Runs inside its own READ transaction,
     * re-fetching the owner by BM id (like every other transaction in this tool: an EMF object resolved
     * OUTSIDE a transaction is not valid to query INSIDE a different one). De-duplicated by
     * (referencingObject, reference, line).
     * <p>
     * FAILS CLOSED: returns {@link PredefinedRefScan#completed}=false (never throws) when the BM model
     * manager / model is unavailable, the owner/item cannot be re-fetched inside the transaction, a
     * per-item scan throws, the transaction itself throws, OR the BSL code-reference step of the scan
     * for the item OR any descendant did not itself complete ({@link
     * MetadataReferenceService.ReferenceScanResult#complete}={@code false} - an unavailable/throwing
     * Xtext reference index) - the caller must then treat the reference state as UNVERIFIED, not as
     * "zero references" (never silently treated as safe).
     * <p>
     * <b>BSL coverage (confirmed live):</b> a predefined item is exposed to BSL as a member of its
     * owner's manager (e.g. {@code Catalogs.Products.SomeItem}). The Xtext scope provider resolves that
     * usage to the {@code PredefinedItem}'s OWN EMF URI, so {@link
     * MetadataReferenceService#collectReferencesForObjectStrict}'s BSL step (which matches on {@code
     * EcoreUtil.getURI(target)}) DOES find it - verified end-to-end on the 2026.1 stand (a common
     * module reading a predefined item blocks the item's non-forced delete). The e2e suite fails hard
     * if this ever stops holding (a BSL URI-resolution / indexing regression), so this path can be
     * relied on to catch a BSL incoming reference to a predefined item.
     *
     * @param project the owning workspace project
     * @param owner the (already resolved) predefined-item owner
     * @param ref the parsed predefined-item FQN
     * @return the scan outcome (never {@code null}); {@code refs} is the de-duplicated blocking-reference
     *     rows, the SAME shape {@link #describeProblem} builds for the generic-node delete path
     */
    private static PredefinedRefScan collectPredefinedItemBlockingReferences(IProject project,
        IBmObject owner, PredefinedWriter.PredefinedRef ref)
    {
        try
        {
            IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
            if (bmModelManager == null)
            {
                return new PredefinedRefScan(Collections.emptyList(), false);
            }
            final IBmModel bmModel = bmModelManager.getModel(project);
            if (bmModel == null)
            {
                return new PredefinedRefScan(Collections.emptyList(), false);
            }
            final long ownerBmId = owner.bmGetId();
            return BmTransactions.<PredefinedRefScan>read(bmModel, "PredefinedItemBackReferences", //$NON-NLS-1$
                (tx, pm) ->
                {
                    EObject txOwner = (EObject)tx.getObjectById(ownerBmId);
                    if (txOwner == null)
                    {
                        return new PredefinedRefScan(Collections.emptyList(), false);
                    }
                    PredefinedItem item = PredefinedWriter.findByName(txOwner, ref.itemName);
                    if (item == null)
                    {
                        return new PredefinedRefScan(Collections.emptyList(), false);
                    }
                    MetadataReferenceService referenceService = new MetadataReferenceService();
                    List<Map<String, Object>> refs = new ArrayList<>();
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    boolean completed = collectOnePredefinedItemReferences(referenceService, bmModel, item,
                        ownerBmId, seen, refs);
                    for (PredefinedItem descendant : PredefinedWriter.descendants(item))
                    {
                        completed = collectOnePredefinedItemReferences(referenceService, bmModel, descendant,
                            ownerBmId, seen, refs) && completed;
                    }
                    return new PredefinedRefScan(refs, completed);
                });
        }
        catch (Exception e)
        {
            Activator.logError("Error collecting predefined item back references; the reference check " //$NON-NLS-1$
                + "could not be completed", e); //$NON-NLS-1$
            return new PredefinedRefScan(Collections.emptyList(), false);
        }
    }

    /**
     * Collects (into {@code out}, de-duplicated via {@code seen}) the non-owner-self references to a
     * single predefined item - reusing the SAME metadata+BSL reference-collection engine
     * find_references uses ({@link MetadataReferenceService#collectReferencesForObjectStrict}), which
     * already applies find_references' OWN technical-noise filter (a transient feature; a {@code
     * dbview} / {@code cmi}+{@code deriveddata} package reference - mirrors {@code
     * MetadataReferenceService.ReferenceCollector#isInternalReference}/{@code #isInternalPath}), and ALSO
     * reports whether its BSL code-reference step completed. This method adds ONE further exclusion on
     * top, specific to THIS delete safety check and NOT applied by the shared service (which
     * intentionally shows self-references in its own diagnostic view): a reference whose source object's
     * own top container IS the item's owner AND whose feature is the structural predefined-data-source
     * linkage is dropped - see {@link #isOwnerSelfReference}.
     *
     * @return {@code true} when the scan for THIS item completed without error AND its BSL
     *     code-reference step ({@link MetadataReferenceService.ReferenceScanResult#complete}) itself ran
     *     to completion (issue #293 P1 fix-round: an unavailable/throwing BSL index now fails the scan
     *     CLOSED instead of silently reading as "no BSL references"), {@code false} when either {@link
     *     MetadataReferenceService#collectReferencesForObjectStrict} threw or its BSL step did not
     *     complete (in which case {@code out} may still hold whatever rows a previous item in the
     *     caller's loop already gathered - the caller ANDs this flag across every item/descendant it
     *     scans, per {@link PredefinedRefScan#completed})
     */
    private static boolean collectOnePredefinedItemReferences(MetadataReferenceService referenceService,
        IBmModel bmModel, PredefinedItem item, long ownerTopId, java.util.Set<String> seen,
        List<Map<String, Object>> out)
    {
        MetadataReferenceService.ReferenceScanResult scanResult;
        try
        {
            scanResult = referenceService.collectReferencesForObjectStrict(bmModel, (IBmObject)item,
                PREDEFINED_REF_SCAN_LIMIT);
        }
        catch (Exception e)
        {
            Activator.logError("Error collecting references for predefined item '" + item.getName() //$NON-NLS-1$
                + "'", e); //$NON-NLS-1$
            return false;
        }
        for (MetadataReferenceService.ReferenceInfo info : scanResult.refs)
        {
            if (isOwnerSelfReference(info, ownerTopId))
            {
                continue;
            }
            Map<String, Object> row = describePredefinedReferenceInfo(item, info);
            String key = row.get("referencingObject") + ":" + row.get("reference") + ":" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + row.get("line"); //$NON-NLS-1$
            if (seen.add(key))
            {
                out.add(row);
            }
        }
        return scanResult.complete;
    }

    /**
     * The structural feature name of the predefined-data-source linkage: a derived-model back-reference
     * to a pristine {@link PredefinedItem} through this EXACT feature always resolves to the item's own
     * owner Catalog/ChartOfCharacteristicTypes - {@link PredefinedItem} itself declares no such feature
     * in {@code MdClass.xcore} (only {@code id}/{@code name}/{@code description}/{@code extension}), so
     * this back-reference is produced by a derived model the platform maintains alongside the raw
     * containment, not by any real dependency. Confirmed live (issue #293 P1): a fresh predefined item
     * with zero real references still surfaces exactly one back-reference whose feature name is this
     * constant and whose resolved source renders as the owner itself.
     */
    private static final String PREDEFINED_DATA_SOURCE_FEATURE = "source"; //$NON-NLS-1$

    /**
     * Whether {@code info} is the STRUCTURAL predefined-data-source self-reference to exclude (issue
     * #293 P1, narrowed): {@code true} ONLY when BOTH (a) {@code info}'s SOURCE object (a metadata
     * reference only - a BSL reference never carries one, see {@link
     * MetadataReferenceService.ReferenceInfo#sourceObject}) belongs to the SAME owner top-object as the
     * predefined item being deleted (walks the source object's container chain up to its OWN top {@code
     * IBmObject} and compares {@code bmGetId()} against {@code ownerTopId}), AND (b) the reference's
     * structural feature is exactly {@link #PREDEFINED_DATA_SOURCE_FEATURE}.
     * <p>
     * The feature check matters: matching on same-owner ALONE was too broad and discarded a REAL
     * same-owner reference - e.g. a Catalog attribute's fill value / {@code ReferenceValue} pointing at
     * a predefined item of the SAME catalog (feature "value"), or a {@code
     * ChartOfCalculationTypesPredefinedItem}'s {@code base}/{@code displaced}/{@code leading} referring
     * to a SIBLING predefined item - both of which must still BLOCK a delete. Only a same-owner
     * reference through the derived predefined-data-source linkage is purely structural (it exists
     * merely because the item lives inside that owner) and never an external dependency.
     * <p>
     * Deliberately NOT applied inside {@code MetadataReferenceService} itself - find_references
     * intentionally shows self-references in its own diagnostic view; this exclusion is specific to the
     * delete safety check. Package-visible for tests.
     *
     * @param info the collected reference (its {@code sourceObject} may be {@code null})
     * @param ownerTopId the {@code bmGetId()} of the predefined item's owner (a top object)
     * @return {@code true} when this reference is the structural predefined-data-source self-reference
     */
    static boolean isOwnerSelfReference(MetadataReferenceService.ReferenceInfo info, long ownerTopId)
    {
        IBmObject source = info.sourceObject;
        if (source == null)
        {
            // A BSL reference (no live source object) - never an owner-self reference: a BSL module is
            // always a DIFFERENT top object from the predefined item's owner.
            return false;
        }
        if (!PREDEFINED_DATA_SOURCE_FEATURE.equals(info.feature))
        {
            // A REAL reference (value / fillValue / type / base / displaced / leading /
            // characteristicType / ...) must never be excluded, even from the same owner top-object.
            return false;
        }
        IBmObject top = findTopContainer(source);
        return top != null && top.bmGetId() == ownerTopId;
    }

    /**
     * Walks {@code object}'s container chain up to (and including) its own top {@link IBmObject} -
     * mirrors {@code MetadataReferenceService.ReferenceCollector#findTopContainer} (private to a
     * different package, so this is a small, deliberate duplicate: the owner-self exclusion belongs in
     * THIS delete path, not the shared find_references service - see {@link #isOwnerSelfReference}).
     * Guarded by an IDENTITY visited-set (not a depth cap): a genuine {@code eContainer()} CYCLE
     * terminates and returns {@code null}, while every finite chain reaches its real top. Returns
     * {@code null} on a top-less / cyclic chain - NOT the last reached non-top object:
     * {@link #isOwnerSelfReference} then treats it as "not owner-self" and KEEPS the reference (a
     * delete over-blocks rather than deleting a possibly referenced item - the fail-safe direction).
     * The ONLY non-null return is a real top object.
     */
    private static IBmObject findTopContainer(IBmObject object)
    {
        if (object == null)
        {
            return null;
        }
        if (object.bmIsTop())
        {
            return object;
        }
        java.util.Set<EObject> visited =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        EObject current = (EObject)object;
        while (current != null && visited.add(current))
        {
            if (current instanceof IBmObject && ((IBmObject)current).bmIsTop())
            {
                return (IBmObject)current;
            }
            current = current.eContainer();
        }
        return null;
    }

    /**
     * Converts one {@link MetadataReferenceService.ReferenceInfo} (from {@link
     * MetadataReferenceService#collectReferencesForObjectStrict}) into this tool's block-row map shape - the
     * SAME {@code problemType} / {@code referencingObject} / {@code reference} / {@code targetObject}
     * fields {@link #describeProblem} builds for the generic-node delete path - so the wire contract
     * ({@code blockingReferences}) is unchanged regardless of which collector produced the row.
     * {@code problemType} carries the reference's CATEGORY (e.g. "BSL modules", "Documents", "Common
     * modules") - more specific than the single "PredefinedItemReference" constant the former
     * hand-rolled collector used, since the shared service already classifies every reference it finds.
     * A BSL reference additionally carries its {@code line} number (a metadata reference has none - it
     * carries a {@code reference} feature name instead). Package-visible for tests.
     */
    static Map<String, Object> describePredefinedReferenceInfo(PredefinedItem item,
        MetadataReferenceService.ReferenceInfo info)
    {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("problemType", info.category != null ? info.category : "Reference"); //$NON-NLS-1$ //$NON-NLS-2$
        if (info.sourcePath != null && !info.sourcePath.isEmpty())
        {
            map.put("referencingObject", info.sourcePath); //$NON-NLS-1$
        }
        if (info.isBslReference)
        {
            map.put("reference", "BSL code"); //$NON-NLS-1$ //$NON-NLS-2$
            map.put("line", info.line); //$NON-NLS-1$
        }
        else if (info.feature != null && !info.feature.isEmpty())
        {
            map.put("reference", info.feature); //$NON-NLS-1$
        }
        map.put("targetObject", item.getName()); //$NON-NLS-1$
        return map;
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
            FormElementWriter.formPathOf(ownerType, resolvedOwnerName, resolvedFormName));
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

    // ==================== XDTO package members (cross-model hop, issue #183 stream 1) ====================

    /**
     * Deletes an XDTO PACKAGE MEMBER (an ObjectType or a Property, package-global or nested in an
     * ObjectType) addressed by {@code ref}. The md-refactoring service is mdclass-only, so the member is
     * removed directly (EMF list removal - {@code ObjectType.getProperties()} is containment, so
     * removing an ObjectType cascades its own properties). Two-phase like the rest of the tool:
     * {@code confirm=false} previews (a rolled-back read, since the package's content is a lazy
     * {@code @ExternalProperty}), {@code confirm=true} removes it (behind the SAME
     * {@link DestructiveConsentGate} the generic mdclass delete path uses) and force-exports the owning
     * package.
     */
    private String deleteXdtoMember(ProjectContext ctx, String normFqn, XdtoWriter.MemberRef ref,
        boolean confirm)
    {
        MetadataNodeResolver.MetadataNode pkgNode =
            MetadataNodeResolver.resolveExistingWithYoFallback(ctx.config, ref.packageFqn).node;
        if (pkgNode == null || !(pkgNode.object instanceof XDTOPackage)
            || !(pkgNode.object instanceof IBmObject))
        {
            return ToolResult.error("XDTOPackage not found: " + ref.packageFqn //$NON-NLS-1$
                + ". Use get_metadata_objects to find an FQN.").toJson(); //$NON-NLS-1$
        }
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        // The generator is needed only on the confirm=true (write) path - to derive the content's own
        // export FQN from the OWNER (never via bmGetFqn() on the content itself; see
        // XdtoWriter.resolvePackageContent's javadoc for why that throws BmAssertionException even on
        // content that looks "attached").
        ITopObjectFqnGenerator fqnGenerator = Activator.getDefault().getTopObjectFqnGenerator();
        if (bmModelManager == null || (confirm && fqnGenerator == null))
        {
            return ToolResult.error("IBmModelManager not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(ctx.project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + ctx.project.getName()).toJson(); //$NON-NLS-1$
        }
        final long pkgBmId = ((IBmObject)pkgNode.object).bmGetId();
        // The RESOLVED package's canonical FQN: with the yo fallback the caller-typed spelling may
        // differ from the stored name, and force-export must target the stored top object.
        final String pkgExportFqn = "XDTOPackage." + ((XDTOPackage)pkgNode.object).getName(); //$NON-NLS-1$
        return confirm
            ? performXdtoMemberDelete(ctx, normFqn, ref, bmModel, pkgBmId, fqnGenerator, pkgExportFqn)
            : buildXdtoMemberDeletePreview(normFqn, ref, bmModel, pkgBmId);
    }

    /** Preview inside a rolled-back (read-with-materialize) transaction: locates the target, no mutation. */
    private String buildXdtoMemberDeletePreview(String normFqn, XdtoWriter.MemberRef ref, IBmModel bmModel,
        long pkgBmId)
    {
        String[] found = BmTransactions.executeAndRollback(bmModel, "DeleteXdtoMemberPreview", (tx, pm) -> //$NON-NLS-1$
        {
            Object inTx = tx.getObjectById(pkgBmId);
            Package content = inTx instanceof XDTOPackage ? ((XDTOPackage)inTx).getPackage() : null;
            return locateXdtoMember(content, ref);
        });
        if (found == null)
        {
            return xdtoMemberNotFoundError(ref);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(formItem(ref.memberName(), found[0]));

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_PREVIEW)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_REFACTORING_TITLE, "Delete XDTO member " + ref.memberName()) //$NON-NLS-1$
            .put(KEY_ITEMS, items)
            .put(KEY_BLOCKING, false);
        return putBlockingReferences(result, Collections.emptyList())
            .put(McpKeys.MESSAGE, "Preview: deleting '" + ref.memberName() + "' (" + found[0] + ") from " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ref.packageFqn
                + (ref.kind == XdtoWriter.Kind.OBJECT_TYPE ? " would remove it and all its own properties." //$NON-NLS-1$
                    : ".") //$NON-NLS-1$
                + " Cross-references to it (a Property whose type/ref points at this ObjectType) are " //$NON-NLS-1$
                + "NOT rewritten - re-check with get_metadata_details afterwards. Call confirm=true to " //$NON-NLS-1$
                + "apply.") //$NON-NLS-1$
            .toJson();
    }

    /** Delete behind the destructive-consent gate, then a write transaction; force-exports the package. */
    private String performXdtoMemberDelete(ProjectContext ctx, String normFqn, XdtoWriter.MemberRef ref,
        IBmModel bmModel, long pkgBmId, ITopObjectFqnGenerator fqnGenerator,
        String pkgExportFqn)
    {
        ConsentPreview preview = new ConsentPreview("Delete metadata node", //$NON-NLS-1$
            "This deletes '" + normFqn + "'" //$NON-NLS-1$ //$NON-NLS-2$
                + (ref.kind == XdtoWriter.Kind.OBJECT_TYPE ? " and all its own properties." : "."), //$NON-NLS-1$ //$NON-NLS-2$
            1, Collections.singletonList(normFqn));
        DestructiveConsentGate.ConsentDecision consentDecision =
            DestructiveConsentGate.getInstance().requireConsent(NAME, preview);
        if (consentDecision != DestructiveConsentGate.ConsentDecision.ALLOW)
        {
            return ToolResult.error(DestructiveConsentGate.consentDeniedMessage(consentDecision, NAME)).toJson();
        }

        XdtoDeleteResult result;
        try
        {
            result = BmTransactions.<XdtoDeleteResult> write(bmModel, "DeleteXdtoMember", (tx, pm) -> //$NON-NLS-1$
                deleteXdtoMemberInTx(tx, pkgBmId, ref, fqnGenerator));
        }
        catch (Exception e)
        {
            String ready = XdtoWriteException.jsonOf(e);
            if (ready != null)
            {
                return ready;
            }
            Activator.logError("Error deleting XDTO member", e); //$NON-NLS-1$
            return ToolResult.error("Delete failed: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        // DUAL force-export, mirroring create_metadata / modify_metadata exactly: the owning
        // XDTOPackage's FQN (drains the .mdo) AND the content's OWN resource FQN (drains the sibling
        // .xdto) - exporting the package FQN alone would leave the deleted member on disk (a
        // #239-class silent false "persisted").
        List<String> exportFqns = new ArrayList<>();
        exportFqns.add(pkgExportFqn);
        if (!result.contentFqn.equals(pkgExportFqn))
        {
            exportFqns.add(result.contentFqn);
        }
        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, exportFqns);
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_EXECUTED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("forced", false) //$NON-NLS-1$
            .put(McpKeys.MESSAGE, "Deleted XDTO member '" + ref.memberName() + "' (" + result.kind //$NON-NLS-1$ //$NON-NLS-2$
                + ") from " + ref.packageFqn //$NON-NLS-1$
                + (persisted ? " and persisted to disk." //$NON-NLS-1$
                    : " (in-memory only; on-disk write did not complete - re-check before relying on " //$NON-NLS-1$
                        + "it).")) //$NON-NLS-1$
            .toJson();
    }

    /** The write-transaction result for {@link #performXdtoMemberDelete}: the removed kind + the content's own export FQN. */
    private static final class XdtoDeleteResult
    {
        final String kind;
        final String contentFqn;

        XdtoDeleteResult(String kind, String contentFqn)
        {
            this.kind = kind;
            this.contentFqn = contentFqn;
        }
    }

    /**
     * The write-transaction body for {@link #performXdtoMemberDelete}: re-fetches the XDTOPackage,
     * reads its (possibly {@code null} / never-materialized) content directly - no ATTACH needed for a
     * delete of an EXISTING member, since a package that already has a member was necessarily
     * materialized + attached by an earlier create/modify - derives the content's OWN export FQN (for
     * the dual force-export) from the OWNER via the generator, locates the target and removes it.
     * Deriving the content FQN from the owner (never via {@code bmGetFqn()} on the content itself) is
     * REQUIRED, not a style choice: a live-stand regression proved {@code bmGetFqn()} throws
     * {@code BmAssertionException} on this package's content even when it looks fully attached
     * ({@code bmIsTop() == true}) - see {@link XdtoWriter#resolvePackageContent}'s javadoc for the full
     * trail. Throws {@link XdtoWriteException} (a ready JSON error) when the package or the target
     * member cannot be resolved, rolling the whole write back with no partial mutation.
     */
    private static XdtoDeleteResult deleteXdtoMemberInTx(IBmTransaction tx, long pkgBmId,
        XdtoWriter.MemberRef ref, ITopObjectFqnGenerator fqnGenerator)
    {
        Object inTx = tx.getObjectById(pkgBmId);
        if (!(inTx instanceof XDTOPackage))
        {
            throw new XdtoWriteException(ToolResult.error("The XDTO package could not be resolved " //$NON-NLS-1$
                + "inside the transaction.").toJson()); //$NON-NLS-1$
        }
        XDTOPackage txPkg = (XDTOPackage)inTx;
        Package content = txPkg.getPackage();
        if (content == null)
        {
            throw new XdtoWriteException(xdtoMemberNotFoundError(ref));
        }
        // Derived from the OWNER, never from the content (see the method javadoc above) - the ONLY call
        // proven safe regardless of which transaction attached the content.
        String contentFqn =
            fqnGenerator.generateExternalPropertyFqn(txPkg, MdClassPackage.Literals.XDTO_PACKAGE__PACKAGE);
        if (contentFqn == null || contentFqn.isEmpty())
        {
            throw new XdtoWriteException(ToolResult.error("Cannot resolve the on-disk resource for the " //$NON-NLS-1$
                + "XDTO package content; report it with the package FQN '" + ref.packageFqn + "'.") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson());
        }

        if (ref.kind == XdtoWriter.Kind.OBJECT_TYPE)
        {
            ObjectType type = XdtoWriter.findObjectType(content, ref.objectTypeName);
            if (type == null)
            {
                throw new XdtoWriteException(xdtoMemberNotFoundError(ref));
            }
            String kind = type.eClass().getName();
            XdtoWriter.removeObjectType(content, type);
            return new XdtoDeleteResult(kind, contentFqn);
        }
        EList<Property> owner = content.getProperties();
        if (ref.kind == XdtoWriter.Kind.OBJECT_TYPE_PROPERTY)
        {
            ObjectType type = XdtoWriter.findObjectType(content, ref.objectTypeName);
            if (type == null)
            {
                throw new XdtoWriteException(xdtoMemberNotFoundError(ref));
            }
            owner = type.getProperties();
        }
        Property property = XdtoWriter.findProperty(owner, ref.propertyName);
        if (property == null)
        {
            throw new XdtoWriteException(xdtoMemberNotFoundError(ref));
        }
        String kind = property.eClass().getName();
        XdtoWriter.removeProperty(owner, property);
        return new XdtoDeleteResult(kind, contentFqn);
    }

    /**
     * Locates the target member (a pure read, used by the preview): {eClassName}, or {@code null}.
     * Package-visible for tests.
     */
    static String[] locateXdtoMember(Package content, XdtoWriter.MemberRef ref)
    {
        if (content == null)
        {
            return null;
        }
        if (ref.kind == XdtoWriter.Kind.OBJECT_TYPE)
        {
            ObjectType type = XdtoWriter.findObjectType(content, ref.objectTypeName);
            return type == null ? null : new String[] { type.eClass().getName() };
        }
        EList<Property> owner = content.getProperties();
        if (ref.kind == XdtoWriter.Kind.OBJECT_TYPE_PROPERTY)
        {
            ObjectType type = XdtoWriter.findObjectType(content, ref.objectTypeName);
            if (type == null)
            {
                return null;
            }
            owner = type.getProperties();
        }
        Property property = XdtoWriter.findProperty(owner, ref.propertyName);
        return property == null ? null : new String[] { property.eClass().getName() };
    }

    /**
     * The actionable "member not found" error, naming the ObjectType/Property and its owner.
     * Package-visible for tests.
     */
    static String xdtoMemberNotFoundError(XdtoWriter.MemberRef ref)
    {
        if (ref.kind == XdtoWriter.Kind.OBJECT_TYPE)
        {
            return ToolResult.error("ObjectType not found: '" + ref.objectTypeName + "' in package " //$NON-NLS-1$ //$NON-NLS-2$
                + ref.packageFqn + ". Use get_metadata_details on the package FQN to list its object " //$NON-NLS-1$
                + "types.").toJson(); //$NON-NLS-1$
        }
        String owner = ref.kind == XdtoWriter.Kind.OBJECT_TYPE_PROPERTY
            ? ref.packageFqn + ".ObjectType." + ref.objectTypeName //$NON-NLS-1$
            : ref.packageFqn;
        return ToolResult.error("Property not found: '" + ref.propertyName + "' on " + owner //$NON-NLS-1$ //$NON-NLS-2$
            + ". Use get_metadata_details on the package FQN to list its properties.").toJson(); //$NON-NLS-1$
    }

    /**
     * Walks the item's contained {@code items} subtree depth-first, appending each descendant as a
     * {name, type} map (the same {@code getReferenceList} / {@code nameOf} walk the form reader uses),
     * so the preview lists what a container delete cascades. The item ITSELF is not added.
     */
    private static void collectItemDescendants(EObject item, List<Map<String, Object>> out)
    {
        for (EObject child : FormStructureReader.getReferenceList(item, KEY_ITEMS))
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
