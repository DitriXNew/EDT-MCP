/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.eclipse.emf.ecore.EClass;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.version.Version;

/**
 * Tests for {@link ExternalObjectRoots}: which factory overload builds a root, and which type
 * tokens name one.
 */
public class ExternalObjectRootsTest
{
    @Test
    public void testRootForAnExistingProjectUsesTheProjectAwareOverload()
    {
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        IV8Project project = mock(IV8Project.class);
        EClass eClass = MdClassPackage.Literals.EXTERNAL_DATA_PROCESSOR;
        ExternalDataProcessor built = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        doReturn(built).when(factory).create(eClass, project);

        MdObject root = ExternalObjectRoots.newRoot(factory, eClass, "Loader", project); //$NON-NLS-1$

        assertSame(built, root);
        assertEquals("Loader", root.getName()); //$NON-NLS-1$
        verify(factory).create(eClass, project);
        verify(factory, never()).create(any(EClass.class), any(Version.class));
    }

    @Test
    public void testRootIsNullWhenTheFactoryDeclines()
    {
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        IV8Project project = mock(IV8Project.class);

        assertNull(ExternalObjectRoots.newRoot(factory, MdClassPackage.Literals.EXTERNAL_REPORT, "R", //$NON-NLS-1$
            project));
    }

    @Test
    public void testRootForANewProjectUsesTheVersionOverload()
    {
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        EClass eClass = MdClassPackage.Literals.EXTERNAL_REPORT;
        MdObject built = MdClassFactory.eINSTANCE.createExternalReport();
        doReturn(built).when(factory).create(eClass, Version.LATEST);

        MdObject root = ExternalObjectRoots.newRootForNewProject(factory, eClass, "Summary", //$NON-NLS-1$
            Version.LATEST);

        assertSame(built, root);
        assertEquals("Summary", root.getName()); //$NON-NLS-1$
        verify(factory).fillDefaultReferences(built);
    }

    @Test
    public void testRootEClassIsBilingualAndLimitedToTheTwoRootTypes()
    {
        assertEquals(MdClassPackage.Literals.EXTERNAL_DATA_PROCESSOR,
            ExternalObjectRoots.rootEClass("ExternalDataProcessor")); //$NON-NLS-1$
        assertEquals(MdClassPackage.Literals.EXTERNAL_REPORT,
            ExternalObjectRoots.rootEClass("ExternalReport")); //$NON-NLS-1$
        assertEquals(MdClassPackage.Literals.EXTERNAL_DATA_PROCESSOR,
            ExternalObjectRoots.rootEClass("\u0412\u043D\u0435\u0448\u043D\u044F\u044F\u041E\u0431\u0440\u0430\u0431\u043E\u0442\u043A\u0430")); //$NON-NLS-1$
        assertEquals(MdClassPackage.Literals.EXTERNAL_REPORT,
            ExternalObjectRoots.rootEClass("\u0412\u043D\u0435\u0448\u043D\u0438\u0439\u041E\u0442\u0447\u0435\u0442")); //$NON-NLS-1$
        assertNull(ExternalObjectRoots.rootEClass("Catalog")); //$NON-NLS-1$
        assertNull(ExternalObjectRoots.rootEClass("DataProcessor")); //$NON-NLS-1$
        assertNull(ExternalObjectRoots.rootEClass(null));
    }
}
