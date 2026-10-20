/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.EditingMode;
import com._1c.g5.v8.dt.core.model.IModelEditingSupport;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;

/** Positive controls: the existing vendor guard must still refuse the addressed nested child. */
public class DeleteMetadataToolSubsystemSupportTest
{
    private IModelEditingSupport support;
    private Subsystem parent;
    private Subsystem child;
    private MetadataScope scope;

    @Before
    public void setUp()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        configuration.setName("SupportedConfiguration");
        parent = MdClassFactory.eINSTANCE.createSubsystem();
        parent.setName("A");
        child = MdClassFactory.eINSTANCE.createSubsystem();
        child.setName("B");
        parent.getSubsystems().add(child);
        child.setParentSubsystem(parent);
        configuration.getSubsystems().add(parent);
        scope = MetadataScope.ofConfiguration(configuration);
        support = mock(IModelEditingSupport.class);
        when(support.canEdit(any(), any())).thenReturn(true);
        when(support.canDelete(any(), any())).thenReturn(true);
        when(support.canDelete(child, EditingMode.DIRECT)).thenReturn(false);
        VendorSupportGuard.setServiceForTests(() -> support);
    }

    @After
    public void tearDown()
    {
        VendorSupportGuard.setServiceForTests(null);
    }

    @Test
    public void testEnglishNestedDeleteStillHonorsVendorSupport()
    {
        assertRefused("Subsystem.A.Subsystem.B");
    }

    @Test
    public void testRussianNonLeadingTokenStillHonorsVendorSupport()
    {
        assertRefused("Subsystem.A.\u041f\u043e\u0434\u0441\u0438\u0441\u0442\u0435\u043c\u0430.B");
    }

    private void assertRefused(String address)
    {
        assertEquals(VendorSupportGuard.Intent.DELETE, new DeleteMetadataTool().writeIntent());
        String error = VendorSupportGuard.refusalForFqn(scope, address, VendorSupportGuard.Intent.DELETE);
        assertNotNull(error);
        assertTrue(error, error.contains(address));
        assertTrue(error, error.contains("vendor support does not allow deleting"));
        assertTrue(error, error.contains("adopt_metadata_object"));
        verify(support).canDelete(child, EditingMode.DIRECT);
        verify(support, never()).canDelete(parent, EditingMode.DIRECT);
    }
}
