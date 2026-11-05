/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import com.ditrix.edt.mcp.server.utils.MetadataRegistration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupport;
import com._1c.g5.v8.dt.core.filesystem.IProjectFileSystemSupportProvider;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.md.copy.IModelObjectCopySupport;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.BmModelResolver;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.WorkspaceModelWrite;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.MetadataCloneEditors;
import com.ditrix.edt.mcp.server.utils.MetadataCloneDiskVerifier;
import com.ditrix.edt.mcp.server.utils.MetadataClonePlan;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.NativeMetadataClone;
import com.ditrix.edt.mcp.server.utils.SecureXml;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Previewed, native EDT cloning of one configuration top object. */
public class CloneMetadataObjectTool extends AbstractMetadataWriteTool
{
    public static final String NAME = "clone_metadata_object";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Clone one metadata object with EDT's native paste semantics. Call once without confirm to preview, "
            + "then repeat the same newName and transfer selection with confirm=true and expectedHash. "
            + "Code text stays unchanged; incoming relations transfer only when selected. "
            + "Full parameters and examples: call get_tool_guide('clone_metadata_object').";
    }

    @Override
    public String getInputSchema()
    {
        JsonObject schema = JsonParser.parseString(JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "Plain configuration project; source and clone stay in this project", true)
            .stringProperty("fqn", "Top Catalog, Document, Report or DataProcessor FQN. Only the TYPE token may "
                + "be Russian/English; Name is a programmatic identifier, not a synonym", true)
            .stringProperty("newName", "Unused 1C identifier; omit for EDT's automatic free name")
            .booleanProperty("confirm", "Default false: preview only. True requires expectedHash from the same preview scope")
            .stringProperty("expectedHash", "contentHash from preview; required with confirm=true")
            .objectArrayProperty("transfer", "Default []: keep incoming relations unchanged. Select {kind:'subsystem'|"
                + "'roleRights', owner:'Subsystem.X'|'Role.Y'} and preview that selection before confirming")
            .build()).getAsJsonObject();
        JsonObject item = JsonParser.parseString(JsonSchemaBuilder.object()
            .enumProperty("kind", "Relation to replicate", true, "subsystem", "roleRights")
            .stringProperty("owner", "Owner FQN from incomingRelations with transferSupport=supported", true)
            .build()).getAsJsonObject();
        item.addProperty("additionalProperties", false);
        schema.getAsJsonObject("properties").getAsJsonObject("transfer").add("items", item);
        return GsonProvider.toJson(schema);
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true)
            .enumProperty("action", "Preview or applied native clone", "preview", "cloned")
            .stringProperty("sourceFqn", "Canonical source FQN")
            .stringProperty("fqn", "Actual created FQN, present after cloning")
            .stringProperty("contentHash", "Preview token covering source, disk, destination names and selected owners")
            .stringProperty("namePolicy", "callerSupplied or edtAutomatic")
            .objectArrayProperty("incomingRelations", "Incoming references grouped by kind; each has owner and transferSupport")
            .booleanProperty("referenceScanComplete", "Whether the indexed incoming reference scan was complete")
            .booleanProperty("supported", "Whether this source passed clone validation")
            .objectArrayProperty("resources", "Source file inventory, or destination files with expected hashes after cloning")
            .objectArrayProperty("codeReferences", "Literal source-name occurrences in copied text files: file and 1-based line")
            .objectArrayProperty("transferred", "Explicit relation selections applied")
            .objectArrayProperty("selectedTransfers", "Explicit selections included in this preview scope")
            .objectArrayProperty("notCopied", "Incoming relations left on the source")
            .integerProperty("modelObjects", "Number of owned model nodes inventoried")
            .stringProperty("codeReferencePolicy", "preserveText; no semantic BSL/query rewriting")
            .stringProperty("incomingReferencePolicy", "explicitSelection")
            .stringProperty("roleMembershipPolicy", "platformDefaultsExceptSelectedRows")
            .stringProperty("subsystemMembershipPolicy", "selectedOnly")
            .booleanProperty("persisted", "True only after export drain and resource verification")
            .stringProperty("warning", "Runtime acceptance limits and partial-result recovery advice")
            .stringArrayProperty(WriteScope.RESULT_MEMBER, WriteScope.OUTPUT_SCHEMA_DESCRIPTION)
            .build();
    }

    @Override
    protected String validateRequest(Map<String, String> params)
    {
        String required = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, "fqn");
        if (required != null)
        {
            return required;
        }
        String error = MetadataClonePlan.requestError(JsonUtils.extractStringArgument(params, "fqn"),
            JsonUtils.extractStringArgument(params, "newName"),
            JsonUtils.extractBooleanArgument(params, "confirm", false),
            JsonUtils.extractStringArgument(params, "expectedHash"), params.get("transfer"));
        return error == null ? null : ToolResult.error(error).toJson();
    }

    @Override
    protected String beforeUiThreadOrError(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        com.ditrix.edt.mcp.server.utils.ProjectContext project =
            com.ditrix.edt.mcp.server.utils.ProjectContext.of(projectName);
        if (!project.exists())
        {
            return com.ditrix.edt.mcp.server.utils.ProjectContext.notFoundMessage(projectName);
        }
        return BuildUtils.waitForDiskExport(project.project(), 10_000L) == BuildUtils.DiskExportState.DRAINED
            ? null : "Source export is not confirmed for project '" + projectName
                + "'. Save the source, wait for list_projects to report ready, then preview again.";
    }

    @Override
    protected String executeOnUiThread(Map<String, String> params) throws Exception
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String fqn = MetadataTypeUtils.normalizeFqn(JsonUtils.extractStringArgument(params, "fqn"));
        String newName = JsonUtils.extractStringArgument(params, "newName");
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false);
        String expectedHash = JsonUtils.extractStringArgument(params, "expectedHash");
        List<JsonObject> transfer = JsonUtils.extractObjectArray(params, "transfer");
        ProjectContext ctx = resolveProjectAndScope(projectName);
        if (ctx.hasError())
        {
            return ctx.error;
        }
        if (Activator.getDefault().getV8ProjectManager() == null)
        {
            return ToolResult.error("EDT's project manager is unavailable. Wait for the project to load "
                + "or restart EDT, then preview again.").toJson();
        }
        if (ctx.scope.isExternalObjects()
            || Activator.getDefault().getV8ProjectManager().getProject(ctx.project) instanceof IExtensionProject)
        {
            return ToolResult.error("Project '" + projectName + "' is not a plain configuration project. "
                + "Use list_projects and select a base configuration; extensions and external-objects projects "
                + "are unsupported by clone_metadata_object.").toJson();
        }
        BmModelResolver.Resolution resolution = BmModelResolver.resolve(ctx.project);
        if (!resolution.isAvailable())
        {
            return ToolResult.error(resolution.actionableError(NAME, "Nothing was written.")).toJson();
        }
        IModelObjectCopySupport service = ServiceAccess.get(IModelObjectCopySupport.class);
        IProjectFileSystemSupportProvider provider = ServiceAccess.get(IProjectFileSystemSupportProvider.class);
        ITopObjectFqnGenerator generator = ServiceAccess.get(ITopObjectFqnGenerator.class);
        if (service == null || provider == null || generator == null)
        {
            return ToolResult.error("EDT native metadata-copy/filesystem services are unavailable. "
                + "Wait for the configuration project to load or restart EDT, then preview again.").toJson();
        }
        IBmModel model = resolution.getModel();
        IProjectFileSystemSupport fs = provider.getProjectFileSystemSupport(ctx.project);
        if (fs == null)
        {
            return ToolResult.error("Project filesystem is unavailable for '" + projectName
                + "'. Refresh/reopen the configuration and preview again.").toJson();
        }
        MetadataCloneEditors.requireClean(ctx.project);
        MetadataClonePlan preview;
        try
        {
            preview = BmTransactions.read(model, NAME + ": preview", (tx, monitor) -> {
                Configuration config = (Configuration)tx.getTopObjectByFqn("Configuration");
                MdObject source = MetadataClonePlan.source(config, fqn);
                checkDestination(config, source, newName);
                MetadataCloneDiskVerifier.verifyOwned(ctx.project, MetadataClonePlan.graph(source), fs);
                MetadataCloneDiskVerifier.verifyOwned(ctx.project, java.util.Collections.singletonMap("", config), fs);
                return MetadataClonePlan.capture(ctx.project, model, config, source, newName, transfer, fs, true);
            });
        }
        catch (RuntimeException e)
        {
            return ToolResult.error("Cannot preview '" + fqn + "': " + unwrapCauseMessage(e).replaceFirst("[.!?]+$", "")
                + ". Save/refresh the source or use resync_to_disk, then preview again.").toJson();
        }
        if (!confirm)
        {
            WriteScope.recordNothingQueued();
            return answer(preview, newName).put("action", "preview").put("contentHash", preview.hash())
                .put("resources", sourceResources(preview)).put("transferred", List.of())
                .put("selectedTransfers", transfer).put("notCopied", notCopied(preview.incoming, transfer))
                .put("codeReferences", sourceTextReferences(ctx.project, preview))
                .put("warning", "No name is reserved. Code, queries and DCS text stay unchanged; role defaults "
                    + "may grant rights. The indexed reference scan is best effort; dynamic code references "
                    + "and binary template text may not be discoverable.").toJson();
        }
        if (!ContentHash.matches(preview.fingerprint, expectedHash))
        {
            return drift();
        }
        ConsentDecision decision = DestructiveConsentGate.getInstance().requireConsent(NAME,
            new ConsentPreview("Clone metadata object", preview.sourceFqn + " -> "
                + (newName == null ? "EDT automatic name" : newName), 1 + transfer.size(),
                List.of(preview.sourceFqn)));
        if (decision != ConsentDecision.ALLOW)
        {
            return ToolResult.error(DestructiveConsentGate.consentDeniedMessage(decision, NAME)).toJson();
        }
        MetadataCloneEditors.requireClean(ctx.project);
        AtomicReference<String> actualFqn = new AtomicReference<>();
        AtomicReference<List<String>> dirty = new AtomicReference<>(List.of());
        AtomicReference<String> result = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        try
        {
            result.set(WorkspaceModelWrite.run(ctx.project.getWorkspace(), model, NAME,
                () -> BmTransactions.writeInGlobalContext(model, NAME, (tx, progress) -> {
                Configuration config = (Configuration)tx.getTopObjectByFqn("Configuration");
                MdObject source = MetadataClonePlan.source(config, fqn);
                checkDestination(config, source, newName);
                MetadataClonePlan current = MetadataClonePlan.capture(ctx.project, model, config, source,
                    newName, transfer, fs, false);
                if (!ContentHash.matches(current.fingerprint, expectedHash))
                {
                    throw new IllegalArgumentException("Preview scope changed. Run clone_metadata_object without "
                        + "confirm again, then use its new contentHash and the same transfer selection.");
                }
                WriteScope.recordUndeterminable("native copy may write external files before failure", List.of(projectName));
                started.set(true);
                NativeMetadataClone copier = NativeMetadataClone.create(tx, model, source, newName,
                    attached -> actualFqn.set(attached.eClass().getName() + "." + attached.getName()));
                MdObject clone = (MdObject)copier.copy(source, config, MetadataClonePlan.collection(config, source));
                if (!(clone instanceof IBmObject) || model.getEngine().getCurrentTransaction() != tx)
                {
                    throw new IllegalStateException("EDT native copy did not join this transaction. Inspect the project "
                        + "with get_metadata_objects before retrying.");
                }
                String created = clone.eClass().getName() + "." + clone.getName();
                actualFqn.set(created);
                if (fs.getFile(clone).exists())
                {
                    throw new IllegalStateException("Clone metadata file already exists for '" + created
                        + "'. Inspect the destination and choose an unused name.");
                }
                validateClone(source, clone, newName);
                Set<String> exports = new LinkedHashSet<>(copier.attachedTopFqns());
                exports.add(created);
                exports.addAll(MetadataClonePlan.collectTopFqns(MetadataClonePlan.graph(clone)));
                exports.addAll(MetadataClonePlan.transfer(config, source, clone, transfer, generator));
                exports.add(((IBmObject)config).bmGetFqn());
                dirty.set(new ArrayList<>(exports));
                JsonArray resources = resourceManifest(preview, source, clone, fs);
                for (String attachedFqn : copier.attachedTopFqns())
                {
                    addModelResource(resources, tx.getTopObjectByFqn(attachedFqn), fs);
                }
                addModelResource(resources, config, fs);
                for (JsonObject selected : transfer)
                {
                    MdObject owner = com.ditrix.edt.mcp.server.utils.MetadataNodeResolver.resolveAddress(
                        MetadataScope.ofConfiguration(config), MetadataTypeUtils.normalizeFqn(
                            selected.get("owner").getAsString()), false);
                    for (EObject object : MetadataClonePlan.graph(owner).values())
                    {
                        if (object instanceof IBmObject && ((IBmObject)object).bmIsTop())
                        {
                            JsonObject resource = addModelResource(resources, object, fs);
                            if ("roleRights".equals(selected.get("kind").getAsString())
                                && object instanceof com._1c.g5.v8.dt.rights.model.RoleDescription)
                            {
                                resource.addProperty("rlsObject", created);
                            }
                        }
                    }
                }
                return answer(preview, newName).put("action", "cloned").put("fqn", created)
                    .put("selectedTransfers", transfer)
                    .put("notCopied", notCopied(preview.incoming, transfer))
                    .put("cloneModelHash", ContentHash.of(MetadataClonePlan.modelFingerprint(clone)))
                    .put("configurationFile", fs.getFile(config).getProjectRelativePath().toPortableString())
                    .put("collection", MetadataClonePlan.collection(config, source).getName())
                    .put("resources", resources).put("transferred", transfer).put("persisted", false)
                    .put("warning", "Texts are preserved. Do not retry this non-idempotent operation after a "
                        + "timeout or partial error; inspect the returned FQN first. Predefined item ids follow "
                        + "EDT native paste semantics.").toJson();
            })));
            WriteScope.recordWrite(ctx.project);
            if (!BmTransactions.forceExportToDisk(ctx.project, dirty.get()))
            {
                return partial(actualFqn.get(), "EDT did not accept all export requests");
            }
            return result.get();
        }
        catch (Exception e)
        {
            return started.get() ? partial(actualFqn.get(), unwrapCauseMessage(e))
                : ToolResult.error(unwrapCauseMessage(e)).toJson();
        }
    }

    private static void checkDestination(Configuration config, MdObject source, String newName)
    {
        String target = source.eClass().getName() + "." + (newName == null ? source.getName() + "Clone" : newName);
        String refusal = VendorSupportGuard.refusalFor(config, MetadataScope.ofConfiguration(config),
            "Configuration", target, "modified to create a clone");
        if (refusal != null)
        {
            throw new IllegalArgumentException(refusal);
        }
        MetadataClonePlan.checkName(config, source, newName);
    }

    static void validateClone(MdObject source, MdObject clone, String newName)
    {
        if (source == clone || source.getUuid().equals(clone.getUuid())
            || (newName != null && !newName.equals(clone.getName())))
        {
            throw new IllegalStateException("EDT did not create a new identity under the requested name. "
                + "Inspect get_metadata_objects and do not repeat confirm.");
        }
        Map<String, EObject> originals = MetadataClonePlan.graph(source);
        Map<String, EObject> copies = MetadataClonePlan.graph(clone);
        for (Map.Entry<String, EObject> entry : originals.entrySet())
        {
            EObject counterpart = copies.get(entry.getKey());
            if (counterpart == null || counterpart == entry.getValue())
            {
                throw new IllegalStateException("Incomplete native copy at '" + entry.getKey()
                    + "'. Inspect the clone before retrying.");
            }
            if (counterpart instanceof MdObject
                && ((MdObject)counterpart).getUuid().equals(((MdObject)entry.getValue()).getUuid()))
            {
                throw new IllegalStateException("A copied metadata node kept the source UUID at '"
                    + entry.getKey() + "'. Inspect the clone before retrying.");
            }
            for (org.eclipse.emf.ecore.EReference reference : counterpart.eClass().getEAllReferences())
            {
                if (reference.isDerived() || reference.isContainment() || reference.isContainer()
                    || (reference.isTransient() && !MetadataClonePlan.external(reference)))
                {
                    continue;
                }
                Object value = counterpart.eGet(reference, false);
                Iterable<?> targets = value instanceof Iterable<?> ? (Iterable<?>)value : java.util.Arrays.asList(value);
                for (Object target : targets)
                {
                    boolean originalTarget = originals.containsValue(target);
                    if (target instanceof EObject && source instanceof IBmObject)
                    {
                        org.eclipse.emf.common.util.URI uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI((EObject)target);
                        if (com._1c.g5.v8.bm.core.BmUriUtil.isBmUri(uri))
                        {
                            String top = com._1c.g5.v8.bm.core.BmUriUtil.extractTopObjectFqn(uri);
                            String sourceTop = ((IBmObject)source).bmGetFqn();
                            originalTarget |= top.equals(sourceTop) || top.startsWith(sourceTop + ".");
                        }
                    }
                    if (originalTarget)
                    {
                        throw new IllegalStateException("Clone reference '" + reference.getName()
                            + "' still targets an original owned node at '" + entry.getKey()
                            + "'. Inspect the clone before retrying.");
                    }
                }
            }
        }
    }

    static JsonArray resourceManifest(MetadataClonePlan plan, MdObject source, MdObject clone,
        IProjectFileSystemSupport fs)
    {
        Map<String, EObject> originals = MetadataClonePlan.graph(source);
        Map<String, EObject> copies = MetadataClonePlan.graph(clone);
        Map<String, IFile> from = MetadataClonePlan.ownedResources(originals, fs);
        Map<String, IFile> to = MetadataClonePlan.destinationResources(copies, fs);
        Map<String, JsonObject> files = new java.util.LinkedHashMap<>();
        IFile sourceFile = fs.getFile(source);
        for (Map.Entry<String, IFile> entry : from.entrySet())
        {
            IFile target = to.get(entry.getKey());
            if (target == null)
            {
                throw new IllegalStateException("Native copy omitted owned resource '" + entry.getKey()
                    + "'. Inspect the clone before retrying.");
            }
            String relative = entry.getValue().getProjectRelativePath().makeRelativeTo(
                sourceFile.getParent().getProjectRelativePath()).toPortableString();
            String hash = plan.files.get(relative);
            if (hash == null)
            {
                throw new IllegalStateException("Source resource inventory changed. Inspect the clone before retrying.");
            }
            String path = target.getProjectRelativePath().toPortableString();
            JsonObject resource = files.computeIfAbsent(path, key -> new JsonObject());
            resource.addProperty("file", path);
            resource.addProperty("sourceHash", hash);
            resource.addProperty("copied", true);
            boolean modeled = entry.getKey().endsWith("@model")
                || (resource.has("modeled") && resource.get("modeled").getAsBoolean());
            resource.addProperty("modeled", modeled);
            if (entry.getValue().equals(sourceFile))
            {
                resource.addProperty("expectedRoot", true);
                resource.addProperty("uuid", clone.getUuid().toString());
                resource.addProperty("name", clone.getName());
            }
        }
        JsonArray manifest = new JsonArray();
        files.values().forEach(manifest::add);
        for (EObject object : copies.values())
        {
            if (object instanceof IBmObject && ((IBmObject)object).bmIsTop())
            {
                addModelResource(manifest, object, fs);
            }
        }
        return manifest;
    }

    private static JsonObject addModelResource(JsonArray resources, EObject object, IProjectFileSystemSupport fs)
    {
        IFile file = fs.getFile(object);
        if (file == null)
        {
            throw new IllegalStateException("Cannot identify an exported model file; inspect the clone in EDT.");
        }
        String path = file.getProjectRelativePath().toPortableString();
        JsonObject resource = null;
        for (JsonElement element : resources)
        {
            if (path.equals(element.getAsJsonObject().get("file").getAsString()))
            {
                resource = element.getAsJsonObject();
                break;
            }
        }
        if (resource == null)
        {
            resource = new JsonObject();
            resource.addProperty("file", path);
            resources.add(resource);
        }
        resource.addProperty("modeled", true);
        resource.addProperty("modelFqn", ((IBmObject)object).bmGetFqn());
        resource.addProperty("modelHash", ContentHash.of(MetadataClonePlan.modelFingerprint(object)));
        return resource;
    }

    private static ToolResult answer(MetadataClonePlan preview, String newName)
    {
        return ToolResult.success().put("sourceFqn", preview.sourceFqn)
            .put("supported", true)
            .put("namePolicy", newName == null ? "edtAutomatic" : "callerSupplied")
            .put("incomingRelations", preview.incoming).put("notCopied", preview.incoming)
            .put("referenceScanComplete", preview.referenceScanComplete).put("modelObjects", preview.modelObjects)
            .put("codeReferencePolicy", "preserveText").put("incomingReferencePolicy", "explicitSelection")
            .put("roleMembershipPolicy", "platformDefaultsExceptSelectedRows")
            .put("subsystemMembershipPolicy", "selectedOnly");
    }

    private static JsonArray sourceResources(MetadataClonePlan preview)
    {
        JsonArray result = new JsonArray();
        preview.files.forEach((path, hash) -> {
            JsonObject file = new JsonObject();
            file.addProperty("file", preview.folder + "/" + path);
            file.addProperty("sourceHash", hash);
            result.add(file);
        });
        return result;
    }

    private static JsonArray sourceTextReferences(IProject project, MetadataClonePlan preview) throws Exception
    {
        JsonArray result = new JsonArray();
        for (Map.Entry<String, String> entry : preview.files.entrySet())
        {
            String path = preview.folder + "/" + entry.getKey();
            if (path.equals(preview.sourceFile))
            {
                continue;
            }
            try (InputStream input = project.getFile(path).getContents(true))
            {
                byte[] bytes = input.readAllBytes();
                if (!entry.getValue().equals(MetadataClonePlan.bytesHash(bytes)))
                {
                    throw new IllegalStateException("Source file changed: '" + path + "'. Preview again.");
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (text.indexOf('\0') < 0 && text.indexOf('\ufffd') < 0)
                {
                    result.addAll(MetadataClonePlan.textReferences(path, text, preview.sourceFqn));
                }
            }
        }
        return result;
    }

    private static JsonArray notCopied(JsonArray incoming, List<JsonObject> transfer)
    {
        JsonArray result = new JsonArray();
        for (JsonElement element : incoming)
        {
            JsonObject relation = element.getAsJsonObject();
            boolean selected = transfer.stream().anyMatch(item ->
                item.get("kind").getAsString().equals(relation.get("kind").getAsString())
                    && MetadataTypeUtils.normalizeFqn(item.get("owner").getAsString())
                        .equalsIgnoreCase(relation.get("owner").getAsString()));
            if (!selected)
            {
                result.add(element);
            }
        }
        return result;
    }

    private static String drift()
    {
        return ToolResult.error("expectedHash does not match the preview scope. Run clone_metadata_object without "
            + "confirm again, with the intended newName and transfer selection, then use its contentHash.").toJson();
    }

    static String partial(String fqn, String reason)
    {
        return ToolResult.error("Clone apply did not complete verification: " + reason.replaceFirst("[.!?]+$", "") + ". "
            + (fqn == null ? "No created FQN was established; inspect get_metadata_objects and project files. "
                : "Inspect clone '" + fqn + "' with get_metadata_details and its project files. ")
            + "External files may have been written even if BM rolled back. Nothing was cleaned up automatically. "
            + "Do not repeat confirm; use resync_to_disk or delete_metadata on the verified clone FQN to recover.")
            .put("fqn", fqn).put("mutationOutcomeUnknown", true).toJson();
    }

    @Override
    protected String refreshAfterExportAwait(Map<String, String> params, String result, boolean drainEstablished)
    {
        JsonObject response = JsonParser.parseString(result).getAsJsonObject();
        if (!"cloned".equals(resultString(response, "action")))
        {
            return result;
        }
        String fqn = resultString(response, "fqn");
        if (!drainEstablished)
        {
            return partial(fqn, "export completion is unobservable");
        }
        IProject project = com.ditrix.edt.mcp.server.utils.ProjectContext.of(
            JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME)).project();
        JsonArray references = new JsonArray();
        try
        {
            BmModelResolver.Resolution model = BmModelResolver.resolve(project);
            if (!model.isAvailable())
            {
                return partial(fqn, "the model became unavailable during export");
            }
            String currentHash = BmTransactions.read(model.getModel(), NAME + ": verify committed clone",
                (tx, monitor) -> {
                    MdObject clone = MetadataClonePlan.source((Configuration)tx.getTopObjectByFqn("Configuration"), fqn);
                    return ContentHash.of(MetadataClonePlan.modelFingerprint(clone));
                });
            if (!currentHash.equals(resultString(response, "cloneModelHash")))
            {
                return partial(fqn, "the clone model changed while export was finishing");
            }
            com._1c.g5.v8.dt.core.platform.IDtProject dtProject =
                Activator.getDefault().getDtProjectManager().getDtProject(project);
            com._1c.g5.v8.dt.core.naming.ISymbolicNameService names =
                ServiceAccess.get(com._1c.g5.v8.dt.core.naming.ISymbolicNameService.class);
            com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport versions =
                ServiceAccess.get(com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport.class);
            if (dtProject == null || names == null || versions == null)
            {
                return partial(fqn, "EDT's exported-model verification services became unavailable");
            }
            BmTransactions.read(model.getModel(), NAME + ": verify exported models", (tx, monitor) -> {
                for (JsonElement element : response.getAsJsonArray("resources"))
                {
                    JsonObject resource = element.getAsJsonObject();
                    if (resource.get("modeled").getAsBoolean())
                    {
                        EObject object = tx.getTopObjectByFqn(resource.get("modelFqn").getAsString());
                        if (object == null || !ContentHash.of(MetadataClonePlan.modelFingerprint(object))
                            .equals(resource.get("modelHash").getAsString()))
                        {
                            throw new IllegalStateException("A written model changed during export: "
                                + resource.get("modelFqn").getAsString());
                        }
                        MetadataCloneDiskVerifier.verify(project.getFile(resource.get("file").getAsString()),
                            object, dtProject, versions.getRuntimeVersion(project), names);
                    }
                }
                return null;
            });
            for (JsonElement element : response.getAsJsonArray("resources"))
            {
                JsonObject resource = element.getAsJsonObject();
                IFile file = project.getFile(resource.get("file").getAsString());
                byte[] bytes = MetadataCloneDiskVerifier.verifyCopiedFile(file,
                    resource.get("modeled").getAsBoolean() ? null : resource.get("sourceHash").getAsString());
                if (resource.has("expectedRoot") && resource.get("expectedRoot").getAsBoolean())
                {
                    org.w3c.dom.Element root = SecureXml.documentBuilderFactory().newDocumentBuilder()
                        .parse(new java.io.ByteArrayInputStream(bytes)).getDocumentElement();
                    if (!resource.get("uuid").getAsString().equals(root.getAttribute("uuid"))
                        || !root.getElementsByTagName("name").item(0).getTextContent()
                            .equals(resource.get("name").getAsString()))
                    {
                        return partial(fqn, "exported root identity/name differ from the clone model");
                    }
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (resource.has("copied") && resource.get("copied").getAsBoolean()
                    && !(resource.has("expectedRoot") && resource.get("expectedRoot").getAsBoolean())
                    && text.indexOf('\0') < 0 && text.indexOf('\ufffd') < 0)
                {
                    references.addAll(MetadataClonePlan.textReferences(file.getProjectRelativePath().toPortableString(),
                        text, resultString(response, "sourceFqn")));
                }
                if (resource.has("rlsObject"))
                {
                    references.addAll(com.ditrix.edt.mcp.server.utils.MetadataCloneRlsReferences.find(
                        file.getProjectRelativePath().toPortableString(), text, resultString(response, "sourceFqn"),
                        resource.get("rlsObject").getAsString()));
                    resource.remove("rlsObject");
                }
                resource.addProperty("verified", true);
                resource.remove("modelHash");
            }
            if (MetadataRegistration.verify(project.getName(),
                resultString(response, "configurationFile"), "Configuration", fqn)
                != MetadataRegistration.State.PRESENT)
            {
                return partial(fqn, "Configuration.mdo does not register the clone");
            }
            response.remove("cloneModelHash");
            response.remove("configurationFile");
            response.remove("collection");
            response.add("codeReferences", references);
            response.addProperty("persisted", true);
            return GsonProvider.toJson(response);
        }
        catch (Exception e)
        {
            return partial(fqn, unwrapCauseMessage(e));
        }
    }
}
