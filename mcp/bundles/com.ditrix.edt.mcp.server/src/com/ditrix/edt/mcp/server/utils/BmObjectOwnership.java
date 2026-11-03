/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.core.BmObject;
import com._1c.g5.v8.bm.core.IBmNamespaceDataStore;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;

/** Primary-store ownership, including objects attached in the current uncommitted write. */
public final class BmObjectOwnership
{
    private BmObjectOwnership()
    {
    }

    /** The caller must hold the namespace's BM transaction. A null store alone never proves ownership. */
    public static boolean isPrimary(IBmTransaction tx, IBmObject object)
    {
        if (object == null || object.eIsProxy() || object.bmGetId() == IBmObject.BM_NULL_ID
            || object.bmGetNamespace() != tx.getNamespace())
        {
            return false;
        }
        IBmNamespaceDataStore store = tx.getPlatformTransaction().getContainingDataStore(object);
        if (store != null)
        {
            return store == tx.getNamespace().getPrimaryStore();
        }
        if (tx.isReadOnly())
        {
            return false;
        }
        EObject stored = tx.toTransactionObject(object);
        if (!(stored instanceof BmObject) || ((IBmObject)stored).bmGetNamespace() != tx.getNamespace()
            || ((IBmObject)stored).bmGetId() != object.bmGetId()
            || ((IBmObject)stored).bmGetPlatformTransaction() != tx.getPlatformTransaction())
        {
            return false;
        }
        return created((BmObject)stored);
    }

    private static boolean created(BmObject object)
    {
        try
        {
            // BM exposes creation status only through its internal object-data access interface.
            ClassLoader loader = BmObject.class.getClassLoader();
            Class<?> secrets = loader.loadClass("com._1c.g5.v8.bm.core.internal.BmSecrets"); //$NON-NLS-1$
            Class<?> accessType = loader.loadClass("com._1c.g5.v8.bm.core.internal.IBmObjectAccess"); //$NON-NLS-1$
            Class<?> dataType = loader.loadClass("com._1c.g5.v8.bm.core.internal.IObjectData"); //$NON-NLS-1$
            Object access = secrets.getMethod("getBmObjectAccess").invoke(null); //$NON-NLS-1$
            Object data = accessType.getMethod("bmGetObjectData", BmObject.class).invoke(access, object); //$NON-NLS-1$
            Object status = data == null ? null : dataType.getMethod("getStatus").invoke(data); //$NON-NLS-1$
            return status instanceof Enum<?> && "CREATED".equals(((Enum<?>)status).name()); //$NON-NLS-1$
        }
        catch (ReflectiveOperationException | LinkageError e)
        {
            throw new IllegalStateException("Cannot inspect BM's current-transaction creation status", e); //$NON-NLS-1$
        }
    }
}
