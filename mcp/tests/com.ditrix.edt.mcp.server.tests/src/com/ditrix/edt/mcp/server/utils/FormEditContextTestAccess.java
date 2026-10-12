/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Builds a {@link FormElementWriter.FormEditContext} over a test {@link IBmModel}, so a test can drive
 * a form write through the real {@link FormElementWriter#writeEditableForm} boundary.
 */
public final class FormEditContextTestAccess
{
    private FormEditContextTestAccess()
    {
    }

    /**
     * @param project the owning project
     * @param bmModel the model whose {@code execute} runs the write task
     * @param mdForm the MD form (pre-transaction snapshot)
     * @param mdFormBmId the id the transaction re-fetches the MD form by
     * @return the context
     */
    public static FormElementWriter.FormEditContext of(IProject project, IBmModel bmModel, MdObject mdForm,
        long mdFormBmId)
    {
        return new FormElementWriter.FormEditContext(project, bmModel, mdForm, mdFormBmId, "Catalog.Catalog.forms.ItemForm"); //$NON-NLS-1$
    }
}
