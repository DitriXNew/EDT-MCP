/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;

/**
 * Resolves the DCS root forms to transaction-stable BM ids and their persistence targets. Metadata
 * and form navigation stays in the shared resolvers; no workspace path or configuration tree is
 * traversed here by hand.
 *
 * <p>The caller first resolves the project with {@link ProjectContext#resolveMetadataRoot(String)} and
 * resolves its BM model, returning either shared failure before calling this class. Model objects never
 * escape this resolver: the result contains ids, canonical FQNs and the parsed form-member reference that
 * a later transaction uses to re-fetch the live object.</p>
 */
public final class DcsTargetResolver
{
    private static final String ECLASS_DYNAMIC_LIST_EXT_INFO = "DynamicListExtInfo"; //$NON-NLS-1$
    private static final String FEATURE_EXT_INFO = "extInfo"; //$NON-NLS-1$
    private static final String FEATURE_LIST_SETTINGS = "listSettings"; //$NON-NLS-1$

    private DcsTargetResolver()
    {
        // utility class
    }

    /** The metadata/form root shape selected by the FQN. */
    public enum TargetKind
    {
        REPORT_MAIN_DCS,
        COMMON_TEMPLATE,
        OWNED_TEMPLATE,
        FORM,
        DYNAMIC_LIST
    }

    /** Named transaction roles for BM ids returned by a successful resolution. */
    public enum BmRole
    {
        ROOT_OWNER,
        TEMPLATE,
        DCS_CONTENT,
        MD_FORM,
        FORM_CONTENT,
        FORM_ATTRIBUTE,
        DYNAMIC_LIST_EXT_INFO,
        LIST_SETTINGS
    }

    /**
     * Semantic force-export roles. A role is present even when its resource does not exist yet and its
     * FQN is therefore {@code null}; a write that creates that resource must fill the FQN inside its
     * transaction before submitting exports.
     */
    public enum ExportRole
    {
        OWNER_TOP_OBJECT,
        DCS_CONTENT,
        FORM_CONTENT,
        DYNAMIC_LIST_SETTINGS
    }

    /** Stable category for a structured resolution failure. */
    public enum FailureCode
    {
        INVALID_CONTEXT,
        UNSUPPORTED_ROOT,
        TARGET_NOT_FOUND,
        WRONG_TARGET_TYPE,
        BM_OBJECT_UNAVAILABLE,
        TRANSACTION_TARGET_MISSING,
        FORM_CONTENT_UNAVAILABLE,
        NOT_DYNAMIC_LIST,
        RESOLUTION_FAILED
    }

    /**
     * Resolves an address root. {@code projectContext} must be the successful result of
     * {@link ProjectContext#resolveMetadataRoot(String)}; this method does not repeat project resolution.
     * Reads of {@link BasicTemplate#getTemplate()} run only in
     * {@link BmTransactions#executeAndRollback(IBmModel, String, BmTransactions.BmOperation)} because the
     * getter lazily materializes the external resource.
     *
     * @param projectContext successful shared project/root resolution
     * @param bmModel the project's resolved BM model
     * @param address parsed DCS address
     * @return a target or a structured actionable failure
     */
    public static Resolution resolve(ProjectContext.ConfigurationResult projectContext,
        IBmModel bmModel, DcsAddress address)
    {
        return resolve(projectContext, bmModel, address, false);
    }

    /**
     * Write resolution variant that also accepts a plain form attribute. The caller must run the
     * existing destructive dynamic-list conversion preflight and consent gate before mutating it.
     */
    public static Resolution resolveForWrite(ProjectContext.ConfigurationResult projectContext,
        IBmModel bmModel, DcsAddress address)
    {
        return resolve(projectContext, bmModel, address, true);
    }

