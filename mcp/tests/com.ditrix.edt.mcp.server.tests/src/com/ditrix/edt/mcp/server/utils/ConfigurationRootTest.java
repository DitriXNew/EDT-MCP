/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * Tests {@link ConfigurationRoot} (issue #741): which addresses name a project's configuration root,
 * what each resolves to (or why it is refused) per project kind, and that the settable surface is
 * exactly the nine properties - every other assignable property of the root stays closed.
 *
 * <p>Russian tokens are built from code points here, independently of the production constant they
 * check, so a non-UTF-8 build cannot corrupt them and a typo in production cannot hide behind the
 * same typo in the test.</p>
 */
public class ConfigurationRootTest
{
    /** "Конфигурация" (Configuration), capitalized as a caller types it. */
    private static final String RU_CONFIGURATION = fromCp(0x041a, 0x043e, 0x043d, 0x0444, 0x0438, 0x0433,
        0x0443, 0x0440, 0x0430, 0x0446, 0x0438, 0x044f);
    /** "Конфигурации" (the plural): a near miss, not the token. */
    private static final String RU_CONFIGURATIONS = fromCp(0x041a, 0x043e, 0x043d, 0x0444, 0x0438, 0x0433,
        0x0443, 0x0440, 0x0430, 0x0446, 0x0438, 0x0438);
    /** "КомандныйИнтерфейс" (CommandInterface). */
    private static final String RU_COMMAND_INTERFACE = fromCp(0x041a, 0x043e, 0x043c, 0x0430, 0x043d, 0x0434,
        0x043d, 0x044b, 0x0439, 0x0418, 0x043d, 0x0442, 0x0435, 0x0440, 0x0444, 0x0435, 0x0439, 0x0441);
    /** "Торговля" - a Cyrillic configuration Name. */
    private static final String RU_NAME = fromCp(0x0422, 0x043e, 0x0440, 0x0433, 0x043e, 0x0432, 0x043b, 0x044f);
    /** "Учет" (accounting) spelled with ye - the spelling create_metadata stores a name with. */
    private static final String RU_NAME_YE = fromCp(0x0423, 0x0447, 0x0435, 0x0442);
    /** "Учёт" - the same name spelled with yo, as a caller may type it. */
    private static final String RU_NAME_YO = fromCp(0x0423, 0x0447, 0x0451, 0x0442);

    /** The nine settable properties, spelled independently of production. */
    private static final List<String> NINE = Arrays.asList("synonym", "comment", "version", "vendor", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "copyright", "briefInformation", "detailedInformation", "vendorInformationAddress", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "configurationInformationAddress"); //$NON-NLS-1$

    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    private static Configuration configuration(String name)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName(name);
        return config;
    }

    // ==================== which addresses name the root ====================

    @Test
    public void testTheEnglishAndRussianTokensNameTheRootInAnyCase()
    {
        for (String address : new String[] { "Configuration", "configuration", "CONFIGURATION", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            " Configuration ", RU_CONFIGURATION, RU_CONFIGURATION.toUpperCase(Locale.ROOT), //$NON-NLS-1$
            RU_CONFIGURATION.toLowerCase(Locale.ROOT) })
        {
            assertTrue(address, ConfigurationRoot.isAddress(address));
            assertTrue(address, ConfigurationRoot.isToken(address));
        }
    }

    @Test
    public void testNearMissTokensAreNotTheRoot()
    {
        for (String address : new String[] { "Configurations", RU_CONFIGURATIONS, "Config", "Catalog", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "   " }) //$NON-NLS-1$
        {
            assertFalse(address, ConfigurationRoot.isAddress(address));
            assertFalse(address, ConfigurationRoot.isToken(address));
        }
        // A public utility answers null instead of throwing (CommandInterfaceAddress's own check threw).
        assertFalse(ConfigurationRoot.isToken(null));
        assertFalse(ConfigurationRoot.isAddress(null));
    }

    @Test
    public void testTheRootNamedByItsNameIsTheRoot()
    {
        assertTrue(ConfigurationRoot.isAddress("Configuration.Cfg")); //$NON-NLS-1$
        assertTrue(ConfigurationRoot.isAddress(RU_CONFIGURATION + ".Cfg")); //$NON-NLS-1$
        assertTrue(ConfigurationRoot.isAddress(" configuration.Cfg ")); //$NON-NLS-1$
    }

    @Test
    public void testACommandInterfaceTailIsNotTheRoot()
    {
        for (String address : new String[] { "Configuration.CommandInterface", //$NON-NLS-1$
            "Configuration.MainSectionCommandInterface", "configuration.commandinterface", //$NON-NLS-1$ //$NON-NLS-2$
            RU_CONFIGURATION + "." + RU_COMMAND_INTERFACE }) //$NON-NLS-1$
        {
            assertFalse(address, ConfigurationRoot.isAddress(address));
        }
    }

    @Test
    public void testOtherShapesAreNotTheRoot()
    {
        for (String address : new String[] { "Configuration.", ".Configuration", "Configuration.A.B", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Configuration..", "Configuration. ", "Catalog.Goods", "Catalog.Configuration" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertFalse(address, ConfigurationRoot.isAddress(address));
        }
    }

    // ==================== what an address resolves to ====================

    @Test
    public void testEveryRootAddressResolvesToTheConfigurationAsATopLevelNode()
    {
        Configuration config = configuration("Cfg"); //$NON-NLS-1$
        MetadataScope scope = MetadataScope.ofConfiguration(config);
        for (String address : new String[] { "Configuration", RU_CONFIGURATION, "Configuration.Cfg", //$NON-NLS-1$ //$NON-NLS-2$
            "configuration.cfg", RU_CONFIGURATION + ".CFG" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            ConfigurationRoot.Resolution root = ConfigurationRoot.resolve(scope, address);
            assertNotNull(address, root);
            assertNull(address + ": " + root.error, root.error);
            assertNotNull(address, root.node);
            assertSame(address, config, root.node.object);
            assertTrue(address, root.node.topLevel);
            assertNull(address, root.node.owner);
            assertNull(address, root.node.feature);
        }
    }

    @Test
    public void testTheNameSegmentIsTrimmed()
    {
        Configuration config = configuration("Cfg"); //$NON-NLS-1$
        ConfigurationRoot.Resolution root =
            ConfigurationRoot.resolve(MetadataScope.ofConfiguration(config), "Configuration. Cfg "); //$NON-NLS-1$
        assertNotNull(root);
        assertNull(root.error, root.error);
        assertSame(config, root.node.object);
    }

    @Test
    public void testAnAddressThatIsNotTheRootResolvesToNothing()
    {
        MetadataScope scope = MetadataScope.ofConfiguration(configuration("Cfg")); //$NON-NLS-1$
        assertNull(ConfigurationRoot.resolve(scope, "Catalog.Goods")); //$NON-NLS-1$
        assertNull(ConfigurationRoot.resolve(scope, "Configuration.CommandInterface")); //$NON-NLS-1$
        assertNull(ConfigurationRoot.resolve(scope, null));
    }

    @Test
    public void testAnotherNameIsRefusedNamingTheRealOne()
    {
        ConfigurationRoot.Resolution root =
            ConfigurationRoot.resolve(MetadataScope.ofConfiguration(configuration("Cfg")), "Configuration.Other"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(root);
        assertNull("a root another Name names is never answered with this one", root.node); //$NON-NLS-1$
        assertNotNull(root.error);
        assertTrue(root.error, root.error.contains("'Other'")); //$NON-NLS-1$
        assertTrue(root.error, root.error.contains("'Cfg'")); //$NON-NLS-1$
        assertTrue(root.error, root.error.contains("'Configuration'")); //$NON-NLS-1$
    }

    @Test
    public void testACyrillicNameResolvesThroughTheRussianToken()
    {
        Configuration config = configuration(RU_NAME);
        MetadataScope scope = MetadataScope.ofConfiguration(config);
        ConfigurationRoot.Resolution root = ConfigurationRoot.resolve(scope, RU_CONFIGURATION + "." + RU_NAME); //$NON-NLS-1$
        assertNotNull(root);
        assertNull(root.error, root.error);
        assertSame(config, root.node.object);
        ConfigurationRoot.Resolution lower =
            ConfigurationRoot.resolve(scope, "Configuration." + RU_NAME.toLowerCase(Locale.ROOT)); //$NON-NLS-1$
        assertNull(lower.error, lower.error);
        assertSame(config, lower.node.object);
    }

    /**
     * The Name segment is matched as modify_metadata matches every Name - ignoring case, and with the
     * shared resolver's yo retry: an address that spells with yo a name stored with ye names that root,
     * as {@code Catalog.<yo spelling>} names the catalog. The retry is one-way like the shared one
     * ({@code MetadataNodeResolver.yoRetryFqn} normalizes the ADDRESS): a name stored with yo is not
     * found by a ye spelling, and is refused naming the real one.
     */
    @Test
    public void testAYoSpellingOfANameStoredWithYeNamesTheRoot()
    {
        Configuration config = configuration(RU_NAME_YE);
        MetadataScope scope = MetadataScope.ofConfiguration(config);
        for (String address : new String[] { "Configuration." + RU_NAME_YO, //$NON-NLS-1$
            RU_CONFIGURATION + "." + RU_NAME_YO.toUpperCase(Locale.ROOT) }) //$NON-NLS-1$
        {
            ConfigurationRoot.Resolution root = ConfigurationRoot.resolve(scope, address);
            assertNotNull(address, root);
            assertNull(address + ": " + root.error, root.error); //$NON-NLS-1$
            assertSame(address, config, root.node.object);
        }

        Configuration storedWithYo = configuration(RU_NAME_YO);
        ConfigurationRoot.Resolution ye =
            ConfigurationRoot.resolve(MetadataScope.ofConfiguration(storedWithYo), "Configuration." + RU_NAME_YE); //$NON-NLS-1$
        assertNull("the retry normalizes the address, never the stored name", ye.node); //$NON-NLS-1$
        assertTrue(ye.error, ye.error.contains("'" + RU_NAME_YO + "'")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAnExternalObjectsProjectHasNoRootOfItsOwn()
    {
        Configuration base = configuration("Base"); //$NON-NLS-1$
        ConfigurationRoot.Resolution root =
            ConfigurationRoot.resolve(MetadataScopeTestFixtures.externalObjectsWithBase(base), "Configuration"); //$NON-NLS-1$
        assertNotNull(root);
        assertNull("the linked base configuration is never answered as this project's root", root.node); //$NON-NLS-1$
        assertNotNull(root.error);
        assertTrue(root.error, root.error.contains("external-objects")); //$NON-NLS-1$
        assertTrue(root.error, root.error.contains("'Configuration'")); //$NON-NLS-1$
        // The fixture has no workspace project behind it: the label must not print a null.
        assertFalse(root.error, root.error.contains("null")); //$NON-NLS-1$
        assertTrue("it IS linked to a base configuration, and says so: " + root.error, //$NON-NLS-1$
            root.error.contains("linked to")); //$NON-NLS-1$
    }

    @Test
    public void testAnExternalObjectsProjectWithoutABaseDoesNotClaimOne()
    {
        ConfigurationRoot.Resolution root =
            ConfigurationRoot.resolve(MetadataScopeTestFixtures.externalObjects(), "Configuration.X"); //$NON-NLS-1$
        assertNotNull(root);
        assertNull(root.node);
        assertTrue(root.error, root.error.contains("external-objects")); //$NON-NLS-1$
        assertFalse("no base configuration is linked, so none may be claimed: " + root.error, //$NON-NLS-1$
            root.error.contains("linked to")); //$NON-NLS-1$
        assertFalse(root.error, root.error.contains("null")); //$NON-NLS-1$
    }

    @Test
    public void testAScopeWithoutAConfigurationIsAnErrorNotANode()
    {
        ConfigurationRoot.Resolution root = ConfigurationRoot.resolve(MetadataScope.ofConfiguration(null),
            "Configuration"); //$NON-NLS-1$
        assertNotNull(root);
        assertNull(root.node);
        assertNotNull(root.error);
    }

    // ==================== the settable surface ====================

    @Test
    public void testTheSettableSurfaceIsExactlyTheNine()
    {
        List<String> settable = ConfigurationRoot.settableProperties();
        assertEquals(settable.toString(), NINE.size(), settable.size());
        assertEquals(new HashSet<>(NINE), new HashSet<>(settable));
        Configuration probe = MdClassFactory.eINSTANCE.createConfiguration();
        for (String name : settable)
        {
            assertNotNull("'" + name + "' must be an assignable property of the root", //$NON-NLS-1$ //$NON-NLS-2$
                MetadataPropertyIntrospector.findFeature(probe, name));
            assertTrue(name, ConfigurationRoot.isSettable(name));
            assertTrue(name, ConfigurationRoot.isSettable(name.toUpperCase(Locale.ROOT)));
        }
    }

    /**
     * The ratchet: every OTHER assignable property of the root is closed - the collections, the behaviour
     * settings, the name, an extension's own settings and whatever a future model adds. Its first half
     * proves the check is not vacuous: those properties ARE assignable on the root, so only the allow-list
     * keeps them out.
     */
    @Test
    public void testEveryOtherAssignableRootPropertyIsClosed()
    {
        List<String> assignable = MetadataPropertyIntrospector.assignableNames(
            MdClassFactory.eINSTANCE.createConfiguration());
        for (String expected : new String[] { "name", "compatibilityMode", "scriptVariant", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "defaultLanguage", "defaultRoles", "catalogs", "keepMappingToExtendedConfigurationObjectsByIDs", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "namePrefix", "configurationExtensionPurpose", "configurationExtensionCompatibilityMode", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "caption", "updateCatalogAddress", "objectBelonging" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue("'" + expected + "' must be assignable on the root, or this ratchet proves nothing: " //$NON-NLS-1$ //$NON-NLS-2$
                + assignable, assignable.contains(expected));
        }
        int closed = 0;
        for (String name : assignable)
        {
            if (NINE.contains(name))
            {
                assertTrue(name, ConfigurationRoot.isSettable(name));
            }
            else
            {
                assertFalse("'" + name + "' must stay closed on the root", ConfigurationRoot.isSettable(name)); //$NON-NLS-1$ //$NON-NLS-2$
                closed++;
            }
        }
        assertTrue("the root has many more assignable properties than the nine: " + assignable, closed > 20); //$NON-NLS-1$
        assertFalse(ConfigurationRoot.isSettable(null));
        assertFalse(ConfigurationRoot.isSettable("")); //$NON-NLS-1$
    }

    @Test
    public void testTheRefusalNamesThePropertyAndTheNine()
    {
        String message = ConfigurationRoot.notSettableMessage("compatibilityMode"); //$NON-NLS-1$
        assertTrue(message, message.startsWith("'compatibilityMode' is not settable on the configuration root")); //$NON-NLS-1$
        assertTrue(message, message.contains("'Configuration'")); //$NON-NLS-1$
        for (String name : NINE)
        {
            assertTrue(name + " in " + message, message.contains(name)); //$NON-NLS-1$
        }
        assertTrue(message, message.contains("keepMappingToExtendedConfigurationObjectsByIDs")); //$NON-NLS-1$
        assertTrue(message, message.contains("in EDT")); //$NON-NLS-1$
        assertFalse("the root has no cascading rename to point at", message.contains("rename_metadata_object")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
