/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.eventlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import com.ditrix.edt.mcp.server.utils.eventlog.EventLogLocator.Resolution;
import com.ditrix.edt.mcp.server.utils.eventlog.EventLogLocator.SelectResult;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/**
 * Headless unit tests for {@link EventLogLocator}. They exercise the pure resolution seams
 * (log-dir override, infobase-application selection, connection-string &rarr; {@code 1Cv8Log})
 * with mocked connection strings and real temporary directories. The full live path
 * ({@code ApplicationSupport.resolveManager} &rarr; {@code getApplications}) needs a running EDT and
 * is covered by the e2e suite.
 */
public class EventLogLocatorTest
{
    private static final String LGF = "1Cv8.lgf"; //$NON-NLS-1$

    // ==================== FILE resolution ====================

    @Test
    public void testFileInfobaseResolvesLogDir() throws IOException
    {
        Path infobaseDir = Files.createTempDirectory("evlog-file"); //$NON-NLS-1$
        Path logDir = infobaseDir.resolve(EventLogLocator.LOG_DIR_NAME);
        Files.createDirectories(logDir);
        Files.createFile(logDir.resolve(LGF));

        Resolution res = EventLogLocator.fromInfobaseReference(fileRef(infobaseDir.toString()));

        assertNull("a file infobase with a 1Cv8Log must resolve", res.getError()); //$NON-NLS-1$
        assertNotNull(res.getLogDir());
        assertEquals(logDir.toAbsolutePath(), res.getLogDir().toAbsolutePath());
        assertEquals(EventLogLocator.TYPE_FILE, res.getInfobaseType());
    }

    @Test
    public void testFileInfobaseWithNoLogDirNamesResolvedPath() throws IOException
    {
        Path infobaseDir = Files.createTempDirectory("evlog-nolog"); //$NON-NLS-1$
        // Deliberately do NOT create the 1Cv8Log subdirectory.

        Resolution res = EventLogLocator.fromInfobaseReference(fileRef(infobaseDir.toString()));

        assertError(res);
        assertContains(res, EventLogLocator.LOG_DIR_NAME);
        assertContains(res, "No event log directory"); //$NON-NLS-1$
        assertContains(res, "logDir"); //$NON-NLS-1$
    }

    @Test
    public void testFileInfobaseWithBlankPathIsActionableError()
    {
        Resolution res = EventLogLocator.fromInfobaseReference(fileRef("   ")); //$NON-NLS-1$

        assertError(res);
        assertContains(res, "logDir"); //$NON-NLS-1$
    }

    // ==================== SERVER resolution ====================

    @Test
    public void testServerInfobaseAsksForLogDir()
    {
        ServerConnectionString scs = mock(ServerConnectionString.class);
        when(scs.getReference()).thenReturn("Accounting"); //$NON-NLS-1$
        when(scs.getServer()).thenReturn("app-srv-1"); //$NON-NLS-1$
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getConnectionString()).thenReturn(scs);

        Resolution res = EventLogLocator.fromInfobaseReference(ref);

        assertError(res);
        assertContains(res, "SERVER"); //$NON-NLS-1$
        assertContains(res, "logDir"); //$NON-NLS-1$
        // Must name the concrete infobase/server so the caller knows which log to point at,
        // and must NOT guess a default srvinfo path.
        assertContains(res, "Accounting"); //$NON-NLS-1$
        assertContains(res, "app-srv-1"); //$NON-NLS-1$
        assertFalse("must not hardcode a default srvinfo path", //$NON-NLS-1$
            res.getError().contains("srvinfo\\\\") || res.getError().contains("/opt/1cv8")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNullConnectionStringIsActionableError()
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getConnectionString()).thenReturn(null);

        Resolution res = EventLogLocator.fromInfobaseReference(ref);

