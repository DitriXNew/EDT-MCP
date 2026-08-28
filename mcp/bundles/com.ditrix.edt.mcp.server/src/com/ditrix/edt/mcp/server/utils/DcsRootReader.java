/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.form.model.DynamicListExtInfo;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.BmRole;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.Target;
import com.ditrix.edt.mcp.server.utils.DcsTargetResolver.TargetKind;

/** Re-fetches a {@link DcsTargetResolver} descriptor inside the caller's active transaction. */
public final class DcsRootReader
{
    private static final String FEATURE_EXT_INFO = "extInfo"; //$NON-NLS-1$

    private DcsRootReader()
    {
        // utility class
    }

    /**
     * Reads the live root without allowing an EObject to escape the caller's transaction. Template
     * content is deliberately reached through {@link BasicTemplate#getTemplate()}, whose lazy model
     * writes are why callers must use {@link BmTransactions#executeAndRollback}.
     *
     * @param transaction active rollback transaction
     * @param target stable descriptor produced by {@link DcsTargetResolver}
     * @return root or an actionable failure
     */
    public static Result read(IBmTransaction transaction, Target target)
    {
        if (transaction == null || target == null)
        {
            return Result.failure("The resolved DCS target is unavailable. Re-run dcs action='get'."); //$NON-NLS-1$
        }
        if (target.kind() == TargetKind.DYNAMIC_LIST)
        {
            return readDynamicList(transaction, target, false);
        }
        if (target.kind() == TargetKind.FORM)
        {
            return readFormConditionalAppearance(transaction, target);
        }
        return readSchema(transaction, target);
    }

    /** Write-side read that represents an allowed plain-attribute conversion as a null root. */
    public static Result readForWrite(IBmTransaction transaction, Target target)
    {
        if (transaction == null || target == null)
        {
            return Result.failure("The resolved DCS target is unavailable. Re-run dcs action='get'."); //$NON-NLS-1$
        }
        return target.kind() == TargetKind.DYNAMIC_LIST
            ? readDynamicList(transaction, target, true)
            : target.kind() == TargetKind.FORM
                ? readFormConditionalAppearance(transaction, target) : readSchema(transaction, target);
    }

    private static Result readSchema(IBmTransaction transaction, Target target)
    {
        Long id = target.bmId(BmRole.TEMPLATE);
        if (id == null)
        {
            // A Report or ExternalReport may not have materialized its main DCS template yet. This
            // is a valid empty root whose hash must still be returned, rather than a not-found error.
            return Result.success(null);
        }
        EObject object = transaction.getObjectById(id.longValue());
        if (!(object instanceof BasicTemplate))
        {
            return Result.failure("DCS template for '" + target.normalizedRootFqn() //$NON-NLS-1$
                + "' disappeared before it could be read. Re-run dcs action='get'."); //$NON-NLS-1$
        }
        EObject content = ((BasicTemplate)object).getTemplate();
        // A template DECLARED as a DCS whose content resource has not been materialized yet answers
        // with an unresolved placeholder (eClass degrades to the bare EObject), not null. That is an
        // EMPTY schema - render it as such rather than refusing to read it.
        if (content != null && !(content instanceof DataCompositionSchema)
            && ((BasicTemplate)object).getTemplateType() == TemplateType.DATA_COMPOSITION_SCHEMA
            && (content.eIsProxy() || content.eClass() == EcorePackage.Literals.EOBJECT))
        {
            content = null;
        }
        if (content != null && !(content instanceof DataCompositionSchema))
        {
            return Result.failure("DCS root '" + target.normalizedRootFqn() + "' now contains '" //$NON-NLS-1$ //$NON-NLS-2$
                + content.eClass().getName() + "', not DataCompositionSchema. Address a DCS template " //$NON-NLS-1$
                + "or re-save the template with type DATA_COMPOSITION_SCHEMA."); //$NON-NLS-1$
        }
        return Result.success(content);
    }

    private static Result readDynamicList(IBmTransaction transaction, Target target,
        boolean allowUnmaterialized)
    {
        Long id = target.bmId(BmRole.MD_FORM);
        EObject mdForm = id == null ? null : transaction.getObjectById(id.longValue());
        EObject form = mdForm == null ? null : FormElementWriter.getEditableForm(mdForm);
        EObject attribute = form == null ? null
            : FormElementWriter.resolveFormMember(form, target.formMemberRef());
        EObject extInfo = singleReference(attribute, FEATURE_EXT_INFO);
        if (extInfo == null && allowUnmaterialized
            && target.bmId(BmRole.DYNAMIC_LIST_EXT_INFO) == null)
        {
            return Result.success(null);
        }
        if (!(extInfo instanceof DynamicListExtInfo))
        {
            String actual = extInfo == null ? "none" : extInfo.eClass().getName(); //$NON-NLS-1$
            return Result.failure(DcsTargetResolver.notDynamicListMessage(
                target.normalizedRootFqn(), actual));
        }
        return Result.success((DynamicListExtInfo)extInfo);
    }

    private static Result readFormConditionalAppearance(IBmTransaction transaction, Target target)
    {
        Long id = target.bmId(BmRole.MD_FORM);
        EObject mdForm = id == null ? null : transaction.getObjectById(id.longValue());
        EObject content = mdForm == null ? null : FormElementWriter.getEditableForm(mdForm);
        if (!(content instanceof Form))
        {
            return Result.failure("Form '" + target.normalizedRootFqn() //$NON-NLS-1$
                + "' has no editable managed-form content. Re-run dcs action='get'."); //$NON-NLS-1$
        }
        Form form = (Form)content;
        DcsFormAppearanceContent.Result appearance =
            DcsFormAppearanceContent.resolve(transaction, form);
        if (!appearance.isSuccess())
        {
            return Result.failure(appearance.error());
        }
        return Result.success(appearance.appearance());
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

    /** Transaction-local root result. */
    public static final class Result
    {
        private final EObject root;
        private final String error;

        private Result(EObject root, String error)
        {
            this.root = root;
            this.error = error;
        }

        static Result success(EObject root)
        {
            return new Result(root, null);
        }

        static Result failure(String error)
        {
            return new Result(null, error);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public EObject root()
        {
            return root;
        }

        public String error()
        {
            return error;
        }
    }
}
