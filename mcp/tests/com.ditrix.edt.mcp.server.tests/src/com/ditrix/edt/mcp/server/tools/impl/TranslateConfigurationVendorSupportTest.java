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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataScopeTestFixtures;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.e1c.g5.v8.dt.distribution.model.DistributionSupport;
import com.e1c.g5.v8.dt.distribution.model.DistributionSupportFactory;

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

    /** Gives the configuration support settings with one parent, as EDT does on taking it on support. */
    private void putOnSupport()
    {
        DistributionSupport settings = DistributionSupportFactory.eINSTANCE.createDistributionSupport();
        settings.getParentConfigurationInfos().add(DistributionSupportFactory.eINSTANCE.createParentConfigurationInfo());
        config.setDistributionSettings(settings);
    }

    private CatalogAttribute addGoodsWithWeight()
    {
        Catalog goods = MdClassFactory.eINSTANCE.createCatalog();
        goods.setName("Goods"); //$NON-NLS-1$
        CatalogAttribute weight = MdClassFactory.eINSTANCE.createCatalogAttribute();
        weight.setName("Weight"); //$NON-NLS-1$
        goods.getAttributes().add(weight);
        config.getCatalogs().add(goods);
        return weight;
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
    public void testALockedAttributeOfAnEditableRootOnSupportIsRefused()
    {
        putOnSupport();
        CatalogAttribute weight = addGoodsWithWeight();
        when(support.canEdit(weight, EditingMode.DIRECT)).thenReturn(false);

        String refusal = TranslateConfigurationTool.inPlaceRefusal(scope, "VendorProject", //$NON-NLS-1$
            Collections.singletonList("en")); //$NON-NLS-1$

        assertNotNull("the run would write the locked attribute's synonym", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.startsWith("'VendorProject' cannot be translated into en in place: " //$NON-NLS-1$
            + "the configuration 'Vendor' is under vendor support and its support rule does not allow " //$NON-NLS-1$
            + "changing Catalog.Goods.Attribute.Weight (or that could not be checked)")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("dependent translation project")); //$NON-NLS-1$
    }

    @Test
    public void testALockedTopObjectOfAnEditableRootOnSupportIsRefused()
    {
        putOnSupport();
        addGoodsWithWeight();
        Catalog goods = config.getCatalogs().get(0);
        when(support.canEdit(goods, EditingMode.DIRECT)).thenReturn(false);

        String refusal = TranslateConfigurationTool.inPlaceRefusal(scope, "VendorProject", //$NON-NLS-1$
            Collections.singletonList("en")); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("does not allow changing Catalog.Goods (or that")); //$NON-NLS-1$
    }

    @Test
    public void testAConfigurationNotOnSupportIsNotWalked()
    {
        CatalogAttribute weight = addGoodsWithWeight();
        when(support.canEdit(weight, EditingMode.DIRECT)).thenReturn(false);

        assertNull("without support settings EDT locks nothing below the root", //$NON-NLS-1$
            TranslateConfigurationTool.inPlaceRefusal(scope, "OwnProject", Collections.singletonList("en"))); //$NON-NLS-1$ //$NON-NLS-2$
        verify(support, never()).canEdit(eq(weight), any());
        verify(support, never()).canEdit(eq(config.getCatalogs().get(0)), any());
    }

    @Test
    public void testANonNativeRootIsNotWalked()
    {
        putOnSupport();
        config.setObjectBelonging(ObjectBelonging.ADOPTED);
        CatalogAttribute weight = addGoodsWithWeight();
        when(support.canEdit(weight, EditingMode.DIRECT)).thenReturn(false);

        assertNull("only a native root carries support settings", //$NON-NLS-1$
            TranslateConfigurationTool.inPlaceRefusal(scope, "ExtProject", Collections.singletonList("en"))); //$NON-NLS-1$ //$NON-NLS-2$
        verify(support, never()).canEdit(eq(weight), any());
    }

    @Test
    public void testAnAllEditableConfigurationOnSupportProceedsAfterTheWalk()
    {
        putOnSupport();
        CatalogAttribute weight = addGoodsWithWeight();

        assertNull(TranslateConfigurationTool.inPlaceRefusal(scope, "VendorProject", //$NON-NLS-1$
            Collections.singletonList("en"))); //$NON-NLS-1$
        verify(support).canEdit(weight, EditingMode.DIRECT);
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
