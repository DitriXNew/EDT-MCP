/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight contract tests for {@link ResyncToDiskTool}: tool metadata, JSON input/output
 * schema, the documented response-format policy, plus the headless-testable decision cores -
 * which FQNs the export takes ({@code selectExportFqns}), the commit-honest dangling-removal
 * reporting ({@code runRemovalWriteTask}) and the missing-{@code .mdo} filesystem checks -
 * without needing the Eclipse/EDT runtime.
 * <p>
 * The {@code execute()} path walks the live BM model, force-exports {@code .mdo} files to disk
 * and (only with {@code cleanDanglingReferences=true}) mutates the {@code Configuration}, so it
 * needs a live workbench and BM model; the real repair behaviour (delete a {@code .mdo} and
 * restore it) is covered by the E2E suite. Fabricating a genuinely dangling Configuration
 * reference through public tools is not possible headless, so the REMOVAL outcome reporting is
 * pinned here at the unit level instead (see the {@code runRemovalWriteTask} tests).
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
    public void testDescriptionIsHonestAboutTheDestructiveCleanup()
    {
        // Review feedback: cleanDanglingReferences=true rewrites Configuration.mdo, so the
        // tool must not advertise itself as "Read-safe". The description has to state the
        // report-only default and call the opt-in removal what it is: destructive.
        String desc = new ResyncToDiskTool().getDescription();
        assertFalse("description must not claim the tool is read-safe", //$NON-NLS-1$
            desc.toLowerCase().contains("read-safe")); //$NON-NLS-1$
        assertTrue("description must state the report-only default", //$NON-NLS-1$
            desc.contains("REPORTED by default")); //$NON-NLS-1$
        assertTrue("description must call the opt-in removal destructive", //$NON-NLS-1$
            desc.contains("destructive")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresAllParameters()
    {
        String schema = new ResyncToDiskTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue("schema must declare the cleanDanglingReferences toggle", //$NON-NLS-1$
            schema.contains("\"cleanDanglingReferences\"")); //$NON-NLS-1$
        assertTrue("schema must declare the fullExport toggle", //$NON-NLS-1$
            schema.contains("\"fullExport\"")); //$NON-NLS-1$
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
        // The request flags are echoed back so a caller can verify what actually ran.
        assertTrue(schema.contains("\"fullExport\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"revalidate\"")); //$NON-NLS-1$
    }

    // ---------------------------------------------------------------------------------------------
    // selectExportFqns: WHICH top objects step 3 exports. Default = only the missing subset
    // (the export runs on the UI thread; re-serializing the whole configuration to restore a
    // handful of files would freeze the workbench); fullExport=true = everything.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void testSelectExportFqnsDefaultsToTheMissingSubset()
    {
        List<String> all = Arrays.asList("Catalog.A", "Catalog.B", "Document.C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<String> missing = Arrays.asList("Catalog.B"); //$NON-NLS-1$
        assertEquals("default export must take ONLY the missing subset, not the full walk", //$NON-NLS-1$
            missing, ResyncToDiskTool.selectExportFqns(false, all, missing));
    }

    @Test
    public void testSelectExportFqnsExportsNothingWhenInSync()
    {
        List<String> all = Arrays.asList("Catalog.A", "Catalog.B"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an in-sync project (nothing missing) must export NOTHING by default", //$NON-NLS-1$
            ResyncToDiskTool.selectExportFqns(false, all, Collections.emptyList()).isEmpty());
    }

    @Test
    public void testSelectExportFqnsFullExportTakesEveryTopObject()
    {
        List<String> all = Arrays.asList("Catalog.A", "Catalog.B", "Document.C"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<String> missing = Arrays.asList("Catalog.B"); //$NON-NLS-1$
        assertEquals("fullExport=true must opt back in to the export-everything refresh", //$NON-NLS-1$
            all, ResyncToDiskTool.selectExportFqns(true, all, missing));
    }

    // ---------------------------------------------------------------------------------------------
    // runRemovalWriteTask: commit-honest dangling-removal reporting. The removedFromModel claim
    // ("Removed N") may only be made AFTER BmTransactions.write returned, i.e. after the
    // transaction committed; a throwing write task must surface as a warning with NO removal
    // claim. A genuinely dangling Configuration reference cannot be fabricated headless via
    // public tools, so this outcome contract is pinned here instead of in the E2E suite.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void testThrowingRemovalTaskReportsNoRemovals()
    {
        ResyncToDiskTool.DanglingResult result = new ResyncToDiskTool.DanglingResult();
        result.found = 3; // the scan had observed entries before the commit failed
        boolean committed = ResyncToDiskTool.runRemovalWriteTask(result, () -> {
            throw new IllegalStateException("commit failed"); //$NON-NLS-1$
        });
        assertFalse("a throwing write task must not count as committed", committed); //$NON-NLS-1$
        assertFalse("a failed commit must NOT claim the removal happened", //$NON-NLS-1$
            result.removedFromModel);
        assertEquals("removedCount must stay 0 when the write task threw", //$NON-NLS-1$
            0, result.removedCount());
        assertNotNull("the failure must surface through the warning plumbing", result.warning); //$NON-NLS-1$
        assertTrue("the warning must carry the failure message", //$NON-NLS-1$
            result.warning.contains("commit failed")); //$NON-NLS-1$
    }

    @Test
    public void testSuccessfulRemovalTaskClaimsRemovalOnlyAfterReturn()
    {
        ResyncToDiskTool.DanglingResult result = new ResyncToDiskTool.DanglingResult();
        result.found = 2;
        boolean committed = ResyncToDiskTool.runRemovalWriteTask(result, () -> Boolean.TRUE);
        assertTrue("a returning write task means the transaction committed", committed); //$NON-NLS-1$
        assertTrue("a committed removal must be claimed", result.removedFromModel); //$NON-NLS-1$
        assertEquals("removedCount reports the committed removals", 2, result.removedCount()); //$NON-NLS-1$
        assertNull("a clean run must not set a warning", result.warning); //$NON-NLS-1$
    }

    @Test
    public void testReportOnlyTaskClaimsNoRemoval()
    {
        // The task committed but removed nothing (report-only scan): found entries are
        // reported, the removal claim stays off and removedCount stays 0.
        ResyncToDiskTool.DanglingResult result = new ResyncToDiskTool.DanglingResult();
        result.found = 2;
        boolean committed = ResyncToDiskTool.runRemovalWriteTask(result, () -> Boolean.FALSE);
        assertTrue(committed);
        assertFalse("a report-only scan must not claim a removal", result.removedFromModel); //$NON-NLS-1$
        assertEquals(0, result.removedCount());
        assertNull(result.warning);
    }

    // ---------------------------------------------------------------------------------------------
    // The post-export integrity check must reflect REAL on-disk state. The force-export
    // flushes the .mdo asynchronously, so stillMissing is computed with a short bounded wait that
    // re-polls the filesystem. These tests drive the filesystem core (findMissingMdoFilesWithWait /
    // findMissingMdoFiles over a plain temp project root; the expected per-FQN path -
    // src/<TypeDir>/<Name>/<Name>.mdo - comes from MetadataPathResolver.resolveTopObjectMdoPath,
    // unit-tested in MetadataPathResolverTest) without the Eclipse/EDT runtime.
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
        File projectRoot = Files.createTempDirectory("resync-missing").toFile(); //$NON-NLS-1$
        try
        {
            // Catalog.Foo maps to src/Catalogs/Foo/Foo.mdo, which does not exist here.
            List<String> missing =
                ResyncToDiskTool.findMissingMdoFiles(projectRoot, Arrays.asList("Catalog.Foo")); //$NON-NLS-1$
            assertEquals(1, missing.size());
            assertEquals("Catalog.Foo", missing.get(0)); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(projectRoot);
        }
    }

    @Test
    public void testFindMissingMdoFilesIgnoresPresentFile() throws IOException
    {
        File projectRoot = Files.createTempDirectory("resync-present").toFile(); //$NON-NLS-1$
        try
        {
            touch(new File(projectRoot, "src/Catalogs/Foo/Foo.mdo")); //$NON-NLS-1$
            List<String> missing =
                ResyncToDiskTool.findMissingMdoFiles(projectRoot, Arrays.asList("Catalog.Foo")); //$NON-NLS-1$
            assertTrue("a present .mdo must not be reported as missing", missing.isEmpty()); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(projectRoot);
        }
    }

    @Test
    public void testBoundedWaitReturnsImmediatelyWhenAlreadyPresent() throws IOException
    {
        File projectRoot = Files.createTempDirectory("resync-wait-present").toFile(); //$NON-NLS-1$
        try
        {
            touch(new File(projectRoot, "src/Catalogs/Foo/Foo.mdo")); //$NON-NLS-1$
            long start = System.currentTimeMillis();
            List<String> missing = ResyncToDiskTool.findMissingMdoFilesWithWait(projectRoot,
                Arrays.asList("Catalog.Foo"), 2500L, 100L); //$NON-NLS-1$
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("an already-present .mdo must not be reported missing", missing.isEmpty()); //$NON-NLS-1$
            // First round is free (no sleep): it must not pay the wait budget when nothing is missing.
            assertTrue("bounded wait must return promptly when nothing is missing (elapsed=" //$NON-NLS-1$
                + elapsed + "ms)", elapsed < 1000L); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(projectRoot);
        }
    }

    @Test
    public void testBoundedWaitStillReportsPermanentlyAbsentFile() throws IOException
    {
        File projectRoot = Files.createTempDirectory("resync-wait-absent").toFile(); //$NON-NLS-1$
        try
        {
            // The file never appears: after the (short) budget it must still be reported missing.
            long start = System.currentTimeMillis();
            List<String> missing = ResyncToDiskTool.findMissingMdoFilesWithWait(projectRoot,
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
            deleteRecursively(projectRoot);
        }
    }

    @Test
    public void testBoundedWaitCountsFileThatLandsDuringTheWait() throws Exception
    {
        File projectRoot = Files.createTempDirectory("resync-wait-lands").toFile(); //$NON-NLS-1$
        final AtomicBoolean wrote = new AtomicBoolean(false);
        try
        {
            File mdo = new File(projectRoot, "src/Catalogs/Foo/Foo.mdo"); //$NON-NLS-1$
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

            List<String> missing = ResyncToDiskTool.findMissingMdoFilesWithWait(projectRoot,
                Arrays.asList("Catalog.Foo"), 2500L, 50L); //$NON-NLS-1$
            flusher.join();

            assertTrue("the flusher must have written the .mdo", wrote.get()); //$NON-NLS-1$
            assertTrue("a .mdo that lands during the bounded wait must be counted as present, " //$NON-NLS-1$
                + "not reported as stillMissing", missing.isEmpty()); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(projectRoot);
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
