/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight contract tests for {@link ResyncToDiskTool}: tool metadata, JSON input/output
 * schema, and the documented response-format policy, without needing the Eclipse/EDT runtime.
 * <p>
 * The {@code execute()} path walks the live BM model, force-exports {@code .mdo} files to disk
 * and mutates the {@code Configuration} (dangling-reference removal), so it needs a live
 * workbench and BM model; the real resync / dangling-cleanup behaviour is covered by the E2E
 * suite (delete a {@code .mdo} and restore it; leave a dangling ref and clear it).
 */
public class ResyncToDiskToolTest
{
    @Test
    public void testName()
    {
        assertEquals("resync_to_disk", new ResyncToDiskTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ResyncToDiskTool.NAME, new ResyncToDiskTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        // resync_to_disk returns a machine-structured payload (counts + FQN lists), so it is a
        // JSON tool and therefore MUST declare an output schema (BuiltInToolOutputSchemaTest).
        assertEquals(ResponseType.JSON, new ResyncToDiskTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new ResyncToDiskTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('resync_to_disk')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresAllParameters()
    {
        String schema = new ResyncToDiskTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue("schema must declare the cleanDanglingReferences toggle", //$NON-NLS-1$
            schema.contains("\"cleanDanglingReferences\"")); //$NON-NLS-1$
        assertTrue("schema must declare the revalidate toggle", //$NON-NLS-1$
            schema.contains("\"revalidate\"")); //$NON-NLS-1$
        // projectName is the only required parameter.
        assertTrue("projectName must be required", //$NON-NLS-1$
            schema.contains("\"required\"") && schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDescribesTheSuccessEnvelope()
    {
        String schema = new ResyncToDiskTool().getOutputSchema();
        assertNotNull("a JSON tool must declare an outputSchema", schema); //$NON-NLS-1$
        assertFalse(schema.isEmpty());
        // The success envelope plus the load-bearing report fields.
        assertTrue(schema.contains("\"success\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectsExported\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"missingBefore\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"stillMissing\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"danglingFound\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"danglingRemovedCount\"")); //$NON-NLS-1$
    }

    // ---------------------------------------------------------------------------------------------
    // The post-export integrity check must reflect REAL on-disk state. The force-export
    // flushes the .mdo asynchronously, so stillMissing is computed with a short bounded wait that
    // re-polls the filesystem. These tests drive the filesystem core (findMissingMdoFilesWithWait /
    // findMissingMdoFiles over a plain temp src/ root) without the Eclipse/EDT runtime.
    // ---------------------------------------------------------------------------------------------

    /** Writes an empty file, creating parent directories, so a .mdo "exists" on disk. */
    private static void touch(File file) throws IOException
    {
        File parent = file.getParentFile();
        if (parent != null)
        {
            parent.mkdirs();
        }
        Files.write(file.toPath(), new byte[0]);
    }

    @Test
    public void testFindMissingMdoFilesDetectsAbsentFile() throws IOException
    {
        File srcRoot = Files.createTempDirectory("resync-missing").toFile(); //$NON-NLS-1$
        try
        {
            // Catalog.Foo maps to src/Catalogs/Foo/Foo.mdo, which does not exist here.
            List<String> missing =
                ResyncToDiskTool.findMissingMdoFiles(srcRoot, Arrays.asList("Catalog.Foo")); //$NON-NLS-1$
            assertEquals(1, missing.size());
            assertEquals("Catalog.Foo", missing.get(0)); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(srcRoot);
        }
    }

    @Test
    public void testFindMissingMdoFilesIgnoresPresentFile() throws IOException
    {
        File srcRoot = Files.createTempDirectory("resync-present").toFile(); //$NON-NLS-1$
        try
        {
            touch(new File(srcRoot, "Catalogs/Foo/Foo.mdo")); //$NON-NLS-1$
            List<String> missing =
                ResyncToDiskTool.findMissingMdoFiles(srcRoot, Arrays.asList("Catalog.Foo")); //$NON-NLS-1$
            assertTrue("a present .mdo must not be reported as missing", missing.isEmpty()); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(srcRoot);
        }
    }

    @Test
    public void testBoundedWaitReturnsImmediatelyWhenAlreadyPresent() throws IOException
    {
        File srcRoot = Files.createTempDirectory("resync-wait-present").toFile(); //$NON-NLS-1$
        try
        {
            touch(new File(srcRoot, "Catalogs/Foo/Foo.mdo")); //$NON-NLS-1$
            long start = System.currentTimeMillis();
            List<String> missing = ResyncToDiskTool.findMissingMdoFilesWithWait(srcRoot,
                Arrays.asList("Catalog.Foo"), 2500L, 100L); //$NON-NLS-1$
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("an already-present .mdo must not be reported missing", missing.isEmpty()); //$NON-NLS-1$
            // First round is free (no sleep): it must not pay the wait budget when nothing is missing.
            assertTrue("bounded wait must return promptly when nothing is missing (elapsed=" //$NON-NLS-1$
                + elapsed + "ms)", elapsed < 1000L); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(srcRoot);
        }
    }

    @Test
    public void testBoundedWaitStillReportsPermanentlyAbsentFile() throws IOException
    {
        File srcRoot = Files.createTempDirectory("resync-wait-absent").toFile(); //$NON-NLS-1$
        try
        {
            // The file never appears: after the (short) budget it must still be reported missing.
            long start = System.currentTimeMillis();
            List<String> missing = ResyncToDiskTool.findMissingMdoFilesWithWait(srcRoot,
                Arrays.asList("Catalog.Foo"), 300L, 50L); //$NON-NLS-1$
            long elapsed = System.currentTimeMillis() - start;
            assertEquals(1, missing.size());
            assertEquals("Catalog.Foo", missing.get(0)); //$NON-NLS-1$
            // It must actually have waited out (roughly) the budget before giving up.
            assertTrue("bounded wait must spend the budget before reporting still-missing (elapsed=" //$NON-NLS-1$
                + elapsed + "ms)", elapsed >= 250L); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(srcRoot);
        }
    }

    @Test
    public void testBoundedWaitCountsFileThatLandsDuringTheWait() throws Exception
    {
        File srcRoot = Files.createTempDirectory("resync-wait-lands").toFile(); //$NON-NLS-1$
        final AtomicBoolean wrote = new AtomicBoolean(false);
        try
        {
            File mdo = new File(srcRoot, "Catalogs/Foo/Foo.mdo"); //$NON-NLS-1$
            // Simulate the asynchronous flush: the .mdo lands ~200ms after the check starts,
            // i.e. AFTER the first (immediate) probe but WELL WITHIN the 2.5s budget.
            Thread flusher = new Thread(() -> {
                try
                {
                    Thread.sleep(200L);
                    touch(mdo);
                    wrote.set(true);
                }
                catch (Exception e)
                {
                    // Test thread: swallow; the assertion on `wrote` covers a failed write.
                }
            }, "resync-test-flusher"); //$NON-NLS-1$
            flusher.start();

            List<String> missing = ResyncToDiskTool.findMissingMdoFilesWithWait(srcRoot,
                Arrays.asList("Catalog.Foo"), 2500L, 50L); //$NON-NLS-1$
            flusher.join();

            assertTrue("the flusher must have written the .mdo", wrote.get()); //$NON-NLS-1$
            assertTrue("a .mdo that lands during the bounded wait must be counted as present, " //$NON-NLS-1$
                + "not reported as stillMissing", missing.isEmpty()); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(srcRoot);
        }
    }

    /** Recursively deletes a temp directory tree (best-effort test cleanup). */
    private static void deleteRecursively(File file)
    {
        if (file == null)
        {
            return;
        }
        File[] children = file.listFiles();
        if (children != null)
        {
            for (File child : children)
            {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
