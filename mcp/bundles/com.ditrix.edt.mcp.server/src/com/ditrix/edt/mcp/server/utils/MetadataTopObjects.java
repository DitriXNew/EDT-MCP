/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EClass;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.provider.IBmObjectExporter;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Top-object FQN snapshots of a project's BM model: the metadata objects with their own .mdo, and
 * every top object EDT writes to a file of its own (forms, rights, template content included).
 */
public final class MetadataTopObjects
{
    /** EDT's extension point for the per-EClass file exporters that {@code forceExport} dispatches to. */
    private static final String EXPORTER_EXTENSION_POINT = "com._1c.g5.v8.dt.core.bmObjectExporter"; //$NON-NLS-1$

    private static volatile List<IBmObjectExporter> exporters;

    private static final Map<EClass, Boolean> EXPORTABLE = new ConcurrentHashMap<>();

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
        return collect(tx, object -> object instanceof MdObject);
    }

    /** Detached snapshot of every top object an EDT exporter writes to disk, in BM order. */
    public static List<String> exportableSnapshot(IBmModel model)
    {
        // Created before the read: instantiating an exporter is not work for a model transaction.
        exporters();
        return BmTransactions.read(model, "ReadExportableTopObjects", //$NON-NLS-1$
            (tx, monitor) -> exportableFqns(tx, MetadataTopObjects::hasExporter));
    }

    /** Top objects whose EClass {@code exportable} accepts, in BM order; requires an active transaction. */
    public static List<String> exportableFqns(IBmTransaction tx, Predicate<EClass> exportable)
    {
        return collect(tx, object -> exportable.test(object.eClass()));
    }

    /** Whether EDT registers a file exporter for {@code eClass}; derived index objects have none. */
    public static boolean hasExporter(EClass eClass)
    {
        return eClass != null && EXPORTABLE.computeIfAbsent(eClass, type -> {
            for (IBmObjectExporter exporter : exporters())
            {
                if (exporter.supports(type))
                {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }).booleanValue();
    }

    private static List<String> collect(IBmTransaction tx, Predicate<IBmObject> keep)
    {
        List<String> fqns = new ArrayList<>();
        Iterator<IBmObject> objects = tx.getTopObjectIterator();
        while (objects.hasNext())
        {
            IBmObject object = objects.next();
            if (keep.test(object))
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

    /** The registered exporters, created once from the extension point EDT's own registry reads. */
    private static List<IBmObjectExporter> exporters()
    {
        List<IBmObjectExporter> loaded = exporters;
        if (loaded == null)
        {
            synchronized (MetadataTopObjects.class)
            {
                loaded = exporters;
                if (loaded == null)
                {
                    loaded = new ArrayList<>();
                    for (IConfigurationElement element : Platform.getExtensionRegistry()
                        .getConfigurationElementsFor(EXPORTER_EXTENSION_POINT))
                    {
                        try
                        {
                            loaded.add((IBmObjectExporter)element.createExecutableExtension("class")); //$NON-NLS-1$
                        }
                        catch (CoreException | RuntimeException e)
                        {
                            Activator.logError("Could not create the BM exporter " //$NON-NLS-1$
                                + element.getAttribute("class"), e); //$NON-NLS-1$
                        }
                    }
                    exporters = loaded;
                }
            }
        }
        return loaded;
    }
}
