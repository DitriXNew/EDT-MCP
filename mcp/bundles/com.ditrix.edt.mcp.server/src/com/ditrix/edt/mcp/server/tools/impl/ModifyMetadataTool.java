/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.StyleElementType;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormValidationException;
import com.ditrix.edt.mcp.server.utils.MdNameNormalizer;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.PropertyInfo;
import com.ditrix.edt.mcp.server.utils.MetadataTypeBuilder;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.StyleValueBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Sets one or more properties of a metadata node (a top-level object or a member) addressed by a 1C
 * full-name FQN. Every property is VALIDATED before any write: an unknown / non-assignable property
 * is rejected with the list of assignable properties, and an out-of-range value (e.g. an enum value
 * that is not a valid literal) is rejected with the allowed values - so the error is actionable.
 * Replaces the former {@code set_metadata_property} (which set only Comment / Synonym).
 *
 * <p>Renaming is out of scope: setting the {@code name} property is refused with a pointer to
 * {@code rename_metadata_object}, because a Name change must cascade across all references.</p>
 */
public class ModifyMetadataTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "modify_metadata"; //$NON-NLS-1$

    /** Output result key: names of the properties that were set. */
    private static final String KEY_APPLIED = "applied"; //$NON-NLS-1$

    /** Output result key: whether the change was exported to disk. */
    private static final String KEY_PERSISTED = "persisted"; //$NON-NLS-1$

    /** Output value for {@link McpKeys#ACTION}: the node was modified. */
    private static final String VAL_MODIFIED = "modified"; //$NON-NLS-1$

    /** Property/JSON key: the value of a property entry. */
    private static final String KEY_VALUE = "value"; //$NON-NLS-1$

    /** Error message prefix for an unresolved form FQN. */
    private static final String ERR_FORM_NOT_FOUND_PREFIX = "Form not found for '"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Set properties of a metadata node (object or member, including a FORM member - item / " //$NON-NLS-1$
            + "attribute / command) addressed by a 1C full-name FQN, as " //$NON-NLS-1$
            + "properties=[{name, value, language?}]. Each property is validated (it must be " //$NON-NLS-1$
            + "assignable, and an enum value must be one of the allowed literals) with an actionable " //$NON-NLS-1$
            + "error. Move/reorder a FORM ITEM with the 'parent' (a group name, 'AutoCommandBar' for " //$NON-NLS-1$
            + "the form's command bar, or the form name for the form root) and/or 'position' " //$NON-NLS-1$
            + "('first'/'last'/'before:<name>'/'after:<name>'/index) " //$NON-NLS-1$
            + "properties. REBIND a form event handler's procedure with a 'procedure' property on a " //$NON-NLS-1$
            + "Handler FQN, or re-point a Button at a different form command with a 'command' property. " //$NON-NLS-1$
            + "Set a StyleItem's value with a 'value' property: a Color " //$NON-NLS-1$
            + "{value:{color:{red:255,green:0,blue:0}}} (or {color:'auto'}) or a Font " //$NON-NLS-1$
            + "{value:{font:{faceName:'Arial',height:12,bold:true}}}. " //$NON-NLS-1$
            + "Give a form list FORM ATTRIBUTE a custom dynamic-list query with a 'queryText' " //$NON-NLS-1$
            + "property (and 'customQuery' true/false, plus an optional 'mainTable' object FQN): this " //$NON-NLS-1$
            + "turns the attribute into a DynamicList and lets EDT auto-fill the available fields from " //$NON-NLS-1$
            + "the query (no manual XML; output a column with create_metadata Field dataPath " //$NON-NLS-1$
            + "'List.<field>'). " //$NON-NLS-1$
            + "Discover assignable properties + allowed values with " //$NON-NLS-1$
            + "get_metadata_details(assignable:true). To rename, use rename_metadata_object. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('modify_metadata')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty("fqn", //$NON-NLS-1$
                "Full-name FQN of the node to modify (required), e.g. 'Catalog.Products' or " //$NON-NLS-1$
                + "'Catalog.Products.Attribute.Weight' (type / kind tokens may be English or Russian; " //$NON-NLS-1$
                + "the Name parts are the programmatic Name).", true) //$NON-NLS-1$
            .objectArrayProperty("properties", //$NON-NLS-1$
                "Properties to set, as [{name, value, language?}] (required, at least one). 'name' is " //$NON-NLS-1$
                + "the property name (e.g. 'comment', 'synonym', 'indexing'); 'value' is the new " //$NON-NLS-1$
                + "value; 'language' is the code for a synonym (default: config default).", true) //$NON-NLS-1$
            .booleanProperty("normalizeYo", //$NON-NLS-1$
                "Normalize the Russian letter 'ё'->'е' / 'Ё'->'Е' in localized-string values (synonym / " //$NON-NLS-1$
                + "title) and in the 'comment' property (default true). Matches the 1C standard " //$NON-NLS-1$
                + "mdo-ru-name-unallowed-letter. Other free-text strings can be identifier-like (e.g. " //$NON-NLS-1$
                + "XDTOPackage.namespace is a URI) and always keep the supplied value. Set false to " //$NON-NLS-1$
                + "keep 'ё' exactly as supplied everywhere.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the properties were set", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION, "'modified' on success") //$NON-NLS-1$
            .stringProperty("fqn", "Normalized FQN of the modified node") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty(KEY_APPLIED, "Names of the properties that were set") //$NON-NLS-1$
            .booleanProperty(KEY_PERSISTED, "Whether the change was exported to disk") //$NON-NLS-1$
            .stringArrayProperty("normalized", //$NON-NLS-1$
                "Properties whose value was rewritten by the 'ё'->'е' normalization (when any)") //$NON-NLS-1$
            .stringProperty("destination", //$NON-NLS-1$
                "Where a moved form item ended up (when 'parent'/'position' moved a form item), e.g. " //$NON-NLS-1$
                + "\"group 'Main' at index 1\"") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable confirmation message") //$NON-NLS-1$
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
        boolean normalizeYo = JsonUtils.extractBooleanArgument(params, "normalizeYo", true); //$NON-NLS-1$
        List<JsonObject> properties = JsonUtils.extractObjectArray(params, "properties"); //$NON-NLS-1$
        if (properties.isEmpty())
        {
            return ToolResult.error("properties is required: provide at least one {name, value} to " //$NON-NLS-1$
                + "set, e.g. [{name: 'comment', value: 'Goods'}].").toJson(); //$NON-NLS-1$
        }

        // 'ё'->'е' normalization is applied at the parse step to every localized-string / free-text
        // value being set (synonym / comment / title / ...), matching mdo-ru-name-unallowed-letter.
        // Rename is out of scope here, so there is no Name to normalize.
        MdNameNormalizer.Report normReport = new MdNameNormalizer.Report(normalizeYo);

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        Configuration config = ctx.config;

        String normFqn = MetadataTypeUtils.normalizeFqn(fqn);

        // A FQN that addresses a FORM member (item / attribute / command) is handled by a dedicated
        // branch: form members live on the editable Form content model (a cross-model hop), not the
        // mdclass tree. The validation + change pipeline (prepare / PreparedChange) is reused as-is.
        FormElementWriter.FormMemberRef formRef = FormElementWriter.parse(normFqn);
        if (formRef != null)
        {
            return modifyFormMember(ctx, normFqn, formRef, properties, normReport);
        }

        // Exact-first resolve with the yo-addressing fallback: create_metadata normalizes
        // 'yo'->'ye' in names by default, so a caller re-typing the original yo spelling
        // would miss the stored name — the resolver retries the normalized FQN.
        MetadataNodeResolver.ResolvedNode resolved =
            MetadataNodeResolver.resolveExistingWithYoFallback(config, normFqn);
        MetadataNodeResolver.MetadataNode node = resolved.node;
        if (node == null || node.object == null)
        {
            return ToolResult.error("Node not found: " + fqn + ". Use 'Type.Name' for a top object or " //$NON-NLS-1$ //$NON-NLS-2$
                + "'Type.Name.Kind.Name' for a member. Use get_metadata_objects to find an FQN." //$NON-NLS-1$
                + MetadataNodeResolver.yoNotFoundHint(normFqn)).toJson();
        }
        if (resolved.yoFallback)
        {
            Activator.logInfo("modify_metadata: '" + normFqn //$NON-NLS-1$
                + "' did not resolve exactly; proceeding with its yo-normalized form '" //$NON-NLS-1$
                + resolved.fqn + "'"); //$NON-NLS-1$
            normFqn = resolved.fqn;
        }
        MdObject target = node.object;

        // Resolve the BM re-fetch strategy (mutation must re-fetch inside the write tx). Only TOP
        // objects are re-fetchable by bmId, so for a member we re-fetch the TOP object and
        // re-navigate to the leaf's owner BY NAME inside the tx - this is what lets a member of a
        // NESTED object (e.g. a tabular-section attribute) be modified, not just a direct member.
        final String[] parts = normFqn.split("\\."); //$NON-NLS-1$
        BmFetchPlan plan = resolveBmFetchPlan(config, node, target, parts);
        if (plan.error != null)
        {
            return plan.error;
        }
        final long topBmId = plan.topBmId;
        final EStructuralFeature memberFeature = plan.memberFeature;
        final String memberName = plan.memberName;

        // The platform version is needed only to build a 'type' value; resolve it best-effort (a
        // missing version is reported only if a 'type' property is actually set).
        final Version version = resolvePlatformVersion(ctx);

        // Validate every property against the introspected schema BEFORE any write (fail fast, no
        // partial mutation). On success, collect the prepared changes to apply inside the tx.
        List<PreparedChange> changes = new ArrayList<>();
        String prepErr = validateAndPrepare(config, version, target, properties, changes, normReport);
        if (prepErr != null)
        {
            return prepErr;
        }

        // The top object that owns the node's .mdo file.
        final String topFqn = topFqn(normFqn);
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(ctx.project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available for project: " + projectName).toJson(); //$NON-NLS-1$
        }

        try
        {
            BmTransactions.<Void>write(bmModel, "ModifyMetadata", (tx, pm) -> //$NON-NLS-1$
            {
                EObject applyTo = resolveApplyTarget(tx, topBmId, memberFeature, memberName, parts);
                for (PreparedChange change : changes)
                {
                    change.applyTo(applyTo, tx);
                }
                return null;
            });
        }
        catch (Exception e)
        {
            Activator.logError("Error modifying metadata", e); //$NON-NLS-1$
            return ToolResult.error("Failed to modify: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        boolean persisted = BmTransactions.forceExportToDisk(ctx.project, topFqn);

        List<String> applied = appliedFeatureNames(changes);
        return buildModifiedResult(normFqn, applied, persisted, normReport);
    }

    /**
     * Resolves the platform {@link Version} for the project best-effort (used only to build a 'type'
     * value): {@code null} when the V8 project manager or project is unavailable. Side-effect-free
     * helper extracted from {@link #executeOnUiThread}.
     */
    private static Version resolvePlatformVersion(ProjectContext ctx)
    {
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IV8Project v8Project = v8ProjectManager != null ? v8ProjectManager.getProject(ctx.project) : null;
        return v8Project != null ? v8Project.getVersion() : null;
    }

    /**
     * Re-navigates to the EObject that the prepared changes must be applied to, INSIDE the write
     * transaction: re-fetches the TOP object by {@code topBmId} and, for a member, re-navigates to
     * the leaf's owner and then to the leaf BY NAME. Read-only resolution (no eSet) - the actual
     * mutation stays in the caller's apply loop. Throws the SAME {@link RuntimeException}s as before
     * (target / owner / member not found), which propagate to the same catch and roll the tx back.
     * Extracted verbatim from {@link #executeOnUiThread}'s transaction body.
     */
    private static EObject resolveApplyTarget(IBmTransaction tx, long topBmId,
        EStructuralFeature memberFeature, String memberName, String[] parts)
    {
        EObject top = (EObject)tx.getObjectById(topBmId);
        if (top == null)
        {
            throw new RuntimeException("Target not found in transaction"); //$NON-NLS-1$ // NOSONAR propagates checked exceptions across the reflective boundary by design
        }
        if (memberFeature == null)
        {
            return top;
        }
        EObject owner = MetadataNodeResolver.resolveOwnerInTx(top, parts);
        if (owner == null)
        {
            throw new RuntimeException("Could not re-navigate to the owner inside the transaction"); //$NON-NLS-1$ // NOSONAR propagates checked exceptions across the reflective boundary by design
        }
        EObject applyTo = childByName(owner, memberFeature, memberName);
        if (applyTo == null)
        {
            throw new RuntimeException("Member not found in transaction: " + memberName); //$NON-NLS-1$ // NOSONAR propagates checked exceptions across the reflective boundary by design
        }
        return applyTo;
    }

    /**
     * Collects the feature names of the applied changes, in order. Pure helper extracted from
     * {@link #executeOnUiThread}.
     */
    private static List<String> appliedFeatureNames(List<PreparedChange> changes)
    {
        List<String> applied = new ArrayList<>();
        for (PreparedChange change : changes)
        {
            applied.add(change.featureName());
        }
        return applied;
    }

    /**
     * Builds the success JSON for a completed modify (action / fqn / applied / persisted, the
     * normalization report and the confirmation message). Pure helper extracted from
     * {@link #executeOnUiThread}; the same shape used by the form-member branch.
     */
    private static String buildModifiedResult(String normFqn, List<String> applied, boolean persisted,
        MdNameNormalizer.Report normReport)
    {
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted);
        normReport.addTo(result);
        return result
            .put(McpKeys.MESSAGE, "Modified " + normFqn + " (" + String.join(", ", applied) + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .toJson();
    }

    /**
     * Holds the BM re-fetch strategy resolved for the modify transaction: the {@code topBmId} of the
     * re-fetchable top object plus, for a member node, the owning {@code memberFeature} and the leaf's
     * {@code memberName} to re-navigate by name inside the tx. {@link #error} is non-null (a ready JSON
     * error) when the target / top object is not a BM object.
     */
    private static final class BmFetchPlan
    {
        private long topBmId;
        private EStructuralFeature memberFeature;
        private String memberName;
        private String error;
    }

    /**
     * Resolves the BM re-fetch strategy for the modify transaction (see {@link BmFetchPlan}). Only TOP
     * objects are re-fetchable by bmId; for a member the TOP object's bmId is captured and the leaf is
     * re-navigated by name inside the tx. Side-effect-free: it only reads ids / features. Extracted
     * verbatim from {@link #executeOnUiThread}; the caller re-checks {@link BmFetchPlan#error} and
     * returns it unchanged, preserving the original error cases.
     */
    private static BmFetchPlan resolveBmFetchPlan(Configuration config,
        MetadataNodeResolver.MetadataNode node, MdObject target, String[] parts)
    {
        BmFetchPlan plan = new BmFetchPlan();
        if (node.topLevel)
        {
            if (!(target instanceof IBmObject))
            {
                plan.error = ToolResult.error("Target is not a BM object").toJson(); //$NON-NLS-1$
                return plan;
            }
            plan.topBmId = ((IBmObject)target).bmGetId();
            plan.memberFeature = null;
            plan.memberName = null;
        }
        else
        {
            MdObject topObject = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
            if (!(topObject instanceof IBmObject))
            {
                plan.error = ToolResult.error("Top object is not a BM object").toJson(); //$NON-NLS-1$
                return plan;
            }
            plan.topBmId = ((IBmObject)topObject).bmGetId();
            plan.memberFeature = node.feature;
            plan.memberName = target.getName();
        }
        return plan;
    }

    /**
     * Validates every property against the introspected schema BEFORE any write (fail fast, no partial
     * mutation), appending a {@link PreparedChange} for each. Returns the first property's JSON error,
     * or {@code null} when all validated. Side-effect-free apart from populating {@code changes};
     * extracted verbatim from {@link #executeOnUiThread} so a failure returns the SAME error in the
     * SAME case, before the BM transaction runs.
     */
    private String validateAndPrepare(Configuration config, Version version, MdObject target,
        List<JsonObject> properties, List<PreparedChange> changes, MdNameNormalizer.Report normReport)
    {
        for (JsonObject prop : properties)
        {
            String pErr = prepare(config, version, target, prop, changes, normReport);
            if (pErr != null)
            {
                return pErr;
            }
        }
        return null;
    }

    /**
     * Modifies a FORM member (item / attribute / command) addressed by a form FQN. The member lives on
     * the editable Form content model (reached via the cross-model hop), so this branch resolves the
     * member there, reuses the shared {@link #prepare} validation + {@link PreparedChange} pipeline
     * (the introspector is EClass-driven, so an item's title / visible / readOnly and an attribute's
     * valueType / enums classify the same way mdclass properties do), then applies the changes inside a
     * BM write transaction and force-exports the CONTENT form to its {@code Form.form} on disk.
     *
     * <p>Validation and mutation run inside ONE BM write transaction: every property is validated
     * first and a failure throws {@link FormValidationException} (carrying a ready JSON error) BEFORE
     * any {@code eSet}, so the transaction rolls back with no partial mutation; building the change
     * values and setting them in the same transaction avoids any cross-transaction detached-object
     * concern. The member is re-navigated by name inside the transaction.</p>
     */
    private String modifyFormMember(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties,
        MdNameNormalizer.Report normReport)
    {
        // A handler FQN ('...Handler.Event' at form / item level) is not a property-bag member: the only
        // supported change is REBINDING its BSL procedure ('procedure' / 'handler' property). Binding a
        // NEW event stays in create_metadata, removing it in delete_metadata; any other property on a
        // handler FQN is refused with that pointer.
        if (FormElementWriter.isHandlerToken(ref.kindToken) || ref.isItemLevel())
        {
            String procName = handlerProcedureValue(properties);
            String rebindErr = validateHandlerRebind(properties, procName);
            if (rebindErr != null)
            {
                return rebindErr;
            }
            return rebindFormHandler(ctx, normFqn, ref, procName);
        }

        // A button's command targets a FormCommand (a form-model object, not an mdclass object), so it
        // is not introspector-assignable; a 'command' property on a Button FQN RE-POINTS it at an
        // existing form command.
        if (FormElementWriter.kindForToken(ref.kindToken) == FormElementWriter.Kind.BUTTON
            && hasCommandProperty(properties))
        {
            return rebindButtonCommand(ctx, normFqn, ref, properties);
        }

        // A MOVE / REORDER is expressed through the 'parent' and/or 'position' properties on a form
        // ITEM (a field / group / decoration / button / table - anything in the items tree). It is a
        // structural re-parent/reorder, not an eSet property change, so it is routed to its own branch
        // (and must not be mixed with ordinary property changes in the same call).
        if (hasMoveProperty(properties))
        {
            return moveFormItem(ctx, normFqn, ref, properties);
        }

        // A DynamicList custom query is set through 'queryText' / 'customQuery' on a form ATTRIBUTE.
        // These live on the attribute's DynamicListExtInfo - a sub-object the generic introspector does
        // not reach - so they route to their own branch that creates / configures the ext-info
        // reflectively, turning a plain form attribute into a custom-query dynamic list.
        if (isDynamicListQueryRequest(ref, properties))
        {
            return configureDynamicListQuery(ctx, normFqn, ref, properties, normReport);
        }

        // Ordinary property modify: the remaining (non-handler, non-command, non-move) case.
        return modifyFormMemberProperties(ctx, normFqn, ref, properties, normReport);
    }

    /**
     * Modifies the ordinary (non-handler, non-command, non-move) properties of a form member, the
     * remaining case of {@link #modifyFormMember} once those three structural branches are ruled out.
     * Resolves the platform version, then validates + applies every property inside ONE BM write
     * transaction (the member is re-navigated by name inside the tx; a validation failure throws
     * {@link FormValidationException} carrying its JSON error BEFORE any {@code eSet}, so the tx rolls
     * back with no partial mutation) and force-exports the CONTENT form to disk. Extracted verbatim
     * from {@link #modifyFormMember}: the BM write transaction (apply loop + forceExport via
     * {@code writeEditableForm}) stays INLINE here, and the early-returns return the SAME JSON in the
     * SAME case.
     */
    private String modifyFormMemberProperties(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties,
        MdNameNormalizer.Report normReport)
    {
        Configuration config = ctx.config;
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IV8Project v8Project = v8ProjectManager != null ? v8ProjectManager.getProject(ctx.project) : null;
        final Version version = v8Project != null ? v8Project.getVersion() : null;

        final List<String> applied = new ArrayList<>();

        // Validate + apply inside ONE BM write transaction: resolve the member, validate every
        // property (a failure throws FormValidationException carrying the JSON error BEFORE any eSet,
        // so the tx rolls back with no partial mutation), then apply. The member is re-navigated by
        // name inside the tx (only the form top object is re-fetchable by bmId). Building the change
        // values and setting them in the SAME tx avoids any cross-transaction detached-object concern.
        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                config, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address a form member as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.<Kind>.Name' or 'CommonForm.FormName.<Kind>.Name' " //$NON-NLS-1$
                    + "(Kind = Attribute / Command / Field / Button / Group / Decoration / Table)."); //$NON-NLS-1$
            persisted = FormElementWriter.writeEditableForm(fctx, "ModifyFormMember", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    EObject member = FormElementWriter.resolveFormMember(formModel, ref);
                    if (member == null)
                    {
                        throw new FormValidationException(ToolResult.error("Form member not found: " //$NON-NLS-1$
                            + ref.name + " (kind '" + ref.kindToken + "') on " + ref.formPath //$NON-NLS-1$ //$NON-NLS-2$
                            + ". Use get_metadata_details to list the members.").toJson()); //$NON-NLS-1$
                    }
                    List<PreparedChange> changes =
                        prepareFormMemberChanges(config, version, member, properties, normReport);
                    for (PreparedChange change : changes)
                    {
                        change.applyTo(member, tx);
                        applied.add(change.featureName());
                    }
                });
        }
        catch (Exception e)
        {
            // A property-validation failure carries a ready JSON error (possibly wrapped by the tx
            // runner) - surface it directly; anything else is a genuine failure.
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Activator.logError("Error modifying form member", e); //$NON-NLS-1$
            return ToolResult.error("Failed to modify form member: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted);
        normReport.addTo(result);
        return result
            .put(McpKeys.MESSAGE, "Modified " + normFqn + " (" + String.join(", ", applied) + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .toJson();
    }

    // --- DynamicList custom query (set on a form ATTRIBUTE) -----------------------------------------

    /** The dynamic-list query property names. {@code queryText} sets the custom query, {@code customQuery}
     * toggles whether the dynamic list uses it (vs the automatic main-table query). */
    private static final String PROP_QUERY_TEXT = "queryText"; //$NON-NLS-1$
    private static final String PROP_CUSTOM_QUERY = "customQuery"; //$NON-NLS-1$
    // ru TekstZaprosa (= queryText) / ProizvolnyjZapros (= customQuery) - pure-ASCII source (cp codepoints).
    private static final String RU_PROP_QUERY_TEXT = MetadataLanguageUtils.cp(0x0422, 0x0435, 0x043a,
        0x0441, 0x0442, 0x0417, 0x0430, 0x043f, 0x0440, 0x043e, 0x0441, 0x0430);
    private static final String RU_PROP_CUSTOM_QUERY = MetadataLanguageUtils.cp(0x041f, 0x0440, 0x043e,
        0x0438, 0x0437, 0x0432, 0x043e, 0x043b, 0x044c, 0x043d, 0x044b, 0x0439, 0x0417, 0x0430, 0x043f,
        0x0440, 0x043e, 0x0441);
    /** The dynamic-list main-table property: an object FQN whose main table the list reads from. */
    private static final String PROP_MAIN_TABLE = "mainTable"; //$NON-NLS-1$
    // ru OsnovnayaTablica (= mainTable) - pure-ASCII source (cp codepoints).
    private static final String RU_PROP_MAIN_TABLE = MetadataLanguageUtils.cp(0x041e, 0x0441, 0x043d,
        0x043e, 0x0432, 0x043d, 0x0430, 0x044f, 0x0422, 0x0430, 0x0431, 0x043b, 0x0438, 0x0446, 0x0430);

    /** Whether a property NAME is the {@code queryText} dynamic-list property (English or Russian). */
    static boolean isQueryTextProp(String name)
    {
        return PROP_QUERY_TEXT.equalsIgnoreCase(name) || RU_PROP_QUERY_TEXT.equalsIgnoreCase(name);
    }

    /** Whether a property NAME is the {@code customQuery} dynamic-list property (English or Russian). */
    static boolean isCustomQueryProp(String name)
    {
        return PROP_CUSTOM_QUERY.equalsIgnoreCase(name) || RU_PROP_CUSTOM_QUERY.equalsIgnoreCase(name);
    }

    /** Whether a property NAME is the {@code mainTable} dynamic-list property (English or Russian). */
    static boolean isMainTableProp(String name)
    {
        return PROP_MAIN_TABLE.equalsIgnoreCase(name) || RU_PROP_MAIN_TABLE.equalsIgnoreCase(name);
    }

    /**
     * Whether this modify is a DynamicList custom-query request: a form ATTRIBUTE FQN carrying a
     * {@code queryText} and/or {@code customQuery} property. Those properties live on the attribute's
     * {@code DynamicListExtInfo} (not on the attribute itself), so they are routed to their own branch
     * instead of the generic introspector path. Reads only the property list (no model mutation).
     */
    private static boolean isDynamicListQueryRequest(FormElementWriter.FormMemberRef ref,
        List<JsonObject> properties)
    {
        if (FormElementWriter.kindForToken(ref.kindToken) != FormElementWriter.Kind.ATTRIBUTE)
        {
            return false;
        }
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (isQueryTextProp(name) || isCustomQueryProp(name) || isMainTableProp(name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Configures a form attribute as a custom-query dynamic list ({@code queryText} / {@code customQuery}).
     * Mirrors {@link #modifyFormMemberProperties}: resolves the platform version, then applies the change
     * inside ONE BM write transaction ({@link FormElementWriter#configureDynamicListQuery} creates /
     * configures the {@code DynamicListExtInfo} reflectively) and force-exports the content form. The
     * query properties are structural (they create the ext-info and the {@code DynamicList} value type),
     * so they must not be mixed with ordinary property changes in one call - the same policy the move /
     * handler / command branches enforce.
     */
    private String configureDynamicListQuery(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties,
        MdNameNormalizer.Report normReport)
    {
        DynListQueryRequest req = parseDynListQueryProps(properties);
        if (req.error != null)
        {
            return req.error;
        }
        final String queryText = req.queryText;
        final Boolean customQuery = req.customQuery;
        final String mainTable = req.mainTable;

        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IV8Project v8Project = v8ProjectManager != null ? v8ProjectManager.getProject(ctx.project) : null;
        final Version version = v8Project != null ? v8Project.getVersion() : null;

        final List<String> applied = new ArrayList<>();
        final String qt = queryText;
        final Boolean cq = customQuery;
        final String mt = mainTable;
        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.config, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address the dynamic-list attribute as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.Attribute.Name'."); //$NON-NLS-1$
            persisted = FormElementWriter.writeEditableForm(fctx, "ConfigureDynamicListQuery", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    EObject member = FormElementWriter.resolveFormMember(formModel, ref);
                    if (member == null)
                    {
                        throw new FormValidationException(ToolResult.error("Form attribute not found: " //$NON-NLS-1$
                            + ref.name + " on " + ref.formPath //$NON-NLS-1$
                            + ". Create it with create_metadata, then set its query.").toJson()); //$NON-NLS-1$
                    }
                    applied.addAll(FormElementWriter.configureDynamicListQuery(
                        formModel, member, qt, cq, mt, ctx.config, version));
                });
        }
        catch (Exception e)
        {
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Activator.logError("Error configuring dynamic-list query", e); //$NON-NLS-1$
            return ToolResult.error("Failed to set the dynamic-list query: " //$NON-NLS-1$
                + unwrapCauseMessage(e)).toJson();
        }

        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted);
        normReport.addTo(result);
        return result
            .put(McpKeys.MESSAGE, "Configured dynamic-list query on " + normFqn //$NON-NLS-1$
                + " (" + String.join(", ", applied) + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .toJson();
    }

    /**
     * The parsed dynamic-list query properties, or a ready JSON {@code error} when a value was malformed
     * or a query prop was mixed with another property change. Lets {@link #configureDynamicListQuery} stay
     * a thin orchestrator while the property loop and its early-return validations live in
     * {@link #parseDynListQueryProps}.
     */
    private static final class DynListQueryRequest
    {
        final String queryText;
        final Boolean customQuery;
        final String mainTable;
        final String error;

        private DynListQueryRequest(String queryText, Boolean customQuery, String mainTable, String error)
        {
            this.queryText = queryText;
            this.customQuery = customQuery;
            this.mainTable = mainTable;
            this.error = error;
        }

        static DynListQueryRequest of(String queryText, Boolean customQuery, String mainTable)
        {
            return new DynListQueryRequest(queryText, customQuery, mainTable, null);
        }

        static DynListQueryRequest failed(String error)
        {
            return new DynListQueryRequest(null, null, null, error);
        }
    }

    /**
     * Reads the dynamic-list query properties ({@code queryText} / {@code customQuery} / {@code mainTable})
     * from the property list into a {@link DynListQueryRequest}. Returns one carrying a ready JSON error
     * when a value is malformed or a query prop is mixed with another property change. Pure (reads only the
     * supplied list).
     */
    private DynListQueryRequest parseDynListQueryProps(List<JsonObject> properties)
    {
        DynListQueryProps acc = new DynListQueryProps();
        for (JsonObject prop : properties)
        {
            String error = readDynListQueryProp(prop, acc);
            if (error != null)
            {
                return DynListQueryRequest.failed(error);
            }
        }
        if (acc.queryTextGiven && (acc.queryText == null || acc.queryText.trim().isEmpty()))
        {
            return DynListQueryRequest.failed(ToolResult.error("'queryText' must be a non-empty 1C query, e.g. " //$NON-NLS-1$
                + "\"SELECT Ref, Description AS Description FROM Catalog.Products\". To switch the " //$NON-NLS-1$
                + "dynamic list back to its automatic query, pass 'customQuery' = false instead.").toJson()); //$NON-NLS-1$
        }
        return DynListQueryRequest.of(acc.queryText, acc.customQuery, acc.mainTable);
    }

    /** Mutable accumulator for the dynamic-list query properties read by {@link #readDynListQueryProp}. */
    private static final class DynListQueryProps
    {
        String queryText;
        Boolean customQuery;
        String mainTable;
        boolean queryTextGiven;
    }

    /**
     * Reads a single property into {@code acc}, returning a ready JSON error string when the value is
     * malformed or a query prop is mixed with another property change, or {@code null} to continue.
     * Pure apart from updating {@code acc}.
     */
    private String readDynListQueryProp(JsonObject prop, DynListQueryProps acc)
    {
        String name = asString(prop.get("name")); //$NON-NLS-1$
        if (isQueryTextProp(name))
        {
            acc.queryText = asString(prop.get(KEY_VALUE));
            acc.queryTextGiven = true;
            return null;
        }
        if (isCustomQueryProp(name))
        {
            Boolean parsed = parseBooleanFlag(prop.get(KEY_VALUE));
            if (parsed == null)
            {
                return ToolResult.error("'customQuery' must be a boolean (true / false).").toJson(); //$NON-NLS-1$
            }
            acc.customQuery = parsed;
            return null;
        }
        if (isMainTableProp(name))
        {
            acc.mainTable = asString(prop.get(KEY_VALUE));
            if (acc.mainTable == null || acc.mainTable.trim().isEmpty())
            {
                return ToolResult.error("'mainTable' must be an object FQN, e.g. " //$NON-NLS-1$
                    + "'Catalog.Products' or 'Document.Order'.").toJson(); //$NON-NLS-1$
            }
            return null;
        }
        return ToolResult.error("Setting a dynamic-list query ('queryText' / 'customQuery' / " //$NON-NLS-1$
            + "'mainTable') cannot be combined with other property changes ('" + name //$NON-NLS-1$
            + "') in one call. Configure the query first, then make the other changes " //$NON-NLS-1$
            + "separately.").toJson(); //$NON-NLS-1$
    }

    /**
     * Parses a flag property value (a JSON boolean, or the string {@code "true"} / {@code "false"}),
     * or {@code null} when the value is not a recognizable boolean.
     */
    static Boolean parseBooleanFlag(JsonElement value)
    {
        if (value == null || !value.isJsonPrimitive())
        {
            return null; // NOSONAR tri-state: null means "not a recognizable boolean"; callers check it explicitly
        }
        JsonPrimitive prim = value.getAsJsonPrimitive();
        if (prim.isBoolean())
        {
            return Boolean.valueOf(prim.getAsBoolean());
        }
        String s = prim.getAsString().trim();
        if ("true".equalsIgnoreCase(s)) //$NON-NLS-1$
        {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s)) //$NON-NLS-1$
        {
            return Boolean.FALSE;
        }
        return null; // NOSONAR tri-state: null means "not a recognizable boolean"; callers check it explicitly
    }

    /**
     * Validates a handler-rebind request on a handler / item-level FQN. A handler FQN supports only
     * REBINDING its BSL procedure, and that rebind is structural so it must not be mixed with other
     * property changes. Returns a JSON error to refuse the call - when no {@code procedure} value was
     * supplied ({@code procName == null}), or when the rebind is mixed with another property change -
     * or {@code null} when the rebind is valid and the caller may proceed to {@link #rebindFormHandler}.
     * Pure (no model mutation): reads only the supplied property list.
     */
    private static String validateHandlerRebind(List<JsonObject> properties, String procName)
    {
        if (procName != null)
        {
            // A handler rebind is structural and must not be mixed with other property changes in
            // one call - the same policy the move ('parent'/'position') and button-command
            // ('command') branches enforce. Reject BEFORE any mutation.
            String mixed = firstNonHandlerRebindProperty(properties);
            if (mixed != null)
            {
                return ToolResult.error("Rebinding a handler's procedure ('procedure') cannot be " //$NON-NLS-1$
                    + "combined with other property changes ('" + mixed + "') in one call. Rebind " //$NON-NLS-1$ //$NON-NLS-2$
                    + "the procedure first, then make the other changes in a separate call.").toJson(); //$NON-NLS-1$
            }
            return null;
        }
        return ToolResult.error("On a form event-handler FQN, modify_metadata can only REBIND the " //$NON-NLS-1$
            + "bound procedure - pass a 'procedure' property (e.g. {name:'procedure', " //$NON-NLS-1$
            + "value:'NewProc'}). To bind a new event use create_metadata, to remove it " //$NON-NLS-1$
            + "delete_metadata.").toJson(); //$NON-NLS-1$
    }

    /**
     * Validates every property of a form-member modify against the introspected schema and builds the
     * ordered list of {@link PreparedChange}s to apply. Runs inside the BM write transaction (called
     * from the {@code writeEditableForm} callback) but performs NO model mutation itself - it only
     * reads {@code member}'s schema and constructs the changes; a structural-property guard or an
     * invalid value throws {@link FormValidationException} BEFORE any {@code eSet}, exactly as the
     * inline loop did, so the transaction rolls back with no partial mutation. The returned changes are
     * applied by the caller.
     */
    private List<PreparedChange> prepareFormMemberChanges(Configuration config, Version version,
        EObject member, List<JsonObject> properties, MdNameNormalizer.Report normReport)
    {
        List<PreparedChange> changes = new ArrayList<>();
        for (JsonObject prop : properties)
        {
            String guard = guardFormProperty(prop);
            if (guard != null)
            {
                throw new FormValidationException(guard);
            }
            String pErr = prepare(config, version, member,
                normalizeFormProperty(member, prop), changes, normReport);
            if (pErr != null)
            {
                throw new FormValidationException(pErr);
            }
        }
        return changes;
    }

    /**
     * The move property names (the structural re-parent / reorder of a form item): {@code parent}
     * (the destination container - a group, the {@code AutoCommandBar} token, a table - or the form
     * name / blank for the form root) and {@code position}
     * (the destination order: {@code first} / {@code last} / {@code before:<name>} / {@code after:<name>}
     * / a 0-based integer index). They are bilingual: ru {@code roditel} / ru {@code poziciya}.
     */
    private static final String PROP_PARENT = "parent"; //$NON-NLS-1$
    private static final String PROP_POSITION = "position"; //$NON-NLS-1$
    // ru "родитель" (roditel) / "позиция" (poziciya) - pure-ASCII source (matching the rest of the project).
    private static final String RU_PROP_PARENT =
        MetadataLanguageUtils.cp(0x0440, 0x043e, 0x0434, 0x0438, 0x0442, 0x0435, 0x043b, 0x044c);
    private static final String RU_PROP_POSITION =
        MetadataLanguageUtils.cp(0x043f, 0x043e, 0x0437, 0x0438, 0x0446, 0x0438, 0x044f);

    /** Whether a property NAME is the {@code parent} move property (English or Russian). */
    private static boolean isParentProp(String name)
    {
        return PROP_PARENT.equalsIgnoreCase(name) || RU_PROP_PARENT.equalsIgnoreCase(name);
    }

    /** Whether a property NAME is the {@code position} move property (English or Russian). */
    private static boolean isPositionProp(String name)
    {
        return PROP_POSITION.equalsIgnoreCase(name) || RU_PROP_POSITION.equalsIgnoreCase(name);
    }

    /** Whether any property in the list is a move property ({@code parent} / {@code position}). */
    private static boolean hasMoveProperty(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (isParentProp(name) || isPositionProp(name))
            {
                return true;
            }
        }
        return false;
    }

    /** The rebind property names. {@code procedure} (alias {@code handler}) rebinds a handler's BSL
     * procedure; {@code command} (alias {@code commandName}) re-points a button at a form command. */
    private static final String PROP_PROCEDURE = "procedure"; //$NON-NLS-1$
    private static final String PROP_HANDLER = "handler"; //$NON-NLS-1$
    private static final String PROP_COMMAND = "command"; //$NON-NLS-1$
    private static final String PROP_COMMAND_NAME = "commandName"; //$NON-NLS-1$

    /**
     * The new BSL procedure name from a {@code procedure} (or {@code handler} alias) property on a
     * handler-rebind call, or {@code null} when no such property is present. The same key
     * {@code create_metadata} accepts when binding a handler.
     */
    private static String handlerProcedureValue(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (PROP_PROCEDURE.equalsIgnoreCase(name) || PROP_HANDLER.equalsIgnoreCase(name))
            {
                return asString(prop.get(KEY_VALUE));
            }
        }
        return null;
    }

    /**
     * The name of the first property that is NOT the handler-rebind property ({@code procedure} /
     * {@code handler} alias), or {@code null} when the list carries only rebind properties. Used to
     * REJECT a handler-rebind call that mixes in other property changes (which the rebind path would
     * otherwise silently drop). Package-visible for tests.
     */
    static String firstNonHandlerRebindProperty(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (!PROP_PROCEDURE.equalsIgnoreCase(name) && !PROP_HANDLER.equalsIgnoreCase(name))
            {
                return name;
            }
        }
        return null;
    }

    /** Whether any property in the list re-points a button at a form command ({@code command}). */
    private static boolean hasCommandProperty(List<JsonObject> properties)
    {
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (PROP_COMMAND.equalsIgnoreCase(name) || PROP_COMMAND_NAME.equalsIgnoreCase(name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves / reorders a form ITEM addressed by {@code ref} (a field / group / decoration / button /
     * table), expressed as the {@code parent} and/or {@code position} move properties. Resolves the
     * MD-form, opens ONE BM write transaction on the re-fetched content form, re-parents / reorders the
     * item via {@link FormElementWriter#moveItem} (which rejects an ambiguous / missing item, an
     * unknown parent - the error advertises the {@code AutoCommandBar} token - a placement the
     * designer forbids and a containment cycle, rolling the tx back), then
     * force-exports the CONTENT form to its {@code Form.form} on disk - the same persistence path the
     * property-modify branch uses. Position semantics match the dedicated move primitive exactly (the
     * integer index is the desired FINAL 0-based position).
     */
    private String moveFormItem(ProjectContext ctx, String normFqn, // NOSONAR form-move orchestration: structural validation + move dispatch; the mutating write stays inline, further extraction deferred
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties)
    {
        // A move addresses a form ITEM only - never an attribute / command (which are not in the items
        // tree and have no position / parent).
        FormElementWriter.Kind kind = FormElementWriter.kindForToken(ref.kindToken);
        if (kind == FormElementWriter.Kind.ATTRIBUTE || kind == FormElementWriter.Kind.COMMAND)
        {
            return ToolResult.error("'parent' / 'position' move a form ITEM (field / group / " //$NON-NLS-1$
                + "decoration / button / table); a form " + ref.kindToken + " is not positioned. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Address the item by its FQN, e.g. 'Type.Object.Form.FormName.Field.Price'.").toJson(); //$NON-NLS-1$
        }

        // A move is structural - it must not be mixed with ordinary property changes in one call.
        String targetParent = null;
        boolean hasParent = false;
        String position = null;
        boolean hasPosition = false;
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (isParentProp(name))
            {
                targetParent = asString(prop.get(KEY_VALUE));
                hasParent = true;
            }
            else if (isPositionProp(name))
            {
                position = asString(prop.get(KEY_VALUE));
                hasPosition = true;
            }
            else
            {
                return ToolResult.error("A move ('parent' / 'position') cannot be combined with other " //$NON-NLS-1$
                    + "property changes ('" + name + "') in one call. Move the item first, then modify " //$NON-NLS-1$ //$NON-NLS-2$
                    + "its properties in a separate call.").toJson(); //$NON-NLS-1$
            }
        }
        if (!hasParent && !hasPosition)
        {
            return ToolResult.error("Nothing to move: provide 'parent' (to re-parent) and/or " //$NON-NLS-1$
                + "'position' (to reorder).").toJson(); //$NON-NLS-1$
        }
        // A re-parent with no explicit position appends to the destination (position stays null); a pure
        // reorder keeps the current parent (targetParent stays null).
        final String targetParentFinal;
        if (!hasParent)
        {
            targetParentFinal = null;
        }
        else
        {
            targetParentFinal = targetParent == null ? "" : targetParent; //$NON-NLS-1$
        }
        final String positionFinal = position;

        final String itemName = ref.name;
        final String[] destination = new String[1];
        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.config, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address a form item as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.<Kind>.Name' or 'CommonForm.FormName.<Kind>.Name'."); //$NON-NLS-1$
            final String mdFormName = fctx.mdForm.getName();
            persisted = FormElementWriter.writeEditableForm(fctx, "MoveFormItem", //$NON-NLS-1$
                (formModel, tx) -> destination[0] = FormElementWriter.moveItem(formModel, itemName,
                    targetParentFinal, positionFinal, mdFormName));
        }
        catch (Exception e)
        {
            return moveFormItemError(e);
        }

        List<String> applied = new ArrayList<>();
        if (hasParent)
        {
            applied.add(PROP_PARENT);
        }
        if (hasPosition)
        {
            applied.add(PROP_POSITION);
        }
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_APPLIED, applied)
            .put(KEY_PERSISTED, persisted)
            .put("destination", destination[0]) //$NON-NLS-1$
            .put(McpKeys.MESSAGE, "Moved form item '" + itemName + "' to " + destination[0]) //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /**
     * Maps a failure from the {@link #moveFormItem} write transaction to its error JSON: a structured
     * {@link FormValidationException} payload when present (the move primitive rejected the item / parent /
     * placement and rolled the tx back), otherwise a generic "Failed to move form item" error built from
     * the unwrapped cause message. Mirrors the catch arm of {@code moveFormItem} verbatim.
     */
    private String moveFormItemError(Exception e)
    {
        String validationJson = FormValidationException.jsonOf(e);
        if (validationJson != null)
        {
            return validationJson;
        }
        Activator.logError("Error moving form item", e); //$NON-NLS-1$
        return ToolResult.error("Failed to move form item: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
    }

    /**
     * REBINDS an existing event handler (addressed by a handler FQN, {@code ...Handler.Event} at form
     * or item level) to a different BSL procedure {@code procName}. Resolves the MD-form, opens ONE BM
     * write transaction on the re-fetched content form, resolves the handler's CONTAINER via
     * {@link FormElementWriter#resolveHandlerContainer} (the form root, the named item, or the form
     * COMMAND for a {@code ...Command.C.Handler.Action} FQN - so a command's Action procedure is
     * rebindable too), re-points the existing handler via {@link
     * FormElementWriter#rebindHandler} (which fails clearly when no handler for the event exists, so the
     * tx rolls back), then force-exports the CONTENT form to its {@code Form.form} on disk - the same
     * persistence path the property-modify branch uses. Does NOT bind a NEW event (that is
     * create_metadata's job); a single {@code procedure} property is the whole change.
     */
    private String rebindFormHandler(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, String procName)
    {
        final String eventName = ref.name;
        final boolean commandOwner = ref.isItemLevel()
            && FormElementWriter.kindForToken(ref.itemKindToken) == FormElementWriter.Kind.COMMAND;
        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.config, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address a handler as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.Handler.Event' or " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.<ItemKind>.<ItemName>.Handler.Event'."); //$NON-NLS-1$
            persisted = FormElementWriter.writeEditableForm(fctx, "RebindFormHandler", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    // Form-level handlers live on the form root; item-level handlers on the named
                    // item; a COMMAND ref (...Command.C.Handler.Action) on the form command - the
                    // same resolution create_metadata / delete_metadata use.
                    EObject container = FormElementWriter.resolveHandlerContainer(formModel, ref);
                    if (container == null)
                    {
                        throw new FormValidationException(ToolResult.error((commandOwner
                            ? "Form command not found: " : "Form item not found: ") + ref.itemName //$NON-NLS-1$ //$NON-NLS-2$
                            + ". Use get_metadata_details to inspect the form items.").toJson()); //$NON-NLS-1$
                    }
                    String err = FormElementWriter.rebindHandler(container, eventName, procName);
                    if (err != null)
                    {
                        throw new FormValidationException(ToolResult.error(err).toJson());
                    }
                });
        }
        catch (Exception e)
        {
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Activator.logError("Error rebinding form handler", e); //$NON-NLS-1$
            return ToolResult.error("Failed to rebind form handler: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_APPLIED, java.util.Collections.singletonList(PROP_PROCEDURE))
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Rebound the handler for event '" + eventName + "' to procedure '" //$NON-NLS-1$ //$NON-NLS-2$
                + procName + "'") //$NON-NLS-1$
            .toJson();
    }

    /**
     * RE-POINTS an existing button (a Button form item) at a different (existing) form command. A
     * button's {@code commandName} references a FormCommand (a form-model object, not an mdclass
     * object), so it is not introspector-assignable and is rebound here. Resolves the MD-form, opens ONE
     * BM write transaction on the re-fetched content form, resolves the button and re-points it via
     * {@link FormElementWriter#rebindButtonCommand} (which validates the command exists, rolling the tx
     * back otherwise), then force-exports the CONTENT form to its {@code Form.form} on disk. A
     * {@code command} change is structural-by-reference and must not be mixed with other property
     * changes in one call.
     */
    private String rebindButtonCommand(ProjectContext ctx, String normFqn,
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties)
    {
        String commandName = null;
        for (JsonObject prop : properties)
        {
            String name = asString(prop.get("name")); //$NON-NLS-1$
            if (PROP_COMMAND.equalsIgnoreCase(name) || PROP_COMMAND_NAME.equalsIgnoreCase(name))
            {
                commandName = asString(prop.get(KEY_VALUE));
            }
            else
            {
                return ToolResult.error("Re-pointing a button's command ('command') cannot be combined " //$NON-NLS-1$
                    + "with other property changes ('" + name + "') in one call. Rebind the command " //$NON-NLS-1$ //$NON-NLS-2$
                    + "first, then modify the button's properties in a separate call.").toJson(); //$NON-NLS-1$
            }
        }
        if (commandName == null || commandName.isEmpty())
        {
            return ToolResult.error("Provide the form command to point the button at in the 'command' " //$NON-NLS-1$
                + "property (e.g. {name:'command', value:'Refresh'}).").toJson(); //$NON-NLS-1$
        }

        final String buttonName = ref.name;
        final String commandNameFinal = commandName;
        final boolean persisted;
        try
        {
            FormElementWriter.FormEditContext fctx = FormElementWriter.resolveForEdit(ctx.project,
                ctx.config, ref.formPath,
                ERR_FORM_NOT_FOUND_PREFIX + normFqn + "'. Address a button as " //$NON-NLS-1$
                    + "'Type.Object.Form.FormName.Button.Name' or 'CommonForm.FormName.Button.Name'."); //$NON-NLS-1$
            persisted = FormElementWriter.writeEditableForm(fctx, "RebindButtonCommand", //$NON-NLS-1$
                (formModel, tx) ->
                {
                    // Strict resolution: an AMBIGUOUS button name (several items by that name anywhere
                    // in the form-item tree) is rejected with a clear error instead of silently
                    // re-pointing the first match (findUniqueFormItem throws; the tx rolls back).
                    EObject button = FormElementWriter.findUniqueFormItem(formModel, buttonName);
                    if (button == null)
                    {
                        throw new FormValidationException(ToolResult.error("Form button not found: " //$NON-NLS-1$
                            + buttonName + ". Use get_metadata_details to inspect the form items.") //$NON-NLS-1$
                            .toJson());
                    }
                    String err =
                        FormElementWriter.rebindButtonCommand(formModel, button, commandNameFinal);
                    if (err != null)
                    {
                        throw new FormValidationException(ToolResult.error(err).toJson());
                    }
                });
        }
        catch (Exception e)
        {
            String validationJson = FormValidationException.jsonOf(e);
            if (validationJson != null)
            {
                return validationJson;
            }
            Activator.logError("Error rebinding button command", e); //$NON-NLS-1$
            return ToolResult.error("Failed to rebind button command: " + unwrapCauseMessage(e)).toJson(); //$NON-NLS-1$
        }

        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_MODIFIED)
            .put("fqn", normFqn) //$NON-NLS-1$
            .put(KEY_APPLIED, java.util.Collections.singletonList(PROP_COMMAND))
            .put(KEY_PERSISTED, persisted)
            .put(McpKeys.MESSAGE, "Re-pointed button '" + buttonName + "' at command '" //$NON-NLS-1$ //$NON-NLS-2$
                + commandNameFinal + "'") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Refuses the structural form property a client must not set as a value: {@code id} (the
     * form-wide-unique item id is allocated automatically). The {@code name} (rename) property is
     * already refused by {@link #prepare}. Returns a JSON error to reject, or {@code null} to allow.
     */
    private static String guardFormProperty(JsonObject prop)
    {
        String name = asString(prop.get("name")); //$NON-NLS-1$
        if ("id".equalsIgnoreCase(name)) //$NON-NLS-1$
        {
            return ToolResult.error("The form item 'id' is allocated automatically and must stay " //$NON-NLS-1$
                + "form-wide unique - it cannot be set.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Maps the friendly {@code type} alias to a form attribute's real {@code valueType} feature so a
     * form attribute's data type is set with the same {@code {name:'type', value:{types:[...]}}} shape
     * mdclass attributes use. Returns the original prop unchanged when no alias applies.
     */
    private static JsonObject normalizeFormProperty(EObject member, JsonObject prop)
    {
        String name = asString(prop.get("name")); //$NON-NLS-1$
        if ("type".equalsIgnoreCase(name) //$NON-NLS-1$
            && member.eClass().getEStructuralFeature("type") == null //$NON-NLS-1$
            && member.eClass().getEStructuralFeature("valueType") != null) //$NON-NLS-1$
        {
            JsonObject copy = prop.deepCopy();
            copy.addProperty("name", "valueType"); //$NON-NLS-1$ //$NON-NLS-2$
            return copy;
        }
        return prop;
    }

    /**
     * Validates one property against the introspected schema and, on success, appends a
     * {@link PreparedChange}. Returns a JSON error string on failure, or {@code null} on success.
     * Accepts any {@link EObject} so it serves both mdclass nodes and form members (the introspector
     * and the prepared change are EClass-driven, not mdclass-specific).
     */
    private String prepare(Configuration config, Version version, EObject target, JsonObject prop,
        List<PreparedChange> out, MdNameNormalizer.Report normReport)
    {
        String name = asString(prop.get("name")); //$NON-NLS-1$
        if (name == null || name.isEmpty())
        {
            return ToolResult.error("Each entry in 'properties' needs a non-empty 'name'.").toJson(); //$NON-NLS-1$
        }
        if ("name".equalsIgnoreCase(name)) //$NON-NLS-1$
        {
            return ToolResult.error("Renaming via the 'name' property is not allowed here: use " //$NON-NLS-1$
                + "rename_metadata_object, which cascades the rename across BSL code, forms and " //$NON-NLS-1$
                + "metadata. modify_metadata only sets non-identity properties.").toJson(); //$NON-NLS-1$
        }
        String value = asString(prop.get(KEY_VALUE));

        // findFeature classifies ONLY the matched feature and skips the current-value rendering
        // (eGet + proxy + type rendering) that the full introspect() performs for EVERY assignable
        // feature - prepare() never reads currentValue, and this runs on the UI thread per property.
        PropertyInfo info = MetadataPropertyIntrospector.findFeature(target, name);
        if (info == null)
        {
            return ToolResult.error("Property '" + name + "' is not assignable on " //$NON-NLS-1$ //$NON-NLS-2$
                + target.eClass().getName() + ". Assignable properties: " //$NON-NLS-1$
                + String.join(", ", MetadataPropertyIntrospector.assignableNames(target)) //$NON-NLS-1$
                + ". Use get_metadata_details with assignable:true for kinds + allowed values.").toJson(); //$NON-NLS-1$
        }

        switch (info.valueKind)
        {
            case LOCALIZED_STRING:
                return prepareLocalized(config, name, value, prop, info, out, normReport);
            case ENUM:
            {
                EEnumLiteral literal = MetadataPropertyIntrospector.resolveEnumLiteral(info.feature, value);
                if (literal == null)
                {
                    return ToolResult.error("'" + value + "' is not a valid value for '" + name //$NON-NLS-1$ //$NON-NLS-2$
                        + "'. Allowed: " + String.join(", ", info.allowedValues) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                out.add(PreparedChange.scalar(info.feature, literal.getInstance()));
                return null;
            }
            case BOOLEAN:
            {
                Boolean b = parseBoolean(value);
                if (b == null)
                {
                    return ToolResult.error("'" + value + "' is not a valid boolean for '" + name //$NON-NLS-1$ //$NON-NLS-2$
                        + "'. Use true or false.").toJson(); //$NON-NLS-1$
                }
                out.add(PreparedChange.scalar(info.feature, b));
                return null;
            }
            case INTEGER:
            {
                Integer i = parseInteger(value);
                if (i == null)
                {
                    return ToolResult.error("'" + value + "' is not a valid integer for '" + name //$NON-NLS-1$ //$NON-NLS-2$
                        + "'.").toJson(); //$NON-NLS-1$
                }
                out.add(PreparedChange.scalar(info.feature, i));
                return null;
            }
            case TYPE_DESCRIPTION:
                return prepareTypeDescription(config, version, name, prop, info, out);
            case REFERENCE:
                return prepareReference(config, name, value, info, out);
            case MANY_REFERENCE:
                return prepareManyReference(config, name, prop, info, out);
            case STYLE_VALUE:
                return prepareStyleValue(name, prop, target, info, out);
            case STRING:
            default:
                if (value == null || value.isEmpty())
                {
                    return requireValueError(name);
                }
                out.add(PreparedChange.scalar(info.feature,
                    normalizeStringPropertyValue(name, value, normReport)));
                return null;
        }
    }

    /**
     * Validates a {@code LOCALIZED_STRING} property (resolving its synonym language code) and, on
     * success, appends the prepared localized change to {@code out}. Returns a JSON error on failure,
     * or {@code null} on success. Read-only: it only builds and queues the change (no model mutation).
     */
    private String prepareLocalized(Configuration config, String name, String value, JsonObject prop,
        PropertyInfo info, List<PreparedChange> out, MdNameNormalizer.Report normReport)
    {
        if (value == null || value.isEmpty())
        {
            return requireValueError(name);
        }
        String code;
        try
        {
            code = MetadataLanguageUtils.resolveSynonymLanguage(config, value,
                asString(prop.get("language")), "'" + name + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }
        out.add(PreparedChange.localized(info.feature, code, normReport.apply(name, value)));
        return null;
    }

    /**
     * Validates a {@code TYPE_DESCRIPTION} property (building the type description for the resolved
     * platform version) and, on success, appends the prepared scalar change to {@code out}. Returns a
     * JSON error on failure, or {@code null} on success. Read-only: it only builds and queues the
     * change (no model mutation).
     */
    private String prepareTypeDescription(Configuration config, Version version, String name,
        JsonObject prop, PropertyInfo info, List<PreparedChange> out)
    {
        if (version == null)
        {
            return ToolResult.error("Cannot resolve the platform version needed to build a " //$NON-NLS-1$
                + "type for '" + name + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        MetadataTypeBuilder.Result tr =
            MetadataTypeBuilder.build(prop.get(KEY_VALUE), config, version);
        if (tr.error != null)
        {
            return ToolResult.error("Invalid 'type' for '" + name + "': " + tr.error).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        out.add(PreparedChange.scalar(info.feature, tr.typeDescription));
        return null;
    }

    /**
     * Validates a single-valued {@code REFERENCE} property (resolving and type-checking its FQN target)
     * and, on success, appends the prepared reference change to {@code out}. Returns a JSON error on
     * failure, or {@code null} on success. Read-only: it only builds and queues the change (no model
     * mutation).
     */
    private String prepareReference(Configuration config, String name, String value, PropertyInfo info,
        List<PreparedChange> out)
    {
        if (value == null || value.isEmpty())
        {
            return requireValueError(name);
        }
        MdObject targetMd = resolveReferenceTarget(config, value);
        String vErr = validateReferenceTarget(name, info.feature, targetMd, value);
        if (vErr != null)
        {
            return vErr;
        }
        out.add(PreparedChange.reference(info.feature, ((IBmObject)targetMd).bmGetId()));
        return null;
    }

    /**
     * Validates a {@code MANY_REFERENCE} property (the value must be a JSON array of non-empty FQNs,
     * each resolving and type-checking) and, on success, appends the prepared list-reference change to
     * {@code out}. Returns a JSON error on failure, or {@code null} on success. Read-only: it only
     * builds and queues the change (no model mutation).
     */
    private String prepareManyReference(Configuration config, String name, JsonObject prop,
        PropertyInfo info, List<PreparedChange> out)
    {
        JsonElement raw = prop.get(KEY_VALUE);
        if (raw == null || !raw.isJsonArray())
        {
            return ToolResult.error("'" + name + "' is a list reference: provide 'value' as an " //$NON-NLS-1$ //$NON-NLS-2$
                + "array of FQNs, e.g. [\"Catalog.Products\", \"Document.Order\"].").toJson(); //$NON-NLS-1$
        }
        List<Long> ids = new ArrayList<>();
        for (JsonElement el : raw.getAsJsonArray())
        {
            String fqn = (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
            if (fqn == null || fqn.isEmpty())
            {
                return ToolResult.error("Each entry of the '" + name + "' list must be a " //$NON-NLS-1$ //$NON-NLS-2$
                    + "non-empty FQN string.").toJson(); //$NON-NLS-1$
            }
            MdObject t = resolveReferenceTarget(config, fqn);
            String vErr = validateReferenceTarget(name, info.feature, t, fqn);
            if (vErr != null)
            {
                return vErr;
            }
            ids.add(((IBmObject)t).bmGetId());
        }
        out.add(PreparedChange.manyReference(info.feature, ids));
        return null;
    }

    /**
     * Validates a {@code STYLE_VALUE} property (building the StyleItem Color / Font value) and, on
     * success, appends the prepared style-value change to {@code out} (which also keeps the sibling
     * {@code type} feature consistent with the value). Returns a JSON error on failure, or {@code null}
     * on success. Read-only: it only builds and queues the change (no model mutation).
     */
    private String prepareStyleValue(String name, JsonObject prop, EObject target, PropertyInfo info,
        List<PreparedChange> out)
    {
        StyleValueBuilder.Result sv = StyleValueBuilder.build(prop.get(KEY_VALUE));
        if (sv.error != null)
        {
            return ToolResult.error("Invalid StyleItem '" + name + "': " + sv.error).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // The style item's `type` (Color / Font) is kept consistent with the value it holds, so
        // the change sets both the `value` and the sibling `type` feature in one shot.
        EStructuralFeature typeFeature = target.eClass().getEStructuralFeature("type"); //$NON-NLS-1$
        out.add(PreparedChange.styleValue(info.feature, typeFeature, sv.value, sv.type));
        return null;
    }

    /**
     * Applies the yo-to-ye normalization to a free STRING property value with a deliberately
     * NARROW scope: only the {@code comment} property is normalized (it is presentation text
     * checked by the same EDT validator, 1C standard #std474, as names and synonyms). Every
     * other free STRING feature can be identifier-like — e.g. {@code XDTOPackage.namespace}
     * is a URI — where a silent yo-to-ye rewrite would corrupt the value, so the caller's
     * text is kept verbatim. LOCALIZED_STRING values are normalized separately (see the
     * LOCALIZED_STRING branch of {@code prepare}).
     *
     * @param name the property name as supplied by the caller
     * @param value the non-empty property value
     * @param normReport the normalization report (honors the {@code normalizeYo} toggle)
     * @return the value to assign — normalized for {@code comment}, verbatim otherwise
     */
    static String normalizeStringPropertyValue(String name, String value,
        MdNameNormalizer.Report normReport)
    {
        return "comment".equalsIgnoreCase(name) ? normReport.apply(name, value) : value; //$NON-NLS-1$
    }

    /** Resolves a reference-target FQN to its metadata object (a top object), or {@code null}. */
    private static MdObject resolveReferenceTarget(Configuration config, String fqn)
    {
        String norm = MetadataTypeUtils.normalizeFqn(fqn);
        MetadataNodeResolver.MetadataNode n = MetadataNodeResolver.resolveExisting(config, norm);
        return n != null ? n.object : null;
    }

    /**
     * Validates a reference target: it must resolve, be a re-fetchable top object, and have a type
     * assignable to the reference feature's target type. Returns a JSON error or {@code null} on OK.
     */
    private static String validateReferenceTarget(String prop, EStructuralFeature feature,
        MdObject target, String fqn)
    {
        if (target == null)
        {
            return ToolResult.error("Reference target '" + fqn + "' for '" + prop + "' was not found. " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "Use a valid FQN (e.g. 'Catalog.Products'); check with get_metadata_objects.").toJson(); //$NON-NLS-1$
        }
        if (!(target instanceof IBmObject) || !((IBmObject)target).bmIsTop())
        {
            return ToolResult.error("Reference target '" + fqn + "' for '" + prop + "' must be a " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "top-level object; references to members are not supported.").toJson(); //$NON-NLS-1$
        }
        EClass targetType = ((EReference)feature).getEReferenceType();
        if (targetType != null && !targetType.isSuperTypeOf(target.eClass()))
        {
            return ToolResult.error("'" + fqn + "' is a " + target.eClass().getName() + " but '" + prop //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "' requires a " + targetType.getName() + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Error for a missing / empty {@code value}: this tool never clears a property on an omitted
     * value (a clear must be explicit), matching the former set_metadata_property's "empty = not
     * provided" guard.
     */
    private static String requireValueError(String name)
    {
        return ToolResult.error("Property '" + name + "' needs a non-empty 'value'. modify_metadata " //$NON-NLS-1$ //$NON-NLS-2$
            + "does not clear a property on an empty value.").toJson(); //$NON-NLS-1$
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * The STYLE_VALUE-only binding of a {@link PreparedChange}: the sibling {@code type} feature
     * (Color / Font) and the {@link StyleElementType} to set on it alongside the value, so the style
     * item's type stays consistent with the value it holds. A parameter-object that keeps the
     * {@link PreparedChange} constructor below the 7-parameter bar; {@code null} for every non-style
     * change. Carries no behaviour.
     */
    private static final class StyleBinding
    {
        private final EStructuralFeature typeFeature;
        private final StyleElementType type;

        StyleBinding(EStructuralFeature typeFeature, StyleElementType type)
        {
            this.typeFeature = typeFeature;
            this.type = type;
        }
    }

    /** A validated, coerced change ready to apply to the re-fetched target inside the write tx. */
    private static final class PreparedChange
    {
        private enum Kind { SCALAR, LOCALIZED, REFERENCE, MANY_REFERENCE, STYLE_VALUE }

        private final EStructuralFeature feature;
        private final Kind kind;
        private final Object scalarValue;
        private final String localizedLanguage;
        private final String localizedValue;
        /** For a REFERENCE: the target's bmId. For a MANY_REFERENCE: the targets' bmIds in order. */
        private final List<Long> referenceBmIds;
        /** For a STYLE_VALUE: the sibling `type` feature + StyleElementType; {@code null} otherwise. */
        private final StyleBinding styleBinding;

        private PreparedChange(EStructuralFeature feature, Kind kind, Object scalarValue,
            String language, String localizedValue, List<Long> referenceBmIds,
            StyleBinding styleBinding)
        {
            this.feature = feature;
            this.kind = kind;
            this.scalarValue = scalarValue;
            this.localizedLanguage = language;
            this.localizedValue = localizedValue;
            this.referenceBmIds = referenceBmIds;
            this.styleBinding = styleBinding;
        }

        static PreparedChange scalar(EStructuralFeature feature, Object value)
        {
            return new PreparedChange(feature, Kind.SCALAR, value, null, null, null, null);
        }

        static PreparedChange localized(EStructuralFeature feature, String language, String value)
        {
            return new PreparedChange(feature, Kind.LOCALIZED, null, language, value, null, null);
        }

        static PreparedChange reference(EStructuralFeature feature, long targetBmId)
        {
            return new PreparedChange(feature, Kind.REFERENCE, null, null, null,
                java.util.Collections.singletonList(targetBmId), null);
        }

        static PreparedChange manyReference(EStructuralFeature feature, List<Long> targetBmIds)
        {
            return new PreparedChange(feature, Kind.MANY_REFERENCE, null, null, null, targetBmIds,
                null);
        }

        /**
         * A StyleItem value change: the freshly-built mcore {@link Value} ({@code styleValue}) is a
         * detached containment object, so it is set directly on the re-fetched style item inside the
         * tx (like the TYPE_DESCRIPTION scalar). The sibling {@code typeFeature} (Color / Font) is set
         * to {@code type} in the same change so the style item's type stays consistent with its value.
         */
        static PreparedChange styleValue(EStructuralFeature valueFeature, EStructuralFeature typeFeature,
            Value styleValue, StyleElementType type)
        {
            return new PreparedChange(valueFeature, Kind.STYLE_VALUE, styleValue, null, null, null,
                new StyleBinding(typeFeature, type));
        }

        String featureName()
        {
            return feature.getName();
        }

        @SuppressWarnings("unchecked")
        void applyTo(EObject target, IBmTransaction tx)
        {
            switch (kind)
            {
                case LOCALIZED:
                {
                    Object map = target.eGet(feature);
                    if (!(map instanceof EMap))
                    {
                        throw new IllegalStateException("Localized feature '" + feature.getName() //$NON-NLS-1$
                            + "' is not a map"); //$NON-NLS-1$
                    }
                    ((EMap<String, String>)map).put(localizedLanguage, localizedValue);
                    return;
                }
                case REFERENCE:
                {
                    // BM normalizes the target to its in-tx counterpart by bmId on set.
                    target.eSet(feature, requireInTx(tx, referenceBmIds.get(0)));
                    return;
                }
                case MANY_REFERENCE:
                {
                    // Replace the whole list (a plain, non-containment cross-reference list, so add()
                    // only links the target - it does not reparent it).
                    EList<EObject> list = (EList<EObject>)target.eGet(feature);
                    list.clear();
                    for (Long id : referenceBmIds)
                    {
                        list.add(requireInTx(tx, id));
                    }
                    return;
                }
                case STYLE_VALUE:
                {
                    // Keep the style item's `type` consistent with the value it now holds (Color / Font),
                    // then set the freshly-built (detached) mcore Value as its containment `value`.
                    if (styleBinding != null && styleBinding.typeFeature != null
                        && styleBinding.type != null)
                    {
                        target.eSet(styleBinding.typeFeature, styleBinding.type);
                    }
                    target.eSet(feature, scalarValue);
                    return;
                }
                case SCALAR:
                default:
                    target.eSet(feature, scalarValue);
                    return;
            }
        }

        /** Re-fetches a reference target inside the tx, failing clearly if it has gone (rolls back). */
        private static EObject requireInTx(IBmTransaction tx, long bmId)
        {
            EObject t = (EObject)tx.getObjectById(bmId);
            if (t == null)
            {
                throw new IllegalStateException("Reference target is no longer in the transaction"); //$NON-NLS-1$
            }
            return t;
        }
    }

    private static String asString(JsonElement el)
    {
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    private static Boolean parseBoolean(String value)
    {
        if (value == null)
        {
            return null; // NOSONAR intentional tri-state Boolean; null is distinct from false for callers
        }
        String v = value.trim().toLowerCase();
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.TRUE;
        }
        if ("false".equals(v) || "0".equals(v) || "no".equals(v)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return Boolean.FALSE;
        }
        return null; // NOSONAR intentional tri-state Boolean; null is distinct from false for callers
    }

    private static Integer parseInteger(String value)
    {
        if (value == null || value.isEmpty())
        {
            return null;
        }
        try
        {
            double d = Double.parseDouble(value.trim());
            if (d != Math.floor(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE)
            {
                return null;
            }
            return Integer.valueOf((int)d);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static EObject childByName(EObject owner, EStructuralFeature feature, String name)
    {
        Object value = owner.eGet(feature);
        if (value instanceof EList<?>)
        {
            for (Object element : (EList<?>)value)
            {
                if (element instanceof MdObject child && name.equalsIgnoreCase(child.getName()))
                {
                    return child;
                }
            }
        }
        return null;
    }

    private static String topFqn(String normFqn)
    {
        String[] parts = normFqn.split("\\."); //$NON-NLS-1$
        return parts.length >= 2 ? parts[0] + "." + parts[1] : normFqn; //$NON-NLS-1$
    }
}
