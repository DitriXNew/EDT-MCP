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
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.PropertyInfo;
import com.ditrix.edt.mcp.server.utils.MetadataTypeBuilder;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
            + "error. Discover assignable properties + allowed values with " //$NON-NLS-1$
            + "get_metadata_details(assignable:true). To rename, use rename_metadata_object. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('modify_metadata')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty("fqn", //$NON-NLS-1$
                "Full-name FQN of the node to modify (required), e.g. 'Catalog.Products' or " //$NON-NLS-1$
                + "'Catalog.Products.Attribute.Weight' (type / kind tokens may be English or Russian; " //$NON-NLS-1$
                + "the Name parts are the programmatic Name).", true) //$NON-NLS-1$
            .objectArrayProperty("properties", //$NON-NLS-1$
                "Properties to set, as [{name, value, language?}] (required, at least one). 'name' is " //$NON-NLS-1$
                + "the property name (e.g. 'comment', 'synonym', 'indexing'); 'value' is the new " //$NON-NLS-1$
                + "value; 'language' is the code for a synonym (default: config default).", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the properties were set", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "'modified' on success") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("fqn", "Normalized FQN of the modified node") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("applied", "Names of the properties that were set") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("persisted", "Whether the change was exported to disk") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable confirmation message") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# modify_metadata\n\n" //$NON-NLS-1$
            + "Sets one or more properties of a metadata node addressed by a 1C full-name FQN (a top " //$NON-NLS-1$
            + "object or a member: attribute / tabular section / dimension / resource / enum value), " //$NON-NLS-1$
            + "then force-exports the owning top object to its `.mdo`. Replaces the former " //$NON-NLS-1$
            + "set_metadata_property (which set only Comment / Synonym); this tool sets any assignable " //$NON-NLS-1$
            + "scalar / boolean / integer / enum / synonym property.\n\n" //$NON-NLS-1$
            + "## Validation (errors are help)\n" //$NON-NLS-1$
            + "- A property that is NOT assignable on this node is rejected with the list of " //$NON-NLS-1$
            + "assignable properties - discover them with get_metadata_details(assignable:true).\n" //$NON-NLS-1$
            + "- An ENUM value that is not one of the allowed literals is rejected WITH the allowed " //$NON-NLS-1$
            + "values; a non-boolean for a boolean property, or a non-integer for an integer " //$NON-NLS-1$
            + "property, is rejected too. Nothing is written unless EVERY property validates.\n\n" //$NON-NLS-1$
            + "## Parameters\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `fqn` (required) - full-name FQN of the node.\n" //$NON-NLS-1$
            + "- `properties` (required) - array of `{name, value, language?}`. `name` is the property " //$NON-NLS-1$
            + "name; `value` the new value; `language` the CODE for a synonym (default: config " //$NON-NLS-1$
            + "default).\n\n" //$NON-NLS-1$
            + "## Not supported here\n" //$NON-NLS-1$
            + "- `name` (rename): refused - use rename_metadata_object, which cascades the rename " //$NON-NLS-1$
            + "across BSL code, forms and metadata.\n\n" //$NON-NLS-1$
            + "## Setting the data type\n" //$NON-NLS-1$
            + "The `type` property takes a STRUCTURED value `{types:[{kind, ...}]}`. Primitive kinds " //$NON-NLS-1$
            + "String / Number / Boolean / Date carry inline qualifiers (length; precision / scale / " //$NON-NLS-1$
            + "nonNegative; fractions = DateTime | Date | Time). A reference is `{kind:'Ref', " //$NON-NLS-1$
            + "ref:'Type.Name'}` (or `{kind:'CatalogRef', ref:'Name'}`). The list may mix several " //$NON-NLS-1$
            + "(a composite type).\n\n" //$NON-NLS-1$
            + "## Setting an object reference\n" //$NON-NLS-1$
            + "A reference property to another metadata object is set by FQN: a SINGLE reference " //$NON-NLS-1$
            + "(e.g. `chartOfAccounts` on an AccountingRegister) takes `value:'Type.Name'`; a LIST " //$NON-NLS-1$
            + "reference (e.g. a Subsystem's `content`) takes `value:['Type.Name', ...]` and REPLACES " //$NON-NLS-1$
            + "the whole list (an empty array `[]` clears it). The target must be a top-level object " //$NON-NLS-1$
            + "whose type matches; get_metadata_details(assignable:true) shows the allowed target " //$NON-NLS-1$
            + "type. Structured content with per-item flags (e.g. a common attribute's content), and " //$NON-NLS-1$
            + "references whose target is a member (e.g. a default form), are not set here yet.\n\n" //$NON-NLS-1$
            + "## Form members\n" //$NON-NLS-1$
            + "A FORM member is addressed like its create FQN: `Catalog.X.Form.F.<Kind>.Name` (or " //$NON-NLS-1$
            + "`CommonForm.F.<Kind>.Name`), Kind = Attribute / Command / Field / Button / Group / " //$NON-NLS-1$
            + "Decoration / Table. The same assignable properties apply: an item's `title` (bilingual; " //$NON-NLS-1$
            + "defaults to the config language when `language` is omitted), `visible`, `readOnly` " //$NON-NLS-1$
            + "(fields / groups / tables only) and any other assignable scalar / boolean / enum the " //$NON-NLS-1$
            + "item carries. NB `type` is context-dependent: on a form ATTRIBUTE it aliases the data " //$NON-NLS-1$
            + "`valueType` (same `{types:[...]}` shape as an mdclass attribute); on a form FIELD / " //$NON-NLS-1$
            + "Button / Decoration it is the display-kind ENUM (InputField / LabelField / ...). A wrong " //$NON-NLS-1$
            + "property name is rejected WITH the member's assignable list. The form item `id` cannot " //$NON-NLS-1$
            + "be set (auto-allocated); modifying an event handler is not supported (create/delete it). " //$NON-NLS-1$
            + "The change persists to the form's `Form.form` on disk.\n\n" //$NON-NLS-1$
            + "## Examples\n" //$NON-NLS-1$
            + "- Set a comment: `{projectName:'P', fqn:'Catalog.Products', properties:[{name:'comment', " //$NON-NLS-1$
            + "value:'Goods'}]}`\n" //$NON-NLS-1$
            + "- Set a synonym: `{projectName:'P', fqn:'Catalog.Products', properties:[{name:'synonym', " //$NON-NLS-1$
            + "value:'Goods', language:'en'}]}`\n" //$NON-NLS-1$
            + "- Set an enum on an attribute: `{projectName:'P', " //$NON-NLS-1$
            + "fqn:'Catalog.Products.Attribute.Weight', properties:[{name:'indexing', value:'Index'}]}`\n" //$NON-NLS-1$
            + "- Set a type: `{projectName:'P', fqn:'Catalog.Products.Attribute.Weight', " //$NON-NLS-1$
            + "properties:[{name:'type', value:{types:[{kind:'Number', precision:10, scale:2}]}}]}`\n" //$NON-NLS-1$
            + "- Set a list reference: `{projectName:'P', fqn:'Subsystem.Sales', " //$NON-NLS-1$
            + "properties:[{name:'content', value:['Catalog.Products', 'Document.Order']}]}`\n" //$NON-NLS-1$
            + "- Hide a form item: `{projectName:'P', fqn:'Catalog.Products.Form.ItemForm.Field.Price', " //$NON-NLS-1$
            + "properties:[{name:'visible', value:false}]}`\n" //$NON-NLS-1$
            + "- Set a form attribute's type: `{projectName:'P', " //$NON-NLS-1$
            + "fqn:'Catalog.Products.Form.ItemForm.Attribute.Total', properties:[{name:'type', " //$NON-NLS-1$
            + "value:{types:[{kind:'Number', precision:10, scale:2}]}}]}`\n\n" //$NON-NLS-1$
            + "## Result\n" //$NON-NLS-1$
            + "JSON with `action='modified'`, the normalized `fqn`, the `applied` property names, and " //$NON-NLS-1$
            + "`persisted`."; //$NON-NLS-1$
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
        List<JsonObject> properties = JsonUtils.extractObjectArray(params, "properties"); //$NON-NLS-1$
        if (properties.isEmpty())
        {
            return ToolResult.error("properties is required: provide at least one {name, value} to " //$NON-NLS-1$
                + "set, e.g. [{name: 'comment', value: 'Goods'}].").toJson(); //$NON-NLS-1$
        }

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
            return modifyFormMember(ctx, normFqn, formRef, properties);
        }

        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, normFqn);
        if (node == null || node.object == null)
        {
            return ToolResult.error("Node not found: " + fqn + ". Use 'Type.Name' for a top object or " //$NON-NLS-1$ //$NON-NLS-2$
                + "'Type.Name.Kind.Name' for a member. Use get_metadata_objects to find an FQN.").toJson(); //$NON-NLS-1$
        }
        MdObject target = node.object;

        // Resolve the BM re-fetch strategy (mutation must re-fetch inside the write tx). Only TOP
        // objects are re-fetchable by bmId, so for a member we re-fetch the TOP object and
        // re-navigate to the leaf's owner BY NAME inside the tx - this is what lets a member of a
        // NESTED object (e.g. a tabular-section attribute) be modified, not just a direct member.
        final String[] parts = normFqn.split("\\."); //$NON-NLS-1$
        final long topBmId;
        final EStructuralFeature memberFeature;
        final String memberName;
        if (node.topLevel)
        {
            if (!(target instanceof IBmObject))
            {
                return ToolResult.error("Target is not a BM object").toJson(); //$NON-NLS-1$
            }
            topBmId = ((IBmObject)target).bmGetId();
            memberFeature = null;
            memberName = null;
        }
        else
        {
            MdObject topObject = MetadataTypeUtils.findObject(config, parts[0], parts[1]);
            if (!(topObject instanceof IBmObject))
            {
                return ToolResult.error("Top object is not a BM object").toJson(); //$NON-NLS-1$
            }
            topBmId = ((IBmObject)topObject).bmGetId();
            memberFeature = node.feature;
            memberName = target.getName();
        }

        // The platform version is needed only to build a 'type' value; resolve it best-effort (a
        // missing version is reported only if a 'type' property is actually set).
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IV8Project v8Project = v8ProjectManager != null ? v8ProjectManager.getProject(ctx.project) : null;
        final Version version = v8Project != null ? v8Project.getVersion() : null;

        // Validate every property against the introspected schema BEFORE any write (fail fast, no
        // partial mutation). On success, collect the prepared changes to apply inside the tx.
        List<PreparedChange> changes = new ArrayList<>();
        for (JsonObject prop : properties)
        {
            String pErr = prepare(config, version, target, prop, changes);
            if (pErr != null)
            {
                return pErr;
            }
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
                EObject top = (EObject)tx.getObjectById(topBmId);
                if (top == null)
                {
                    throw new RuntimeException("Target not found in transaction"); //$NON-NLS-1$
                }
                EObject applyTo = top;
                if (memberFeature != null)
                {
                    EObject owner = MetadataNodeResolver.resolveOwnerInTx(top, parts);
                    if (owner == null)
                    {
                        throw new RuntimeException("Could not re-navigate to the owner inside the transaction"); //$NON-NLS-1$
                    }
                    applyTo = childByName(owner, memberFeature, memberName);
                    if (applyTo == null)
                    {
                        throw new RuntimeException("Member not found in transaction: " + memberName); //$NON-NLS-1$
                    }
                }
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

        List<String> applied = new ArrayList<>();
        for (PreparedChange change : changes)
        {
            applied.add(change.featureName());
        }
        return ToolResult.success()
            .put("action", "modified") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("applied", applied) //$NON-NLS-1$
            .put("persisted", persisted) //$NON-NLS-1$
            .put("message", "Modified " + normFqn + " (" + String.join(", ", applied) + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .toJson();
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
        FormElementWriter.FormMemberRef ref, List<JsonObject> properties)
    {
        // Event handlers are added/removed (create_metadata / delete_metadata), not property-modified.
        if (FormElementWriter.isHandlerToken(ref.kindToken) || ref.isItemLevel())
        {
            return ToolResult.error("Modifying a form event handler is not supported. Use " //$NON-NLS-1$
                + "create_metadata to add a handler or delete_metadata to remove it.").toJson(); //$NON-NLS-1$
        }

        Configuration config = ctx.config;
        IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
        IV8Project v8Project = v8ProjectManager != null ? v8ProjectManager.getProject(ctx.project) : null;
        final Version version = v8Project != null ? v8Project.getVersion() : null;

        MdObject mdForm = FormStructureReader.resolveMdForm(config, ref.formPath);
        if (mdForm == null)
        {
            return ToolResult.error("Form not found for '" + normFqn + "'. Address a form member as " //$NON-NLS-1$ //$NON-NLS-2$
                + "'Type.Object.Form.FormName.<Kind>.Name' or 'CommonForm.FormName.<Kind>.Name' " //$NON-NLS-1$
                + "(Kind = Attribute / Command / Field / Button / Group / Decoration / Table).").toJson(); //$NON-NLS-1$
        }
        if (!(mdForm instanceof IBmObject))
        {
            return ToolResult.error("Form is not a BM object").toJson(); //$NON-NLS-1$
        }

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            return ToolResult.error("IBmModelManager not available").toJson(); //$NON-NLS-1$
        }
        IBmModel bmModel = bmModelManager.getModel(ctx.project);
        if (bmModel == null)
        {
            return ToolResult.error("BM model not available").toJson(); //$NON-NLS-1$
        }

        final long mdFormBmId = ((IBmObject)mdForm).bmGetId();
        final List<String> applied = new ArrayList<>();

        // Validate + apply inside ONE BM write transaction: resolve the member, validate every
        // property (a failure throws FormValidationException carrying the JSON error BEFORE any eSet,
        // so the tx rolls back with no partial mutation), then apply. The member is re-navigated by
        // name inside the tx (only the form top object is re-fetchable by bmId). Building the change
        // values and setting them in the SAME tx avoids any cross-transaction detached-object concern.
        final String contentFormFqn;
        try
        {
            contentFormFqn = BmTransactions.<String>write(bmModel, "ModifyFormMember", (tx, pm) -> //$NON-NLS-1$
            {
                EObject txMdForm = (EObject)tx.getObjectById(mdFormBmId);
                if (txMdForm == null)
                {
                    throw new RuntimeException("Form object not found in transaction"); //$NON-NLS-1$
                }
                EObject formModel = FormElementWriter.getEditableForm(txMdForm);
                if (formModel == null)
                {
                    throw new FormValidationException(ToolResult.error("the form has no editable " //$NON-NLS-1$
                        + "content model (it may be empty, an ordinary/legacy form, or not yet " //$NON-NLS-1$
                        + "built)").toJson());
                }
                EObject member = FormElementWriter.resolveFormMember(formModel, ref);
                if (member == null)
                {
                    throw new FormValidationException(ToolResult.error("Form member not found: " //$NON-NLS-1$
                        + ref.name + " (kind '" + ref.kindToken + "') on " + ref.formPath //$NON-NLS-1$ //$NON-NLS-2$
                        + ". Use get_form_structure to list the members.").toJson()); //$NON-NLS-1$
                }
                List<PreparedChange> changes = new ArrayList<>();
                for (JsonObject prop : properties)
                {
                    String guard = guardFormProperty(prop);
                    if (guard != null)
                    {
                        throw new FormValidationException(guard);
                    }
                    String pErr =
                        prepare(config, version, member, normalizeFormProperty(member, prop), changes);
                    if (pErr != null)
                    {
                        throw new FormValidationException(pErr);
                    }
                }
                for (PreparedChange change : changes)
                {
                    change.applyTo(member, tx);
                    applied.add(change.featureName());
                }
                return (formModel instanceof IBmObject) ? ((IBmObject)formModel).bmGetFqn() : null;
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

        boolean persisted = contentFormFqn != null && !contentFormFqn.isEmpty()
            && BmTransactions.forceExportToDisk(ctx.project, contentFormFqn);

        return ToolResult.success()
            .put("action", "modified") //$NON-NLS-1$ //$NON-NLS-2$
            .put("fqn", normFqn) //$NON-NLS-1$
            .put("applied", applied) //$NON-NLS-1$
            .put("persisted", persisted) //$NON-NLS-1$
            .put("message", "Modified " + normFqn + " (" + String.join(", ", applied) + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
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
     * Thrown out of the form write lambda when a property fails validation (or the form/member cannot
     * be resolved): carries a ready {@code ToolResult.error(...).toJson()} so the caller surfaces the
     * actionable message instead of a generic failure. Throwing BEFORE any {@code eSet} rolls the tx
     * back with no partial mutation.
     */
    private static final class FormValidationException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        final String json;

        FormValidationException(String json)
        {
            super("form member validation failed"); //$NON-NLS-1$
            this.json = json;
        }

        /** Finds a {@link FormValidationException} in the cause chain and returns its JSON, or null. */
        static String jsonOf(Throwable t)
        {
            for (Throwable c = t; c != null; c = c.getCause())
            {
                if (c instanceof FormValidationException)
                {
                    return ((FormValidationException)c).json;
                }
            }
            return null;
        }
    }

    /**
     * Validates one property against the introspected schema and, on success, appends a
     * {@link PreparedChange}. Returns a JSON error string on failure, or {@code null} on success.
     * Accepts any {@link EObject} so it serves both mdclass nodes and form members (the introspector
     * and the prepared change are EClass-driven, not mdclass-specific).
     */
    private String prepare(Configuration config, Version version, EObject target, JsonObject prop,
        List<PreparedChange> out)
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
        String value = asString(prop.get("value")); //$NON-NLS-1$

        PropertyInfo info = MetadataPropertyIntrospector.find(target, name);
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
            {
                if (value == null || value.isEmpty())
                {
                    return requireValueError(name);
                }
                String language = asString(prop.get("language")); //$NON-NLS-1$
                String code = MetadataLanguageUtils.resolveLanguageCode(config, language);
                if (code == null)
                {
                    return ToolResult.error("Cannot determine a language code for '" + name //$NON-NLS-1$
                        + "'. Specify a 'language' code (e.g. 'en' or 'ru').").toJson(); //$NON-NLS-1$
                }
                out.add(PreparedChange.localized(info.feature, code, value));
                return null;
            }
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
            {
                if (version == null)
                {
                    return ToolResult.error("Cannot resolve the platform version needed to build a " //$NON-NLS-1$
                        + "type for '" + name + "'.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                MetadataTypeBuilder.Result tr =
                    MetadataTypeBuilder.build(prop.get("value"), config, version); //$NON-NLS-1$
                if (tr.error != null)
                {
                    return ToolResult.error("Invalid 'type' for '" + name + "': " + tr.error).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                out.add(PreparedChange.scalar(info.feature, tr.typeDescription));
                return null;
            }
            case REFERENCE:
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
            case MANY_REFERENCE:
            {
                JsonElement raw = prop.get("value"); //$NON-NLS-1$
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
            case STRING:
            default:
                if (value == null || value.isEmpty())
                {
                    return requireValueError(name);
                }
                out.add(PreparedChange.scalar(info.feature, value));
                return null;
        }
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

    /** A validated, coerced change ready to apply to the re-fetched target inside the write tx. */
    private static final class PreparedChange
    {
        private enum Kind { SCALAR, LOCALIZED, REFERENCE, MANY_REFERENCE }

        private final EStructuralFeature feature;
        private final Kind kind;
        private final Object scalarValue;
        private final String localizedLanguage;
        private final String localizedValue;
        /** For a REFERENCE: the target's bmId. For a MANY_REFERENCE: the targets' bmIds in order. */
        private final List<Long> referenceBmIds;

        private PreparedChange(EStructuralFeature feature, Kind kind, Object scalarValue,
            String language, String localizedValue, List<Long> referenceBmIds)
        {
            this.feature = feature;
            this.kind = kind;
            this.scalarValue = scalarValue;
            this.localizedLanguage = language;
            this.localizedValue = localizedValue;
            this.referenceBmIds = referenceBmIds;
        }

        static PreparedChange scalar(EStructuralFeature feature, Object value)
        {
            return new PreparedChange(feature, Kind.SCALAR, value, null, null, null);
        }

        static PreparedChange localized(EStructuralFeature feature, String language, String value)
        {
            return new PreparedChange(feature, Kind.LOCALIZED, null, language, value, null);
        }

        static PreparedChange reference(EStructuralFeature feature, long targetBmId)
        {
            return new PreparedChange(feature, Kind.REFERENCE, null, null, null,
                java.util.Collections.singletonList(targetBmId));
        }

        static PreparedChange manyReference(EStructuralFeature feature, List<Long> targetBmIds)
        {
            return new PreparedChange(feature, Kind.MANY_REFERENCE, null, null, null, targetBmIds);
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
                        throw new RuntimeException("Localized feature '" + feature.getName() //$NON-NLS-1$
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
                throw new RuntimeException("Reference target is no longer in the transaction"); //$NON-NLS-1$
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
            return null;
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
        return null;
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
