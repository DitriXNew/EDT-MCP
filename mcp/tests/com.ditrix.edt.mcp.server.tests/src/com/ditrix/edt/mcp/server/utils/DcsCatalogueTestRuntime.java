/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.mockito.Mockito;

import com._1c.g5.v8.dt.dcs.typedvalue.TypedValueFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.platform.version.Version;

/** Supplies the stripped Tycho application with type records requested by real EDT catalogues. */
final class DcsCatalogueTestRuntime
{
    private DcsCatalogueTestRuntime()
    {
        // Test utility
    }

    static Scope prepareCatalogues(Version version)
    {
        TypedValueFactory previous = TypedValueFactory.INSTANCE;
        TypedValueFactory testFactory = Mockito.spy(previous);
        Mockito.doAnswer(invocation ->
        {
            String name = invocation.getArgument(0);
            try
            {
                return invocation.callRealMethod();
            }
            catch (IllegalArgumentException e)
            {
                // The plain Tycho application omits the platform type index. Preserve the
                // catalogue's exact requested type name; TypedValueFactory still supplies the
                // real enum/value factory for that name when the default value is created.
                Type type = McoreFactory.eINSTANCE.createType();
                type.setName(name);
                type.setNameRu(name);
                return type;
            }
        }).when(testFactory).createTypeByName(Mockito.anyString(), Mockito.eq(version));
        TypedValueFactory.INSTANCE = testFactory;
        return new Scope(previous);
    }

    static final class Scope implements AutoCloseable
    {
        private final TypedValueFactory previous;

        Scope(TypedValueFactory previous)
        {
            this.previous = previous;
        }

        @Override
        public void close()
        {
            TypedValueFactory.INSTANCE = previous;
        }
    }
}
