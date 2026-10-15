/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataScopeTestFixtures;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/**
 * Tests the configuration ROOT in {@code get_metadata_details} (issue #741): with {@code assignable:true}
 * the root renders exactly the nine properties {@code modify_metadata} sets on it - never its collections
 * or behaviour settings - under its canonical address; the default view routes to the tools that read and
 * write the root instead of answering "Invalid FQN".
 *
 * <p>Russian tokens are built from code points so a non-UTF-8 build cannot corrupt them.</p>
 */
public class GetMetadataDetailsConfigurationRootTest
{
    /** "Конфигурация" (Configuration). */
    private static final String RU_CONFIGURATION = new String(new int[] { 0x041a, 0x043e, 0x043d, 0x0444,
        0x0438, 0x0433, 0x0443, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f }, 0, 12);

    private static final String ROOT_HEADING = "## Assignable properties: Configuration\n"; //$NON-NLS-1$

    private static final Set<String> NINE = new HashSet<>(Arrays.asList("synonym", "comment", "version", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "vendor", "copyright", "briefInformation", "detailedInformation", "vendorInformationAddress", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "configurationInformationAddress")); //$NON-NLS-1$

    /** A root with values in the nine AND in the properties that must stay out of the view. */
    private static Configuration configuration()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName("Cfg"); //$NON-NLS-1$
        config.setVersion("1.0"); //$NON-NLS-1$
        config.setVendor("V"); //$NON-NLS-1$
        config.getSynonym().put("en", "Root"); //$NON-NLS-1$ //$NON-NLS-2$
        config.getCopyright().put("en", "(c) Root"); //$NON-NLS-1$ //$NON-NLS-2$
        config.setScriptVariant(ScriptVariant.ENGLISH);
        config.setCompatibilityMode(CompatibilityMode.VERSION8_327);
        config.setKeepMappingToExtendedConfigurationObjectsByIDs(true);
        config.setNamePrefix("p_"); //$NON-NLS-1$
        Catalog goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods"); //$NON-NLS-1$
        config.getCatalogs().add(goods);
        return config;
    }

    /** Runs one FQN through the tool's per-object renderer; vendor support is not asked. */
    private static String render(MetadataScope scope, String fqn, boolean assignable, List<String[]> failures)
    {
        GetMetadataDetailsTool.RenderContext ctx =
            new GetMetadataDetailsTool.RenderContext(null, scope, null, "en", false, assignable, false, 0); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        VendorSupportGuard.setServiceForTests(() -> null);
        try
        {
            new GetMetadataDetailsTool().processFqn(fqn, sb, failures, ctx);
        }
        finally
        {
            VendorSupportGuard.setServiceForTests(null);
        }
        return sb.toString();
    }

    /** The property names of the rendered table rows. */
    private static Set<String> rowNames(String md)
    {
        Set<String> names = new HashSet<>();
        for (String line : md.split("\n")) //$NON-NLS-1$
        {
            if (line.startsWith("| ") && !line.startsWith("| Property |") && !line.startsWith("| --- |")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                names.add(line.substring(2, line.indexOf(" |", 2))); //$NON-NLS-1$
            }
        }
        return names;
    }

    @Test
    public void testTheAssignableRootRendersExactlyTheNine()
    {
        List<String[]> failures = new ArrayList<>();
        String md = render(MetadataScope.ofConfiguration(configuration()), "Configuration", true, failures); //$NON-NLS-1$

        assertTrue("no failure expected: " + rows(failures), failures.isEmpty()); //$NON-NLS-1$
        assertTrue(md, md.contains(ROOT_HEADING));
        assertTrue(md, md.contains("| version | STRING | 1.0 |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| vendor | STRING | V |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| synonym | LOCALIZED_STRING |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| copyright | LOCALIZED_STRING |")); //$NON-NLS-1$
        assertEquals(md, NINE, rowNames(md));
        for (String closed : new String[] { "| catalogs |", "| scriptVariant |", "| compatibilityMode |", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "| keepMappingToExtendedConfigurationObjectsByIDs |", "| namePrefix |", "| uuid |", "| name |" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertFalse(closed + " must not be offered on the root", md.contains(closed)); //$NON-NLS-1$
        }
        assertTrue("the view says the rest is read elsewhere: " + md, //$NON-NLS-1$
            md.contains("get_configuration_properties")); //$NON-NLS-1$
    }

    @Test
    public void testEveryRootAddressRendersTheCanonicalHeading()
    {
        for (String address : new String[] { RU_CONFIGURATION, "Configuration.Cfg", "configuration" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            List<String[]> failures = new ArrayList<>();
            String md = render(MetadataScope.ofConfiguration(configuration()), address, true, failures);
            assertTrue(address + ": " + rows(failures), failures.isEmpty()); //$NON-NLS-1$
            assertTrue(address + ": " + md, md.contains(ROOT_HEADING)); //$NON-NLS-1$
            assertEquals(address, NINE, rowNames(md));
        }
    }

    @Test
    public void testAnotherNameIsAFailureRowNamingTheRealOne()
    {
        List<String[]> failures = new ArrayList<>();
        String md = render(MetadataScope.ofConfiguration(configuration()), "Configuration.Other", true, failures); //$NON-NLS-1$

        assertEquals(1, failures.size());
        assertEquals("Configuration.Other", failures.get(0)[0]); //$NON-NLS-1$
        assertTrue(failures.get(0)[1], failures.get(0)[1].contains("'Cfg'")); //$NON-NLS-1$
        assertFalse(md, md.contains("## Assignable properties")); //$NON-NLS-1$
    }

    @Test
    public void testAnExternalObjectsProjectNeverRendersItsLinkedBase()
    {
        List<String[]> failures = new ArrayList<>();
        String md = render(MetadataScopeTestFixtures.externalObjectsWithBase(configuration()), "Configuration", //$NON-NLS-1$
            true, failures);

        assertEquals(1, failures.size());
        assertTrue(failures.get(0)[1], failures.get(0)[1].contains("external-objects")); //$NON-NLS-1$
        assertFalse("the linked base must not be rendered as this project's root: " + md, //$NON-NLS-1$
            md.contains("## Assignable properties")); //$NON-NLS-1$
    }

    @Test
    public void testTheDefaultViewRoutesInsteadOfRendering()
    {
        List<String[]> failures = new ArrayList<>();
        String md = render(MetadataScope.ofConfiguration(configuration()), "Configuration", false, failures); //$NON-NLS-1$

        assertEquals(1, failures.size());
        String reason = failures.get(0)[1];
        assertTrue(reason, reason.contains("get_configuration_properties")); //$NON-NLS-1$
        assertTrue(reason, reason.contains("assignable")); //$NON-NLS-1$
        assertFalse("the root is a valid address, not a malformed one: " + reason, reason.contains("Invalid FQN")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("nothing is rendered for it", "", md); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheDefaultViewOfAnotherNameNamesTheRealOne()
    {
        List<String[]> failures = new ArrayList<>();
        render(MetadataScope.ofConfiguration(configuration()), "Configuration.Other", false, failures); //$NON-NLS-1$

        assertEquals(1, failures.size());
        assertTrue(failures.get(0)[1], failures.get(0)[1].contains("'Cfg'")); //$NON-NLS-1$
    }

    @Test
    public void testAMalformedFqnStillSaysInvalidFqnAndNamesTheRoot()
    {
        String reason = new GetMetadataDetailsTool().describeResolutionFailure("NotAnFqn"); //$NON-NLS-1$
        assertTrue(reason, reason.contains("Invalid FQN")); //$NON-NLS-1$
        assertTrue(reason, reason.contains("Type.Name")); //$NON-NLS-1$
        assertTrue(reason, reason.contains("'Configuration'")); //$NON-NLS-1$
    }

    /**
     * The shared table restricted to the nine, then one sentence on where else the root is read - and it
     * claims no more for get_configuration_properties than that tool renders: the root's name, its
     * behaviour settings and its languages, not "the rest" (it does not render, e.g., the default roles,
     * the update-catalog address or an extension's keepMapping).
     */
    @Test
    public void testTheRootFormatterIsTheSharedTableRestrictedToTheNine()
    {
        String md = GetMetadataDetailsTool.formatConfigurationRootAssignable(configuration());
        assertTrue(md, md.startsWith(ROOT_HEADING));
        assertTrue(md, md.contains("Set these with `modify_metadata`")); //$NON-NLS-1$
        assertEquals(md, NINE, rowNames(md));
        assertTrue(md, md.endsWith("\nOnly these properties of the configuration root are settable; " //$NON-NLS-1$
            + "get_configuration_properties also reads its name, behaviour settings and languages.\n")); //$NON-NLS-1$
        assertFalse(md, md.contains("reads the rest")); //$NON-NLS-1$
    }

    /** The guide says how to read the root: the assignable heading it renders, and what the reader reads. */
    @Test
    public void testTheGuideRoutesTheRootToTheAssignableViewAndTheRootReader()
    {
        String guide = new GetMetadataDetailsTool().getGuide();
        assertTrue(guide, guide.contains("`## Assignable properties: Configuration`")); //$NON-NLS-1$
        assertTrue(guide, guide.contains("objectFqns: [\"Configuration\"], assignable: true")); //$NON-NLS-1$
        assertTrue(guide, guide.contains("except `Configuration`")); //$NON-NLS-1$
        assertTrue(guide, guide.contains("its `## Errors` row points to `get_configuration_properties`, which " //$NON-NLS-1$
            + "also reads the root's name, behaviour settings and languages.")); //$NON-NLS-1$
        assertFalse("get_configuration_properties does not read every property of the root: " + guide, //$NON-NLS-1$
            guide.contains("reads every property")); //$NON-NLS-1$
    }

    private static String rows(List<String[]> failures)
    {
        StringBuilder sb = new StringBuilder();
        for (String[] f : failures)
        {
            sb.append(Arrays.toString(f));
        }
        return sb.toString();
    }
}
