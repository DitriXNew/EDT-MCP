/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import org.junit.Test;

/** Every owner in a chain must actually declare the next reference (#448 P1). */
public class GetProjectErrorsExternalDataSourceTest
{
    @Test public void validatesTheOwnerTypeAtEveryLevel() throws Exception
    {
        Method method = GetProjectErrorsTool.class.getDeclaredMethod("isMdclassChain", String.class);
        method.setAccessible(true);
        assertEquals(true, method.invoke(null, "ExternalDataSource.E.Table.T.Field.F"));
        assertEquals(false, method.invoke(null, "Catalog.P.TabularSection.T.Field.X"));
        assertEquals(false, method.invoke(null, "Catalog.P.Field.X"));
        assertEquals(true, method.invoke(null, "Catalog.P.TabularSection.T.Attribute.X"));
        assertEquals(true, method.invoke(null, "ExternalDataSource.E.Cube.C.DimensionTable.T.Field.F"));
        assertEquals(true, method.invoke(null, "ExternalDataSource.E.Cube.C.Dimension.D"));
        assertEquals(true, method.invoke(null, "ExternalDataSource.E.Cube.C.Resource.R"));
        assertEquals(false, method.invoke(null, "ExternalDataSource.E.DimensionTable.T"));
        assertEquals(false, method.invoke(null, "ExternalDataSource.E.Table.T.Dimension.D"));
        assertEquals(true, method.invoke(null, "ExternalDataSource.E.Cube.C."
            + com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.RU_DIMENSION_TABLE + ".T."
            + com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.RU_FIELD + ".F"));
    }
}