        assertError(res);
        assertContains(res, "logDir"); //$NON-NLS-1$
    }

    // ==================== logDir override ====================

    @Test
    public void testLogDirOverrideResolves() throws IOException
    {
        // The override points AT an existing 1Cv8Log directory.
        Path logDir = Files.createTempDirectory("evlog-override"); //$NON-NLS-1$

        Resolution res = EventLogLocator.fromLogDirOverride(logDir.toString());

        assertNull("a valid override must resolve", res.getError()); //$NON-NLS-1$
        assertEquals(logDir.toAbsolutePath(), res.getLogDir().toAbsolutePath());
        assertEquals(EventLogLocator.TYPE_OVERRIDE, res.getInfobaseType());
    }

    @Test
    public void testLogDirOverrideShortCircuitsResolutionInResolve() throws IOException
    {
        // With an override, projectName/applicationId are ignored and no EDT services are touched.
        Path logDir = Files.createTempDirectory("evlog-override-resolve"); //$NON-NLS-1$

        Resolution res = EventLogLocator.resolve("IgnoredProject", "ignoredApp", logDir.toString()); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("the override must short-circuit to a resolved log", res.getError()); //$NON-NLS-1$
        assertEquals(logDir.toAbsolutePath(), res.getLogDir().toAbsolutePath());
        assertEquals(EventLogLocator.TYPE_OVERRIDE, res.getInfobaseType());
    }

    @Test
    public void testLogDirOverrideMissingDirNamesPath()
    {
        String missing = new java.io.File(System.getProperty("java.io.tmpdir"), //$NON-NLS-1$
            "evlog-does-not-exist-" + System.nanoTime()).getAbsolutePath(); //$NON-NLS-1$

        Resolution res = EventLogLocator.fromLogDirOverride(missing);

        assertError(res);
        assertContains(res, "No event log directory"); //$NON-NLS-1$
    }

    @Test
    public void testResolveWithoutProjectOrOverrideIsActionableError()
    {
        Resolution res = EventLogLocator.resolve(null, null, null);

        assertError(res);
        assertContains(res, "projectName is required"); //$NON-NLS-1$
        assertContains(res, "logDir"); //$NON-NLS-1$
    }

    @Test
    public void testResolveWithBlankOverrideFallsBackToProjectRequirement()
    {
        // A blank override must NOT short-circuit; with no project it names the missing projectName.
        Resolution res = EventLogLocator.resolve(null, null, "   "); //$NON-NLS-1$

        assertError(res);
        assertContains(res, "projectName is required"); //$NON-NLS-1$
    }

    // ==================== Infobase-application selection ====================

    @Test
    public void testSelectSingleInfobaseNeedsNoApplicationId()
    {
        IInfobaseApplication ib = infobaseApp("ib-1"); //$NON-NLS-1$

        SelectResult res = EventLogLocator.selectInfobase(
            Collections.singletonList(ib), null, "Proj"); //$NON-NLS-1$

        assertNull("a lone infobase must select without error", res.errorJson); //$NON-NLS-1$
        assertSame(ib, res.app);
    }

    @Test
    public void testSelectByApplicationIdPicksTheMatch()
    {
        IInfobaseApplication ib1 = infobaseApp("ib-1"); //$NON-NLS-1$
        IInfobaseApplication ib2 = infobaseApp("ib-2"); //$NON-NLS-1$

        SelectResult res = EventLogLocator.selectInfobase(
            Arrays.asList(ib1, ib2), "ib-2", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(res.errorJson);
        assertSame(ib2, res.app);
    }

    @Test
    public void testMultipleInfobasesWithoutApplicationIdIsAmbiguityError()
    {
        IInfobaseApplication ib1 = infobaseApp("ib-alpha"); //$NON-NLS-1$
        IInfobaseApplication ib2 = infobaseApp("ib-beta"); //$NON-NLS-1$

        SelectResult res = EventLogLocator.selectInfobase(
            Arrays.asList(ib1, ib2), null, "Proj"); //$NON-NLS-1$

        assertNotNull("ambiguity must be an error", res.errorJson); //$NON-NLS-1$
        assertTrue(res.errorJson.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("must ask for applicationId", res.errorJson.contains("applicationId")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must list the first candidate id", res.errorJson.contains("ib-alpha")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must list the second candidate id", res.errorJson.contains("ib-beta")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnknownApplicationIdIsActionableError()
    {
        IInfobaseApplication ib1 = infobaseApp("ib-1"); //$NON-NLS-1$

        SelectResult res = EventLogLocator.selectInfobase(
            Collections.singletonList(ib1), "nope", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(res.errorJson);
        assertTrue("must name the bad id", res.errorJson.contains("nope")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must steer to get_applications", //$NON-NLS-1$
            res.errorJson.contains("get_applications")); //$NON-NLS-1$
    }

    @Test
    public void testNoInfobaseApplicationsIsActionableError()
    {
        SelectResult res = EventLogLocator.selectInfobase(
            Collections.emptyList(), null, "Proj"); //$NON-NLS-1$

        assertNotNull(res.errorJson);
        assertTrue("must explain no infobase", //$NON-NLS-1$
            res.errorJson.contains("No infobase applications")); //$NON-NLS-1$
        assertTrue("must offer the logDir escape hatch", res.errorJson.contains("logDir")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== helpers ====================

    private static InfobaseReference fileRef(String filePath)
    {
        FileConnectionString fcs = mock(FileConnectionString.class);
        when(fcs.getFile()).thenReturn(filePath);
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getConnectionString()).thenReturn(fcs);
        return ref;
    }

    private static IInfobaseApplication infobaseApp(String id)
    {
        IInfobaseApplication ib = mock(IInfobaseApplication.class);
        when(ib.getId()).thenReturn(id);
        return ib;
    }

    private static void assertError(Resolution res)
    {
        assertNotNull(res);
        assertNotNull("an error result must carry error JSON", res.getError()); //$NON-NLS-1$
        assertNull("an error result has no logDir", res.getLogDir()); //$NON-NLS-1$
        assertTrue("error JSON must be a ToolResult error", //$NON-NLS-1$
            res.getError().contains("\"success\":false")); //$NON-NLS-1$
    }

    private static void assertContains(Resolution res, String needle)
    {
        assertTrue("error must mention '" + needle + "': " + res.getError(), //$NON-NLS-1$ //$NON-NLS-2$
            res.getError().contains(needle));
    }
}
