/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;

/**
 * Test support: a schema that answers like one attached to the BM model under an FQN. A mock, because
 * {@code BmObject.bmIsTop()} and {@code bmGetFqn()} are final and cannot be stubbed on a real schema.
 */
final class DcsAttachedSchemas
{
    private DcsAttachedSchemas()
    {
        // utility class
    }

    /**
     * @param fqn the top-object FQN the schema is attached under
     * @return a schema whose {@code bmIsTop()} and {@code bmGetFqn()} answer as an attached one
     */
    static DataCompositionSchema attached(String fqn)
    {
        DataCompositionSchema schema = mock(DataCompositionSchema.class, withSettings().extraInterfaces(IBmObject.class));
        when(Boolean.valueOf(((IBmObject)schema).bmIsTop())).thenReturn(Boolean.TRUE);
        when(((IBmObject)schema).bmGetFqn()).thenReturn(fqn);
        return schema;
    }
}
