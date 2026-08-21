/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.form.model.DynamicListExtInfo;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com.ditrix.edt.mcp.server.Activator;

/** Materializes the external {@code ListSettings.dcss} resource owned by a dynamic-list ext-info. */
public final class DcsDynamicListContent
{
    private DcsDynamicListContent()
    {
        // Utility class
    }

    /**
     * Ensures list settings are a BM top object and returns the exact FQN that must be force-exported.
     * EDT serializes that top object to {@code Attributes/<name>/ExtInfo/ListSettings.dcss}; exporting
     * only the content Form is insufficient for a newly materialized settings resource.
     */
    public static Result ensureAttached(IBmTransaction transaction, DynamicListExtInfo extInfo)
    {
        DataCompositionSettings settings = extInfo == null ? null : extInfo.getListSettings();
        if (settings == null)
        {
            return Result.success(null);
        }
        if (settings instanceof IBmObject && ((IBmObject)settings).bmIsTop())
        {
            String fqn = ((IBmObject)settings).bmGetFqn();
            if (fqn == null || fqn.isEmpty())
            {
                return Result.failure("The managed dynamic-list listSettings object has no FQN, so " //$NON-NLS-1$
                    + "ListSettings.dcss cannot be exported. Re-open and save the form in EDT, then retry."); //$NON-NLS-1$
            }
            return Result.success(fqn);
        }
        if (!(settings instanceof IBmObject))
        {
            return Result.failure("Dynamic-list listSettings are not a managed BM object. Re-open " //$NON-NLS-1$
                + "and save the form in EDT, then retry the dcs write."); //$NON-NLS-1$
        }
        ITopObjectFqnGenerator generator = Activator.getDefault().getTopObjectFqnGenerator();
        if (generator == null)
        {
            return Result.failure("The external-property FQN generator needed for ListSettings.dcss " //$NON-NLS-1$
                + "is unavailable. Re-open EDT and retry the dcs write."); //$NON-NLS-1$
        }
        String fqn = generator.generateExternalPropertyFqn(extInfo,
            FormPackage.Literals.DYNAMIC_LIST_EXT_INFO__LIST_SETTINGS);
        if (fqn == null || fqn.isEmpty())
        {
            return Result.failure("Could not generate the external ListSettings.dcss FQN for the " //$NON-NLS-1$
                + "dynamic list. Re-open and save the form, then retry."); //$NON-NLS-1$
        }
        transaction.attachTopObject((IBmObject)settings, fqn);
        return Result.success(fqn);
    }

    /** External settings carrier result. */
    public static final class Result
    {
        private final String fqn;
        private final String error;
        private Result(String fqn, String error) { this.fqn = fqn; this.error = error; }
        private static Result success(String fqn) { return new Result(fqn, null); }
        private static Result failure(String error) { return new Result(null, error); }
        public boolean isSuccess() { return error == null; }
        public String fqn() { return fqn; }
        public String error() { return error; }
    }
}
