/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractRoleDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.metadata.MetadataFormatterRegistry;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.ExtensionOriginUtils;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.RoleRightsReader;

/**
 * Tool to get detailed properties of metadata objects from 1C configuration.
 * Supports sections: basic, attributes, tabular, forms, commands.
 */
public class GetMetadataDetailsTool implements IMcpTool
{
    public static final String NAME = "get_metadata_details"; //$NON-NLS-1$

    /** Markdown separator between rendered object sections. */
    private static final String SECTION_SEPARATOR = "\n---\n\n"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get detailed properties of one or more 1C metadata objects (basic info by default, " + //$NON-NLS-1$
               "or every reflected section with 'full: true'). Use it after get_metadata_objects to " + //$NON-NLS-1$
               "inspect a known object's attributes/forms/commands; in full mode each section is " + //$NON-NLS-1$
               "capped so request fewer FQNs to keep the response small. A FORM FQN " + //$NON-NLS-1$
               "('Catalog.X.Form.ItemForm' or 'CommonForm.Name') renders that form's STRUCTURE " + //$NON-NLS-1$
               "(items / attributes / commands). A ROLE FQN ('Role.FullAccess') renders that role's " + //$NON-NLS-1$
               "ACCESS RIGHTS - the object->right matrix, RLS restrictions, RLS templates and the role " + //$NON-NLS-1$
               "properties ('full: true' shows every object, otherwise only the non-default rows, the " + //$NON-NLS-1$
               "first 100 by default - page past them with 'roleObjectOffset' or use 'full: true'). " + //$NON-NLS-1$
               "Use this for the full properties of one named object; to list objects by type use get_metadata_objects. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('get_metadata_details')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringArrayProperty("objectFqns", //$NON-NLS-1$
                "Required. FQNs as Type.Name, e.g. ['Catalog.Products', 'Document.SalesOrder']; " + //$NON-NLS-1$
                "Russian type tokens also work (e.g. '\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.Products').", //$NON-NLS-1$
                true)
            .booleanProperty("full", //$NON-NLS-1$
                "All reflected properties (true) or only key info (false). Default: false") //$NON-NLS-1$
            .integerProperty("roleObjectOffset", //$NON-NLS-1$
                "For a ROLE FQN only: 0-based object offset into the rights matrix, for paging past the " + //$NON-NLS-1$
                "first 100 authored objects in the default (non-full) view (default: 0; ignored when " + //$NON-NLS-1$
                "'full: true', which renders every object up to 1000). Use it (or 'full: true') to read a " + //$NON-NLS-1$
                "role that authors more than 100 objects.") //$NON-NLS-1$
            .booleanProperty("assignable", //$NON-NLS-1$
                "Instead of the details view, return the ASSIGNABLE-property schema (default false): " + //$NON-NLS-1$
                "per property its value kind, current value and ALLOWED values (enum literals). This " + //$NON-NLS-1$
                "is what modify_metadata can set; FQNs may address members (e.g. " + //$NON-NLS-1$
                "'Catalog.Products.Attribute.Weight').") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Synonym language code, e.g. 'en'/'ru' (default: configuration default)") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }
    
    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        if (projectName != null && !projectName.isEmpty())
        {
            return "metadata-details-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "metadata-details.md"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        List<String> objectFqns = JsonUtils.extractArrayArgument(params, "objectFqns"); //$NON-NLS-1$
        String fullStr = JsonUtils.extractStringArgument(params, "full"); //$NON-NLS-1$
        boolean assignable = JsonUtils.extractBooleanArgument(params, "assignable", false); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        // Role-matrix pagination offset (0-based objects). Only the ROLE branch consumes it; a negative
        // request is clamped to 0 downstream. Ignored in full mode.
        int roleObjectOffset = JsonUtils.extractIntArgument(params, "roleObjectOffset", 0); //$NON-NLS-1$

        // Validate required parameters
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }

