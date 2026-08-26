/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com._1c.g5.v8.dt.platform.version.Version;

/** Readiness tests for DCS content materialization services. */
public class DcsSchemaContentTest
{
    @Test
    public void testNullPlatformVersionIsNotReadyAndTellsCallerToRetry()
    {
        IV8Project project = Mockito.mock(IV8Project.class);
        Mockito.doReturn(null).when(project).getVersion();

        DcsSchemaContent.Services services = DcsSchemaContent.resolveServices(
            Mockito.mock(IBmModel.class), Mockito.mock(ITopObjectFqnGenerator.class),
            Mockito.mock(IModelObjectFactory.class), project);

        assertFalse(services.isSuccess());
        assertTrue(services.error(), services.error().contains("platform version")); //$NON-NLS-1$
        assertTrue(services.error(), services.error().contains("finish loading")); //$NON-NLS-1$
        assertTrue(services.error(), services.error().contains("retry")); //$NON-NLS-1$
    }

    @Test
    public void testResolvedPlatformVersionIsRetainedByReadyServices()
    {
        IV8Project project = Mockito.mock(IV8Project.class);
        Mockito.doReturn(Version.LATEST).when(project).getVersion();

        DcsSchemaContent.Services services = DcsSchemaContent.resolveServices(
            Mockito.mock(IBmModel.class), Mockito.mock(ITopObjectFqnGenerator.class),
            Mockito.mock(IModelObjectFactory.class), project);

        assertTrue(services.error(), services.isSuccess());
        assertSame(Version.LATEST, services.version());
    }

    @Test
    public void testMainSchemaAccessorSupportsReportAndExternalReportOnly()
    {
        Report report = MdClassFactory.eINSTANCE.createReport();
        Template reportTemplate = MdClassFactory.eINSTANCE.createTemplate();
        DcsMainSchemaOwner.addAndSet(report, reportTemplate);

        ExternalReport externalReport = MdClassFactory.eINSTANCE.createExternalReport();
        Template externalTemplate = MdClassFactory.eINSTANCE.createTemplate();
        DcsMainSchemaOwner.addAndSet(externalReport, externalTemplate);

        ExternalDataProcessor processor = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        assertTrue(DcsMainSchemaOwner.supports(report));
        assertTrue(DcsMainSchemaOwner.supports(externalReport));
        assertFalse(DcsMainSchemaOwner.supports(processor));
        assertSame(reportTemplate, DcsMainSchemaOwner.get(report));
        assertSame(externalTemplate, DcsMainSchemaOwner.get(externalReport));
        assertTrue(report.getTemplates().contains(reportTemplate));
        assertTrue(externalReport.getTemplates().contains(externalTemplate));
        assertEquals("ExternalReport", //$NON-NLS-1$
            DcsMainSchemaOwner.expectedType("ExternalReport.ExtReport")); //$NON-NLS-1$
        assertEquals("ExternalReport", //$NON-NLS-1$
            DcsMainSchemaOwner.expectedType(
                "\u0412\u043D\u0435\u0448\u043D\u0438\u0439\u041E\u0442\u0447\u0435\u0442.ExtReport")); //$NON-NLS-1$
    }
}
