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
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
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
        return "Set properties of a metadata node (object or member) addressed by a 1C full-name FQN, " //$NON-NLS-1$
            + "as properties=[{name, value, language?}]. Each property is validated (it must be " //$NON-NLS-1$
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
            + "## Examples\n" //$NON-NLS-1$
            + "- Set a comment: `{projectName:'P', fqn:'Catalog.Products', properties:[{name:'comment', " //$NON-NLS-1$
            + "value:'Goods'}]}`\n" //$NON-NLS-1$
            + "- Set a synonym: `{projectName:'P', fqn:'Catalog.Products', properties:[{name:'synonym', " //$NON-NLS-1$
            + "value:'Goods', language:'en'}]}`\n" //$NON-NLS-1$
            + "- Set an enum on an attribute: `{projectName:'P', " //$NON-NLS-1$
            + "fqn:'Catalog.Products.Attribute.Weight', properties:[{name:'indexing', value:'Index'}]}`\n" //$NON-NLS-1$
            + "- Set a type: `{projectName:'P', fqn:'Catalog.Products.Attribute.Weight', " //$NON-NLS-1$
            + "properties:[{name:'type', value:{types:[{kind:'Number', precision:10, scale:2}]}}]}`\n\n" //$NON-NLS-1$
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
                    change.applyTo(applyTo);
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
     * Validates one property against the introspected schema and, on success, appends a
     * {@link PreparedChange}. Returns a JSON error string on failure, or {@code null} on success.
     */
    private String prepare(Configuration config, Version version, MdObject target, JsonObject prop,
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
        private final EStructuralFeature feature;
        private final Object scalarValue;
        private final String localizedLanguage;
        private final String localizedValue;
        private final boolean localized;

        private PreparedChange(EStructuralFeature feature, Object scalarValue, String language,
            String localizedValue, boolean localized)
        {
            this.feature = feature;
            this.scalarValue = scalarValue;
            this.localizedLanguage = language;
            this.localizedValue = localizedValue;
            this.localized = localized;
        }

        static PreparedChange scalar(EStructuralFeature feature, Object value)
        {
            return new PreparedChange(feature, value, null, null, false);
        }

        static PreparedChange localized(EStructuralFeature feature, String language, String value)
        {
            return new PreparedChange(feature, null, language, value, true);
        }

        String featureName()
        {
            return feature.getName();
        }

        @SuppressWarnings("unchecked")
        void applyTo(EObject target)
        {
            if (localized)
            {
                Object map = target.eGet(feature);
                if (map instanceof EMap)
                {
                    ((EMap<String, String>)map).put(localizedLanguage, localizedValue);
                }
                else
                {
                    throw new RuntimeException("Localized feature '" + feature.getName() //$NON-NLS-1$
                        + "' is not a map"); //$NON-NLS-1$
                }
            }
            else
            {
                target.eSet(feature, scalarValue);
            }
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
