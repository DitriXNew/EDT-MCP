/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Adopts a base-configuration metadata object — or one of its members
 * (a form, an attribute, a tabular section, ...) — into a configuration EXTENSION, so
 * the extension can override / intercept it. This is the MCP counterpart of EDT's
 * "Add To Extension" (Alt+F3) for the OBJECT/metadata side; adopting BSL code/methods
 * (the {@code &Before/&After/&Around/&ChangeAndValidate} interceptors) is a separate,
 * deliberately-not-implemented concern.
 * <p>
 * The whole adopt+attach is performed by the platform service
 * {@link IModelObjectAdopter#adoptAndAttach(EObject, IExtensionProject, org.eclipse.core.runtime.IProgressMonitor)}:
 * it runs its OWN BM write task on the extension's model, creates the adopted copy with
 * {@code ObjectBelonging.ADOPTED}, attaches it by generated FQN, and wires the
 * {@code extendedConfigurationObject} UUID link to the base object (by-ID mapping). This
 * tool resolves the source object and the target extension, calls that service, and then
 * force-exports the new {@code .mdo} (+ the extension's {@code Configuration.mdo}
 * registration) so the change survives a refresh / clean_project / EDT restart.
 */
public class AdoptMetadataObjectTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "adopt_metadata_object"; //$NON-NLS-1$

    /** Output key: the extension project the object was adopted into. */
    private static final String KEY_EXTENSION_PROJECT = "extensionProject"; //$NON-NLS-1$

    /** Output key: the object's belonging marker (ADOPTED). */
    private static final String KEY_OBJECT_BELONGING = "objectBelonging"; //$NON-NLS-1$

    /** Output key: whether the change was exported to disk. */
    private static final String KEY_PERSISTED = "persisted"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Adopt a base-configuration metadata object or member " //$NON-NLS-1$
            + "(object / form / attribute / tabular section / ...) into a configuration EXTENSION so the " //$NON-NLS-1$
            + "extension can override or intercept it - the MCP equivalent of EDT's 'Add To Extension'. " //$NON-NLS-1$
            + "Addressed by the base object FQN; pass extensionProjectName when more than one extension " //$NON-NLS-1$
            + "extends the configuration. Adopting BSL code/methods is NOT covered. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('adopt_metadata_object')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "The BASE configuration EDT project that owns the object (NOT the extension) (required)", //$NON-NLS-1$
                true)
            .stringProperty("fqn", //$NON-NLS-1$
                "Full-name FQN of the object or member to adopt (required), e.g. 'Catalog.Products', " //$NON-NLS-1$
                    + "'Catalog.Products.Attribute.Weight', 'Catalog.Products.Form.ItemForm'", //$NON-NLS-1$
                true)
            .stringProperty("extensionProjectName", //$NON-NLS-1$
                "Target extension EDT project name; REQUIRED only when more than one extension extends " //$NON-NLS-1$
                    + "the configuration (otherwise the single extension is used automatically)") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION, "'adopted' or 'alreadyAdopted'") //$NON-NLS-1$
            .stringProperty("fqn", "FQN of the adopted object in the extension") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_EXTENSION_PROJECT, "The extension the object was adopted into") //$NON-NLS-1$
            .stringProperty(KEY_OBJECT_BELONGING, "ADOPTED (the object is now an adopted copy)") //$NON-NLS-1$
            .booleanProperty(KEY_PERSISTED, "Whether the change was exported to disk", false) //$NON-NLS-1$
            .build();
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params) throws Exception
    {
        String argErr = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, "fqn"); //$NON-NLS-1$
        if (argErr != null)
        {
            return argErr;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String fqn = JsonUtils.extractStringArgument(params, "fqn"); //$NON-NLS-1$
        String extensionProjectName = JsonUtils.extractStringArgument(params, "extensionProjectName"); //$NON-NLS-1$

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }

        String normFqn = MetadataTypeUtils.normalizeFqn(fqn);
        // Resolve the source: a top object or a member (attribute/tabular section/...) via the shared
        // resolver; a FORM object via the form resolver (forms are a separate getForms() collection,
        // not in the mdclass child-token tree, so resolveExisting does not see them).
        EObject source;
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(ctx.config, normFqn);
        if (node != null && node.object != null)
        {
            source = node.object;
        }
        else
        {
            MdObject form = resolveFormObject(ctx.config, normFqn);
            if (form == null)
            {
                return ToolResult.error("Object not found: " + normFqn + ". " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Check the FQN: 'Type.Name' for a top object (e.g. 'Catalog.Products'), " //$NON-NLS-1$
                    + "'Type.Name.Kind.Name' for a member (e.g. 'Catalog.Products.Attribute.Weight'), " //$NON-NLS-1$
                    + "'Type.Name.Form.FormName' for a form (e.g. 'Catalog.Products.Form.ItemForm').").toJson(); //$NON-NLS-1$
            }
            source = form;
        }

        // Resolve the target extension: the configuration extensions whose parent is this config project.
        IV8ProjectManager v8pm = Activator.getDefault().getV8ProjectManager();
        if (v8pm == null)
        {
            return ToolResult.error("V8 project manager not available").toJson(); //$NON-NLS-1$
        }
        List<IExtensionProject> candidates = v8pm.getProjects(IExtensionProject.class).stream()
            .filter(c -> ctx.project.equals(c.getParentProject()))
            .collect(Collectors.toList());

        IExtensionProject target;
        if (extensionProjectName != null && !extensionProjectName.isEmpty())
        {
            target = candidates.stream()
                .filter(c -> extensionProjectName.equals(c.getProject().getName()))
                .findFirst()
                .orElse(null);
            if (target == null)
            {
                return ToolResult.error("'" + extensionProjectName + "' is not a configuration extension of '" //$NON-NLS-1$ //$NON-NLS-2$
                    + projectName + "'. Available extensions: " + candidateNames(candidates) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        else if (candidates.isEmpty())
        {
            return ToolResult.error("No configuration extension found for '" + projectName //$NON-NLS-1$
                + "'. Open/create an extension project (V8ExtensionNature) that extends it first " //$NON-NLS-1$
                + "(and pass the BASE configuration as projectName, not an extension).").toJson(); //$NON-NLS-1$
        }
        else if (candidates.size() > 1)
        {
            return ToolResult.error("Several extensions extend '" + projectName + "': " //$NON-NLS-1$ //$NON-NLS-2$
                + candidateNames(candidates) + ". Pass extensionProjectName to choose which to adopt into.").toJson(); //$NON-NLS-1$
        }
        else
        {
            target = candidates.get(0);
        }

        IModelObjectAdopter adopter = ServiceAccess.get(IModelObjectAdopter.class);
        if (adopter == null)
        {
            return ToolResult.error("Model object adopter service not available " //$NON-NLS-1$
                + "(the md.extension bundle may be inactive).").toJson(); //$NON-NLS-1$
        }

        if (!adopter.isAdoptable(source))
        {
            return ToolResult.error("'" + normFqn + "' cannot be adopted into an extension " //$NON-NLS-1$ //$NON-NLS-2$
                + "(the platform reports it is not adoptable).").toJson(); //$NON-NLS-1$
        }

        String extName = target.getProject().getName();

        if (adopter.isAdopted(source, target))
        {
            // The adopted FQN equals the source FQN (adoption is by-UUID, the Name is preserved).
            // Do NOT call bmGetFqn() on the adopted object - for a MEMBER (form/attribute) it is not
            // a top object and bmGetFqn() throws ("may be called on top objects only").
            return ToolResult.success()
                .put(McpKeys.ACTION, "alreadyAdopted") //$NON-NLS-1$
                .put("fqn", normFqn) //$NON-NLS-1$
                .put(KEY_EXTENSION_PROJECT, extName)
                .put(KEY_OBJECT_BELONGING, "ADOPTED") //$NON-NLS-1$
                .put(KEY_PERSISTED, true)
                .put("message", "'" + normFqn + "' is already adopted in extension '" + extName + "'.") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .toJson();
        }

        // The service runs its own BM write task on the extension's model.
        EObject adopted = adopter.adoptAndAttach(source, target, new NullProgressMonitor());

        // The adopted FQN equals the source FQN (adoption is by-UUID; the Name is preserved). Do NOT
        // call bmGetFqn() on the adopted object - for a MEMBER (form/attribute) it is not a top object
        // and bmGetFqn() throws. Persist the adopted TOP object's .mdo AND the extension
        // Configuration.mdo registration (the parent collection changed), mirroring create_metadata.
        // bmGetTopObject()/its bmGetFqn() are safe identity reads on the object the platform returned.
        String adoptedFqn = normFqn;
        List<String> dirty = new ArrayList<>();
        if (adopted instanceof IBmObject)
        {
            IBmObject topObject = ((IBmObject)adopted).bmGetTopObject();
            if (topObject != null)
            {
                dirty.add(topObject.bmGetFqn());
            }
        }
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        Configuration extConfig = configProvider != null ? configProvider.getConfiguration(target.getProject()) : null;
        if (extConfig instanceof IBmObject)
        {
            dirty.add(((IBmObject)extConfig).bmGetFqn());
        }
        boolean persisted = !dirty.isEmpty() && BmTransactions.forceExportToDisk(target.getProject(), dirty);

        return ToolResult.success()
            .put(McpKeys.ACTION, "adopted") //$NON-NLS-1$
            .put("fqn", adoptedFqn) //$NON-NLS-1$
            .put(KEY_EXTENSION_PROJECT, extName)
            .put(KEY_OBJECT_BELONGING, "ADOPTED") //$NON-NLS-1$
            .put(KEY_PERSISTED, persisted)
            .toJson();
    }

    /**
     * Resolves a FORM object by FQN. Forms live in a separate {@code getForms()} collection, so the
     * mdclass child-token resolver ({@code resolveExisting}) does not see them. Accepts both the
     * plural addressing the form reader defines ({@code Type.Name.Forms.FormName} and
     * {@code CommonForm.Name}) and the singular {@code Type.Name.Form.FormName} used elsewhere for
     * form members (normalized to the plural form here). Returns {@code null} when it is not a form.
     */
    private static MdObject resolveFormObject(Configuration config, String normFqn)
    {
        MdObject form = FormStructureReader.resolveMdForm(config, normFqn);
        if (form != null)
        {
            return form;
        }
        String[] parts = normFqn.split("\\."); //$NON-NLS-1$
        if (parts.length == 4 && "form".equalsIgnoreCase(parts[2])) //$NON-NLS-1$
        {
            return FormStructureReader.resolveMdForm(config,
                parts[0] + "." + parts[1] + ".Forms." + parts[3]); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    private static String candidateNames(List<IExtensionProject> candidates)
    {
        if (candidates.isEmpty())
        {
            return "(none)"; //$NON-NLS-1$
        }
        return candidates.stream().map(c -> c.getProject().getName()).collect(Collectors.joining(", ")); //$NON-NLS-1$
    }
}
