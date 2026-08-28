/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com.ditrix.edt.mcp.server.Activator;

/** Resolves and materializes a form's BM external conditional-appearance property. */
public final class DcsFormAppearanceContent
{
    private DcsFormAppearanceContent()
    {
        // Utility class
    }

    /** Resolves the external property by its owner/feature FQN inside the active transaction. */
    public static Result resolve(IBmTransaction transaction, Form form)
    {
        Activator activator = Activator.getDefault();
        return resolve(transaction, form,
            activator == null ? null : activator.getTopObjectFqnGenerator());
    }

    static Result resolve(IBmTransaction transaction, Form form,
        ITopObjectFqnGenerator generator)
    {
        if (transaction == null || form == null)
        {
            return Result.failure("The form conditional-appearance owner is unavailable in the " //$NON-NLS-1$
                + "active BM transaction. Re-run dcs action='get'."); //$NON-NLS-1$
        }
        // FormImpl's typed getter resolves proxies. EDT's own rename contributor deliberately reads
        // this @ExternalProperty with resolve=false, so an absent external top object stays an
        // ordinary empty state instead of becoming a resource-local resolution attempt.
        DataCompositionConditionalAppearance raw = rawAppearance(form);
        if (raw == null)
        {
            return Result.success(null, null);
        }
        FqnResult external = externalFqn(form, generator);
        if (external.error != null)
        {
            return Result.failure(external.error);
        }
        IBmObject object = transaction.getTopObjectByFqn(external.fqn);
        if (object == null)
        {
            // A brand-new form owns a proxy to the canonical external-property URI before the top
            // object exists. That means an empty appearance, not a broken form.
            return Result.success(null, external.fqn);
        }
        if (!(object instanceof DataCompositionConditionalAppearance))
        {
            return Result.failure("The form conditional-appearance FQN '" + external.fqn //$NON-NLS-1$
                + "' resolves to " + object.eClass().getName() //$NON-NLS-1$
                + ", not DataCompositionConditionalAppearance. Re-save the form and retry."); //$NON-NLS-1$
        }
        return Result.success((DataCompositionConditionalAppearance)object, external.fqn);
    }

    /**
     * Attaches an absent external property, re-fetches it from BM, then copies the detached plan.
     * The returned FQN must be force-exported in addition to the content form FQN.
     */
    public static Result commit(IBmTransaction transaction, Form form,
        DataCompositionConditionalAppearance planned)
    {
        Activator activator = Activator.getDefault();
        return commit(transaction, form, planned,
            activator == null ? null : activator.getTopObjectFqnGenerator());
    }