    private static Resolution resolve(ProjectContext.ConfigurationResult projectContext,
        IBmModel bmModel, DcsAddress address, boolean allowPlainDynamicList)
    {
        RootClassification classification = classifyRoot(address);
        if (!classification.isSuccess())
        {
            return Resolution.failure(classification.failure);
        }
        if (projectContext == null || !projectContext.ok() || projectContext.project() == null
            || projectContext.scope() == null)
        {
            return failure(FailureCode.INVALID_CONTEXT, classification.normalizedRoot, null,
                "DCS target '" + classification.normalizedRoot + "' cannot be resolved without a " //$NON-NLS-1$ //$NON-NLS-2$
                    + "successful ProjectContext.resolveMetadataRoot result. Return the project-resolution " //$NON-NLS-1$
                    + "error before resolving the DCS target."); //$NON-NLS-1$
        }
        if (bmModel == null)
        {
            return failure(FailureCode.BM_OBJECT_UNAVAILABLE, classification.normalizedRoot, null,
                "BM model is not available for DCS target '" + classification.normalizedRoot //$NON-NLS-1$
                    + "'. Wait for EDT to finish opening the project, then retry."); //$NON-NLS-1$
        }

        try
        {
            switch (classification.kind)
            {
                case REPORT_MAIN_DCS:
                    return resolveReport(projectContext, bmModel, address, classification);
                case COMMON_TEMPLATE:
                case OWNED_TEMPLATE:
                    return resolveTemplate(projectContext, bmModel, address, classification);
                case DYNAMIC_LIST:
                    return resolveDynamicList(projectContext, bmModel, address, classification,
                        allowPlainDynamicList);
                case FORM:
                    return resolveForm(projectContext, bmModel, address, classification);
                default:
                    return failure(FailureCode.UNSUPPORTED_ROOT, classification.normalizedRoot, null,
                        unsupportedRootMessage(classification.normalizedRoot));
            }
        }
        catch (RuntimeException e)
        {
            return failure(FailureCode.RESOLUTION_FAILED, classification.normalizedRoot,
                e.getClass().getSimpleName(),
                "Could not resolve DCS target '" + classification.normalizedRoot + "': " //$NON-NLS-1$ //$NON-NLS-2$
                    + messageOf(e) + ". Re-open or clean the project, then retry; if it persists, " //$NON-NLS-1$
                    + "report the target FQN and this message."); //$NON-NLS-1$
        }
    }

    /** Pure root-shape dispatch seam used by the unit test and by {@link #resolve}. */
    static RootClassification classifyRoot(DcsAddress address)
    {
        if (address == null)
        {
            return RootClassification.failure(new Failure(FailureCode.UNSUPPORTED_ROOT, null, null,
                unsupportedRootMessage(null)));
        }
        String normalized = MetadataTypeUtils.normalizeFqn(address.rootFqn());
        String[] parts = normalized.split("\\.", -1); //$NON-NLS-1$
        String englishType = parts.length == 0 ? null : MetadataTypeUtils.toEnglishSingular(parts[0]);

        if (parts.length == 2 && ("Report".equals(englishType) //$NON-NLS-1$
            || "ExternalReport".equals(englishType))) //$NON-NLS-1$
        {
            return RootClassification.success(TargetKind.REPORT_MAIN_DCS, normalized, null);
        }
        if (parts.length == 2 && "CommonTemplate".equals(englishType)) //$NON-NLS-1$
        {
            return RootClassification.success(TargetKind.COMMON_TEMPLATE, normalized, null);
        }
        if (parts.length == 4 && isNestedKind(parts[2], "Template")) //$NON-NLS-1$
        {
            return RootClassification.success(TargetKind.OWNED_TEMPLATE, normalized, null);
        }

        String formPath = FormElementWriter.parseFormPath(normalized);
        if (formPath != null)
        {
            return RootClassification.success(TargetKind.FORM, normalized, null);
        }

        FormElementWriter.FormMemberRef ref = FormElementWriter.parse(normalized);
        if (ref != null && !ref.isAttributeColumn() && !ref.isItemLevel()
            && FormElementWriter.kindForToken(ref.kindToken) == FormElementWriter.Kind.ATTRIBUTE)
        {
            return RootClassification.success(TargetKind.DYNAMIC_LIST, normalized, ref);
        }
        return RootClassification.failure(new Failure(FailureCode.UNSUPPORTED_ROOT, normalized, null,
            unsupportedRootMessage(normalized)));
    }

    /** Pure mapping that makes every root kind's required persistence targets explicit. */
    static List<ExportRole> requiredExportRoles(TargetKind kind)
    {
        if (kind == TargetKind.FORM)
        {
            return Collections.singletonList(ExportRole.FORM_CONTENT);
        }
        if (kind == TargetKind.DYNAMIC_LIST)
        {
            List<ExportRole> roles = new ArrayList<>();
            roles.add(ExportRole.FORM_CONTENT);
            roles.add(ExportRole.DYNAMIC_LIST_SETTINGS);
            return Collections.unmodifiableList(roles);
        }
        if (kind == TargetKind.REPORT_MAIN_DCS || kind == TargetKind.COMMON_TEMPLATE
            || kind == TargetKind.OWNED_TEMPLATE)
        {
            List<ExportRole> roles = new ArrayList<>();
            roles.add(ExportRole.OWNER_TOP_OBJECT);
            roles.add(ExportRole.DCS_CONTENT);
            return Collections.unmodifiableList(roles);
        }
        return Collections.emptyList();
    }

    /** Pure actionable error for a template whose external content is not a DCS. */
    static String nonDcsTemplateMessage(String fqn, String actualType)
    {
        return "Template '" + fqn + "' contains '" + actualType //$NON-NLS-1$ //$NON-NLS-2$
            + "', not a DataCompositionSchema. Address a DATA_COMPOSITION_SCHEMA template; " //$NON-NLS-1$
            + "for SpreadsheetDocument content use modify_metadata's template payload instead."; //$NON-NLS-1$
    }

