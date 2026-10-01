/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.emf.ecore.EClass;

import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils.MetadataTypeInfo;

/**
 * Builds the ROOT object of an external-objects project - an {@code ExternalDataProcessor} or an
 * {@code ExternalReport} - through the EDT model-object factory, so it carries the same default
 * content EDT's "New" wizard gives it (uuid, {@code producedTypes}, {@code containedObjects}).
 */
public final class ExternalObjectRoots
{
    private ExternalObjectRoots()
    {
        // utility class
    }

    /**
     * The root EClass a TYPE token names, resolved through the shared bilingual catalogue.
     *
     * @param typeToken the leading FQN type token (English or Russian)
     * @return {@code EXTERNAL_DATA_PROCESSOR} / {@code EXTERNAL_REPORT}, or {@code null} for any
     *     other token
     */
    public static EClass rootEClass(String typeToken)
    {
        MetadataTypeInfo info = MetadataTypeUtils.resolve(typeToken);
        if (info == MetadataTypeInfo.EXTERNAL_DATA_PROCESSOR)
        {
            return MdClassPackage.Literals.EXTERNAL_DATA_PROCESSOR;
        }
        if (info == MetadataTypeInfo.EXTERNAL_REPORT)
        {
            return MdClassPackage.Literals.EXTERNAL_REPORT;
        }
        return null;
    }

    /**
     * A detached root for a project that does not exist yet - there is no {@link IV8Project} to
     * hand the factory, so this is the version overload (as in EDT's own new-project wizard).
     *
     * @return the named root, or {@code null} when the factory declines the type
     */
    public static MdObject newRootForNewProject(IModelObjectFactory factory, EClass eClass, String name,
        Version version)
    {
        MdObject root = factory.create(eClass, version);
        if (root != null)
        {
            factory.fillDefaultReferences(root);
            root.setName(name);
        }
        return root;
    }

    /**
     * A root for an EXISTING external-objects project. The project-aware overload hands the
     * project to the platform initializer, as EDT's "New" wizard does.
     *
     * @return the named, not yet attached root, or {@code null} when the factory declines the type
     */
    public static MdObject newRoot(IModelObjectFactory factory, EClass eClass, String name,
        IV8Project project)
    {
        MdObject root = factory.create(eClass, project);
        if (root != null)
        {
            root.setName(name);
        }
        return root;
    }
}
