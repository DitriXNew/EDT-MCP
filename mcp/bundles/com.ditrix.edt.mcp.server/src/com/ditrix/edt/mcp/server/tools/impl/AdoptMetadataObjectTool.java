/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;
import com.google.gson.JsonObject;

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
            .stringArrayProperty(WriteScope.RESULT_MEMBER, WriteScope.OUTPUT_SCHEMA_DESCRIPTION)
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
        // Resolve the source: a subsystem by its chain (a NESTED subsystem is a separate top object the
        // containment grammar cannot reach), a FORM via the form resolver (forms are a separate
        // getForms() collection, not in the mdclass child-token tree), and any other top object or
        // member (attribute/tabular section/...) via the shared resolver - together with the address
        // every result below names it by (for a subsystem, its chain as list_subsystems prints it).
        AdoptionSource source = AdoptionSource.resolve(ctx.config, normFqn);
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

        if (!adopter.isAdoptable(source.object))
        {
            return notAdoptableError(source);
        }

        String extName = target.getProject().getName();

        if (adopter.isAdopted(source.object, target))
        {
            // A SUCCESS that changes nothing: adoptAndAttach is never called, so no export is
            // queued anywhere. Stated rather than left silent, because "queued nothing" and "did
            // not say" owe the barrier different answers.
            WriteScope.recordNothingQueued();
            return alreadyAdoptedResult(source, extName);
        }

        // Vendor support (#642) does not apply: the base side is only read, and EDT reads support
        // only for a NATIVE configuration root - an extension's root is ADOPTED, so nothing here locks.

        // The service runs its own BM write task on the extension's model, but exposes no rollback
        // outcome if it throws. Record the opaque interval before entering it; the known write
        // declaration immediately after a normal return takes precedence.
        WriteScope.recordUndeterminable("model-object adopter may mutate before throwing", //$NON-NLS-1$
            java.util.Collections.singletonList(extName));
        EObject adopted = adopter.adoptAndAttach(source.object, target, new NullProgressMonitor());

        // projectName is the BASE configuration by contract; the write lands in the EXTENSION.
        // Stated here rather than left to the export submission below, because that submission is
        // skipped when nothing came back dirty - and a write with no export of its own is still a
        // write in this project, not a call that wrote nowhere.
        WriteScope.recordWrite(target.getProject());

        // Persist the adopted TOP object's .mdo, the .mdo of every parent subsystem of an adopted
        // nested subsystem, AND the extension Configuration.mdo registration (the parent collection
        // changed), mirroring create_metadata. bmGetTopObject()/bmGetFqn() are safe identity reads on
        // the objects the platform returned.
        List<String> dirty = collectDirtyFqns(adopted, target);
        boolean persisted = !dirty.isEmpty() && BmTransactions.forceExportToDisk(target.getProject(), dirty);

        return adoptedResult(source, extName, persisted);
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
     * already. The copy is named by the source's address - it keeps the source's Name (the platform
     * maps it by UUID); {@code bmGetFqn()} of an adopted object is never asked, because for a MEMBER (a
     * form, an attribute) it is not a top object and throws ("may be called on top objects only").
     * Package-visible for tests.
     *
     * @param source the resolved source
     * @param extName the extension project's name
     * @return the success JSON
     */
    static String alreadyAdoptedResult(AdoptionSource source, String extName)
    {
        return ToolResult.success()
            .put(McpKeys.ACTION, "alreadyAdopted") //$NON-NLS-1$
            .put("fqn", source.fqn) //$NON-NLS-1$
            .put(KEY_EXTENSION_PROJECT, extName)
            .put(KEY_OBJECT_BELONGING, "ADOPTED") //$NON-NLS-1$
            .put(KEY_PERSISTED, true)
            .put("message", "'" + source.fqn + "' is already adopted in extension '" + extName + "'.") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .toJson();
    }

    /**
     * The success of an adoption that ran, naming the adopted copy by the source's address for the
     * reason given at {@link #alreadyAdoptedResult}. Package-visible for tests.
     *
     * @param source the resolved source
     * @param extName the extension project's name
     * @param persisted whether the platform accepted a save task for the change
     * @return the success JSON
     */
    static String adoptedResult(AdoptionSource source, String extName, boolean persisted)
    {
        return ToolResult.success()
            .put(McpKeys.ACTION, "adopted") //$NON-NLS-1$
            .put("fqn", source.fqn) //$NON-NLS-1$
            .put(KEY_EXTENSION_PROJECT, extName)
            .put(KEY_OBJECT_BELONGING, "ADOPTED") //$NON-NLS-1$
            .put(KEY_PERSISTED, persisted)
            .toJson();
    }

    /**
     * Resolves the source object to adopt, read-only, through the shared bilingual resolvers in the
     * order {@code VendorSupportGuard} judges an address: a SUBSYSTEM by its chain, then a FORM, then
     * any other top object or member. Package-visible for tests.
     *
     * <p>A subsystem goes through {@link SubsystemUtils} because a NESTED one
     * ({@code Subsystem.Sales.Subsystem.Orders}) is a separate BM top object that its parent only
     * REFERS to ({@code Subsystem.subsystems} is not a containment), so the containment grammar of
     * {@link MetadataNodeResolver} has no step to it - and must not get one: it would make a top object
     * look like a member to every tool at once (see {@code CreateMetadataTool}, issue #351). A chain
     * answers with the subsystem at its LAST level or with {@code null}, never with a parent; a bare
     * {@code Subsystem.<Child>} names a top-level subsystem only, because same-named children can live
     * under different parents. The platform adopter itself adopts the missing parents first.</p>
     *
     * <p>A form is recognized by the shared bilingual form-token predicate, so the Russian kind token
     * ({@code ...Form.X} / {@code ...Forms.X} and their Russian twins) addresses a form as the guide
     * promises.</p>
     *
     * @param config the configuration to resolve against
     * @param normFqn the normalized FQN
     * @return the resolved source object, or {@code null} when not found
     */
    static EObject resolveAdoptionSource(Configuration config, String normFqn)
    {
        String[] chain = SubsystemUtils.parseSubsystemPath(normFqn);
        if (chain != null)
        {
            return SubsystemUtils.resolveByPath(config, chain, chain.length);
        }
        String formPath = FormElementWriter.parseFormPath(normFqn);
        if (formPath != null)
        {
            MdObject form = FormStructureReader.resolveMdForm(config, formPath);
            if (form != null)
            {
                return form;
            }
        }
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, normFqn);
        return node == null ? null : node.object;
    }

    /**
     * The address the result names a resolved source by. A subsystem addressed by its chain - any
     * depth, any mix of English and Russian tokens, any letter case - is named by that chain rewritten
     * with the canonical English token and the STORED name of every level it resolved through, which
     * is exactly what {@code list_subsystems} prints. Anything else keeps the normalized FQN. Called
     * by {@link AdoptionSource#resolve} once the source resolved; package-visible for tests.
     *
     * @param config the configuration the source resolved against
     * @param normFqn the normalized FQN
     * @return the address to echo, never {@code null} for a non-{@code null} {@code normFqn}
     */
    static String canonicalFqn(Configuration config, String normFqn)
    {
        String[] chain = SubsystemUtils.parseSubsystemPath(normFqn);
        if (chain == null)
        {
            return normFqn;
        }
        String[] stored = new String[chain.length];
        for (int depth = 1; depth <= chain.length; depth++)
        {
            Subsystem level = SubsystemUtils.resolveByPath(config, chain, depth);
            if (level == null)
            {
                return normFqn;
            }
            stored[depth - 1] = level.getName();
        }
        return SubsystemUtils.chainFqn(stored, stored.length);
    }

    /**
     * The refusal for an FQN that resolves to nothing. An address that starts with a subsystem token
     * also learns how a NESTED subsystem is addressed - its bare {@code Subsystem.<Child>} names only a
     * top-level subsystem, so that spelling misses. Package-visible for tests.
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
            message += " A nested subsystem is addressed by its whole chain from a top-level subsystem, " //$NON-NLS-1$
                + "'Subsystem.<Parent>.Subsystem.<Child>' (any depth), exactly as list_subsystems prints it."; //$NON-NLS-1$
        }
        return message;
    }

    /**
     * Collects the FQNs of the objects whose {@code .mdo} must be re-exported after an adoption: the
     * {@link #dirtyFqns} of the adopted object and the target extension's own {@code Configuration}.
     * A read-only computation — the actual disk export is done by the caller.
     *
     * @param adopted the object returned by the adopter
     * @param target the target extension project
     * @return the (possibly empty) list of dirty FQNs
     */
    private static List<String> collectDirtyFqns(EObject adopted, IExtensionProject target)
    {
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        Configuration extConfig =
            configProvider != null ? configProvider.getConfiguration(target.getProject()) : null;
        return dirtyFqns(adopted, extConfig);
    }

    /**
     * The FQNs of the top objects an adoption changed, in export order: the adopted object's TOP
     * object (when it is a {@link IBmObject}); for an adopted NESTED subsystem, every parent subsystem
     * above it - each parent's {@code .mdo} lists the child in {@code <subsystems>}, and after the
     * platform's cascade the parent is itself new (the same rule {@code create_metadata} applies to a
     * nested create); then the extension {@code Configuration} (whose child collection changed).
     * Package-visible for tests.
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
        for (Subsystem ancestor : subsystemAncestors(adopted))
        {
            if (ancestor instanceof IBmObject)
            {
                dirty.add(((IBmObject)ancestor).bmGetFqn());
            }
        }
        if (extConfig instanceof IBmObject)
        {
            dirty.add(((IBmObject)extConfig).bmGetFqn());
        }
        return dirty;
    }

    /**
     * The subsystems above {@code object} along its {@code parentSubsystem} references, nearest
     * first - the parents the platform adopter adopts first when the extension lacks them, and links
     * the child under. Empty for anything that is not a nested subsystem. The walk stops at an
     * unresolved proxy and at a repeat, so a broken (cyclic) model cannot keep it going.
     * Package-visible for tests.
     *
     * @param object the object (may be {@code null})
     * @return the ancestors, nearest first; never {@code null}
     */
    static List<Subsystem> subsystemAncestors(EObject object)
    {
        List<Subsystem> ancestors = new ArrayList<>();
        if (!(object instanceof Subsystem))
        {
            return ancestors;
        }
        Set<EObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(object);
        for (Subsystem parent = ((Subsystem)object).getParentSubsystem();
            parent != null && !parent.eIsProxy() && seen.add(parent); parent = parent.getParentSubsystem())
        {
            ancestors.add(parent);
        }
        return ancestors;
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
     * the canonical chain, never the caller's spelling (issue #708). Package-visible for tests.
     */
    static final class AdoptionSource
    {
        /** The base object or member to adopt. */
        final EObject object;

        /** The address the results name it by - see {@link AdoptMetadataObjectTool#canonicalFqn}. */
        final String fqn;

        private AdoptionSource(EObject object, String fqn)
        {
            this.object = object;
            this.fqn = fqn;
        }

        /**
         * Resolves the source a normalized FQN addresses ({@link AdoptMetadataObjectTool#resolveAdoptionSource})
         * and names it ({@link AdoptMetadataObjectTool#canonicalFqn}).
         *
         * @param config the configuration to resolve against
         * @param normFqn the normalized FQN
         * @return the source, or {@code null} when the FQN addresses nothing
         */
        static AdoptionSource resolve(Configuration config, String normFqn)
        {
            EObject object = resolveAdoptionSource(config, normFqn);
            return object == null ? null : new AdoptionSource(object, canonicalFqn(config, normFqn));
        }
    }
}
