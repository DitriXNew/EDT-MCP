/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.bm.core.BmObject;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmNamespaceDataStore;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.form.model.FormFactory;

/** Supplies BM's real object-data status API without starting a disk-backed platform. */
public final class BmCreationTestFixture
{
    private BmCreationTestFixture()
    {
    }

    public static IBmNamespace namespace() throws Exception
    {
        return (IBmNamespace)mock(type("IBmInternalNamespace"));
    }

    public static IBmPlatformTransaction platform() throws Exception
    {
        return (IBmPlatformTransaction)mock(type("IBmInternalPlatformTransaction"));
    }

    public static IBmNamespaceDataStore store() throws Exception
    {
        return (IBmNamespaceDataStore)mock(type("IBmInternalNamespaceDataStore"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static IBmObject object(IBmTransaction tx, long id, String status) throws Exception
    {
        BmObject object = (BmObject)FormFactory.eINSTANCE.createForm();
        Class<?> accessType = type("IBmObjectAccess");
        Object access = type("BmSecrets").getMethod("getBmObjectAccess").invoke(null);
        accessType.getMethod("bmSetId", BmObject.class, long.class).invoke(access, object, id);
        accessType.getMethod("bmSetNamespace", BmObject.class, type("IBmInternalNamespace"))
            .invoke(access, object, tx.getNamespace());
        Object handle = mock(type("ITransactionHandle"));
        IBmPlatformTransaction platform = tx.getPlatformTransaction();
        when(type("ITransactionHandle").getMethod("getUserTransactionFacade").invoke(handle))
            .thenReturn(platform);
        accessType.getMethod("bmSetTransactionHandle", BmObject.class, type("ITransactionHandle"))
            .invoke(access, object, handle);
        Object data = mock(type("IObjectData"));
        Object flag = Enum.valueOf((Class)type("ObjectDataStatus"), status);
        when(type("IObjectData").getMethod("getStatus").invoke(data)).thenReturn(flag);
        accessType.getMethod("bmSetObjectData", BmObject.class, type("IObjectData")).invoke(access, object, data);
        return object;
    }

    private static Class<?> type(String name) throws Exception
    {
        return BmObject.class.getClassLoader().loadClass("com._1c.g5.v8.bm.core.internal." + name);
    }
}
