/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataScopeTestFixtures;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/**
 * translate_configuration and vendor support (#642): synchronizing a language the configuration
 * declares writes into the configuration's own objects and is refused when the configuration is
 * locked; a language it does not declare can only go to a dependent translation project and
 * proceeds. The platform verdict is mocked; the real one is proved by test_vendor_support_guard.py.
 */
public class TranslateConfigurationVendorSupportTest
{
    private IModelEditingSupport support;
    private Configuration config;
    private MetadataScope scope;

    @Before
    public void setUp()
    {
        support = mock(IModelEditingSupport.class);
        when(support.canEdit(any(), any())).thenReturn(true);
        VendorSupportGuard.setServiceForTests(() -> support);
        config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName("Vendor"); //$NON-NLS-1$
        Language english = MdClassFactory.eINSTANCE.createLanguage();
        english.setName("English"); //$NON-NLS-1$
        english.setLanguageCode("en"); //$NON-NLS-1$
        config.getLanguages().add(english);
        scope = MetadataScope.ofConfiguration(config);
    }

    @After
    public void tearDown()
    {
        VendorSupportGuard.setServiceForTests(null);
    }

    @Test
    public void testOnlyTheDeclaredLanguagesAreInPlaceTargets()
    {
        List<String> declared =
            TranslateConfigurationTool.declaredTargets(null, config, Arrays.asList("EN", "de")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("a code matches case-insensitively; an undeclared one is a dependent project's", //$NON-NLS-1$
            Collections.singletonList("EN"), declared); //$NON-NLS-1$
    }

    @Test
    public void testADeclaredLanguageOfALockedConfigurationIsRefused()
    {
        when(support.canEdit(config, EditingMode.DIRECT)).thenReturn(false);

        String refusal = TranslateConfigurationTool.inPlaceRefusal(scope, "VendorProject", //$NON-NLS-1$
            Collections.singletonList("en")); //$NON-NLS-1$

        assertNotNull("an in-place run would write the locked configuration's objects", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.startsWith("'VendorProject' cannot be translated into en in place: " //$NON-NLS-1$
            + "the configuration 'Vendor' is under vendor support")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was changed")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("dependent translation project")); //$NON-NLS-1$
    }

    @Test
    public void testAnUndeclaredLanguageProceedsWithoutAsking()
    {
        when(support.canEdit(config, EditingMode.DIRECT)).thenReturn(false);

        assertNull("a dependent translation project is not the locked configuration", //$NON-NLS-1$
            TranslateConfigurationTool.inPlaceRefusal(scope, "VendorProject", Collections.emptyList())); //$NON-NLS-1$
        verifyZeroInteractions(support);
    }

    @Test
    public void testAnEditableConfigurationProceeds()
    {
        assertNull(TranslateConfigurationTool.inPlaceRefusal(scope, "OwnProject", //$NON-NLS-1$
            Collections.singletonList("en"))); //$NON-NLS-1$
    }

    @Test
    public void testAnUnanswerableCheckRefuses()
    {
        VendorSupportGuard.setServiceForTests(() -> null);

        assertNotNull("fail closed", TranslateConfigurationTool.inPlaceRefusal(scope, "VendorProject", //$NON-NLS-1$ //$NON-NLS-2$
            Collections.singletonList("en"))); //$NON-NLS-1$
    }

    @Test
    public void testAnExternalObjectsProjectIsNeverAsked()
    {
        MetadataScope external = MetadataScopeTestFixtures.externalObjectsWithBase(config,
            MdClassFactory.eINSTANCE.createExternalDataProcessor());

        assertNull(TranslateConfigurationTool.inPlaceRefusal(external, "Ext", Collections.singletonList("en"))); //$NON-NLS-1$ //$NON-NLS-2$
        verifyZeroInteractions(support);
    }
}
