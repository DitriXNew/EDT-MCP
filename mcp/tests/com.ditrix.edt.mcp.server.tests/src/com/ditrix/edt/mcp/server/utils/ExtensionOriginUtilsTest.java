/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;

/**
 * Tests for {@link ExtensionOriginUtils#originLabel(ObjectBelonging, boolean)} — the
 * pure decision that turns an object's belonging plus its owning-project type into a
 * core / core (adopted) / extension label. The {@code isExtensionProject(IProject)}
 * resolver needs a live workbench (project managers) and is e2e-covered; this
 * unit-tests only the pure label logic.
 */
public class ExtensionOriginUtilsTest
{
    @Test
    public void testBaseProjectIsAlwaysCore()
    {
        // A base configuration never holds adopted objects; every object is core
        // regardless of its (always NATIVE) belonging.
        assertEquals(ExtensionOriginUtils.ORIGIN_CORE,
            ExtensionOriginUtils.originLabel(ObjectBelonging.NATIVE, false));
        // Defensive: even an (impossible) ADOPTED flag in a base project reads as core.
        assertEquals(ExtensionOriginUtils.ORIGIN_CORE,
            ExtensionOriginUtils.originLabel(ObjectBelonging.ADOPTED, false));
    }

    @Test
    public void testExtensionAdoptedObjectIsCoreAdopted()
    {
        // In an extension an ADOPTED object is borrowed from the base configuration.
        assertEquals(ExtensionOriginUtils.ORIGIN_ADOPTED,
            ExtensionOriginUtils.originLabel(ObjectBelonging.ADOPTED, true));
    }

    @Test
    public void testExtensionNativeObjectIsExtensionOwn()
    {
        // In an extension a NATIVE object is the extension's own new object.
        assertEquals(ExtensionOriginUtils.ORIGIN_EXTENSION,
            ExtensionOriginUtils.originLabel(ObjectBelonging.NATIVE, true));
    }

    @Test
    public void testNullBelongingTreatedAsNative()
    {
        // A null belonging (defensive) is treated as NATIVE: extension-own in an
        // extension, core in a base configuration.
        assertEquals(ExtensionOriginUtils.ORIGIN_EXTENSION,
            ExtensionOriginUtils.originLabel(null, true));
        assertEquals(ExtensionOriginUtils.ORIGIN_CORE,
            ExtensionOriginUtils.originLabel(null, false));
    }

    /**
     * An external data processor / report belongs to its external-objects project. The
     * core/extension question does not apply to it, and answering "core" states that it belongs
     * to a base configuration - the exact confusion issue #309 exists to remove.
     */
    @Test
    public void testExternalObjectsProjectIsNeitherCoreNorExtension()
    {
        assertEquals(ExtensionOriginUtils.ORIGIN_EXTERNAL,
            ExtensionOriginUtils.originLabel(ObjectBelonging.NATIVE, false, true));
        // The external answer wins even if something claimed extension too - there is no
        // project that is both, and "external" is the one that names the owner.
        assertEquals(ExtensionOriginUtils.ORIGIN_EXTERNAL,
            ExtensionOriginUtils.originLabel(ObjectBelonging.ADOPTED, true, true));
        // The two-argument form is unchanged for every existing caller.
        assertEquals(ExtensionOriginUtils.originLabel(ObjectBelonging.NATIVE, true),
            ExtensionOriginUtils.originLabel(ObjectBelonging.NATIVE, true, false));
        assertEquals(ExtensionOriginUtils.ORIGIN_CORE,
            ExtensionOriginUtils.originLabel(ObjectBelonging.NATIVE, false, false));
    }
}
