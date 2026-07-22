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
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractRoleDescription;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegister;
import com._1c.g5.v8.dt.metadata.mdclass.InformationRegisterDimension;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Role;
import com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com._1c.g5.v8.dt.xdto.model.Package;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.metadata.MetadataFormatterRegistry;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.DcsStructureReader;
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
import com.ditrix.edt.mcp.server.utils.XdtoStructureReader;

/**
 * Tool to get detailed properties of metadata objects from 1C configuration.
 * Supports sections: basic, attributes, tabular, forms, commands.
 */
public class GetMetadataDetailsTool implements IMcpTool
{
    public static final String NAME = "get_metadata_details"; //$NON-NLS-1$

    /** Markdown separator between rendered object sections. */
    private static final String SECTION_SEPARATOR = "\n---\n\n"; //$NON-NLS-1$

    /** Placeholder for an absent/empty value in the type-specific property tables below. */
    private static final String DASH = "-"; //$NON-NLS-1$

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
               "In the default (non-full) view a ScheduledJob or CommonModule also renders a " + //$NON-NLS-1$
               "type-specific Properties table (e.g. methodName/schedule/use for a job; " + //$NON-NLS-1$
               "server/serverCall/global/returnValuesReuse for a module), and an InformationRegister's " + //$NON-NLS-1$
               "Dimensions additionally show their Indexing. " + //$NON-NLS-1$
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
            appendAssignableView(fqn, sb, failures, ctx);
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

        // A FQN that addresses a TEMPLATE whose content is a DataCompositionSchema (a report's DCS
        // template, a common template, or an object-owned template - issue #267) renders the
        // schema's STRUCTURE (data sources / data sets / calculated fields / total fields /
        // parameters / the default settings variant) via the cross-model hop
        // (DcsStructureReader), mirroring the FORM branch above. Resolved through the shared
        // MetadataNodeResolver (handles both the 2-part CommonTemplate FQN and the 4-part
        // owned-template FQN, bilingually - the same resolver TemplateScreenshotHelper uses), so
        // this does not duplicate FQN navigation. A FQN that is not a template, or a template whose
        // content is NOT a DataCompositionSchema (e.g. a SpreadsheetDocument print form), falls
        // through UNCHANGED to the generic object-resolution path below.
        String dcsStructure = renderTemplateDcsIfApplicable(ctx.config, ctx.bmModel, fqn, ctx.effectiveLanguage);
        if (dcsStructure != null)
        {
            sb.append(dcsStructure);
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
            appendRoleRightsView((Role)mdObject, fqn, sb, failures, ctx);
            return;
        }

