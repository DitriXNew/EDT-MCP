/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.ImportConfigurationFromXmlTool.ImportClaim;
import com.ditrix.edt.mcp.server.utils.WorkspacePaths;

/** Both deterministic case rules and the atomic shared-folder claim. */
public class ImportClaimFolderCaseTest extends ImportFolderCaseTestSupport
{
    @Test
    public void testAClaimIsSeenUnderACaseVariantOnlyWhenTheFileSystemFoldsCase() throws Exception
    {
        String name = name();
        assertNull(claim(name, false));
        ImportClaim running = ImportConfigurationFromXmlTool.importClaimedAt(name);
        assertSame(running, claim(variant(name), false));
        assertEquals(name, running.projectName);
        assertNull("a case-sensitive file system allows a separate folder", claim(variant(name), true));
    }

    @Test
    public void testProductionClaimAndLookupUseThePlatformRule()
    {
        String name = name();
        assertNull(ImportConfigurationFromXmlTool.tryClaimImport(name));
        ImportClaim running = ImportConfigurationFromXmlTool.importClaimedAt(name);
        if (caseSensitive())
        {
            assertNull(ImportConfigurationFromXmlTool.importClaimedAt(variant(name)));
            assertNull(ImportConfigurationFromXmlTool.tryClaimImport(variant(name)));
        }
        else
        {
            assertSame(running, ImportConfigurationFromXmlTool.importClaimedAt(variant(name)));
            assertSame(running, ImportConfigurationFromXmlTool.tryClaimImport(variant(name)));
        }
    }

    @Test
    public void testTheRefusalNamesBothNamesAndTheirFolder() throws Exception
    {
        String name = name();
        assertNull(claim(name, false));
        ImportClaim running = ImportConfigurationFromXmlTool.importClaimedAt(name);
        String message = ImportConfigurationFromXmlTool.importInProgressMessage(variant(name), running);
        assertTrue(message, message.contains("`" + name + "`"));
        assertTrue(message, message.contains("`" + variant(name) + "`"));
        assertTrue(message, message.contains("letter case"));
        assertTrue(message, message.contains("already in progress"));
        assertTrue(message, message.contains("list_projects"));
        assertTrue(message, message.contains(WorkspacePaths.defaultProjectFolder(name).toString()));
        String exact = ImportConfigurationFromXmlTool.importInProgressMessage(name, running);
        assertFalse(exact, exact.contains("letter case"));
        assertTrue(exact, exact.endsWith("second import of the same name races the first and can destroy both."));
    }

    @Test
    public void testReleaseRemovesOnlyTheClaimantsOwnKey() throws Exception
    {
        String name = name();
        assertNull(claim(name, false));
        ImportClaim running = ImportConfigurationFromXmlTool.importClaimedAt(name);
        ImportConfigurationFromXmlTool.releaseImport(variant(name));
        assertSame(running, ImportConfigurationFromXmlTool.importClaimedAt(name));
        ImportConfigurationFromXmlTool.releaseImport(name);
        assertNull(ImportConfigurationFromXmlTool.importClaimedAt(name));
    }

    @Test
    public void testScanAndInsertionAreAtomicForConcurrentCaseVariants() throws Exception
    {
        int workers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try
        {
            for (int round = 0; round < 60; round++)
            {
                String name = name();
                CyclicBarrier start = new CyclicBarrier(workers);
                List<Future<Boolean>> attempts = new ArrayList<>();
                for (int i = 0; i < workers; i++)
                {
                    String requested = i % 2 == 0 ? name : variant(name);
                    attempts.add(pool.submit(() ->
                    {
                        start.await(30, TimeUnit.SECONDS);
                        return claim(requested, false) == null;
                    }));
                }
                int owners = 0;
                for (Future<Boolean> attempt : attempts)
                {
                    if (attempt.get(30, TimeUnit.SECONDS))
                    {
                        owners++;
                    }
                }
                assertEquals("only one caller may own the folder in round " + round, 1, owners);
                ImportConfigurationFromXmlTool.releaseImport(name);
                ImportConfigurationFromXmlTool.releaseImport(variant(name));
            }
        }
        finally
        {
            pool.shutdownNow();
            assertTrue("all claim attempts finish", pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    private static ImportClaim claim(String name, boolean sensitive)
    {
        return ImportConfigurationFromXmlTool.tryClaimImport(name, sensitive);
    }
}