    /** Pure actionable error for an attribute whose ext-info is not a dynamic list. */
    static String notDynamicListMessage(String fqn, String actualType)
    {
        return "Form attribute '" + fqn + "' is not a dynamic list (its extInfo is '" //$NON-NLS-1$ //$NON-NLS-2$
            + actualType + "'). Address an attribute whose value type is DynamicList and whose " //$NON-NLS-1$
            + "extInfo is DynamicListExtInfo."; //$NON-NLS-1$
    }

    private static Resolution resolveReport(ProjectContext.ConfigurationResult context,
        IBmModel bmModel, DcsAddress address, RootClassification classification)
    {
        String ownerType = DcsMainSchemaOwner.expectedType(classification.normalizedRoot);
        MetadataNodeResolver.MetadataNode node =
            MetadataNodeResolver.resolveExisting(context.scope(), classification.normalizedRoot);
        if (node == null)
        {
            return notFound(classification.normalizedRoot,
                "Verify the " + ownerType //$NON-NLS-1$
                    + " programmatic Name with get_metadata_objects, then retry."); //$NON-NLS-1$
        }
        if (!DcsMainSchemaOwner.supports(node.object))
        {
            return wrongType(classification.normalizedRoot, node.object,
                "Address an existing " + ownerType + ".<Name> root."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!(node.object instanceof IBmObject))
        {
            return bmObjectUnavailable(classification.normalizedRoot, node.object);
        }

        IBmObject reportBm = (IBmObject)node.object;
        String ownerFqn = topFqn(reportBm);
        if (ownerFqn == null)
        {
            return exportOwnerUnavailable(classification.normalizedRoot);
        }
        DcsInspection inspection = BmTransactions.executeAndRollback(bmModel,
            "ResolveReportMainDcs", (tx, monitor) -> //$NON-NLS-1$
        {
            EObject txObject = tx.getObjectById(reportBm.bmGetId());
            if (!DcsMainSchemaOwner.supports(txObject))
            {
                return DcsInspection.failure(new Failure(FailureCode.TRANSACTION_TARGET_MISSING,
                    classification.normalizedRoot, typeName(txObject),
                    ownerType + " '" + classification.normalizedRoot //$NON-NLS-1$
                        + "' disappeared before its DCS " //$NON-NLS-1$
                        + "could be read. Re-run dcs action=get.")); //$NON-NLS-1$
            }
            BasicTemplate template = DcsMainSchemaOwner.get(txObject);
            return template == null ? DcsInspection.empty() : inspectTemplate(template,
                classification.normalizedRoot);
        });
        if (inspection.failure != null)
        {
            return Resolution.failure(inspection.failure);
        }

        EnumMap<BmRole, Long> ids = new EnumMap<>(BmRole.class);
        putId(ids, BmRole.ROOT_OWNER, reportBm);
        inspection.copyIdsTo(ids);
        List<ExportTarget> exports = dcsExports(ownerFqn, inspection.contentFqn);
        return Resolution.success(new Target(address, classification.normalizedRoot,
            TargetKind.REPORT_MAIN_DCS, ids, exports, null));
    }

    private static Resolution resolveTemplate(ProjectContext.ConfigurationResult context,
        IBmModel bmModel, DcsAddress address, RootClassification classification)
    {
        MetadataNodeResolver.MetadataNode node =
            MetadataNodeResolver.resolveExisting(context.scope(), classification.normalizedRoot);
        if (node == null)
        {
            return notFound(classification.normalizedRoot,
                "Verify the template programmatic Name and owner FQN with get_metadata_details, then retry."); //$NON-NLS-1$
        }
        if (!(node.object instanceof BasicTemplate))
        {
            return wrongType(classification.normalizedRoot, node.object,
                "Address a CommonTemplate or an object's Template member."); //$NON-NLS-1$
        }
        if (!(node.object instanceof IBmObject))
        {
            return bmObjectUnavailable(classification.normalizedRoot, node.object);
        }

        IBmObject templateBm = (IBmObject)node.object;
        IBmObject owner = topObject(templateBm);
        String ownerFqn = topFqn(templateBm);
        if (owner == null || ownerFqn == null)
        {
            return exportOwnerUnavailable(classification.normalizedRoot);
        }
        DcsInspection inspection = BmTransactions.executeAndRollback(bmModel,
            "ResolveTemplateDcs", (tx, monitor) -> //$NON-NLS-1$
        {
            EObject txObject = tx.getObjectById(templateBm.bmGetId());
            if (!(txObject instanceof BasicTemplate))
            {
                return DcsInspection.failure(new Failure(FailureCode.TRANSACTION_TARGET_MISSING,
                    classification.normalizedRoot, typeName(txObject),
                    "Template '" + classification.normalizedRoot + "' disappeared before its content " //$NON-NLS-1$ //$NON-NLS-2$
                        + "could be read. Re-run dcs action=get.")); //$NON-NLS-1$
            }
            return inspectTemplate((BasicTemplate)txObject, classification.normalizedRoot);
        });
        if (inspection.failure != null)
        {
            return Resolution.failure(inspection.failure);
        }

        EnumMap<BmRole, Long> ids = new EnumMap<>(BmRole.class);
        putId(ids, BmRole.ROOT_OWNER, owner);
        putId(ids, BmRole.TEMPLATE, templateBm);
        inspection.copyIdsTo(ids);
        return Resolution.success(new Target(address, classification.normalizedRoot,
            classification.kind, ids, dcsExports(ownerFqn, inspection.contentFqn), null));
    }

    private static Resolution resolveDynamicList(ProjectContext.ConfigurationResult context,
        IBmModel bmModel, DcsAddress address, RootClassification classification,
        boolean allowPlainDynamicList)
    {
        // Resolve the FORM through the form-aware resolver, the same one FormElementWriter.resolveForEdit
        // uses - not the generic MetadataNodeResolver. A managed form is addressed as
        // 'Type.Object.Form.Name' (or 'CommonForm.Name'), a shape the generic node resolver does not
        // walk, so using it here made every dynamic-list root report "not found" for a form that
        // modify_metadata could resolve perfectly well.
        MdObject mdForm =
            FormStructureReader.resolveMdForm(context.scope(), classification.formMemberRef.formPath);
        if (mdForm == null)
        {
            return notFound(classification.normalizedRoot,
                "Verify the form and attribute programmatic Names with get_metadata_details, then retry."); //$NON-NLS-1$
        }
        if (!(mdForm instanceof IBmObject))
        {
            return bmObjectUnavailable(classification.normalizedRoot, mdForm);
        }

        IBmObject mdFormBm = (IBmObject)mdForm;
        DynamicListInspection inspection = BmTransactions.executeAndRollback(bmModel,
            "ResolveDynamicListDcs", (tx, monitor) -> //$NON-NLS-1$
        {
            EObject txMdForm = tx.getObjectById(mdFormBm.bmGetId());
            if (txMdForm == null)
            {
                return DynamicListInspection.failure(new Failure(FailureCode.TRANSACTION_TARGET_MISSING,
                    classification.normalizedRoot, null,
                    "Form for dynamic-list target '" + classification.normalizedRoot //$NON-NLS-1$
                        + "' disappeared before it could be read. Re-run dcs action=get.")); //$NON-NLS-1$
            }
            EObject form = FormElementWriter.getEditableForm(txMdForm);
            if (form == null)
            {
                return DynamicListInspection.failure(new Failure(FailureCode.FORM_CONTENT_UNAVAILABLE,
                    classification.normalizedRoot, null,
                    "Form for dynamic-list target '" + classification.normalizedRoot //$NON-NLS-1$
                        + "' has no editable managed-form content. Open and save it in the form designer, " //$NON-NLS-1$
                        + "then retry.")); //$NON-NLS-1$
            }
            EObject attribute = FormElementWriter.resolveFormMember(form, classification.formMemberRef);
            if (attribute == null)
            {
                return DynamicListInspection.failure(new Failure(FailureCode.TARGET_NOT_FOUND,
                    classification.normalizedRoot, null,
                    "Form attribute '" + classification.normalizedRoot //$NON-NLS-1$
                        + "' was not found. Verify its programmatic Name with get_metadata_details.")); //$NON-NLS-1$
            }
            EObject extInfo = singleReference(attribute, FEATURE_EXT_INFO);
            String actual = extInfo == null ? "none" : extInfo.eClass().getName(); //$NON-NLS-1$
            if (extInfo == null || !ECLASS_DYNAMIC_LIST_EXT_INFO.equals(actual))
            {
                if (allowPlainDynamicList)
                {
                    return inspectDynamicList(form, attribute, null, null,
                        classification.normalizedRoot);
                }
                return DynamicListInspection.failure(new Failure(FailureCode.NOT_DYNAMIC_LIST,
                    classification.normalizedRoot, actual,
                    notDynamicListMessage(classification.normalizedRoot, actual)));
            }
            EObject settings = singleReference(extInfo, FEATURE_LIST_SETTINGS);
            if (settings != null && !(settings instanceof DataCompositionSettings))
            {
                String settingsType = typeName(settings);
                return DynamicListInspection.failure(new Failure(FailureCode.WRONG_TARGET_TYPE,
                    classification.normalizedRoot, settingsType,
                    "Dynamic-list settings for '" + classification.normalizedRoot + "' are '" //$NON-NLS-1$ //$NON-NLS-2$
                        + settingsType + "', not DataCompositionSettings. Re-save the form in EDT, " //$NON-NLS-1$
                        + "then retry.")); //$NON-NLS-1$
            }
            return inspectDynamicList(form, attribute, extInfo, settings,
                classification.normalizedRoot);
        });
        if (inspection.failure != null)
        {
            return Resolution.failure(inspection.failure);
        }

        EnumMap<BmRole, Long> ids = new EnumMap<>(BmRole.class);
        putId(ids, BmRole.MD_FORM, mdFormBm);
        inspection.copyIdsTo(ids);
        List<ExportTarget> exports = new ArrayList<>();
        exports.add(new ExportTarget(ExportRole.FORM_CONTENT, inspection.formContentFqn));
        exports.add(new ExportTarget(ExportRole.DYNAMIC_LIST_SETTINGS, inspection.settingsCarrierFqn));
        return Resolution.success(new Target(address, classification.normalizedRoot,
            TargetKind.DYNAMIC_LIST, ids, exports, classification.formMemberRef));
    }

    private static Resolution resolveForm(ProjectContext.ConfigurationResult context,
        IBmModel bmModel, DcsAddress address, RootClassification classification)
    {
        String formPath = FormElementWriter.parseFormPath(classification.normalizedRoot);
        MdObject mdForm = FormStructureReader.resolveMdForm(context.scope(), formPath);
        if (mdForm == null)
        {
            return notFound(classification.normalizedRoot,
                "Verify the form programmatic Name with get_metadata_details, then retry."); //$NON-NLS-1$
        }
        if (!(mdForm instanceof IBmObject))
        {
            return bmObjectUnavailable(classification.normalizedRoot, mdForm);
        }

        IBmObject mdFormBm = (IBmObject)mdForm;
        FormInspection inspection = BmTransactions.executeAndRollback(bmModel,
            "ResolveFormConditionalAppearance", (tx, monitor) -> //$NON-NLS-1$
        {
            EObject txMdForm = tx.getObjectById(mdFormBm.bmGetId());
            EObject content = txMdForm == null ? null : FormElementWriter.getEditableForm(txMdForm);
            if (!(content instanceof Form))
            {
                return FormInspection.failure(new Failure(FailureCode.FORM_CONTENT_UNAVAILABLE,
                    classification.normalizedRoot, typeName(content),
                    "Form '" + classification.normalizedRoot //$NON-NLS-1$
                        + "' has no editable managed-form content. Open and save it in the form " //$NON-NLS-1$
                        + "designer, then retry.")); //$NON-NLS-1$
            }
            String contentFqn = ownTopFqn(content);
            if (contentFqn == null)
            {
                return FormInspection.failure(new Failure(FailureCode.BM_OBJECT_UNAVAILABLE,
                    classification.normalizedRoot, typeName(content),
                    "Editable content Form for '" + classification.normalizedRoot //$NON-NLS-1$
                        + "' has no top-object FQN to export. Re-open and save the form, then retry.")); //$NON-NLS-1$
            }
            return FormInspection.success(contentFqn);
        });
        if (inspection.failure != null) return Resolution.failure(inspection.failure);

        EnumMap<BmRole, Long> ids = new EnumMap<>(BmRole.class);
        putId(ids, BmRole.MD_FORM, mdFormBm);
        List<ExportTarget> exports = Collections.singletonList(
            new ExportTarget(ExportRole.FORM_CONTENT, inspection.formContentFqn));
        return Resolution.success(new Target(address, classification.normalizedRoot,
            TargetKind.FORM, ids, exports, null));
    }

    private static DcsInspection inspectTemplate(BasicTemplate template, String fqn)
    {
        EObject content = template.getTemplate();
        final boolean declaresDcs =
            template.getTemplateType() == TemplateType.DATA_COMPOSITION_SCHEMA;
        // A template DECLARED as a DCS whose .dcs resource has not been materialized yet does NOT
        // answer getTemplate() with null - it answers with an unresolved placeholder whose eClass
        // degrades to the bare EObject. That is an EMPTY schema awaiting its first write, not wrong
        // content, so it is normalized to "absent" here; the declared type is what makes this safe,
        // so a SpreadsheetDocument print form still reports its real content type below.
        if (content != null && !(content instanceof DataCompositionSchema) && declaresDcs
            && (content.eIsProxy() || content.eClass() == EcorePackage.Literals.EOBJECT))
        {
            content = null;
        }
        if (content != null && !(content instanceof DataCompositionSchema))
        {
            String actual = typeName(content);
            return DcsInspection.failure(new Failure(FailureCode.WRONG_TARGET_TYPE, fqn, actual,
                nonDcsTemplateMessage(fqn, actual)));
        }
        if (content == null && !declaresDcs)
        {
            String declared = template.getTemplateType() == null
                ? "no content; declared template type is unset" //$NON-NLS-1$
                : "no content; declared template type is " + template.getTemplateType().getName(); //$NON-NLS-1$
            return DcsInspection.failure(new Failure(FailureCode.WRONG_TARGET_TYPE, fqn, declared,
                nonDcsTemplateMessage(fqn, declared)));
        }

        DcsInspection result = new DcsInspection();
        putId(result.ids, BmRole.TEMPLATE, template);
        if (content != null)
        {
            putId(result.ids, BmRole.DCS_CONTENT, content);
            result.contentFqn = ownTopFqn(content);
        }
        return result;
    }

    private static DynamicListInspection inspectDynamicList(EObject form, EObject attribute,
        EObject extInfo, EObject settings, String fqn)
    {
        DynamicListInspection result = new DynamicListInspection();
        putId(result.ids, BmRole.FORM_CONTENT, form);
        putId(result.ids, BmRole.FORM_ATTRIBUTE, attribute);
        putId(result.ids, BmRole.DYNAMIC_LIST_EXT_INFO, extInfo);
        putId(result.ids, BmRole.LIST_SETTINGS, settings);

        result.formContentFqn = ownTopFqn(form);
        if (result.formContentFqn == null)
        {
            result.failure = new Failure(FailureCode.BM_OBJECT_UNAVAILABLE, fqn, typeName(form),
                "Editable content Form for '" + fqn + "' has no top-object FQN to export. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Re-open and save the form in EDT, then retry."); //$NON-NLS-1$
            return result;
        }
        // The list settings are an @ExternalProperty sub-resource, NOT a BM top object of their own, so
        // asking them for an FQN throws ("attached BM objects only"). What actually drains
        // Attributes/<Name>/ExtInfo/ListSettings.dcss is exporting the content FORM, so fall back to it
        // rather than losing the export.
        String carrier = settings == null ? null : carrierFqn(settings);
        result.settingsCarrierFqn = carrier != null ? carrier : result.formContentFqn;
        return result;
    }

    private static List<ExportTarget> dcsExports(String ownerFqn, String contentFqn)
    {
        List<ExportTarget> exports = new ArrayList<>();
        exports.add(new ExportTarget(ExportRole.OWNER_TOP_OBJECT, ownerFqn));
        exports.add(new ExportTarget(ExportRole.DCS_CONTENT, contentFqn));
        return exports;
    }

    private static Resolution notFound(String fqn, String fix)
    {
        return failure(FailureCode.TARGET_NOT_FOUND, fqn, null,
            "DCS root target '" + fqn + "' was not found. " + fix); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Resolution wrongType(String fqn, EObject actual, String fix)
    {
        String actualType = typeName(actual);
        return failure(FailureCode.WRONG_TARGET_TYPE, fqn, actualType,
            "DCS root target '" + fqn + "' is '" + actualType + "', which cannot own the " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "requested DCS content. " + fix); //$NON-NLS-1$
    }

    private static Resolution bmObjectUnavailable(String fqn, EObject actual)
    {
        return failure(FailureCode.BM_OBJECT_UNAVAILABLE, fqn, typeName(actual),
            "DCS root target '" + fqn + "' is not a managed BM object. Re-open or clean the " //$NON-NLS-1$ //$NON-NLS-2$
                + "project, then retry."); //$NON-NLS-1$
    }

    private static Resolution exportOwnerUnavailable(String fqn)
    {
        return failure(FailureCode.BM_OBJECT_UNAVAILABLE, fqn, null,
            "Cannot determine the top-level force-export target for DCS root '" + fqn //$NON-NLS-1$
                + "'. Re-open or clean the project, then retry; if it persists, report the FQN."); //$NON-NLS-1$
    }

    private static Resolution failure(FailureCode code, String fqn, String actualType, String message)
    {
        return Resolution.failure(new Failure(code, fqn, actualType, message));
    }

    private static boolean isNestedKind(String token, String english)
    {
        MetadataTypeUtils.NestedKindInfo info = MetadataTypeUtils.resolveNestedKind(token);
        return info != null && english.equals(info.getEnglish());
    }

    private static EObject singleReference(EObject object, String featureName)
    {
        if (object == null)
        {
            return null;
        }
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null || feature.isMany())
        {
            return null;
        }
        Object value = object.eGet(feature);
        return value instanceof EObject ? (EObject)value : null;
    }

    private static void putId(Map<BmRole, Long> ids, BmRole role, Object object)
    {
        if (object instanceof IBmObject)
        {
            ids.put(role, Long.valueOf(((IBmObject)object).bmGetId()));
        }
    }

    private static IBmObject topObject(IBmObject object)
    {
        return object == null ? null : object.bmIsTop() ? object : object.bmGetTopObject();
    }

    /**
     * The FQN of an object's BM top object, or {@code null} when it has none. {@code bmGetFqn()} is
     * legal only on an ATTACHED TOP object and throws otherwise, so both conditions are checked here -
     * a transient {@code @ExternalProperty} sub-resource (a form's list settings, a template's DCS
     * content before it is attached) legitimately has no FQN, and that is an answer, not a failure.
     */
    private static String topFqn(IBmObject object)
    {
        return fqnOrNull(topObject(object));
    }

    private static String ownTopFqn(Object object)
    {
        return object instanceof IBmObject ? fqnOrNull((IBmObject)object) : null;
    }

    /**
     * The object's BM FQN, or {@code null} when it has none. {@code bmGetFqn()} is legal only on an
     * ATTACHED TOP object and THROWS otherwise, and {@link IBmObject} exposes no attachment predicate -
     * {@code bmIsTop()} alone is not enough, because a detached top object still answers {@code true}.
     * The call is therefore the only reliable test.
     * <p>
     * Having no FQN is a legitimate answer here, not a failure: a form's list settings and a template's
     * DCS content are transient {@code @ExternalProperty} sub-resources that never carry one. Callers
     * fall back to the owning top object, which is what actually drains them to disk.
     */
    private static String fqnOrNull(IBmObject object)
    {
        if (object == null || !object.bmIsTop())
        {
            return null;
        }
        try
        {
            return object.bmGetFqn();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static String carrierFqn(Object object)
    {
        return object instanceof IBmObject ? topFqn((IBmObject)object) : null;
    }

    private static String typeName(EObject object)
    {
        return object == null ? "none" : object.eClass().getName(); //$NON-NLS-1$
    }

    private static String messageOf(RuntimeException error)
    {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String unsupportedRootMessage(String fqn)
    {
        if (fqn != null)
        {
            String[] parts = fqn.split("\\.", -1); //$NON-NLS-1$
            String englishType = parts.length == 0 ? null
                : MetadataTypeUtils.toEnglishSingular(parts[0]);
            if (parts.length == 2 && "ExternalDataProcessor".equals(englishType)) //$NON-NLS-1$
            {
                return "FQN '" + fqn + "' is not a supported main-DCS root: an " //$NON-NLS-1$ //$NON-NLS-2$
                    + "ExternalDataProcessor has owned DCS templates but no main DCS. Use " //$NON-NLS-1$
                    + "ExternalDataProcessor.<Name>.Template.<Name> instead."; //$NON-NLS-1$
            }
        }
        return "FQN '" + fqn + "' is not a supported DCS root. Use Report.<Name>, " //$NON-NLS-1$ //$NON-NLS-2$
            + "ExternalReport.<Name>, CommonTemplate.<Name>, " //$NON-NLS-1$
            + "<Type>.<Owner>.Template.<Name>, or " //$NON-NLS-1$
            + "<Type>.<Owner>.Form.<Name> (for conditional appearance), or " //$NON-NLS-1$
            + "<Type>.<Owner>.Form.<Name>.Attribute.<Name> (for a dynamic list)."; //$NON-NLS-1$
    }

    /** Successful target or structured failure; neither branch throws across the utility boundary. */
    public static final class Resolution
    {
        private final Target target;
        private final Failure failure;

        private Resolution(Target target, Failure failure)
        {
            this.target = target;
            this.failure = failure;
        }

        private static Resolution success(Target target)
        {
            return new Resolution(target, null);
        }

        private static Resolution failure(Failure failure)
        {
            return new Resolution(null, failure);
        }

        /** @return whether a target resolved */
        public boolean isSuccess()
        {
            return target != null;
        }

        /** @return the resolved descriptor, or {@code null} on failure */
        public Target target()
        {
            return target;
        }

        /** @return the structured failure, or {@code null} on success */
        public Failure failure()
        {
            return failure;
        }
    }

    /** Transaction-stable descriptor of a resolved root. */
    public static final class Target
    {
        private final DcsAddress address;
        private final String normalizedRootFqn;
        private final TargetKind kind;
        private final Map<BmRole, Long> bmIds;
        private final List<ExportTarget> exportTargets;
        private final FormElementWriter.FormMemberRef formMemberRef;

        private Target(DcsAddress address, String normalizedRootFqn, TargetKind kind,
            Map<BmRole, Long> bmIds, List<ExportTarget> exportTargets,
            FormElementWriter.FormMemberRef formMemberRef)
        {
            this.address = address;
            this.normalizedRootFqn = normalizedRootFqn;
            this.kind = kind;
            this.bmIds = Collections.unmodifiableMap(new EnumMap<>(bmIds));
            this.exportTargets = Collections.unmodifiableList(new ArrayList<>(exportTargets));
            this.formMemberRef = formMemberRef;
        }

        public DcsAddress address()
        {
            return address;
        }

        public String normalizedRootFqn()
        {
            return normalizedRootFqn;
        }

        public TargetKind kind()
        {
            return kind;
        }

        public Map<BmRole, Long> bmIds()
        {
            return bmIds;
        }

        public Long bmId(BmRole role)
        {
            return bmIds.get(role);
        }

        public List<ExportTarget> exportTargets()
        {
            return exportTargets;
        }

        /**
         * Existing FQNs to pass to {@link BmTransactions#forceExportToDisk}, in stable order and
         * without duplicates. A write that materializes a currently-null export role must append its
         * in-transaction FQN before calling the exporter.
         */
        public List<String> forceExportFqns()
        {
            LinkedHashSet<String> fqns = new LinkedHashSet<>();
            for (ExportTarget target : exportTargets)
            {
                if (target.fqn != null && !target.fqn.isEmpty())
                {
                    fqns.add(target.fqn);
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(fqns));
        }

        public FormElementWriter.FormMemberRef formMemberRef()
        {
            return formMemberRef;
        }

        /** Form path in the shape accepted by {@link FormElementWriter#resolveForEdit}. */
        public String formPath()
        {
            return formMemberRef == null ? FormElementWriter.parseFormPath(normalizedRootFqn)
                : formMemberRef.formPath;
        }
    }

    private static final class FormInspection
    {
        final String formContentFqn;
        final Failure failure;

        private FormInspection(String formContentFqn, Failure failure)
        {
            this.formContentFqn = formContentFqn;
            this.failure = failure;
        }

        static FormInspection success(String fqn)
        {
            return new FormInspection(fqn, null);
        }

        static FormInspection failure(Failure failure)
        {
            return new FormInspection(null, failure);
        }
    }

    /** One required persistence role and the currently resolved carrier FQN. */
    public static final class ExportTarget
    {
        private final ExportRole role;
        private final String fqn;

        private ExportTarget(ExportRole role, String fqn)
        {
            this.role = role;
            this.fqn = fqn;
        }

        public ExportRole role()
        {
            return role;
        }

        /** @return current carrier FQN, or {@code null} when the write must materialize it */
        public String fqn()
        {
            return fqn;
        }
    }

    /** Structured failure for the future tool layer to wrap with {@code ToolResult.error(message)}. */
    public static final class Failure
    {
        private final FailureCode code;
        private final String fqn;
        private final String actualType;
        private final String message;

        private Failure(FailureCode code, String fqn, String actualType, String message)
        {
            this.code = code;
            this.fqn = fqn;
            this.actualType = actualType;
            this.message = message;
        }

        public FailureCode code()
        {
            return code;
        }

        public String fqn()
        {
            return fqn;
        }

        public String actualType()
        {
            return actualType;
        }

        public String message()
        {
            return message;
        }
    }

    static final class RootClassification
    {
        final TargetKind kind;
        final String normalizedRoot;
        final FormElementWriter.FormMemberRef formMemberRef;
        final Failure failure;

        private RootClassification(TargetKind kind, String normalizedRoot,
            FormElementWriter.FormMemberRef formMemberRef, Failure failure)
        {
            this.kind = kind;
            this.normalizedRoot = normalizedRoot;
            this.formMemberRef = formMemberRef;
            this.failure = failure;
        }

        static RootClassification success(TargetKind kind, String normalizedRoot,
            FormElementWriter.FormMemberRef formMemberRef)
        {
            return new RootClassification(kind, normalizedRoot, formMemberRef, null);
        }

        static RootClassification failure(Failure failure)
        {
            return new RootClassification(null, failure.fqn(), null, failure);
        }

        boolean isSuccess()
        {
            return kind != null;
        }
    }

    private static final class DcsInspection
    {
        final EnumMap<BmRole, Long> ids = new EnumMap<>(BmRole.class);
        Failure failure;
        String contentFqn;

        static DcsInspection empty()
        {
            return new DcsInspection();
        }

        static DcsInspection failure(Failure failure)
        {
            DcsInspection result = new DcsInspection();
            result.failure = failure;
            return result;
        }

        void copyIdsTo(Map<BmRole, Long> destination)
        {
            destination.putAll(ids);
        }
    }

    private static final class DynamicListInspection
    {
        final EnumMap<BmRole, Long> ids = new EnumMap<>(BmRole.class);
        Failure failure;
        String formContentFqn;
        String settingsCarrierFqn;

        static DynamicListInspection failure(Failure failure)
        {
            DynamicListInspection result = new DynamicListInspection();
            result.failure = failure;
            return result;
        }

        void copyIdsTo(Map<BmRole, Long> destination)
        {
            destination.putAll(ids);
        }
    }
}
