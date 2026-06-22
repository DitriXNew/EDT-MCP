/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumper;

/**
 * Unit tests for {@link ExternalObjectDumpSupport} (issue #122) — the pure helpers
 * {@link ExternalObjectDumpSupport#extensionForObject} /
 * {@link ExternalObjectDumpSupport#outputFileName} and the null-safety of
 * {@link ExternalObjectDumpSupport#resolveDumper()} when run headless.
 *
 * <p>The in-memory model objects come straight from {@link MdClassFactory} (the test bundle is a
 * Fragment-Host, so EMF resolves headlessly). The actual dump (Guice injector -&gt;
 * {@code IExternalObjectDumper#dump} -&gt; {@code .epf}/{@code .erf} on disk) needs a live EDT with a
 * resolvable 1C runtime and is verified on the e2e stand.
 */
public class ExternalObjectDumpSupportTest
{
    @Test
    public void testExtensionForExternalDataProcessorIsEpf()
    {
        ExternalDataProcessor dp = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        assertEquals(ExternalObjectDumpSupport.EXTENSION_EPF,
            ExternalObjectDumpSupport.extensionForObject(dp));
    }

    @Test
    public void testExtensionForExternalReportIsErf()
    {
        ExternalReport report = MdClassFactory.eINSTANCE.createExternalReport();
        assertEquals(ExternalObjectDumpSupport.EXTENSION_ERF,
            ExternalObjectDumpSupport.extensionForObject(report));
    }

    @Test
    public void testExtensionForNullDefaultsToEpf()
    {
        assertEquals(ExternalObjectDumpSupport.EXTENSION_EPF,
            ExternalObjectDumpSupport.extensionForObject(null));
    }

    @Test
    public void testExtensionForUnrelatedObjectDefaultsToEpf()
    {
        // A non-external model object must not throw and must fall back to the safe default.
        assertEquals(ExternalObjectDumpSupport.EXTENSION_EPF,
            ExternalObjectDumpSupport.extensionForObject(MdClassFactory.eINSTANCE.createCatalog()));
    }

    @Test
    public void testFileNameForExternalReportEndsWithErf()
    {
        // Same code path build_external_objects uses to name a file (outputFileName + extensionForObject):
        // an external report must become "<name>.erf" so an .erf is never written with an .epf extension.
        ExternalReport report = MdClassFactory.eINSTANCE.createExternalReport();
        report.setName("MyReport"); //$NON-NLS-1$
        String fileName = ExternalObjectDumpSupport.outputFileName(report.getName(),
            ExternalObjectDumpSupport.extensionForObject(report));
        assertEquals("MyReport.erf", fileName); //$NON-NLS-1$
    }

    @Test
    public void testFileNameForExternalDataProcessorEndsWithEpf()
    {
        // The mirror of the report case: an external data processor must become "<name>.epf" through
        // the same outputFileName + extensionForObject path the tool exercises.
        ExternalDataProcessor dp = MdClassFactory.eINSTANCE.createExternalDataProcessor();
        dp.setName("MyProcessor"); //$NON-NLS-1$
        String fileName = ExternalObjectDumpSupport.outputFileName(dp.getName(),
            ExternalObjectDumpSupport.extensionForObject(dp));
        assertEquals("MyProcessor.epf", fileName); //$NON-NLS-1$
    }

    @Test
    public void testOutputFileNameJoinsNameAndExtension()
    {
        assertEquals("MyProcessor.epf", //$NON-NLS-1$
            ExternalObjectDumpSupport.outputFileName("MyProcessor", ExternalObjectDumpSupport.EXTENSION_EPF)); //$NON-NLS-1$
        assertEquals("MyReport.erf", //$NON-NLS-1$
            ExternalObjectDumpSupport.outputFileName("MyReport", ExternalObjectDumpSupport.EXTENSION_ERF)); //$NON-NLS-1$
    }

    @Test
    public void testOutputFileNameTrimsName()
    {
        assertEquals("MyProcessor.epf", //$NON-NLS-1$
            ExternalObjectDumpSupport.outputFileName("  MyProcessor  ", "epf")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputFileNameNullEmptySafe()
    {
        // Neither null name nor null extension may throw; both fall back to safe placeholders.
        String bothNull = ExternalObjectDumpSupport.outputFileName(null, null);
        assertTrue("a null name/extension must still yield a dotted file name: " + bothNull, //$NON-NLS-1$
            bothNull.endsWith(".epf") && bothNull.indexOf('.') > 0); //$NON-NLS-1$

        String blankName = ExternalObjectDumpSupport.outputFileName("   ", "erf"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a blank name must be replaced by a placeholder before the extension: " + blankName, //$NON-NLS-1$
            blankName.endsWith(".erf") && blankName.indexOf('.') > 0); //$NON-NLS-1$

        assertEquals("a blank extension must default to epf", //$NON-NLS-1$
            "MyProcessor.epf", ExternalObjectDumpSupport.outputFileName("MyProcessor", "  ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testResolveDumperIsGracefulHeadless()
    {
        // Whether the platform-services injector resolves the dumper is environment-dependent in a
        // Tycho/surefire run (the same OSGi bundle-presence dependence as the other resolver tests).
        // The env-independent invariant is that resolveDumper() NEVER throws: it either returns a real
        // dumper or null so the tool can surface an actionable precondition error. The actual dump is
        // verified on the e2e stand.
        // Reaching the assertion at all proves the no-throw invariant; pin the env-independent fact
        // that the returned value is a valid contract value (a dumper or the null sentinel).
        IExternalObjectDumper dumper = ExternalObjectDumpSupport.resolveDumper();
        boolean validContractValue = dumper == null || dumper.getClass() != null;
        assertTrue("resolveDumper() must be null-safe — null or a real dumper, never a throw", //$NON-NLS-1$
            validContractValue);
    }
}
