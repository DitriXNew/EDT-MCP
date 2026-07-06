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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight contract tests for {@link ResyncToDiskTool}: tool metadata, the bundled
 * {@code get_tool_guide} body, JSON input/output schema (incl. input/output field separation),
 * the documented response-format policy, plus the headless-testable decision cores - which FQNs
 * the export takes ({@code selectExportFqns}), the {@code DanglingResult} report/commit-count
 * semantics, the commit-honest dangling-removal reporting ({@code runRemovalWriteTask}) and the
 * missing-{@code .mdo} filesystem checks ({@code findMissingMdoFiles} /
 * {@code findMissingMdoFilesWithWait}) - without needing the Eclipse/EDT runtime.
 * <p>
 * {@code execute()} is NOT exercised here: it is {@code final} on
 * {@link com.ditrix.edt.mcp.server.tools.base.AbstractMetadataWriteTool} and immediately calls
 * {@code ProjectStateChecker.buildingErrorOrNull(...)} then
 * {@code PlatformUI.getWorkbench().getDisplay()} BEFORE any subclass arg-validation, so it cannot
 * run headlessly - the {@code projectName is required} guard lives in {@code executeOnUiThread},
 * which only runs on the SWT UI thread. The {@code execute()} path then walks the live BM model,
 * force-exports {@code .mdo} files and (only with {@code cleanDanglingReferences=true}) mutates the
 * {@code Configuration}, so the real repair behaviour (delete a {@code .mdo} and restore it) is
 * covered by the E2E suite. Fabricating a genuinely dangling Configuration reference through public
 * tools is not possible headless, so the REMOVAL outcome reporting is pinned here at the unit level
 * instead (see the {@code runRemovalWriteTask} tests).
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
        assertTrue("schema must declare the overwriteDiskEdits confirm toggle", //$NON-NLS-1$
            schema.contains("\"overwriteDiskEdits\"")); //$NON-NLS-1$
        assertTrue("schema must declare the revalidate toggle", //$NON-NLS-1$
            schema.contains("\"revalidate\"")); //$NON-NLS-1$
        // projectName is the only required parameter.
        assertTrue("projectName must be required", //$NON-NLS-1$
            schema.contains("\"required\"") && schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaDeclaresTheOverwriteDiskEditsConfirmGuard()
    {
        // The fullExport confirm guard reads overwriteDiskEdits in executeOnUiThread, so it MUST be
        // declared in getInputSchema() (ToolContractConsistencyTest) - else it is invisible to
        // schema-driven clients and lowerCamelCase-checked.
        String schema = new ResyncToDiskTool().getInputSchema();
        assertTrue("schema must declare the overwriteDiskEdits confirm guard read in execute()", //$NON-NLS-1$
            schema.contains("\"overwriteDiskEdits\"")); //$NON-NLS-1$
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
        assertTrue(schema.contains("\"cleanDanglingReferences\"")); //$NON-NLS-1$
    }

    @Test
    public void testReportOnlyOutputFieldsAreNotInputParameters()
    {
        // The dangling-scan report fields are AUTO-COMPUTED outputs, never inputs. A future
        // edit must not accidentally turn one into an input the tool would then ignore.
        ResyncToDiskTool tool = new ResyncToDiskTool();
        String input = tool.getInputSchema();
        assertFalse("danglingFound is an output, not an input parameter", //$NON-NLS-1$
            input.contains("\"danglingFound\"")); //$NON-NLS-1$
        assertFalse("missingBefore is an output, not an input parameter", //$NON-NLS-1$
            input.contains("\"missingBefore\"")); //$NON-NLS-1$
        assertFalse("objectsExported is an output, not an input parameter", //$NON-NLS-1$
            input.contains("\"objectsExported\"")); //$NON-NLS-1$
        // ...and they ARE declared on the output side.
        String output = tool.getOutputSchema();
        assertTrue(output.contains("\"danglingFound\"")); //$NON-NLS-1$
        assertTrue(output.contains("\"objectsExported\"")); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameDefaultsToToolNameMarkdown()
    {
        // resync_to_disk does not override getResultFileName, so the IMcpTool default
        // (toolName + ".md") applies regardless of params.
        ResyncToDiskTool tool = new ResyncToDiskTool();
        assertEquals("resync_to_disk.md", tool.getResultFileName(Collections.emptyMap())); //$NON-NLS-1$
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "Whatever"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the result file name must not depend on the params", //$NON-NLS-1$
            "resync_to_disk.md", tool.getResultFileName(params)); //$NON-NLS-1$
    }

    // ---------------------------------------------------------------------------------------------
    // getGuide(): the on-demand how-to. resync_to_disk does NOT override it, so the IMcpTool
    // default loads guides/resync_to_disk.md via GuideLoader (resolves through the OSGi bundle
    // active under Tycho Surefire). This pins that the guide resource is actually PACKAGED and
    // keyed off the tool name, and that it documents the destructive opt-in and the report fields.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void testGuideIsPackagedAndNonEmpty()
    {
        String guide = new ResyncToDiskTool().getGuide();
        assertNotNull("getGuide() must never return null", guide); //$NON-NLS-1$
        assertFalse("the bundled guides/resync_to_disk.md must be packaged and load non-empty", //$NON-NLS-1$
            guide.isEmpty());
    }

    @Test
    public void testGuideDocumentsTheDestructiveDanglingOptIn()
    {
        String guide = new ResyncToDiskTool().getGuide();
        assertTrue("guide must document the cleanDanglingReferences opt-in", //$NON-NLS-1$
            guide.contains("cleanDanglingReferences")); //$NON-NLS-1$
        assertTrue("guide must warn the opt-in is destructive", //$NON-NLS-1$
            guide.toLowerCase().contains("destructive")); //$NON-NLS-1$
        assertTrue("guide must document the fullExport refresh", guide.contains("fullExport")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document the missingBefore desync report", //$NON-NLS-1$
            guide.contains("missingBefore")); //$NON-NLS-1$
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

    @Test
    public void testSelectExportFqnsDefaultReturnsTheMissingListItself()
    {
        // The default branch must hand back the missing subset as-is (no copy/filter): the
        // caller exports exactly the objects integrity-checked as missing, nothing more.
        List<String> all = Arrays.asList("Catalog.A", "Catalog.B"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> missing = Arrays.asList("Catalog.B"); //$NON-NLS-1$
        assertSame("default must reuse the missing list, not derive a new one", //$NON-NLS-1$
            missing, ResyncToDiskTool.selectExportFqns(false, all, missing));
    }

    @Test
    public void testSelectExportFqnsFullExportEvenWhenNothingMissing()
    {
        // fullExport=true is a deliberate full refresh: it exports every object even on an
        // in-sync project (empty missing set), unlike the default no-op.
        List<String> all = Arrays.asList("Catalog.A", "Document.C"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("fullExport=true must export all even with an empty missing set", //$NON-NLS-1$
            all, ResyncToDiskTool.selectExportFqns(true, all, Collections.emptyList()));
    }

    // ---------------------------------------------------------------------------------------------
    // fullExportGuardError: the fullExport confirm guard. A fullExport re-serializes EVERY .mdo from
    // the in-memory model and would overwrite on-disk edits, so it must be confirmed with
    // overwriteDiskEdits=true. The guard is a pure, pre-BM early return (unattended-safe) and ONLY
    // gates fullExport - the default missing-only resync and cleanDanglingReferences paths pass
    // fullExport=false and are never blocked.
    // ---------------------------------------------------------------------------------------------

    @Test
    public void testFullExportGuardRejectsUnconfirmedFullExport()
    {
        // fullExport=true without overwriteDiskEdits=true must be rejected before any BM read.
        String guardError = ResyncToDiskTool.fullExportGuardError(true, false);
        assertNotNull("an unconfirmed fullExport must be rejected", guardError); //$NON-NLS-1$
        assertTrue("the rejection must be a JSON error envelope", //$NON-NLS-1$
            guardError.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("the error must name the overwriteDiskEdits confirmation to unblock", //$NON-NLS-1$
            guardError.contains("overwriteDiskEdits")); //$NON-NLS-1$
    }

    @Test
    public void testFullExportGuardAllowsConfirmedFullExport()
    {
        // fullExport=true WITH overwriteDiskEdits=true is the confirmed full refresh: no error.
        assertNull("a confirmed fullExport must pass the guard", //$NON-NLS-1$
            ResyncToDiskTool.fullExportGuardError(true, true));
    }

    @Test
    public void testFullExportGuardDoesNotGateTheDefaultResync()
    {
        // The default missing-only resync (fullExport=false) is never gated, regardless of the
        // overwriteDiskEdits value - the guard only concerns the full export.
        assertNull("the default missing-only resync must never be gated", //$NON-NLS-1$
            ResyncToDiskTool.fullExportGuardError(false, false));
        assertNull("overwriteDiskEdits is ignored when fullExport=false", //$NON-NLS-1$
            ResyncToDiskTool.fullExportGuardError(false, true));
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

    @Test
    public void testFreshDanglingResultIsAnEmptyCleanReport()
    {
        // A just-constructed result (the clean-project / scan-skipped baseline) must report
        // nothing found, nothing removed, no warning - so an in-sync run shows danglingFound 0.
        ResyncToDiskTool.DanglingResult result = new ResyncToDiskTool.DanglingResult();
        assertEquals("a fresh result must have found nothing", 0, result.found); //$NON-NLS-1$
        assertFalse("a fresh result must claim no removal", result.removedFromModel); //$NON-NLS-1$
        assertEquals("a fresh result must report 0 removed", 0, result.removedCount()); //$NON-NLS-1$
        assertNotNull("details must be a live (empty) list, never null", result.details); //$NON-NLS-1$
        assertTrue(result.details.isEmpty());
        assertNull("a fresh result must carry no warning", result.warning); //$NON-NLS-1$
    }

    @Test
    public void testRemovedCountIsZeroWhenFoundButNotCommitted()
    {
        // removedCount() gates on the COMMIT flag, not on `found`: entries were observed
        // (found > 0) but the removal was not committed, so the reported count stays 0 -
        // a report-only scan never over-claims a removal.
        ResyncToDiskTool.DanglingResult result = new ResyncToDiskTool.DanglingResult();
        result.found = 5;
        // removedFromModel left at its default false (no committed write).
        assertEquals("found entries that were not committed must report 0 removed", //$NON-NLS-1$
            0, result.removedCount());
    }

    @Test
    public void testRemovedCountEqualsFoundOnlyWhenCommitted()
    {
        ResyncToDiskTool.DanglingResult result = new ResyncToDiskTool.DanglingResult();
        result.found = 4;
        result.removedFromModel = true;
        assertEquals("a committed removal reports exactly the found count", //$NON-NLS-1$
            4, result.removedCount());
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
    public void testFindMissingMdoFilesEmptyInputIsEmpty() throws IOException
    {
        File projectRoot = Files.createTempDirectory("resync-empty").toFile(); //$NON-NLS-1$
        try
        {
            assertTrue("no FQNs in means no missing FQNs out", //$NON-NLS-1$
                ResyncToDiskTool.findMissingMdoFiles(projectRoot, Collections.emptyList()).isEmpty());
        }
        finally
        {
            deleteRecursively(projectRoot);
        }
    }

    @Test
    public void testFindMissingMdoFilesPartitionsPresentFromAbsent() throws IOException
    {
        File projectRoot = Files.createTempDirectory("resync-mixed").toFile(); //$NON-NLS-1$
        try
        {
            // Foo exists on disk; Bar does not - only Bar must be reported, in input order.
            touch(new File(projectRoot, "src/Catalogs/Foo/Foo.mdo")); //$NON-NLS-1$
            List<String> missing = ResyncToDiskTool.findMissingMdoFiles(projectRoot,
                Arrays.asList("Catalog.Foo", "Document.Bar")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(1, missing.size());
            assertEquals("only the absent .mdo is reported missing", //$NON-NLS-1$
                "Document.Bar", missing.get(0)); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(projectRoot);
        }
    }

    @Test
    public void testFindMissingMdoFilesSkipsFqnWithNoTypeDirectoryLayout() throws IOException
    {
        File projectRoot = Files.createTempDirectory("resync-skip").toFile(); //$NON-NLS-1$
        try
        {
            // "Configuration" is dotless -> MetadataPathResolver.resolveTopObjectMdoPath returns
            // null (no src/<TypeDir>/<Name>/<Name>.mdo layout), so it must be SKIPPED, never
            // reported as a missing desync even though no such file exists on disk.
            List<String> missing = ResyncToDiskTool.findMissingMdoFiles(projectRoot,
                Arrays.asList("Configuration", "Catalog.Foo")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("the dotless/no-layout FQN must be skipped, only the real desync reported", //$NON-NLS-1$
                Arrays.asList("Catalog.Foo"), missing); //$NON-NLS-1$
        }
        finally
        {
            deleteRecursively(projectRoot);
        }
    }

    @Test
    public void testBoundedWaitEmptyInputReturnsImmediatelyEmpty() throws IOException
    {
        File projectRoot = Files.createTempDirectory("resync-wait-empty").toFile(); //$NON-NLS-1$
        try
        {
            // Nothing to wait for: an empty FQN set must return at once without paying the budget.
            long start = System.currentTimeMillis();
            List<String> missing = ResyncToDiskTool.findMissingMdoFilesWithWait(projectRoot,
                Collections.emptyList(), 5000L, 100L);
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("an empty input must yield an empty still-missing set", missing.isEmpty()); //$NON-NLS-1$
            assertTrue("an empty input must not spend the wait budget (elapsed=" //$NON-NLS-1$
                + elapsed + "ms)", elapsed < 1000L); //$NON-NLS-1$
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
