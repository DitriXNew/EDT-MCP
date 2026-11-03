/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/** Metadata top objects with their own .mdo, excluding form content and internal BM indexes. */
public final class MetadataTopObjects
{
    private MetadataTopObjects()
    {
    }

    /** Detached FQN snapshot, read inside the project's BM read transaction. */
    public static List<String> snapshot(IBmModel model)
    {
        return BmTransactions.read(model, "ReadMetadataTopObjects", (tx, monitor) -> fqns(tx)); //$NON-NLS-1$
    }

    /** Enumerate in BM order, including separately stored nested metadata; requires an active transaction. */
    public static List<String> fqns(IBmTransaction tx)
    {
        List<String> fqns = new ArrayList<>();
        Iterator<IBmObject> objects = tx.getTopObjectIterator();
        while (objects.hasNext())
        {
            IBmObject object = objects.next();
            if (object instanceof MdObject)
            {
                String fqn = object.bmGetFqn();
                if (fqn != null && !fqn.isEmpty())
                {
                    fqns.add(fqn);
                }
            }
        }
        return fqns;
    }
}