        sb.append(MetadataFormatterRegistry.format(mdObject, ctx.full, ctx.effectiveLanguage));
        // Type-specific properties the universal reflective formatter's default view omits
        // (issue #288): ScheduledJob / CommonModule / an InformationRegister's dimension Indexing.
        sb.append(formatTypeSpecificProperties(mdObject, ctx.full));
        // An XDTOPackage FQN additionally renders its CONTENT (object types with their properties,
        // package-global properties, value types, dependencies - issue #183 stream 1) via the
        // cross-model hop (XdtoStructureReader), APPENDED after the standard property block (unlike
        // the template/DCS branch above, the package's own properties - namespace, comment, synonym -
        // stay useful, so this augments rather than replaces the generic render).
        if (mdObject instanceof XDTOPackage)
        {
            String xdtoStructure = renderXdtoPackageStructure(ctx.bmModel, (XDTOPackage)mdObject, fqn);
            if (xdtoStructure != null)
            {
                sb.append(xdtoStructure);
            }
        }
        // ORIGIN footer: core / core (adopted) / extension. For a base
        // configuration this is always "core"; for an extension it distinguishes
        // an adopted base object from one the extension itself owns.
        sb.append("\n**Origin:** ") //$NON-NLS-1$
            .append(ExtensionOriginUtils.originLabel(mdObject.getObjectBelonging(), ctx.isExtensionProject))
            .append("\n"); //$NON-NLS-1$
        sb.append(SECTION_SEPARATOR);
    }

    /**
     * Renders the ASSIGNABLE-property view for one FQN of the request: a form-member FQN is routed
     * through the form resolver (issue #235), any other FQN through the shared mdclass resolver.
     * Appends the table to {@code sb}, or records a {@code {fqn, reason}} row in {@code failures}
     * when the node cannot be resolved. Extracted verbatim from the {@code ctx.assignable} branch
     * of {@link #processFqn}; no behaviour change.
     */
    private void appendAssignableView(String fqn, StringBuilder sb, List<String[]> failures, RenderContext ctx)
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
    }

    /**
     * Renders a Role FQN's ACCESS-RIGHTS matrix (rights values / RLS / templates / role properties)
     * followed by the ORIGIN footer. Appends the section to {@code sb}, or records a
     * {@code {fqn, reason}} row in {@code failures} when the editable rights model is unavailable.
     * Extracted verbatim from the {@code Role} branch of {@link #processFqn}; no behaviour change.
     */
    private void appendRoleRightsView(Role role, String fqn, StringBuilder sb, List<String[]> failures,
        RenderContext ctx)
    {
        String roleRights = renderRoleRights(ctx.bmModel, role,
            MetadataTypeUtils.normalizeFqn(fqn), ctx.full, ctx.effectiveLanguage, ctx.roleObjectOffset);
        if (roleRights == null)
        {
            failures.add(new String[] { fqn, "the role's rights model is unavailable (the BM model " //$NON-NLS-1$
                + "is not ready or the project is not yet built)" }); //$NON-NLS-1$
            return;
        }
        sb.append(roleRights);
        sb.append("\n**Origin:** ") //$NON-NLS-1$
            .append(ExtensionOriginUtils.originLabel(role.getObjectBelonging(), ctx.isExtensionProject))
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
     * Renders TYPE-SPECIFIC properties that the universal reflective formatter's DEFAULT
     * (non-{@code full}) view does not surface (issue #288): {@code modify_metadata} already WRITES
     * a ScheduledJob's execution properties (Schedule presence via {@code eIsSet} - EXPLICITLY set,
     * not a non-null default), a CommonModule's context-availability flags, and an
     * InformationRegister dimension's Indexing, but the basic details view rendered only Name /
     * Synonym for the first two, and no Indexing at all for the third - reading them back was
     * impossible. Appended right after the universal object section, before the ORIGIN footer.
     * <p>
     * The ScheduledJob/CommonModule "Properties" table is skipped when {@code full} is {@code true}:
     * {@code UniversalMetadataFormatter}'s full-mode "All Properties" reflective dump already lists
     * every plain {@code EAttribute} of both types (methodName / use / predefined /
     * restartCountOnFailure / restartIntervalOnFailure / key; server / serverCall / ... /
     * returnValuesReuse), so a second table here would only duplicate it.
     * <p>
     * The InformationRegister "Dimension Indexing" table is emitted regardless of {@code full}: the
     * generic attributes-table Indexing column (in {@code UniversalMetadataFormatter}) only
     * recognizes {@code com._1c.g5.v8.dt.metadata.mdclass.DbObjectAttribute}, while a register
     * dimension is a {@code com._1c.g5.v8.dt.metadata.mdclass.RegisterDimension} - a SEPARATE
     * sub-interface of {@code BasicFeature} - so a dimension never gets an Indexing value there in
     * EITHER mode; this is the only place Indexing is visible for a dimension.
     *
     * @param mdObject the resolved top object (a type this method does not handle is a no-op)
     * @param full the request's full-mode flag
     * @return the Markdown section to append (possibly empty)
     */
    static String formatTypeSpecificProperties(MdObject mdObject, boolean full)
    {
        StringBuilder sb = new StringBuilder();
        // Render in BOTH basic and full mode. It would be tempting to skip these in full mode
        // (the generic "All Properties" dump repeats the scalar getters), but that dump does NOT
        // include the transient Schedule reference, so skipping in full mode silently LOSES the
        // Schedule row that basic mode shows (codex #288). A little overlap in full mode is
        // preferable to full mode carrying LESS information than basic.
        if (mdObject instanceof ScheduledJob)
        {
            appendScheduledJobProperties(sb, (ScheduledJob)mdObject);
        }
        else if (mdObject instanceof CommonModule)
        {
            appendCommonModuleProperties(sb, (CommonModule)mdObject);
        }
        else if (mdObject instanceof InformationRegister)
        {
            appendDimensionIndexing(sb, (InformationRegister)mdObject);
        }
        return sb.toString();
    }

    /**
     * Renders a ScheduledJob's execution properties as a Property/Value table: the method it calls,
     * whether it is enabled/predefined, its failure-restart policy, its (import) key, and whether a
     * Schedule is attached. The Schedule is rendered as mere PRESENCE ("set" / {@link #DASH}), never a
     * {@code toString()} dump - a {@code Schedule} is a cross-model {@code EObject} (not an
     * {@code MdObject}), so dumping it would print an unhelpful implementation-detail string instead of
     * a meaningful value. Presence is read via {@link #scheduledJobHasSchedule(ScheduledJob)}.
     */
    private static void appendScheduledJobProperties(StringBuilder sb, ScheduledJob job)
    {
        sb.append("\n### Properties\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("Property", "Value")); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(MarkdownUtils.tableRow("Method Name", valueOrDash(job.getMethodName()))); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableRow("Use", yesNo(job.isUse()))); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableRow("Predefined", yesNo(job.isPredefined()))); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableRow("Restart Count On Failure", //$NON-NLS-1$
            String.valueOf(job.getRestartCountOnFailure())));
        sb.append(MarkdownUtils.tableRow("Restart Interval On Failure", //$NON-NLS-1$
            String.valueOf(job.getRestartIntervalOnFailure())));
        sb.append(MarkdownUtils.tableRow("Key", valueOrDash(job.getKey()))); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableRow("Schedule", scheduledJobHasSchedule(job) ? "set" : DASH)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Returns whether the job has an EXPLICITLY-configured Schedule, via EMF {@code eIsSet} on the
     * {@code SCHEDULED_JOB__SCHEDULE} feature. This deliberately does NOT use {@code getSchedule() != null}:
     * a {@code create_metadata}-made job carries a non-null DEFAULT schedule, so a null-check would
     * always report "set". {@code eIsSet} needs only the EMF feature literal, so it avoids importing the
     * {@code com._1c.g5.v8.dt.schedule.model} package (which this bundle's {@code MANIFEST.MF} does not
     * declare) and needs no reflection.
     */
    private static boolean scheduledJobHasSchedule(ScheduledJob job)
    {
        // eIsSet (EXPLICITLY configured), NOT getSchedule() != null: a create_metadata-made job
        // carries a non-null DEFAULT schedule, so a plain null-check would always render "set".
        // eIsSet is true only once a schedule is actually assigned - and it takes the EMF feature
        // literal, so it needs no import of the schedule model package (which this bundle does not
        // declare in its MANIFEST).
        return job.eIsSet(MdClassPackage.Literals.SCHEDULED_JOB__SCHEDULE);
    }

    /**
     * Renders a CommonModule's context-availability flags and its return-values-reuse mode as a
     * Property/Value table. Every boolean is rendered directly (Yes/No) - {@code false} is a valid,
     * meaningful value here (the user wants to see e.g. server=No), not an "absent" marker. The
     * {@code returnValuesReuse} enum is rendered by its LITERAL name (via {@link #enumLiteral(Object)}),
     * never a raw object dump.
     */
    private static void appendCommonModuleProperties(StringBuilder sb, CommonModule module)
    {
        sb.append("\n### Properties\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("Property", "Value")); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(MarkdownUtils.tableRow("Server", yesNo(module.isServer()))); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableRow("Server Call", yesNo(module.isServerCall()))); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableRow("Client (Managed Application)", //$NON-NLS-1$
            yesNo(module.isClientManagedApplication())));
        sb.append(MarkdownUtils.tableRow("Client (Ordinary Application)", //$NON-NLS-1$
            yesNo(module.isClientOrdinaryApplication())));
        sb.append(MarkdownUtils.tableRow("External Connection", yesNo(module.isExternalConnection()))); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableRow("Global", yesNo(module.isGlobal()))); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableRow("Privileged", yesNo(module.isPrivileged()))); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableRow("Return Values Reuse", //$NON-NLS-1$
            enumLiteral(module.getReturnValuesReuse())));
    }

    /**
     * Renders an InformationRegister's dimensions' Indexing as a small Dimension/Indexing table. A
     * no-op when the register has no dimensions. See {@link #formatTypeSpecificProperties} for why this
     * cannot be folded into the shared attributes table.
     */
    private static void appendDimensionIndexing(StringBuilder sb, InformationRegister register)
    {
        List<InformationRegisterDimension> dimensions = register.getDimensions();
        if (dimensions == null || dimensions.isEmpty())
        {
            return;
        }
        sb.append("\n### Dimension Indexing\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("Dimension", "Indexing")); //$NON-NLS-1$ //$NON-NLS-2$
        for (InformationRegisterDimension dimension : dimensions)
        {
            sb.append(MarkdownUtils.tableRow(dimension.getName(), enumLiteral(dimension.getIndexing())));
        }
    }

    /**
     * Renders a possibly-{@code null}/empty string, or {@link #DASH} when absent.
     */
    private static String valueOrDash(String value)
    {
        return value != null && !value.isEmpty() ? value : DASH;
    }

    /**
     * Renders a boolean as {@code Yes}/{@code No}, matching the Yes/No Markdown convention the rest of
     * get_metadata_details uses. {@code false} is rendered as {@code No}, not omitted - it is a real,
     * meaningful value for these flags.
     */
    private static String yesNo(boolean value)
    {
        return value ? "Yes" : "No"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Renders an EMF enum's LITERAL name (its {@code toString()}, which the generated enum overrides to
     * return the literal), or {@link #DASH} when {@code null}. Never used on a plain {@link EObject}
     * (e.g. a ScheduledJob's Schedule) - only on an actual enum value.
     */
    private static String enumLiteral(Object enumValue)
    {
        return enumValue != null ? enumValue.toString() : DASH;
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
     * Renders a template's Data Composition Schema STRUCTURE for a template FQN whose content is a
     * {@link DataCompositionSchema} (issue #267): resolves the FQN to its {@link BasicTemplate} via the
     * shared {@link MetadataNodeResolver} (handles a 2-part common template and a 4-part owned-object
     * template, bilingually - the same resolver {@code TemplateScreenshotHelper} uses for the analogous
     * SpreadsheetDocument-template screenshot), then inside a {@link BmTransactions#executeAndRollback}
     * boundary re-fetches it by its BM id and reads its content. A write-capable-but-rolled-back
     * transaction is required here (not a plain read) because {@code BasicTemplate.getTemplate()} lazily
     * materializes the content from the separate, lazily-loaded {@code .dcs} resource - a model-write side
     * effect - mirroring {@code TemplateScreenshotHelper.renderTemplate}'s reasoning for a
     * SpreadsheetDocument template; every edit the lazy materialization makes is discarded when the
     * transaction returns, so this pure read never persists anything.
     *
     * @param config the resolved configuration
     * @param bmModel the (best-effort) BM model; {@code null} yields {@code null}
     * @param fqn the requested FQN (not yet normalized)
     * @param language the resolved title/presentation language CODE (may be {@code null})
     * @return the rendered Markdown, or {@code null} when the FQN does not resolve to a template, the BM
     *         model is unavailable, or the resolved template's content is NOT a
     *         {@link DataCompositionSchema} (a {@code null} content, a {@code SpreadsheetDocument} print
     *         form, etc.) - {@code null} means "not applicable here", NOT a failure: the caller falls
     *         through to the generic object-resolution render.
     */
    private static String renderTemplateDcsIfApplicable(Configuration config, IBmModel bmModel, String fqn,
        String language)
    {
        if (bmModel == null)
        {
            return null;
        }
        MetadataNodeResolver.MetadataNode node = MetadataNodeResolver.resolveExisting(config, fqn);
        if (node == null || !(node.object instanceof BasicTemplate) || !(node.object instanceof IBmObject))
        {
            return null;
        }
        final long templateBmId = ((IBmObject)node.object).bmGetId();
        final String normalized = MetadataTypeUtils.normalizeFqn(fqn);
        return BmTransactions.executeAndRollback(bmModel, "GetMetadataDetailsTemplateDcs", (tx, monitor) -> //$NON-NLS-1$
        {
            EObject txTemplate = tx.getObjectById(templateBmId);
            EObject content =
                txTemplate instanceof BasicTemplate ? ((BasicTemplate)txTemplate).getTemplate() : null;
            if (!(content instanceof DataCompositionSchema))
            {
                return null;
            }
            return DcsStructureReader.render(normalized, (DataCompositionSchema)content, language);
        });
    }

    /**
     * Renders an XDTOPackage's CONTENT (object types with their properties, package-global properties,
     * value types, dependencies - issue #183 stream 1) via {@link XdtoStructureReader}: re-fetches the
     * package inside a {@link BmTransactions#executeAndRollback} boundary (required, not a plain read -
     * {@code XDTOPackage.getPackage()} lazily materializes the content from the separate, lazily-loaded
     * {@code .xdto} resource, a model-write side effect, exactly like {@code BasicTemplate.getTemplate()}
     * for the DCS branch above) and renders it. Returns {@code null} only when the BM model or the
     * package's BM id is unavailable (never - a resolved {@code mdObject} is always a real BM object);
     * an XDTOPackage with NO content yet still renders the "(no package content)" note, matching
     * {@link XdtoStructureReader#render}'s null-content contract.
     */
    private static String renderXdtoPackageStructure(IBmModel bmModel, XDTOPackage pkg, String fqn)
    {
        if (bmModel == null || !(pkg instanceof IBmObject))
        {
            return null;
        }
        final long pkgBmId = ((IBmObject)pkg).bmGetId();
        final String normalized = MetadataTypeUtils.normalizeFqn(fqn);
        return BmTransactions.executeAndRollback(bmModel, "GetMetadataDetailsXdtoPackage", (tx, monitor) -> //$NON-NLS-1$
        {
            EObject txPackage = tx.getObjectById(pkgBmId);
            Package content = txPackage instanceof XDTOPackage ? ((XDTOPackage)txPackage).getPackage() : null;
            return XdtoStructureReader.render(normalized, content);
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
