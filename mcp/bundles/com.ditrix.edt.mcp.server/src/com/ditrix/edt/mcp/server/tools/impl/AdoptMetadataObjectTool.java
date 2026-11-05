/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import com.ditrix.edt.mcp.server.HostKind;

import com.ditrix.edt.mcp.server.utils.MetadataRegistration;
import com.ditrix.edt.mcp.server.utils.WorkspaceModelWrite;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.core.event.BmAssociationEvent;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.extension.adopt.IModelObjectAdopter;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.AdoptedFormAttachment;
import com.ditrix.edt.mcp.server.utils.BmObjectOwnership;
import com.ditrix.edt.mcp.server.utils.AdoptedFormLookup;
import com.ditrix.edt.mcp.server.utils.BmModelResolver;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.ExtensionRootBuilder;
import com.ditrix.edt.mcp.server.utils.FormPictureDependencies;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ReferencedTopObjects;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;

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
 * its list overload joins the tool's global-context BM write task and creates the adopted copy with
 * {@code ObjectBelonging.ADOPTED}, attaches it by generated FQN, and - only when the extension root
 * keeps mapping by IDs and the platform version is above 8.3.14 - wires the
 * {@code extendedConfigurationObject} UUID link to the base object (by-ID mapping; otherwise the copy
 * is mapped by name, and the result says so, see {@link #byNameMappingNote}). This
 * tool resolves the source object and the target extension, calls that service, and then
 * force-exports the new {@code .mdo} (+ the extension's {@code Configuration.mdo}
 * registration) so the change survives a refresh / clean_project / EDT restart.
 */
public class AdoptMetadataObjectTool extends AbstractMetadataWriteTool
{
    @Override
    protected String refreshAfterExportAwait(Map<String, String> params, String result,
        boolean drainEstablished, WriteScope scope)
    {
        if (HostKind.current() != HostKind.HEADLESS
            || ToolResult.isErrorPayload(result)) return result;
        var response = com.google.gson.JsonParser.parseString(result).getAsJsonObject();
        String extension = resultString(response, KEY_EXTENSION_PROJECT);
        for (String fqn : scope.exportedTopObjects(extension))
        {
            String[] parts = fqn.split("\\.");
            if (parts.length != 2 || MetadataTypeUtils.getConfigReferenceName(parts[0]) == null) continue;
            if (!drainEstablished || MetadataRegistration.verify(extension,
                "src/Configuration/Configuration.mdo", "Configuration", fqn)
                != MetadataRegistration.State.PRESENT)
            {
                return ToolResult.errorAfterMutation("Adoption changed extension '" + extension
                    + "', but Configuration.mdo does not confirm registration of '" + fqn
                    + "' on disk. Run resync_to_disk and inspect the extension before retrying or committing.")
                    .put("fqn", fqn).put(KEY_EXTENSION_PROJECT, extension).toJson();
            }
        }
        return result;
    }

    public static final String NAME = "adopt_metadata_object"; //$NON-NLS-1$

    /** Output key: the extension project the object was adopted into. */
    private static final String KEY_EXTENSION_PROJECT = "extensionProject"; //$NON-NLS-1$

    /** Output key: the object's belonging marker (ADOPTED). */
    private static final String KEY_OBJECT_BELONGING = "objectBelonging"; //$NON-NLS-1$

    /** Output key: whether the change was exported to disk. */
    private static final String KEY_PERSISTED = "persisted"; //$NON-NLS-1$

    private static final String KEY_CASCADED_OBJECTS = "cascadedObjects"; //$NON-NLS-1$
    private static final String KEY_CASCADED_COUNT = "cascadedCount"; //$NON-NLS-1$
    private static final String KEY_CASCADE_ATTRIBUTION = "cascadeAttribution"; //$NON-NLS-1$
    private static final String KEY_WARNING = "warning"; //$NON-NLS-1$

    /**
     * The source is resolved ({@link MetadataNodeResolver#resolveAddress}) EXACTLY, as adopt always
     * resolved it - without the yo retry the vendor-support guard and modify/delete resolve with.
     */
    private static final boolean YO_FALLBACK = false;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Add a base-configuration object or member to an extension for customization. Parameters and " //$NON-NLS-1$
            + "examples: get_tool_guide('adopt_metadata_object')."; //$NON-NLS-1$
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
            .booleanProperty("includeReferencedPictures", //$NON-NLS-1$
                "Adopt referenced CommonPicture dependencies; forms only, default false") //$NON-NLS-1$
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
            .booleanProperty(KEY_PERSISTED, //$NON-NLS-1$
                "Whether the platform accepted a save task for the change. The tool then waits for the " //$NON-NLS-1$
                    + "export queue of the EXTENSION project to drain before answering, so a success normally " //$NON-NLS-1$
                    + "means the write has already run - but that establishes the queue is empty, not that " //$NON-NLS-1$
                    + "the bytes are correct (a platform-side write failure is logged inside EDT), and the " //$NON-NLS-1$
                    + "wait is skipped where the export state cannot be observed", false) //$NON-NLS-1$
            .objectProperty("dependencies", "Form dependencies when includeReferencedPictures=true", JsonSchemaBuilder.object() //$NON-NLS-1$ //$NON-NLS-2$
                .objectProperty("commonPictures", "Referenced common pictures", JsonSchemaBuilder.object() //$NON-NLS-1$ //$NON-NLS-2$
                    .stringArrayProperty("found", "Canonical picture FQNs referenced by the form", true) //$NON-NLS-1$ //$NON-NLS-2$
                    .stringArrayProperty("adopted", "Picture FQNs newly adopted by this call", true) //$NON-NLS-1$ //$NON-NLS-2$
                    .stringArrayProperty("alreadyPresent", "Picture FQNs already present in the extension", true) //$NON-NLS-1$ //$NON-NLS-2$
                    .stringArrayProperty("unresolved", "Unreadable content or picture references that could not be adopted", true), //$NON-NLS-1$ //$NON-NLS-2$
                    true), false)
            .booleanProperty("formAdopted", "Whether the form is observed as adopted after a platform failure") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty(WriteScope.RESULT_MEMBER, WriteScope.OUTPUT_SCHEMA_DESCRIPTION)
            .stringArrayProperty(KEY_CASCADED_OBJECTS,
                "Additional metadata top objects added by EDT, excluding the requested object's implicit " //$NON-NLS-1$
                    + "owner/parent chain; sorted FQNs, capped at 100; absent when none were added") //$NON-NLS-1$
            .integerProperty(KEY_CASCADED_COUNT,
                "Total additional top objects added, including those beyond cascadedObjects' cap; absent when zero") //$NON-NLS-1$
            .enumProperty(KEY_CASCADE_ATTRIBUTION,
                "Present as 'unavailable' when adoption committed but its cascade could not be determined", //$NON-NLS-1$
                "unavailable") //$NON-NLS-1$
            .stringProperty(KEY_WARNING,
                "Post-commit cascade attribution warning; inspect the extension instead of retrying adoption") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE,
                "Present when the object was already adopted, and when the adopted copy carries no " //$NON-NLS-1$
                    + "extendedConfigurationObject link, so it is mapped to the base by name rather than by ID (it " //$NON-NLS-1$
                    + "says the likely reason and, where it can, how to change it)") //$NON-NLS-1$
            .build();
    }

    @Override
    protected String beforeUiThreadOrError(Map<String, String> params)
    {
        return ReferencedTopObjects.memberOperationRefusal(NAME,
            JsonUtils.extractStringArgument(params, "fqn")); //$NON-NLS-1$
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
        boolean includeReferencedPictures = JsonUtils.extractBooleanArgument(params, "includeReferencedPictures", false); //$NON-NLS-1$
        if (includeReferencedPictures && !isFormFqn(fqn))
        {
            return ToolResult.error("includeReferencedPictures=true requires a form FQN, got '" + fqn //$NON-NLS-1$
                + "'. Use CommonForm.Name or Type.Object.Form.Name, or omit includeReferencedPictures.").toJson(); //$NON-NLS-1$
        }
        String memberRefusal = ReferencedTopObjects.memberOperationRefusal(NAME, fqn);
        if (memberRefusal != null)
        {
            return ToolResult.error(memberRefusal).toJson();
        }

        ProjectContext ctx = resolveProjectAndConfig(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        String projectKindError = projectKindRefusal(ctx.scope, projectName);
        if (projectKindError != null)
        {
            return ToolResult.error(projectKindError).toJson();
        }

        String normFqn = MetadataTypeUtils.normalizeFqn(fqn);
        // Resolve the source - a subsystem by its chain, a form, any other top object or member -
        // through the one dispatch the vendor-support guard judges an address with, together with
        // the address every result below names it by (for a subsystem, its chain as list_subsystems
        // prints it).
        AdoptionSource source = readPreparation(ctx.project, () -> AdoptionSource.resolve(preparationScope(ctx), normFqn));
        if (source == null)
        {
            return ToolResult.error(sourceNotFound(normFqn)).toJson();
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
        if (isNonEmpty(extensionProjectName))
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

        if (!readPreparation(ctx.project, () -> adopter.isAdoptable(sourceInRead(ctx.project, source.fqn, source.object))))
        {
            return notAdoptableError(source);
        }

        String extName = target.getProject().getName();

        // The mapping note says what the COPY is - whether it carries the extendedConfigurationObject link to its base
        // object - and the root's flag and the platform version only name the likely reason (#706). The flag is read
        // before the adoption, as EDT's copier reads it during the adoption.
        IBmModel extensionModel = extensionModel(target);
        MappingEvidence mapping = new MappingEvidence(extName, extensionModel, platformVersion(target),
            extensionModel == null ? null : keepsMappingByIds(extensionModel));

        if (includeReferencedPictures)
        {
            return adoptForm(ctx, source, target, adopter, extensionModel, mapping);
        }

        if (readAdoptionLookup(target, () -> adopter.isAdopted(sourceInRead(target.getParentProject(), source.fqn, source.object), target)))
        {
            // A SUCCESS that changes nothing: adoptAndAttach is never called, so no export is
            // queued anywhere. Stated rather than left silent, because "queued nothing" and "did
            // not say" owe the barrier different answers.
            WriteScope.recordNothingQueued();
            // The existing copy's link was decided when it was adopted, under the flag and the version of that time.
            return alreadyAdoptedResult(source, extName,
                mapping.forEarlierCopy(adoptedCopy(adopter, source.object, source.fqn, target)));
        }

        // Vendor support (#642) does not apply: the base side is only read, and EDT reads support
        // only for a NATIVE configuration root - an extension's root is ADOPTED, so nothing here locks.

        if (extensionModel == null)
        {
            return ToolResult.error("Cannot inspect extension '" + extName //$NON-NLS-1$
                + "' before adoption. Wait for the project to finish loading and retry.").toJson(); //$NON-NLS-1$
        }
        AdoptionWrite write = adoptUnderWorkspaceRule(ctx.project.getWorkspace(), extensionModel,
            adopter, source.object, ctx.scope, source.fqn, target);
        Long adoptedId = write.copyId;
        if (adoptedId == null)
        {
            WriteScope.recordNothingQueued();
            return alreadyAdoptedResult(source, extName,
                mapping.forEarlierCopy(adoptedCopy(adopter, source.object, source.fqn, target)));
        }

        AdoptionChanges changes = write.changes;
        // The global-context commit also schedules EDT's cascade exports; explicitly submit every
        // added metadata TOP here so WriteScope/history and the export barrier share the full set.
        boolean persisted = changes.persistToDisk(target.getProject());

        // What the new copy IS, read back after the adoption - not what the flag and the version say it should be.
        return adoptedResult(source, extName, persisted, mapping.forNewCopy(adoptedId), changes);
    }

    /** Discover and preflight before entering the extension adoption write. */
    private static String adoptForm(ProjectContext ctx, AdoptionSource source, IExtensionProject target,
        IModelObjectAdopter adopter, IBmModel extensionModel, MappingEvidence mapping) throws Exception
    {
        String extName = target.getProject().getName();
        boolean alreadyAdopted = readAdoptionLookup(target, () -> adopter.isAdopted(sourceInRead(target.getParentProject(), source.fqn, source.object), target));
        EObject adopted = alreadyAdopted ? adoptedCopy(adopter, source.object, source.fqn, target) : null;
        Long adoptedId = null;
        List<String> found = List.of();
        Set<String> unresolved = new LinkedHashSet<>();
        List<String> alreadyPresent = new ArrayList<>();
        Map<String, EObject> missing = new LinkedHashMap<>();
        IBmModel baseModel = null;
        String refusal = null;
        try
        {
            if (!alreadyAdopted && extensionModel != null)
            {
                // Native name/scope lookup can miss an ID-mapped copy after an extension rename.
                alreadyAdopted = BmTransactions.read(extensionModel,
                    "adopt_metadata_object: locate adopted form", (tx, monitor) -> //$NON-NLS-1$
                        AdoptedFormLookup.find(tx, (BasicForm)source.object) != null);
            }
            if (!alreadyAdopted)
            {
                baseModel = requireBaseModel(ctx.project);
            }
            FormPictureDependencies.Result pictures = FormPictureDependencies.collectPersisted(
                alreadyAdopted ? extensionModel : baseModel, source.fqn, alreadyAdopted, (BasicForm)source.object);
            found = pictures.found;
            unresolved.addAll(pictures.unresolved);
        }
        catch (RuntimeException e)
        {
            unresolved.add("Form content: " + source.fqn + " (" + unwrapCauseMessage(e) + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        String baseModelError = null;
        if (baseModel == null && !found.isEmpty())
        {
            try
            {
                baseModel = requireBaseModel(ctx.project);
            }
            catch (RuntimeException e)
            {
                baseModelError = unwrapCauseMessage(e);
            }
        }
        for (String pictureFqn : found)
        {
            try
            {
                if (picturePresent(extensionModel, pictureFqn))
                {
                    alreadyPresent.add(pictureFqn);
                    continue;
                }
                // The base model was resolved once before the loop; use the shared picture resolver.
                if (baseModel == null)
                {
                    throw new IllegalStateException(baseModelError);
                }
                MdObject picture = BmTransactions.read(baseModel,
                    "adopt_metadata_object: resolve form picture", //$NON-NLS-1$
                    (tx, monitor) -> {
                        var foundNode = MetadataNodeResolver.resolveExisting(preparationScope(ctx), pictureFqn);
                        return foundNode == null ? null : HostKind.current() == HostKind.HEADLESS
                            ? MetadataNodeResolver.detachedIdentity(foundNode.object) : foundNode.object;
                    });
                if (picture == null)
                {
                    unresolved.add(pictureFqn);
                    refusal = "Form picture '" + pictureFqn //$NON-NLS-1$
                        + "' was not found in the base configuration. Fix the reference using " //$NON-NLS-1$
                        + "list_common_pictures and modify_metadata, then retry. No objects were adopted."; //$NON-NLS-1$
                }
                else if (!BmTransactions.read(baseModel, "adopt_metadata_object: preflight picture", //$NON-NLS-1$
                    (tx, monitor) -> adopter.isAdoptable(sourceInRead(ctx.project, pictureFqn, picture))))
                {
                    // EDT's isAdopted requires an adoptable source.
                    unresolved.add(pictureFqn);
                    refusal = "Form picture '" + pictureFqn //$NON-NLS-1$
                        + "' cannot be adopted into extension '" + extName //$NON-NLS-1$
                        + "'. Choose an adoptable picture using list_common_pictures and update the form " //$NON-NLS-1$
                        + "with modify_metadata, then retry. No objects were adopted."; //$NON-NLS-1$
                }
                else if (extensionModel != null && isAdoptedInModel(extensionModel, adopter, picture, pictureFqn, target))
                {
                    alreadyPresent.add(pictureFqn);
                }
                else
                {
                    missing.put(pictureFqn, picture);
                }
            }
            catch (RuntimeException e)
            {
                unresolved.add(pictureFqn);
                refusal = "Cannot read form picture '" + pictureFqn //$NON-NLS-1$
                    + "'. Inspect the reference with get_metadata_details or reload the project with " //$NON-NLS-1$
                    + "clean_project, then retry adopt_metadata_object. No objects were adopted. Details: " //$NON-NLS-1$
                    + unwrapCauseMessage(e);
            }
        }
        if (!unresolved.isEmpty())
        {
            WriteScope.recordNothingQueued();
            boolean unreadableContent = unresolved.stream().anyMatch(value -> value.startsWith("Form content:")); //$NON-NLS-1$
            String message = unreadableContent
                ? "Cannot read form content for '" + source.fqn + "': " + unresolved //$NON-NLS-1$ //$NON-NLS-2$
                    + ". Inspect the form with get_metadata_details or reload the project with clean_project, " //$NON-NLS-1$
                    + "then retry adopt_metadata_object. No objects were adopted." //$NON-NLS-1$
                : refusal != null ? refusal : "Cannot adopt '" + source.fqn //$NON-NLS-1$
                    + "': unresolved form picture references " + unresolved //$NON-NLS-1$
                    + ". Fix the form references using list_common_pictures and modify_metadata, then retry. " //$NON-NLS-1$
                    + "No objects were adopted."; //$NON-NLS-1$
            return withPictures(ToolResult.error(message), found, List.of(), alreadyPresent, unresolved);
        }

        if (alreadyAdopted && missing.isEmpty())
        {
            WriteScope.recordNothingQueued();
            return withPictures(alreadyAdoptedPayload(source, extName, mapping.forEarlierCopy(adopted)),
                found, List.of(), alreadyPresent, unresolved);
        }
        if (extensionModel == null)
        {
            WriteScope.recordNothingQueued();
            unresolved.addAll(missing.keySet());
            return withPictures(ToolResult.error("Cannot inspect extension '" + extName //$NON-NLS-1$
                + "'. Wait for the project to finish loading and retry adopt_metadata_object."), //$NON-NLS-1$
                found, List.of(), alreadyPresent, unresolved);
        }

        Set<String> dirty = new LinkedHashSet<>();
        Set<String> cascaded = new LinkedHashSet<>();
        List<String> adoptedPictures = new ArrayList<>();
        String warning = null;
        boolean persisted = true;
        boolean changed = false;
        try
        {
            // Commit each picture before the form's native copier resolves its dependencies.
            List<String> sources = new ArrayList<>(missing.keySet());
            if (!alreadyAdopted)
            {
                sources.add(source.fqn);
            }
            for (String candidate : sources)
            {
                EObject original = candidate.equals(source.fqn) ? source.object : missing.get(candidate);
                AdoptionWrite write = adoptUnderWorkspaceRule(ctx.project.getWorkspace(), extensionModel,
                    adopter, original, ctx.scope, candidate, target);
                if (write.copyId == null)
                {
                    continue;
                }
                changed = true;
                if (candidate.equals(source.fqn))
                {
                    adoptedId = write.copyId;
                }
                dirty.addAll(write.changes.dirty);
                cascaded.addAll(write.changes.cascaded);
                if (write.changes.warning != null)
                {
                    warning = write.changes.warning;
                }
                persisted = write.changes.persistToDisk(target.getProject()) && persisted;
            }
            for (Map.Entry<String, EObject> picture : missing.entrySet())
            {
                if (dirty.contains(picture.getKey()))
                {
                    adoptedPictures.add(picture.getKey());
                }
                else if (picturePresent(extensionModel, picture.getKey())
                    || isAdoptedInModel(extensionModel, adopter, picture.getValue(), picture.getKey(), target))
                {
                    alreadyPresent.add(picture.getKey());
                }
                else
                {
                    unresolved.add(picture.getKey());
                }
            }
            if (adopted == null && adoptedId == null)
            {
                adopted = adoptedCopy(adopter, source.object, source.fqn, target);
            }
            if (!changed)
            {
                WriteScope.recordNothingQueued();
            }
            ToolResult result;
            if (!unresolved.isEmpty() || !alreadyAdopted && adoptedId == null && adopted == null)
            {
                result = ToolResult.error("Adoption incomplete for '" + source.fqn //$NON-NLS-1$
                    + "'. Inspect the extension with get_metadata_details and fix unresolved references " //$NON-NLS-1$
                    + "using list_common_pictures before retrying adopt_metadata_object."); //$NON-NLS-1$
                result.put("fqn", source.fqn).put(KEY_EXTENSION_PROJECT, extName).put(KEY_PERSISTED, persisted); //$NON-NLS-1$
            }
            else
            {
                result = alreadyAdopted || !changed
                    ? alreadyAdoptedPayload(source, extName, mapping.forEarlierCopy(adopted))
                    : adoptedPayload(source, extName, persisted, mapping.forNewCopy(adoptedId));
                result.put(KEY_PERSISTED, persisted);
            }
            List<String> cascadeFqns = cascaded.stream().sorted().collect(Collectors.toList());
            putCascade(result, new AdoptionChanges(new ArrayList<>(dirty), cascadeFqns, warning));
            return withPictures(result, found, adoptedPictures, alreadyPresent, unresolved);
        }
        catch (Exception e)
        {
            return failedFormAdoption(source, target, adopter, extensionModel, alreadyAdopted,
                found, alreadyPresent, missing, dirty, persisted, adoptedId != null, e);
        }
    }

    private static String failedFormAdoption(AdoptionSource source, IExtensionProject target,
        IModelObjectAdopter adopter, IBmModel model, boolean wasAdopted, List<String> found,
        List<String> alreadyPresent, Map<String, EObject> missing, Set<String> completedDirty,
        boolean completedPersisted, boolean formCommitted, Exception failure)
    {
        List<String> adoptedPictures = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();
        Set<String> dirty = new LinkedHashSet<>();
        Boolean formAdopted = wasAdopted || formCommitted ? Boolean.TRUE : null;
        if (formAdopted == null)
        {
            try
            {
                formAdopted = isAdoptedInModel(model, adopter, source.object, source.fqn, target);
                if (formAdopted)
                {
                    dirty.addAll(dirtyCopyFqns(model, adoptedCopy(adopter, source.object, source.fqn, target)));
                }
            }
            catch (RuntimeException e)
            {
                // Continue checking independent pictures when the form's top cannot be read.
            }
        }
        for (Map.Entry<String, EObject> picture : missing.entrySet())
        {
            if (alreadyPresent.contains(picture.getKey()))
            {
                continue;
            }
            if (completedDirty.contains(picture.getKey()))
            {
                adoptedPictures.add(picture.getKey());
                continue;
            }
            try
            {
                if (isAdoptedInModel(model, adopter, picture.getValue(), picture.getKey(), target))
                {
                    adoptedPictures.add(picture.getKey());
                    dirty.add(picture.getKey());
                }
                else
                {
                    unresolved.add(picture.getKey());
                }
            }
            catch (RuntimeException e)
            {
                unresolved.add(picture.getKey());
            }
        }
        dirty.remove("Configuration"); //$NON-NLS-1$
        boolean persisted = !completedDirty.isEmpty() && completedPersisted;
        if (!dirty.isEmpty())
        {
            dirty.add("Configuration"); //$NON-NLS-1$
            WriteScope.recordWrite(target.getProject());
            persisted = BmTransactions.forceExportToDisk(target.getProject(), new ArrayList<>(dirty))
                && (completedDirty.isEmpty() || completedPersisted);
        }
        String detail = String.valueOf(unwrapCauseMessage(failure));
        ToolResult result = ToolResult.error("Adoption failed for '" + source.fqn + "': " //$NON-NLS-1$ //$NON-NLS-2$
            + detail + (detail.endsWith(".") ? " " : ". ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "Inspect the extension with get_metadata_objects before retrying; " //$NON-NLS-1$
            + "completed dependencies are reported below and cascade attribution is unavailable.") //$NON-NLS-1$
            .put("fqn", source.fqn).put(KEY_EXTENSION_PROJECT, target.getProject().getName()) //$NON-NLS-1$
            .put(KEY_PERSISTED, persisted).put(KEY_CASCADE_ATTRIBUTION, "unavailable"); //$NON-NLS-1$
        if (formAdopted != null)
        {
            result.put("formAdopted", formAdopted); //$NON-NLS-1$
        }
        return withPictures(result, found, adoptedPictures, alreadyPresent, unresolved);
    }

    private static boolean isAdoptedInModel(IBmModel model, IModelObjectAdopter adopter, EObject source,
        String sourceFqn, IExtensionProject target)
    {
        IProject base = HostKind.current() == HostKind.HEADLESS ? target.getParentProject() : null;
        return readPreparation(base, () -> BmTransactions.read(model,
            "adopt_metadata_object: inspect adoption", (tx, monitor) -> adopter.isAdopted(sourceInRead(target.getParentProject(), sourceFqn, source), target))); //$NON-NLS-1$
    }

    private static List<String> dirtyCopyFqns(IBmModel model, EObject copy)
    {
        return BmTransactions.read(model, "adopt_metadata_object: inspect completed adoption", (tx, monitor) -> { //$NON-NLS-1$
            URI uri = copy != null && copy.eIsProxy() ? ((InternalEObject)copy).eProxyURI() : null;
            EObject inTx = uri != null ? (EObject)tx.getObjectByUri(uri)
                : copy instanceof IBmObject ? (EObject)tx.getObjectById(((IBmObject)copy).bmGetId()) : null;
            IBmObject root = tx.getTopObjectByFqn("Configuration"); //$NON-NLS-1$
            return dirtyFqns(inTx, root instanceof Configuration ? (Configuration)root : null);
        });
    }

    /** Validate the address before any project/model work; type tokens are bilingual. */
    static boolean isFormFqn(String fqn)
    {
        String normalized = MetadataTypeUtils.normalizeFqn(fqn);
        String[] parts = normalized == null ? new String[0] : normalized.split("\\.", -1); //$NON-NLS-1$
        return parts.length == 2 && "CommonForm".equals(parts[0]) && !parts[1].isEmpty() //$NON-NLS-1$
            || parts.length == 4 && MetadataTypeUtils.toEnglishSingular(parts[0]) != null
                && MetadataTypeUtils.resolveNestedKind(parts[2]) != null
                && "Form".equals(MetadataTypeUtils.resolveNestedKind(parts[2]).getEnglish()) //$NON-NLS-1$
                && MetadataTypeUtils.typeCanContain(parts[0], "forms") //$NON-NLS-1$
                && !parts[0].isEmpty()
                && !parts[1].isEmpty() && !parts[3].isEmpty();
    }

    /** Resolve presence in the target before looking for a base counterpart to adopt. */
    private static boolean picturePresent(IBmModel extensionModel, String fqn)
    {
        return extensionModel != null && BmTransactions.read(extensionModel,
            "adopt_metadata_object: check existing form picture", (tx, monitor) -> { //$NON-NLS-1$
                IBmObject root = tx.getTopObjectByFqn("Configuration"); //$NON-NLS-1$
                return root instanceof Configuration
                    && MetadataNodeResolver.resolveExisting((Configuration)root, fqn) != null;
            });
    }

    private static IBmModel requireBaseModel(IProject project)
    {
        BmModelResolver.Resolution model = BmModelResolver.resolve(project);
        if (!model.isAvailable())
        {
            throw new IllegalStateException(model.actionableError(NAME, "No objects were adopted.")); //$NON-NLS-1$
        }
        return model.getModel();
    }

    private static String withPictures(ToolResult result, List<String> found, List<String> adopted,
        List<String> alreadyPresent, Collection<String> unresolved)
    {
        return result.put("dependencies", Map.of("commonPictures", Map.of( //$NON-NLS-1$ //$NON-NLS-2$
            "found", found, "adopted", adopted, //$NON-NLS-1$ //$NON-NLS-2$
            "alreadyPresent", alreadyPresent, "unresolved", List.copyOf(unresolved)))).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static ToolResult alreadyAdoptedPayload(AdoptionSource source, String extName, String mappingNote)
    {
        String message = "'" + source.fqn + "' is already adopted in extension '" + extName + "'."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return ToolResult.success()
            .put(McpKeys.ACTION, "alreadyAdopted") //$NON-NLS-1$
            .put("fqn", source.fqn) //$NON-NLS-1$
            .put(KEY_EXTENSION_PROJECT, extName)
            .put(KEY_OBJECT_BELONGING, "ADOPTED") //$NON-NLS-1$
            .put(KEY_PERSISTED, true)
            .put(McpKeys.MESSAGE, mappingNote == null ? message : message + " " + mappingNote); //$NON-NLS-1$
    }

    private static ToolResult adoptedPayload(AdoptionSource source, String extName, boolean persisted, String mappingNote)
    {
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, "adopted") //$NON-NLS-1$
            .put("fqn", source.fqn) //$NON-NLS-1$
            .put(KEY_EXTENSION_PROJECT, extName)
            .put(KEY_OBJECT_BELONGING, "ADOPTED") //$NON-NLS-1$
            .put(KEY_PERSISTED, persisted);
        if (mappingNote != null)
        {
            result.put(McpKeys.MESSAGE, mappingNote);
        }
        return result;
    }

    /**
     * What a call's mapping note is judged with besides the copy: the extension's model, where the copy is read
     * ({@link AdoptMetadataObjectTool#carriesBaseLink}), the platform version and the root's flag, read before the
     * adoption as EDT's copier reads it. The two paths differ only in the copy's history. Package-visible for tests.
     */
    static final class MappingEvidence
    {
        private final String extName;
        private final IBmModel extensionModel;
        private final Version version;
        private final Boolean keepsMappingByIds;

        /**
         * @param extName the extension project's name
         * @param extensionModel the extension project's model, or {@code null} when it is not available
         * @param version the extension's platform version, or {@code null}
         * @param keepsMappingByIds the root's flag ({@link AdoptMetadataObjectTool#keepsMappingByIds}), or {@code null}
         *     when it could not be read
         */
        MappingEvidence(String extName, IBmModel extensionModel, Version version, Boolean keepsMappingByIds)
        {
            this.extName = extName;
            this.extensionModel = extensionModel;
            this.version = version;
            this.keepsMappingByIds = keepsMappingByIds;
        }

        /**
         * @param copy the copy that was already there ('alreadyAdopted'), or {@code null}
         * @return its note, or {@code null}
         */
        String forEarlierCopy(EObject copy)
        {
            return mappingNote(extName, extensionModel, copy, version, keepsMappingByIds, true);
        }

        /**
         * @param copyId the stored identity captured during this call's adoption, or {@code null}
         * @return its note, or {@code null}
         */
        String forNewCopy(Long copyId)
        {
            Boolean linked = copyId == null ? null : readCopyLink(extensionModel, tx -> tx.getObjectById(copyId));
            return byNameMappingNote(extName, linked, version, keepsMappingByIds, false);
        }
    }

    /**
     * The refusal for a source the platform reports as not adoptable, naming it the way every result
     * of the call does. Package-visible for tests.
     *
     * @param source the resolved source
     * @return the error JSON
     */
    static String notAdoptableError(AdoptionSource source)
    {
        return ToolResult.error("'" + source.fqn + "' cannot be adopted into an extension " //$NON-NLS-1$ //$NON-NLS-2$
            + "(the platform reports it is not adoptable).").toJson(); //$NON-NLS-1$
    }

    /**
     * The success of an adoption that was not needed: the source is adopted in {@code extName}
     * already. The copy is named by the source's address - it keeps the source's Name; {@code bmGetFqn()}
     * of an adopted object is never asked, because for a MEMBER (a form, an attribute) it is not a top
     * object and throws ("may be called on top objects only"). A mapping note is appended to the message,
     * never put in its place. Package-visible for tests.
     *
     * @param source the resolved source
     * @param extName the extension project's name
     * @param mappingNote the {@link #byNameMappingNote}, or {@code null}
     * @return the success JSON
     */
    static String alreadyAdoptedResult(AdoptionSource source, String extName, String mappingNote)
    {
        return alreadyAdoptedPayload(source, extName, mappingNote).toJson();
    }

    /**
     * The success of an adoption that ran, naming the adopted copy by the source's address for the
     * reason given at {@link #alreadyAdoptedResult}. Package-visible for tests.
     *
     * @param source the resolved source
     * @param extName the extension project's name
     * @param persisted whether the platform accepted a save task for the change
     * @param mappingNote the {@link #byNameMappingNote}, or {@code null} for a copy mapped by ID
     * @return the success JSON
     */
    static String adoptedResult(AdoptionSource source, String extName, boolean persisted, String mappingNote)
    {
        return adoptedResult(source, extName, persisted, mappingNote, Collections.emptyList());
    }

    /** Same result with the measured additional top objects; no new fields for an ordinary adoption. */
    static String adoptedResult(AdoptionSource source, String extName, boolean persisted, String mappingNote,
        List<String> cascaded)
    {
        return adoptedResult(source, extName, persisted, mappingNote,
            new AdoptionChanges(Collections.emptyList(), cascaded));
    }

    /** A committed write stays successful even when its additional scope could not be read. */
    static String adoptedResult(AdoptionSource source, String extName, boolean persisted, String mappingNote,
        AdoptionChanges changes)
    {
        ToolResult result = adoptedPayload(source, extName, persisted, mappingNote);
        putCascade(result, changes);
        return result.toJson();
    }

    private static void putCascade(ToolResult result, AdoptionChanges changes)
    {
        if (changes.warning != null)
        {
            result.put(KEY_CASCADE_ATTRIBUTION, "unavailable").put(KEY_WARNING, changes.warning); //$NON-NLS-1$
        }
        else if (!changes.cascaded.isEmpty())
        {
            result.put(KEY_CASCADED_OBJECTS, changes.cascaded.subList(0,
                Math.min(changes.cascaded.size(), Pagination.DEFAULT_LIMIT)))
                .put(KEY_CASCADED_COUNT, changes.cascaded.size());
        }
    }

    /**
     * Why an adopted copy is mapped to its base object by NAME, or {@code null} when it is mapped by ID - or when that
     * cannot be told. The COPY decides, not the root's flag: EDT links an adopted object to its base object by ID only
     * through the {@code extendedConfigurationObject} link its copier writes at adoption, and only when, at that
     * moment, the platform version is above 8.3.14 ({@link ExtensionRootBuilder#versionMapsByNameOnly}) and the
     * extension ROOT keeps mapping by IDs; nothing in EDT adds the link later, and EDT's own rename of a base object
     * carries along exactly the counterpart whose link names it ({@code MdRefactoringService.getAdoptedCounterpart}).
     * So a copy with the link gets no note whatever the root's flag says now, a copy without one gets the note
     * whatever it says now, and a copy that could not be read gets none - an unknown fact is never blamed (what counts
     * as the link, and what that leaves out, is at {@link #carriesBaseLink}). The flag and the version only name the
     * reason: for a copy adopted by this call they are the ones its adoption ran under, for an earlier copy they are
     * read now, so they are named as the likely reason, and when neither holds the copy is said to have most likely
     * been adopted while the extension mapped by name - inferred, not known: such a copy also comes from an XML import
     * of an extension exported without IDs, or from a platform version raised after the adoption. A change is advised
     * only for a reason that has one: turning the flag on (or recreating the extension) only when the version does not
     * already decide, since a recreated extension keeps its base's version; an earlier copy with neither reason gets no
     * advice, as EDT adds no link to an existing copy. Package-visible for tests.
     *
     * @param extName the extension project's name
     * @param copyLinked whether the adopted copy carries the link, or {@code null} when the copy could not be read
     * @param version the extension's platform version - the one EDT adopts with - or {@code null}
     * @param keepsMappingByIds the root's {@code keepMappingToExtendedConfigurationObjectsByIDs}, or
     *     {@code null} when it could not be read
     * @param adoptedEarlier {@code true} for a copy that was already there ('alreadyAdopted'), {@code false} for one
     *     this call adopted
     * @return the note, or {@code null}
     */
    static String byNameMappingNote(String extName, Boolean copyLinked, Version version, Boolean keepsMappingByIds, // NOSONAR the copy's fact, the two reasons and the path, one each
        boolean adoptedEarlier)
    {
        if (!Boolean.FALSE.equals(copyLinked))
        {
            return null;
        }
        boolean flagOff = Boolean.FALSE.equals(keepsMappingByIds);
        boolean versionTooOld = ExtensionRootBuilder.versionMapsByNameOnly(version);
        List<String> reasons = new ArrayList<>(2);
        if (flagOff)
        {
            reasons.add("the root's keepMappingToExtendedConfigurationObjectsByIDs is false"); //$NON-NLS-1$
        }
        if (versionTooOld)
        {
            reasons.add("the extension's platform version is " + version); //$NON-NLS-1$
        }
        StringBuilder note = new StringBuilder("The adopted copy in extension '").append(extName) //$NON-NLS-1$
            .append("' is mapped to the base by NAME, not by ID: it carries no extendedConfigurationObject link, ") //$NON-NLS-1$
            .append("which EDT writes only when, at adoption, the extension root keeps mapping by IDs ") //$NON-NLS-1$
            .append("(keepMappingToExtendedConfigurationObjectsByIDs) and the platform version is above 8.3.14"); //$NON-NLS-1$
        if (!reasons.isEmpty())
        {
            String why = String.join(" and ", reasons); //$NON-NLS-1$
            note.append(adoptedEarlier ? "; it was adopted earlier, and now " + why + " - the likely reason" //$NON-NLS-1$ //$NON-NLS-2$
                : ", and here " + why); //$NON-NLS-1$
        }
        else if (adoptedEarlier)
        {
            // Inferred, never stated as a fact: only the copy, the flag and the version NOW are known, and a copy
            // without a link also comes from an XML import of an extension exported without IDs, or from a version
            // raised later.
            note.append("; it was most likely adopted while the extension mapped by name, and EDT does not add the ") //$NON-NLS-1$
                .append("link afterwards, so objects adopted before stay mapped by name"); //$NON-NLS-1$
        }
        note.append(". Renaming the base object or the copy breaks such a mapping."); //$NON-NLS-1$
        if (versionTooOld)
        {
            note.append(" Mapping by ID needs a platform version above 8.3.14."); //$NON-NLS-1$
        }
        else if (flagOff)
        {
            // Only where the version does not already decide: an extension recreated over the same base keeps the
            // base's version, and at 8.3.14 or lower EDT maps by name whatever the flag says.
            note.append(" To map by ID, turn keepMappingToExtendedConfigurationObjectsByIDs on in EDT, or ") //$NON-NLS-1$
                .append("recreate the extension with create_project, which sets it; objects adopted before ") //$NON-NLS-1$
                .append("that stay mapped by name."); //$NON-NLS-1$
        }
        return note.toString();
    }

    /**
     * The {@link #byNameMappingNote} for the adopted copy {@code copy} in the extension whose model is
     * {@code extensionModel}: whether the copy carries the link, read by {@link #carriesBaseLink}, with the root's
     * flag and the platform version as the reason. Package-visible for tests.
     *
     * @param extName the extension project's name
     * @param extensionModel the extension project's model, or {@code null} when it is not available
     * @param copy the adopted copy - the object this call adopted, or the one already there - or {@code null}
     * @param version the extension's platform version, or {@code null}
     * @param keepsMappingByIds the root's flag ({@link #keepsMappingByIds}), or {@code null} when it could not be read
     * @param adoptedEarlier whether the copy was already there
     * @return the note, or {@code null}
     */
    static String mappingNote(String extName, IBmModel extensionModel, EObject copy, Version version, // NOSONAR the copy, where to read it, the two reasons and the path
        Boolean keepsMappingByIds, boolean adoptedEarlier)
    {
        return byNameMappingNote(extName, carriesBaseLink(extensionModel, copy), version, keepsMappingByIds,
            adoptedEarlier);
    }

    /**
     * Whether the adopted copy carries the {@code extendedConfigurationObject} link to its base object - the
     * {@code .mdo} attribute of that name, {@code MdObject.extendedConfigurationObject} - read in a READ transaction
     * of the extension's model, the copy re-resolved there: by its BM id, or by its URI when it is a proxy (EDT's
     * adopter can name an existing copy by a global-scope entry, {@code IEObjectDescription.getEObjectOrProxy}, whose id
     * means nothing). {@code null} when that cannot be told: no model, no BM object, nothing there, or a read that
     * failed. Package-visible for tests.
     * <p>
     * The link counts as soon as it is set: EDT's adopter and its XML import always set it together with the copy's
     * own state for it ({@code MdObjectExtension.extendedConfigurationObject}) Checked. EDT's readers of the link ask
     * more, and not the same: its XML export writes the link only while that state is Checked or Notify, and its
     * model updater follows a copy found by its base object's UUID only while that state is set and not None - but
     * its rename of a base object carries along the counterpart whose link equals the base object's UUID, whatever
     * that state ({@code MdRefactoringService}). So two kinds of copy read as linked here are mapped by name by some of
     * EDT: one whose link is left in the state None, and one whose link names a base object that is gone (a recreated
     * base object; EDT's validator reports it while the root keeps mapping by IDs). EDT's readers disagree on both, so
     * neither is told apart here.
     *
     * @param extensionModel the extension project's model, or {@code null}
     * @param copy the adopted copy, or {@code null}
     * @return the answer, or {@code null}
     */
    static Boolean carriesBaseLink(IBmModel extensionModel, EObject copy)
    {
        if (extensionModel == null || !(copy instanceof IBmObject))
        {
            return null; // NOSONAR tri-state: null means "could not be read"
        }
        return readCopyLink(extensionModel, tx -> {
            URI proxyUri = copy.eIsProxy() ? ((InternalEObject)copy).eProxyURI() : null;
            return proxyUri != null ? tx.getObjectByUri(proxyUri) : tx.getObjectById(((IBmObject)copy).bmGetId());
        });
    }

    private static Boolean readCopyLink(IBmModel model, Function<IBmTransaction, IBmObject> resolve)
    {
        if (model == null)
        {
            return null;
        }
        try
        {
            return BmTransactions.read(model, "adopt_metadata_object: read the copy's link", //$NON-NLS-1$
                (tx, monitor) -> {
                    IBmObject inTx = resolve.apply(tx);
                    return inTx instanceof MdObject
                        ? Boolean.valueOf(((MdObject)inTx).getExtendedConfigurationObject() != null)
                        : null;
                });
        }
        catch (RuntimeException e)
        {
            // The note is informational: a failed read claims nothing either way.
            Activator.logInfo("adopt_metadata_object: could not read the adopted copy's link: " + e); //$NON-NLS-1$
            return null; // NOSONAR tri-state: null means "could not be read"
        }
    }

    /**
     * The copy of {@code source} already adopted into {@code target}, as EDT's adopter finds it (the lookup its
     * {@code isAdopted} answers with), or {@code null} when it names none or the lookup fails.
     */
    private static EObject adoptedCopy(IModelObjectAdopter adopter, EObject source, String sourceFqn, IExtensionProject target)
    {
        try
        {
            return readAdoptionLookup(target, () -> {
                EObject copy = adopter.getAdopted(sourceInRead(target.getParentProject(), sourceFqn, source), target);
                if (copy == null || HostKind.current() != HostKind.HEADLESS) return copy;
                EObject proxy = EcoreUtil.create(copy.eClass());
                ((InternalEObject)proxy).eSetProxyURI(EcoreUtil.getURI(copy));
                return proxy;
            });
        }
        catch (RuntimeException e)
        {
            Activator.logInfo("adopt_metadata_object: could not find the adopted copy: " + e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * The extension root's {@code keepMappingToExtendedConfigurationObjectsByIDs}, read in a READ transaction of
     * the extension's model, or {@code null} when it cannot be read: the model holds no configuration root, or
     * the read failed. Package-visible for tests.
     *
     * @param extensionModel the extension project's model
     * @return the flag, or {@code null}
     */
    static Boolean keepsMappingByIds(IBmModel extensionModel)
    {
        try
        {
            return BmTransactions.read(extensionModel, "adopt_metadata_object: read the mapping mode", //$NON-NLS-1$
                (tx, monitor) -> {
                    IBmObject root = tx.getTopObjectByFqn("Configuration"); //$NON-NLS-1$
                    return root instanceof Configuration
                        ? Boolean.valueOf(((Configuration)root).isKeepMappingToExtendedConfigurationObjectsByIDs())
                        : null;
                });
        }
        catch (RuntimeException e)
        {
            // The note is informational: a failed read claims nothing either way.
            Activator.logInfo("adopt_metadata_object: could not read the extension root's mapping mode: " + e); //$NON-NLS-1$
            return null; // NOSONAR tri-state: null means "could not be read"
        }
    }

    /** The extension project's model, or {@code null} when it is not available. */
    private static IBmModel extensionModel(IExtensionProject target)
    {
        try
        {
            BmModelResolver.Resolution model = BmModelResolver.resolve(target.getProject());
            return model.isAvailable() ? model.getModel() : null;
        }
        catch (RuntimeException e)
        {
            Activator.logInfo("adopt_metadata_object: the model of " + target.getProject().getName() //$NON-NLS-1$
                + " is not available: " + e); //$NON-NLS-1$
            return null;
        }
    }

    /** The extension's platform version - the one EDT's adopter adopts with - or {@code null}. */
    private static Version platformVersion(IExtensionProject target)
    {
        try
        {
            return target.getVersion();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * The refusal for a project nothing is adopted FROM: an external-objects project. An extension
     * extends only a configuration, so no extension adopts from such a project - and its scope holds
     * its own external objects only, so a base object's valid FQN would resolve to nothing there and
     * the caller would be told to fix the address instead of the project. Hence refused by its KIND,
     * before any address is resolved. Package-visible for tests.
     *
     * @param scope the project's resolution root
     * @param projectName the project name as the caller passed it
     * @return the refusal message, or {@code null} for a configuration project
     */
    static String projectKindRefusal(MetadataScope scope, String projectName)
    {
        if (scope == null || !scope.isExternalObjects())
        {
            return null;
        }
        return "Project '" + projectName + "' is an EXTERNAL-OBJECTS project, and an extension extends " //$NON-NLS-1$ //$NON-NLS-2$
            + "only a configuration: pass the BASE configuration as projectName (adopt_metadata_object " //$NON-NLS-1$
            + "adopts an object of that configuration into one of its extensions)."; //$NON-NLS-1$
    }

    /**
     * The refusal for an FQN that resolves to nothing. An address that starts with a subsystem token
     * also learns how subsystems are addressed, in the sentence the subsystem refusals share
     * ({@link SubsystemUtils#addressingHint()}): its bare {@code Subsystem.<Child>} names only a
     * top-level subsystem, so that spelling misses a nested one. Package-visible for tests.
     *
     * @param normFqn the normalized FQN that resolved to nothing
     * @return the error message
     */
    static String sourceNotFound(String normFqn)
    {
        String message = "Object not found: " + normFqn + ". " //$NON-NLS-1$ //$NON-NLS-2$
            + "Check the FQN: 'Type.Name' for a top object (e.g. 'Catalog.Products'), " //$NON-NLS-1$
            + "'Type.Name.Kind.Name' for a member (e.g. 'Catalog.Products.Attribute.Weight'), " //$NON-NLS-1$
            + "'Type.Name.Form.FormName' for a form (e.g. 'Catalog.Products.Form.ItemForm')."; //$NON-NLS-1$
        int dot = normFqn == null ? -1 : normFqn.indexOf('.');
        if (dot > 0 && SubsystemUtils.isSubsystemTypeToken(normFqn.substring(0, dot)))
        {
            message += " " + SubsystemUtils.addressingHint() + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return message;
    }

    static AdoptionWrite adoptUnderWorkspaceRule(IWorkspace workspace, IBmModel model,
        IModelObjectAdopter adopter, EObject original, MetadataScope sourceScope, String sourceFqn,
        IExtensionProject target) throws CoreException
    {
        return WorkspaceModelWrite.run(workspace, model, NAME, () -> adoptWithChanges(model, adopter,
            () -> sourceForWrite(original, sourceScope, sourceFqn), target));
    }

    static EObject sourceForWrite(EObject original, MetadataScope scope, String fqn)
    {
        return HostKind.current() == HostKind.HEADLESS
            ? scope.project() == null ? MetadataNodeResolver.resolveAddress(scope, fqn, YO_FALLBACK)
                : sourceInRead(scope.project(), fqn, original) : original;
    }

    private static EObject sourceInRead(IProject base, String fqn, EObject windowed)
    {
        if (HostKind.current() != HostKind.HEADLESS || base == null) return windowed;
        var context = com.ditrix.edt.mcp.server.utils.ProjectContext.of(base.getName()).resolveMetadataRootInTransaction();
        return context.ok() ? MetadataNodeResolver.resolveAddress(context.scope(), fqn, YO_FALLBACK) : null;
    }

    static <T> T readAdoptionLookup(IExtensionProject target, java.util.function.Supplier<T> lookup)
    {
        IProject base = HostKind.current() == HostKind.HEADLESS ? target.getParentProject() : null;
        return readPreparation(base, () -> readPreparation(target.getProject(), lookup));
    }

    /** Adopt in one global write and attribute only the objects attached by that task's commit event. */
    static AdoptionWrite adoptWithChanges(IBmModel model, IModelObjectAdopter adopter, EObject source,
        IExtensionProject target)
    {
        return adoptWithChanges(model, adopter, () -> source, target);
    }

    private static AdoptionWrite adoptWithChanges(IBmModel model, IModelObjectAdopter adopter,
        java.util.function.Supplier<EObject> sourceResolver, IExtensionProject target)
    {
        AtomicReference<Set<Long>> associatedIds = new AtomicReference<>();
        List<String> requested = new ArrayList<>();
        Long adoptedId = BmTransactions.writeInGlobalContext(model, "adopt_metadata_object", (tx, monitor) -> { //$NON-NLS-1$
            requested.clear(); // A deadlock retry executes the task again.
            EObject source = sourceResolver.get();
            if (source == null) throw com.ditrix.edt.mcp.server.utils.Refusals.state("Adoption source disappeared. Re-read get_metadata_details and retry.");
            IBmModel adoptingModel = target.getAdapter(IBmModel.class);
            if (adoptingModel == null || adoptingModel.getEngine().getCurrentTransaction() != tx)
            {
                throw new IllegalStateException("EDT's adopter cannot join the extension write transaction. " //$NON-NLS-1$
                    + "Wait for the project to finish loading and retry adoption."); //$NON-NLS-1$
            }
            IBmObject root = tx.getTopObjectByFqn("Configuration"); //$NON-NLS-1$
            if (!(root instanceof Configuration))
            {
                throw new IllegalStateException("Extension configuration is unavailable; inspect the extension " //$NON-NLS-1$
                    + "with get_metadata_objects before retrying adoption."); //$NON-NLS-1$
            }
            List<EObject> copies;
            WriteScope.recordUndeterminable("model-object adopter may mutate before throwing", //$NON-NLS-1$
                Collections.singletonList(target.getProject().getName()));
            try
            {
                copies = adopter.adoptAndAttach(Collections.singletonList(source), target, new NullProgressMonitor());
            }
            catch (CoreException e)
            {
                throw new IllegalStateException("Adoption failed; inspect the extension with get_metadata_objects " //$NON-NLS-1$
                    + "before retrying: " + e.getMessage(), e); //$NON-NLS-1$
            }
            if (copies.isEmpty())
            {
                return null; // Another writer adopted the source before this transaction started.
            }
            EObject copy = copies.get(0);
            EObject inTx = copy instanceof IBmObject && ((IBmObject)copy).bmGetId() != IBmObject.BM_NULL_ID
                ? (EObject)tx.getObjectById(((IBmObject)copy).bmGetId()) : null;
            if (copy instanceof IBmObject && ((IBmObject)copy).bmGetId() == IBmObject.BM_NULL_ID
                && source instanceof MdObject)
            {
                // The stored owner's member is available before EDT's global lookup indexes it.
                EObject located = source instanceof BasicForm
                    ? AdoptedFormLookup.find(tx, (BasicForm)source) : null;
                if (located == null)
                {
                    located = adopter.getAdopted(source, target);
                }
                if (located != null && located.eIsProxy())
                {
                    located = (EObject)tx.getObjectByUri(((InternalEObject)located).eProxyURI());
                }
                if (located instanceof IBmObject && ((IBmObject)located).bmGetId() != IBmObject.BM_NULL_ID)
                {
                    inTx = tx.toTransactionObject(located);
                    if (inTx != null && !BmObjectOwnership.isPrimary(tx, (IBmObject)inTx))
                    {
                        inTx = null;
                    }
                }
            }
            if (inTx == null)
            {
                String recoveryFailure = null;
                AtomicReference<String> declined = new AtomicReference<>("notApplicable"); //$NON-NLS-1$
                if (source instanceof BasicForm && copy instanceof IBmObject
                    && ((IBmObject)copy).bmGetId() == IBmObject.BM_NULL_ID)
                {
                    try
                    {
                        inTx = AdoptedFormAttachment.complete(tx, (BasicForm)source, copy,
                            Activator.getDefault().getTopObjectFqnGenerator(), declined::set);
                        if (inTx != null)
                        {
                            Activator.logInfo("adopt_metadata_object: Completed native form attachment after a " //$NON-NLS-1$
                                + "cross-namespace containment collision: " + MetadataNodeResolver.addressOf((MdObject)inTx)); //$NON-NLS-1$
                        }
                    }
                    catch (RuntimeException e)
                    {
                        recoveryFailure = e.getMessage();
                        declined.set("exception(" + e.getClass().getSimpleName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
                if (inTx == null)
                {
                    String message = "Adopted copy is unavailable (" //$NON-NLS-1$
                        + AdoptedFormAttachment.describe(tx, source, copy)
                        + ", declined=" + declined.get() //$NON-NLS-1$
                        + (recoveryFailure == null ? "" : ", attachmentFailure=" + recoveryFailure) //$NON-NLS-1$ //$NON-NLS-2$
                        + "); inspect the extension with get_metadata_objects before retrying adoption."; //$NON-NLS-1$
                    Activator.logWarning("adopt_metadata_object: " + message); //$NON-NLS-1$
                    throw new IllegalStateException(message);
                }
            }
            requested.addAll(dirtyFqns(inTx, (Configuration)root));
            return Long.valueOf(((IBmObject)inTx).bmGetId());
        }, event -> {
            Set<Long> ids = new LinkedHashSet<>();
            BmAssociationEvent association = event.getAssociationEvent();
            if (association != null)
            {
                for (IBmObject object : association.getAssociated())
                {
                    if (object instanceof MdObject)
                    {
                        ids.add(object.bmGetId());
                    }
                }
            }
            associatedIds.set(ids);
        });
        if (adoptedId == null)
        {
            return new AdoptionWrite(null, new AdoptionChanges(Collections.emptyList(), Collections.emptyList()));
        }
        WriteScope.recordWrite(target.getProject());
        Set<Long> ids = associatedIds.get();
        if (ids == null)
        {
            return new AdoptionWrite(adoptedId, AdoptionChanges.unavailable(requested,
                "EDT did not report its transaction additions")); //$NON-NLS-1$
        }
        AdoptionChanges changes;
        try
        {
            changes = BmTransactions.read(model, "adopt_metadata_object: read committed additions", //$NON-NLS-1$
            (tx, monitor) -> {
                Set<String> added = new LinkedHashSet<>();
                for (long id : ids)
                {
                    IBmObject object = tx.getObjectById(id);
                    if (object == null)
                    {
                        return AdoptionChanges.unavailable(requested,
                            "an adopted object disappeared after commit"); //$NON-NLS-1$
                    }
                    if (object instanceof MdObject && object.bmIsTop())
                    {
                        added.add(object.bmGetFqn());
                    }
                }
                List<String> cascaded = added.stream().filter(fqn -> !requested.contains(fqn))
                    .sorted().collect(Collectors.toList());
                Set<String> dirty = new LinkedHashSet<>(requested);
                String configurationFqn = requested.get(requested.size() - 1);
                dirty.remove(configurationFqn);
                dirty.addAll(added);
                dirty.add(configurationFqn);
                return new AdoptionChanges(new ArrayList<>(dirty), cascaded);
            });
        }
        catch (RuntimeException e)
        {
            Activator.logInfo("adopt_metadata_object: could not read committed additions: " + e); //$NON-NLS-1$
            changes = AdoptionChanges.unavailable(requested, "the committed additions could not be read"); //$NON-NLS-1$
        }
        return new AdoptionWrite(adoptedId, changes);
    }

    /** Detached identity and FQNs of the committed adoption; no transaction-local object escapes. */
    static final class AdoptionWrite
    {
        final Long copyId;
        final AdoptionChanges changes;

        AdoptionWrite(Long copyId, AdoptionChanges changes)
        {
            this.copyId = copyId;
            this.changes = changes;
        }
    }

    /** Detached FQNs only: response caps never limit the export/history scope. */
    static final class AdoptionChanges
    {
        final List<String> dirty;
        final List<String> cascaded;
        final String warning;

        AdoptionChanges(List<String> dirty, List<String> cascaded)
        {
            this(dirty, cascaded, null);
        }

        private AdoptionChanges(List<String> dirty, List<String> cascaded, String warning)
        {
            this.dirty = dirty;
            this.cascaded = cascaded;
            this.warning = warning;
        }

        static AdoptionChanges unavailable(List<String> requested, String reason)
        {
            return new AdoptionChanges(new ArrayList<>(requested), Collections.emptyList(),
                "Adoption committed; " + reason //$NON-NLS-1$
                    + ". Inspect the extension with get_metadata_objects; do not retry adoption."); //$NON-NLS-1$
        }

        boolean persistToDisk(IProject project)
        {
            return !dirty.isEmpty() && BmTransactions.forceExportToDisk(project, dirty);
        }
    }

    /**
     * The FQNs of the top objects an adoption changed, in export order: the adopted object's TOP
     * object (when it is a {@link IBmObject}); for an adopted NESTED subsystem, every parent subsystem
     * above it - each parent's {@code .mdo} lists the child in {@code <subsystems>}, and after the
     * platform's cascade the parent is itself new (the same rule {@code create_metadata} applies to a
     * nested create); then the extension {@code Configuration} (whose child collection changed).
     * The parents are read off the adopted copy by {@link SubsystemUtils#lineage}, the walk that also
     * names a subsystem source. Package-visible for tests.
     *
     * @param adopted the object returned by the adopter
     * @param extConfig the extension's configuration, or {@code null} when it is not available
     * @return the (possibly empty) list of dirty FQNs
     */
    static List<String> dirtyFqns(EObject adopted, Configuration extConfig)
    {
        List<String> dirty = new ArrayList<>();
        if (adopted instanceof IBmObject)
        {
            IBmObject topObject = ((IBmObject)adopted).bmGetTopObject();
            if (topObject != null)
            {
                dirty.add(topObject.bmGetFqn());
            }
        }
        if (adopted instanceof Subsystem)
        {
            for (Subsystem ancestor : SubsystemUtils.lineage((Subsystem)adopted).ancestors())
            {
                if (ancestor instanceof IBmObject)
                {
                    dirty.add(((IBmObject)ancestor).bmGetFqn());
                }
            }
        }
        if (extConfig instanceof IBmObject)
        {
            dirty.add(((IBmObject)extConfig).bmGetFqn());
        }
        return dirty;
    }

    /**
     * Tells whether the given string is non-{@code null} and non-empty. Behaviour-identical to the former
     * inline {@code s != null && !s.isEmpty()}.
     *
     * @param value the value to test
     * @return {@code true} when {@code value} is non-{@code null} and non-empty
     */
    private static boolean isNonEmpty(String value)
    {
        return value != null && !value.isEmpty();
    }

    private static String candidateNames(List<IExtensionProject> candidates)
    {
        if (candidates.isEmpty())
        {
            return "(none)"; //$NON-NLS-1$
        }
        return candidates.stream().map(c -> c.getProject().getName()).collect(Collectors.joining(", ")); //$NON-NLS-1$
    }

    /**
     * What a call is asked to adopt: the base object or member the FQN resolved to, and the address
     * every result of the call names it by. The two are resolved together, once, so the refusal, the
     * "already adopted" answer and the adoption's own result all carry the same name - for a subsystem
     * the canonical chain rather than the caller's spelling (issue #708). Package-visible for tests.
     */
    static final class AdoptionSource
    {
        /** The base object or member to adopt. */
        final EObject object;

        /** The address the results name it by - see {@link #resolve}. */
        final String fqn;

        private AdoptionSource(EObject object, String fqn)
        {
            this.object = object;
            this.fqn = fqn;
        }

        /**
         * Resolves the source a normalized FQN addresses and names it.
         *
         * <p>The source resolves through {@link MetadataNodeResolver#resolveAddress}, the dispatch
         * the vendor-support guard judges an address with: a subsystem by its chain (any depth, any
         * mix of English and Russian tokens, any letter case; a bare {@code Subsystem.<Child>} names a
         * top-level subsystem only), a form (bilingual kind token), any other top object or member.
         * The platform adopter itself adopts the missing parents of a nested subsystem first.</p>
         *
         * <p>A subsystem is named by the canonical chain {@link SubsystemUtils#lineage} reads off the
         * source itself - the English token and the STORED name of every level, which is exactly what
         * {@code list_subsystems} prints - but only when that walk up passes through the very names
         * the address resolved down by, level for level (see {@link #canonicalChain}). Anything else
         * keeps the normalized FQN: any other source, and a subsystem whose walk up does not spell its
         * address - for example one cut short (a cycle, an unresolved parent), one with fewer levels
         * than the address (a parent link that is not set, or one to a shallower subsystem) or with
         * more (a parent link on a subsystem the configuration lists at the top, or one to a deeper
         * subsystem), and one that names another subsystem at some level (a parent link to another
         * subsystem of the same depth).</p>
         *
         * @param scope the project's resolution root
         * @param normFqn the normalized FQN
         * @return the source, or {@code null} when the FQN addresses nothing
         */
        static AdoptionSource resolve(MetadataScope scope, String normFqn)
        {
            MdObject object = MetadataNodeResolver.resolveAddress(scope, normFqn, YO_FALLBACK);
            if (object == null)
            {
                return null;
            }
            String chain = object instanceof Subsystem ? canonicalChain((Subsystem)object, normFqn) : null;
            return new AdoptionSource(HostKind.current() == HostKind.HEADLESS
                ? MetadataNodeResolver.detachedIdentity(object) : object, chain != null ? chain : normFqn);
        }

        /**
         * The canonical chain of a subsystem source, read off its own parents by
         * {@link SubsystemUtils#lineage} - or {@code null} when that walk does not spell the address
         * the source was resolved by. The address resolved DOWN from a top-level subsystem, one name
         * per level, along each parent's {@code subsystems} list; the walk reads the levels back UP,
         * along the {@code parentSubsystem} back-references, and in a broken model the two can part.
         * So the chain is used only when it passes through the very names the descent walked, level
         * for level ({@link SubsystemUtils.Lineage#spells}). Counting the levels is not enough: a
         * back-reference to another subsystem of the same depth ({@code Sales} lists {@code Stray},
         * whose parent link is {@code Planning}) keeps the count and spells
         * {@code Subsystem.Planning.Subsystem.Stray} - the address of a different {@code Stray}, or of
         * none. The same check refuses a walk with fewer levels than the address (an unset parent
         * link - the source's own makes it read as top-level - or a link to a shallower subsystem), a
         * walk with more (a parent link on a subsystem the configuration lists at the top, or a link
         * to a deeper subsystem), and a walk cut short by an unresolved parent or a cycle (it names
         * nothing).
         *
         * @param source the resolved subsystem
         * @param normFqn the normalized chain FQN it was resolved by
         * @return the canonical chain FQN, or {@code null} to keep {@code normFqn}
         */
        private static String canonicalChain(Subsystem source, String normFqn)
        {
            String[] levels = SubsystemUtils.parseSubsystemPath(normFqn);
            SubsystemUtils.Lineage lineage = SubsystemUtils.lineage(source);
            return levels != null && lineage.spells(levels) ? lineage.chainFqn() : null;
        }
    }
}