        if (objectFqns == null || objectFqns.isEmpty())
        {
            return ToolResult.error("objectFqns is required (array of FQNs like 'Catalog.Products')").toJson(); //$NON-NLS-1$
        }

        boolean full = "true".equalsIgnoreCase(fullStr); //$NON-NLS-1$

        // Execute on UI thread
        AtomicReference<String> resultRef = new AtomicReference<>();
        final List<String> fqns = objectFqns;
        final boolean fullMode = full;
        final boolean assignableMode = assignable;
        final String lang = language;
        final int roleOffset = roleObjectOffset;

        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result =
                    getMetadataDetailsInternal(projectName, fqns, fullMode, assignableMode, lang, roleOffset);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error getting metadata details", e); //$NON-NLS-1$
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
            }
        });
        
        return resultRef.get();
    }
    
    /**
     * Internal implementation that runs on UI thread.
     */
    private String getMetadataDetailsInternal(String projectName, List<String> objectFqns,
                                               boolean full, boolean assignable, String language,
                                               int roleObjectOffset)
    {
        // Resolve the project and its configuration
        ProjectContext.ConfigurationResult resolved = ProjectContext.resolveConfiguration(projectName);
        if (!resolved.ok())
        {
            return resolved.errorJson();
        }
        IProject project = resolved.project();
        Configuration config = resolved.configuration();
        
        // Determine language CODE for synonyms (the synonym map is keyed by code,
        // e.g. "ru"/"en", not by the Language object's name). May be null when the
        // configuration has no languages; downstream synonym lookup tolerates that.
        String effectiveLanguage = MetadataLanguageUtils.resolveLanguageCode(config, language);

        // The BM model is needed only to render a FORM's structure (a cross-model hop into the
        // editable Form content); resolved best-effort (a form FQN with no model reports a failure).
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        IBmModel bmModel = bmModelManager != null ? bmModelManager.getModel(project) : null;

        // An object's ORIGIN (core vs extension-adopted vs extension-own) is resolved
        // against the project type, computed once for the whole request.
        boolean isExtensionProject = ExtensionOriginUtils.isExtensionProject(project);

        StringBuilder sb = new StringBuilder();
        sb.append("# Metadata Details: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // Per-object outcomes are split into two channels so a structural client
        // can tell a failed object from data: successfully resolved objects render
        // as data in the body, while failures are collected and emitted as a
        // dedicated, clearly-delimited machine-readable table at the end. A
        // per-object failure is NOT a whole-call failure, so it stays in this
        // success body (the top-level ToolResult.error channel above is reserved
        // for whole-call failures such as a missing project or configuration).
        List<String[]> failures = new ArrayList<>();

        // Per-request render context, constant across every FQN in the loop.
        RenderContext ctx = new RenderContext(config, bmModel, effectiveLanguage, full, assignable,
            isExtensionProject, roleObjectOffset);

        // Process each FQN
        for (String fqn : objectFqns)
        {
            processFqn(fqn, sb, failures, ctx);
        }

        if (!failures.isEmpty())
        {
            sb.append(formatFailures(failures));
        }

        return sb.toString();
    }

    /**
     * Renders a single FQN of the request into {@code sb}, or records a {@code {fqn, reason}}
     * row in {@code failures} when it cannot be resolved. Extracted verbatim from the
     * {@link #getMetadataDetailsInternal} loop body; a per-object failure is not a whole-call
     * failure, so this method never throws on a resolution miss.
     */
    private void processFqn(String fqn, StringBuilder sb, List<String[]> failures, RenderContext ctx)
    {
        // Assignable-schema mode: resolve the node (top object OR member) via the shared
        // resolver and render its assignable-property table - what modify_metadata can set.
        if (ctx.assignable)
        {
            String normFqn = MetadataTypeUtils.normalizeFqn(fqn);
            // A form-member FQN (a group / field / table / decoration inside a form's editable content
            // model) is NOT part of the mdclass tree, so MetadataNodeResolver cannot see it and the
            // assignable view used to fail with "Object not found". Route it - BEFORE the mdclass
            // resolver - through the SAME form resolver modify_metadata uses, and render the element's
            // own features UNION its extInfo's layout props (the general reflective extInfo path,
            // issue #235). A plain mdclass FQN yields no FormMemberRef, so its path is unchanged.
            FormElementWriter.FormMemberRef memberRef = FormElementWriter.parse(normFqn);
            if (memberRef != null)
            {
                String memberAssignable =
                    renderFormMemberAssignable(ctx.config, ctx.bmModel, normFqn, memberRef);
                if (memberAssignable == null)
                {
                    failures.add(new String[] { fqn, "the form member could not be resolved (the form " //$NON-NLS-1$
                        + "may have no editable content model, or the element does not exist)" }); //$NON-NLS-1$
                    return;
                }
                sb.append(memberAssignable);
                sb.append(SECTION_SEPARATOR);
                return;
            }
            MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(ctx.config, fqn);
            if (node == null || node.object == null)
            {
                failures.add(new String[] { fqn, describeResolutionFailure(fqn) });
                return;
            }
            sb.append(formatAssignable(normFqn, node.object));
            sb.append(SECTION_SEPARATOR);
            return;
        }

        // A FQN that addresses a FORM ITSELF (Type.Object.Form.FormName or CommonForm.FormName)
        // renders the form's STRUCTURE (items / attributes / commands) via the cross-model hop
        // (FormStructureReader). Form MEMBERS use their own create/modify/delete FQNs; this branch
        // is for the whole form.
        String formPath = FormElementWriter.parseFormPath(MetadataTypeUtils.normalizeFqn(fqn));
        if (formPath != null)
        {
            String formStructure = renderFormStructure(ctx.config, ctx.bmModel, formPath, ctx.effectiveLanguage);
            if (formStructure == null)
            {
                failures.add(new String[] { fqn, "the form has no editable content model (it may " //$NON-NLS-1$
                    + "be empty, an ordinary/legacy form, or not yet built)" }); //$NON-NLS-1$
                return;
            }
            sb.append(formStructure);
            sb.append(SECTION_SEPARATOR);
            return;
        }

        MdObject mdObject = resolveObject(ctx.config, fqn);
        if (mdObject == null)
        {
            failures.add(new String[] { fqn, describeResolutionFailure(fqn) });
            return;
        }

        // A Role FQN renders its ACCESS-RIGHTS matrix (rights values / RLS / templates / role
        // properties) via the cross-model hop into the editable rights model, mirroring the FORM branch.
        // The rights model is a sub-resource of the Role top object, so it is read inside a BM read
        // boundary; the EObjects must not escape the read task.
        if (mdObject instanceof Role)
        {
            String roleRights = renderRoleRights(ctx.bmModel, (Role)mdObject,
                MetadataTypeUtils.normalizeFqn(fqn), ctx.full, ctx.effectiveLanguage, ctx.roleObjectOffset);
            if (roleRights == null)
            {
                failures.add(new String[] { fqn, "the role's rights model is unavailable (the BM model " //$NON-NLS-1$
                    + "is not ready or the project is not yet built)" }); //$NON-NLS-1$
                return;
            }
            sb.append(roleRights);
            sb.append("\n**Origin:** ") //$NON-NLS-1$
                .append(ExtensionOriginUtils.originLabel(mdObject.getObjectBelonging(), ctx.isExtensionProject))
                .append("\n"); //$NON-NLS-1$
            sb.append(SECTION_SEPARATOR);
            return;
        }

        sb.append(MetadataFormatterRegistry.format(mdObject, ctx.full, ctx.effectiveLanguage));
        // ORIGIN footer: core / core (adopted) / extension. For a base
        // configuration this is always "core"; for an extension it distinguishes
        // an adopted base object from one the extension itself owns.
        sb.append("\n**Origin:** ") //$NON-NLS-1$
            .append(ExtensionOriginUtils.originLabel(mdObject.getObjectBelonging(), ctx.isExtensionProject))
            .append("\n"); //$NON-NLS-1$
        sb.append(SECTION_SEPARATOR);
    }

    /**
     * Immutable per-request render context threaded through {@link #processFqn}: the resolved
     * configuration, the (best-effort) BM model used only for a form's cross-model hop, the
     * effective synonym language code and the three rendering flags. Computed once in
     * {@link #getMetadataDetailsInternal} and constant across every FQN. Bundles the parameters
     * without changing any value or rendering behaviour.
     */
    private static final class RenderContext
    {
        final Configuration config;
        final IBmModel bmModel;
        final String effectiveLanguage;
        final boolean full;
        final boolean assignable;
        final boolean isExtensionProject;
        /** 0-based object offset for a Role FQN's paginated rights matrix (ignored in {@code full} mode). */
        final int roleObjectOffset;

        RenderContext(Configuration config, IBmModel bmModel, String effectiveLanguage,
            boolean full, boolean assignable, boolean isExtensionProject, int roleObjectOffset)
        {
            this.config = config;
            this.bmModel = bmModel;
            this.effectiveLanguage = effectiveLanguage;
            this.full = full;
            this.assignable = assignable;
            this.isExtensionProject = isExtensionProject;
            this.roleObjectOffset = roleObjectOffset;
        }
    }

    /**
     * Renders a node's ASSIGNABLE-property schema as a Markdown table: per property its value kind,
     * current value, and (for an enum) the allowed values. This is the discovery view for
     * modify_metadata - it lists exactly what can be set and the valid enum literals. The shared
     * {@link MarkdownUtils} builder escapes every cell.
     * <p>
     * Widened to any {@link EObject} so it also serves a FORM MEMBER: for a form element the table is
     * the element's own assignable features UNION its {@code extInfo}'s layout properties (the general
     * reflective extInfo path, issue #235). A plain mdclass object has no {@code extInfo}
     * ({@link FormElementWriter#resolveExtInfoEClass} returns {@code null}), so it takes the
     * single-argument introspection and its output is byte-identical - the mdclass assignable view is
     * unchanged.
     *
     * @param fqn the (normalized) FQN, for the section heading
     * @param obj the resolved node (a top object, an mdclass member, or a form member)
     * @return the Markdown section
     */
    static String formatAssignable(String fqn, EObject obj)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Assignable properties: ").append(fqn).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("Set these with `modify_metadata`. For an ENUM property the value must be one of " //$NON-NLS-1$
            + "the listed Allowed values.\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("Property", "Kind", "Current", "Allowed values")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // A form element additionally exposes the assignable properties nested in its <extInfo> (e.g. a
        // UsualGroup's group / united layout props). When it carries a LIVE extInfo instance (the common
        // designer-authored case) render those props off that instance, so their Current column shows the
        // SET values just like the direct features do; fall back to the EClass-only listing (kind +
        // allowed values, no Current) when the <extInfo> slot is empty, and to the single-arg
        // introspection for a plain mdclass object (no extInfo - byte-identical, mdclass view unchanged).
        EObject liveExtInfo = FormElementWriter.extInfoInstance(obj);
        EClass extInfoEClass = FormElementWriter.resolveExtInfoEClass(obj); // type-authoritative
        // Read Current values off the live instance ONLY when it MATCHES the type-derived class. A form
        // group whose `type` was changed carries a STALE extInfo (class != the type-derived one); list the
        // NEW type's props (kind + allowed, no Current) rather than the stale instance's, so the assignable
        // view stays consistent with the group's current type (#235 review).
        boolean liveMatches = liveExtInfo != null && extInfoEClass != null
            && liveExtInfo.eClass().getName().equals(extInfoEClass.getName());
        Iterable<MetadataPropertyIntrospector.PropertyInfo> props;
        if (liveMatches)
        {
            props = MetadataPropertyIntrospector.introspect(obj, liveExtInfo);
        }
        else if (extInfoEClass != null)
        {
            props = MetadataPropertyIntrospector.introspect(obj, extInfoEClass);
        }
        else
        {
            props = MetadataPropertyIntrospector.introspect(obj);
        }
        for (MetadataPropertyIntrospector.PropertyInfo p : props)
        {
            String allowed = p.allowedValues.isEmpty() ? null : String.join(", ", p.allowedValues); //$NON-NLS-1$
            sb.append(MarkdownUtils.tableRow(p.name, p.valueKind.toString(), p.currentValue, allowed));
        }
        return sb.toString();
    }

    /**
     * Resolves a single FQN to its metadata object, or {@code null} when the FQN
     * is malformed or the object does not exist. A {@code null} result is a
     * per-object failure (recorded in the machine-readable failures table), never
     * a whole-call failure.
     */
    /**
     * Renders a form's structure (items / attributes / commands) for a form FQN, reusing
     * {@code FormStructureReader}'s resolver + renderer: resolve the {@code BasicForm}, then inside a
     * BM READ transaction reach its editable {@code Form} content and render it to markdown (the
     * EObjects must not escape the read task). Returns {@code null} when the form has no editable
     * content model (empty / legacy / not built) or the BM model is unavailable.
     */
    private static String renderFormStructure(Configuration config, IBmModel bmModel, String formPath,
        String language)
    {
        if (bmModel == null)
        {
            return null;
        }
        MdObject mdForm = FormStructureReader.resolveMdForm(config, formPath);
        if (!(mdForm instanceof IBmObject))
        {
            return null;
        }
        final long mdFormBmId = ((IBmObject)mdForm).bmGetId();
        final String normalized = MetadataTypeUtils.normalizeFqn(formPath);
        return BmTransactions.read(bmModel, "GetMetadataDetailsForm", (tx, monitor) -> //$NON-NLS-1$
        {
            EObject txMdForm = tx.getObjectById(mdFormBmId);
            if (txMdForm == null)
            {
                return null;
            }
            EObject formModel = FormElementWriter.getEditableForm(txMdForm);
            if (formModel == null)
            {
                return null;
            }
            return FormStructureReader.render(normalized, formModel, language);
        });
    }

    /**
     * Renders the ASSIGNABLE-property table for a FORM MEMBER (a group / field / table / decoration /
     * attribute / command inside a form's editable content model). A form member is NOT in the mdclass
     * tree, so {@link MetadataNodeResolver} cannot see it; this reuses modify_metadata's proven form
     * resolver instead - the SAME BM-read hop {@link #renderFormStructure} uses. It resolves the
     * {@code BasicForm} from {@code ref.formPath}, then INSIDE a BM READ transaction hops to the
     * editable content form, resolves the member and renders its assignable table (the element's own
     * features UNION its {@code extInfo}'s layout properties, issue #235). The member EObject must not
     * escape the read task, so the whole render runs inside it.
     *
     * @param config the resolved configuration
     * @param bmModel the (best-effort) BM model; {@code null} yields {@code null}
     * @param normFqn the normalized member FQN, for the section heading
     * @param ref the parsed form-member reference (see {@link FormElementWriter#parse})
     * @return the Markdown assignable table, or {@code null} when the BM model is unavailable, the form
     *     has no editable content model, or the member does not exist
     */
    private static String renderFormMemberAssignable(Configuration config, IBmModel bmModel,
        String normFqn, FormElementWriter.FormMemberRef ref)
    {
        if (bmModel == null)
        {
            return null;
        }
        MdObject mdForm = FormStructureReader.resolveMdForm(config, ref.formPath);
        if (!(mdForm instanceof IBmObject))
        {
            return null;
        }
        final long mdFormBmId = ((IBmObject)mdForm).bmGetId();
        return BmTransactions.read(bmModel, "GetMetadataDetailsFormMember", (tx, monitor) -> //$NON-NLS-1$
        {
            EObject txMdForm = tx.getObjectById(mdFormBmId);
            if (txMdForm == null)
            {
                return null;
            }
            EObject formModel = FormElementWriter.getEditableForm(txMdForm);
            if (formModel == null)
            {
                return null;
            }
            EObject member = FormElementWriter.resolveFormMember(formModel, ref);
            if (member == null)
            {
                return null;
            }
            return formatAssignable(normFqn, member);
        });
    }

    /**
     * Renders a Role's access-rights matrix (rights values / RLS restrictions / RLS templates / the three
     * role-property booleans) for a Role FQN, reusing {@link RoleRightsReader}: resolve the Role live
     * inside a BM READ transaction and read its editable rights model ({@code Role.getRights()}, guarded to
     * the concrete {@code RoleDescription}), then render it to Markdown. The rights EObjects must not escape
     * the read task. Returns {@code null} when the BM model is unavailable or the Role cannot be
     * re-resolved in the transaction; a role that simply has no editable rights model still renders a
     * document (with a note), so it is NOT a failure.
     *
     * @param bmModel the (best-effort) BM model; {@code null} yields {@code null}
     * @param role the resolved Role MdObject (used only for its BM id)
     * @param normalizedFqn the normalized Role FQN, for the heading
     * @param full render every object with any right cell (true) or only the authored/non-default objects
     *            plus a summary count and pagination (false)
     * @param language the right/field-name language CODE (may be {@code null})
     * @param objectOffset the 0-based object offset into the paginated matrix (ignored in {@code full}
     *            mode); lets a caller page past the first 100 authored objects in the default view
     * @return the Markdown document, or {@code null} when the rights model is unavailable
     */
    private static String renderRoleRights(IBmModel bmModel, Role role, String normalizedFqn,
        boolean full, String language, int objectOffset)
    {
        if (bmModel == null || !(role instanceof IBmObject))
        {
            return null;
        }
        final long roleBmId = ((IBmObject)role).bmGetId();
        return BmTransactions.read(bmModel, "GetMetadataDetailsRole", (tx, monitor) -> //$NON-NLS-1$
        {
            EObject txRole = tx.getObjectById(roleBmId);
            if (!(txRole instanceof Role))
            {
                return null;
            }
            // Role.getRights() may be null or a bare AbstractRoleDescription marker; the reader guards on
            // the concrete RoleDescription and renders a note when the matrix is absent.
            AbstractRoleDescription rights = ((Role)txRole).getRights();
            return RoleRightsReader.render(normalizedFqn, rights, full, language, objectOffset);
        });
    }

    private MdObject resolveObject(Configuration config, String fqn)
    {
        // Parse FQN: Type.Name
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }

        String mdType = parts[0];
        String mdName = parts[1];

        // Normalize metadata type to English singular form (supports Russian and plural forms)
        String normalized = MetadataTypeUtils.toEnglishSingular(mdType);
        if (normalized != null)
        {
            mdType = normalized;
        }

        return MetadataTypeUtils.findObject(config, mdType, mdName);
    }

    /**
     * Builds the machine-readable reason for a FQN that {@link #resolveObject}
     * could not resolve. The reason becomes data in the failures table, never
     * prose mixed into the data body.
     */
    String describeResolutionFailure(String fqn)
    {
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return "Invalid FQN. Expected format: Type.Name (e.g. Catalog.Products)"; //$NON-NLS-1$
        }
        return "Object not found - use get_metadata_objects to list valid FQNs"; //$NON-NLS-1$
    }

    /**
     * Renders the per-object failures as a dedicated, clearly-delimited
     * machine-readable section. Every cell goes through the shared table builder,
     * so an FQN or reason containing '|' or a newline cannot break the table. The
     * heading marker {@code ## Errors} lets a structural client locate failed
     * objects without scraping prose out of the data body.
     */
    String formatFailures(List<String[]> failures)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Errors\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("FQN", "Status", "Reason")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String[] failure : failures)
        {
            sb.append(MarkdownUtils.tableRow(failure[0], "ERROR", failure[1])); //$NON-NLS-1$
        }
        return sb.toString();
    }

}
