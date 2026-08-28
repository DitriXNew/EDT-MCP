/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.XDTOPackage;
import com.google.gson.JsonParser;

/** Headless parsing and bilingual-resolution tests for {@link McoreValueListBuilder}. */
public class McoreValueListBuilderTest
{
    @Test
    public void testReferenceAndNamespaceResolveInCallerOrder()
    {
        Configuration configuration = configurationWithPackage("Orders"); //$NON-NLS-1$

        McoreValueListBuilder.Result result = McoreValueListBuilder.build(JsonParser.parseString(
            "[\"XDTOPackage.Orders\",\"http://v8.1c.ru/8.1/data/core\"]"), //$NON-NLS-1$
            MetadataScope.ofConfiguration(configuration));

        assertNull(result.error);
        assertEquals(2, result.items.size());
        assertSame(configuration.getXDTOPackages().get(0), result.items.get(0).referenceTarget);
        assertNull(result.items.get(0).namespaceUri);
        assertEquals("http://v8.1c.ru/8.1/data/core", result.items.get(1).namespaceUri); //$NON-NLS-1$
        assertNull(result.items.get(1).referenceTarget);
    }

    @Test
    public void testRussianXdtoPackageTypeTokenUsesSharedResolver()
    {
        Configuration configuration = configurationWithPackage("Orders"); //$NON-NLS-1$
        String russianToken = MetadataLanguageUtils.cp(0x041f, 0x0430, 0x043a, 0x0435,
            0x0442) + "XDTO"; //$NON-NLS-1$

        McoreValueListBuilder.Result result = McoreValueListBuilder.build(
            JsonParser.parseString("[\"" + russianToken + ".Orders\"]"), //$NON-NLS-1$ //$NON-NLS-2$
            MetadataScope.ofConfiguration(configuration));

        assertNull(result.error);
        assertEquals(1, result.items.size());
        assertSame(configuration.getXDTOPackages().get(0), result.items.get(0).referenceTarget);
    }

    @Test
    public void testUnresolvableNonUriEntryNamesBothAcceptedForms()
    {
        McoreValueListBuilder.Result result = McoreValueListBuilder.build(
            JsonParser.parseString("[\"XDTOPackege.Nope\"]"), //$NON-NLS-1$
            MetadataScope.ofConfiguration(configurationWithPackage("Orders"))); //$NON-NLS-1$

        assertNotNull(result.error);
        assertEquals("Mcore Value-list entry 'XDTOPackege.Nope' could not be resolved as a " //$NON-NLS-1$
            + "configuration XDTO package and is not a supported namespace URI. Use an existing " //$NON-NLS-1$
            + "'XDTOPackage.<Name>' FQN (the type token may also be Russian), or a platform " //$NON-NLS-1$
            + "namespace URI beginning with 'http://', 'https://', or 'urn:'.", result.error); //$NON-NLS-1$
    }

    @Test
    public void testNamespaceRequiresOneOfTheDocumentedSchemes()
    {
        McoreValueListBuilder.Result result = McoreValueListBuilder.build(
            JsonParser.parseString("[\"v8.1c.ru/8.1/data/core\"]"), //$NON-NLS-1$
            MetadataScope.ofConfiguration(null));

        assertNotNull(result.error);
        assertNull(result.items);
    }

    private static Configuration configurationWithPackage(String name)
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        XDTOPackage xdtoPackage = MdClassFactory.eINSTANCE.createXDTOPackage();
        xdtoPackage.setName(name);
        configuration.getXDTOPackages().add(xdtoPackage);
        return configuration;
    }
}