    static Result commit(IBmTransaction transaction, Form form,
        DataCompositionConditionalAppearance planned, ITopObjectFqnGenerator generator)
    {
        if (transaction == null || form == null)
        {
            return Result.failure("The form conditional-appearance owner is unavailable in the " //$NON-NLS-1$
                + "active BM write transaction. The write was rolled back."); //$NON-NLS-1$
        }
        if (planned == null)
        {
            form.setConditionalAppearance(null);
            return Result.success(null, null);
        }

        DataCompositionConditionalAppearance raw = rawAppearance(form);
        FqnResult external = raw == null ? null : externalFqn(form, generator);
        if (external != null && external.error != null)
        {
            return Result.failure(external.error);
        }
        IBmObject registered = external == null
            ? null : transaction.getTopObjectByFqn(external.fqn);
        IBmObject toAttach = null;
        if (registered == null)
        {
            DataCompositionConditionalAppearance fresh =
                DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
            // Match the dynamic-list external-property order: assign an EMPTY carrier, attach it,
            // then re-fetch the registered object before copying any planned content.
            form.setConditionalAppearance(fresh);
            FqnResult assignedFqn = externalFqn(form, generator);
            if (assignedFqn.error != null)
            {
                return Result.failure(assignedFqn.error);
            }
            // The owner/feature pair is stable, but generate only after assignment as the
            // authoritative attach name. This is the ordering used by the dynamic-list path.
            if (external == null || !assignedFqn.fqn.equals(external.fqn))
            {
                registered = transaction.getTopObjectByFqn(assignedFqn.fqn);
            }
            external = assignedFqn;
            DataCompositionConditionalAppearance assigned = rawAppearance(form);
            if (registered == null && (!(assigned instanceof IBmObject) || assigned.eIsProxy()))
            {
                return Result.failure("A fresh form conditional appearance is not an attachable BM " //$NON-NLS-1$
                    + "object. The write was rolled back; re-open EDT and retry."); //$NON-NLS-1$
            }
            toAttach = registered == null ? (IBmObject)assigned : null;
        }
        if (registered == null)
        {
            transaction.attachTopObject(toAttach, external.fqn);
            registered = transaction.getTopObjectByFqn(external.fqn);
        }
        if (!(registered instanceof DataCompositionConditionalAppearance))
        {
            String actual = registered == null ? "none" : registered.eClass().getName(); //$NON-NLS-1$
            return Result.failure("Form conditional appearance was attached under '" //$NON-NLS-1$
                + external.fqn + "', but BM re-fetch returned " + actual //$NON-NLS-1$
                + " instead of DataCompositionConditionalAppearance. The write was rolled back."); //$NON-NLS-1$
        }

        DataCompositionConditionalAppearance attached =
            (DataCompositionConditionalAppearance)registered;
        DcsSettingsWriter.commitConditionalAppearance(attached, planned);
        String difference = DcsModelComparison.firstDifference(planned, attached);
        if (difference != null)
        {
            return Result.failure("Form conditional appearance does not match the validated plan " //$NON-NLS-1$
                + "after attachment. First differing model path: " + difference //$NON-NLS-1$
                + ". The write was rolled back instead of reporting Applied."); //$NON-NLS-1$
        }
        return Result.success(attached, external.fqn);
    }

    private static DataCompositionConditionalAppearance rawAppearance(Form form)
    {
        Object value = form.eGet(FormPackage.Literals.FORM__CONDITIONAL_APPEARANCE, false);
        return value instanceof DataCompositionConditionalAppearance
            ? (DataCompositionConditionalAppearance)value : null;
    }

    private static FqnResult externalFqn(Form form, ITopObjectFqnGenerator generator)
    {
        if (generator == null)
        {
            return FqnResult.failure("The external-property FQN generator needed for form " //$NON-NLS-1$
                + "conditional appearance is unavailable. Re-open EDT and retry."); //$NON-NLS-1$
        }
        final String fqn;
        try
        {
            fqn = generator.generateExternalPropertyFqn(form,
                FormPackage.Literals.FORM__CONDITIONAL_APPEARANCE);
        }
        catch (RuntimeException e)
        {
            return FqnResult.failure("Could not generate the form conditional-appearance external " //$NON-NLS-1$
                + "property FQN. Re-open and save the form, then retry."); //$NON-NLS-1$
        }
        return fqn == null || fqn.isEmpty()
            ? FqnResult.failure("Could not generate the form conditional-appearance external " //$NON-NLS-1$
                + "property FQN. Re-open and save the form, then retry.") //$NON-NLS-1$
            : FqnResult.success(fqn);
    }

    private static final class FqnResult
    {
        private final String fqn;
        private final String error;
        private FqnResult(String fqn, String error) { this.fqn = fqn; this.error = error; }
        private static FqnResult success(String fqn) { return new FqnResult(fqn, null); }
        private static FqnResult failure(String error) { return new FqnResult(null, error); }
    }

    /** Transaction-local external-property result. */
    public static final class Result
    {
        private final DataCompositionConditionalAppearance appearance;
        private final String fqn;
        private final String error;

        private Result(DataCompositionConditionalAppearance appearance, String fqn, String error)
        {
            this.appearance = appearance;
            this.fqn = fqn;
            this.error = error;
        }

        private static Result success(DataCompositionConditionalAppearance appearance, String fqn)
        {
            return new Result(appearance, fqn, null);
        }

        private static Result failure(String error)
        {
            return new Result(null, null, error);
        }

        public boolean isSuccess() { return error == null; }
        public DataCompositionConditionalAppearance appearance() { return appearance; }
        public String fqn() { return fqn; }
        public String error() { return error; }
    }
}
