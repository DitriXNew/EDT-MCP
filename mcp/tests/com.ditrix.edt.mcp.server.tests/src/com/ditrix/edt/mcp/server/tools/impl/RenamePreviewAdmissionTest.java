/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.google.gson.JsonParser;

/**
 * Round 2 of #803: a preview is admitted against the rename previews only, the default wait keeps
 * a finished preview inline, the stage-named timeout fires before the registry deadline, and a
 * form element attaches across dialects.
 */
public class RenamePreviewAdmissionTest
{
    private static final String PREVIEW = "---\naction: preview\ncontentHash: abc\n---\n";

    @Test
    public void testTwoForeignRunningJobsDoNotBlockAPreview() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 4))
        {
            try
            {
                startForeign(jobs, release);
                startForeign(jobs, release);
                String answer = start(tool(jobs), "Catalog.Products", "Goods", blocked(release));
                assertTrue("Other tools' jobs must not refuse a preview while a worker is free: " + answer,
                    answer.startsWith("**Pending:**"));
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void testTwoRunningPreviewsRefuseAThirdNamingThePreviewLimit() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 4))
        {
            try
            {
                RenameMetadataObjectTool tool = tool(jobs);
                assertTrue(start(tool, "Catalog.Products", "Goods", blocked(release)).startsWith("**Pending:**"));
                assertTrue(start(tool, "Catalog.Other", "Items", blocked(release)).startsWith("**Pending:**"));
                String refused = start(tool, "Catalog.Third", "More", blocked(release));
                assertTrue(refused, refused.contains("2 other rename previews are already running, "
                    + "the limit for rename previews"));
                assertTrue(refused.contains("get_job_status"));
                assertFalse(refused.contains("busy with other jobs"));
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void testEveryWorkerBusyWithOtherJobsRefusesWithoutBlamingPreviews() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 2))
        {
            try
            {
                startForeign(jobs, release);
                startForeign(jobs, release);
                String refused = start(tool(jobs), "Catalog.Products", "Goods", blocked(release));
                assertTrue(refused, refused.contains("All 2 background-job workers are busy with other jobs"));
                assertTrue(refused.contains("Nothing was renamed"));
                assertFalse(refused.contains("rename previews"));
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void testDefaultWaitIsTheTransportSafeMaximum()
    {
        RenameMetadataObjectTool tool = new RenameMetadataObjectTool((project, timeout) -> "must not settle");
        String wait = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject()
            .getAsJsonObject("properties").getAsJsonObject("waitSeconds").get("description").getAsString();
        assertTrue(wait, wait.contains("(default 45, accepts 0..45;"));
        Map<String, String> params = new HashMap<>(Map.of("projectName", "Demo",
            "objectFqn", "Catalog.Products", "newName", "Goods", "waitSeconds", "46"));
        String refused = tool.execute(params);
        assertTrue(refused, refused.contains("omit it to wait up to 45 seconds"));
    }

    @Test
    public void testStageBoundLeavesTheMarginBeforeTheRegistryDeadline() throws Exception
    {
        Method bound = RenameMetadataObjectTool.class.getDeclaredMethod("previewRenameBoundMs",
            long.class, long.class, long.class);
        bound.setAccessible(true);
        java.lang.reflect.Field marginField = RenameMetadataObjectTool.class.getDeclaredField("PREVIEW_JOB_MARGIN_MS");
        marginField.setAccessible(true);
        long margin = marginField.getLong(null);
        assertTrue("The margin must cover the hand-off's own slack", margin >= 30_000L);
        long timeout = 420_000L;
        long now = 1_000_000_000L;
        // Settle finished on time: the caller's timeout is kept.
        assertEquals(timeout, bound.invoke(null, now + ms(timeout + margin + 10_000L), now, timeout));
        // Settle overran: the stage bound shrinks so it still expires `margin` before the job.
        assertEquals(100_000L, bound.invoke(null, now + ms(100_000L + margin), now, timeout));
        // Budget spent: the smallest bound, which reports a stage-named not-started error.
        assertEquals(1_000L, bound.invoke(null, now + ms(margin - 5_000L), now, timeout));
    }

    @Test
    public void testRussianFormAndKindTokensAttachToTheEnglishPreview() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 4))
        {
            try
            {
                RenameMetadataObjectTool tool = tool(jobs);
                String english = jobId(start(tool, "Catalog.Products.Form.ItemForm.Field.Price", "Cost",
                    blocked(release)));
                String russian = "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.Products."
                    + "\u0424\u043e\u0440\u043c\u0430.ItemForm.\u041f\u043e\u043b\u0435.Price";
                assertEquals(english, jobId(start(tool, russian, "Cost", blocked(release))));
                assertNotEquals(english, jobId(start(tool, "Catalog.Products.Form.ItemForm.Group.Price",
                    "Cost", blocked(release))));
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void testGuideSaysEachTextMatchIsItsOwnIndex()
    {
        String guide = new RenameMetadataObjectTool().getGuide();
        assertTrue(guide.contains("Each whole-word match is its own leaf with its own '#' index"));
        assertFalse(guide.contains("all sharing one '#' index"));
        assertTrue(guide.contains("a confirm applies them unless you skip them with disableIndices or "
            + "`textOccurrences: exclude`"));
    }

    private static long ms(long millis)
    {
        return millis * 1_000_000L;
    }

    private static void startForeign(BackgroundJobs jobs, CountDownLatch release)
    {
        assertNotNull(jobs.start("run_yaxunit_tests", 60_000L, "Accepted a foreign job.", progress -> {
            release.await();
            return "done";
        }));
    }

    private static RenameMetadataObjectTool.IRenameAction blocked(CountDownLatch release)
    {
        return progress -> {
            release.await();
            return PREVIEW;
        };
    }

    private static RenameMetadataObjectTool tool(BackgroundJobs jobs) throws Exception
    {
        Constructor<RenameMetadataObjectTool> ctor = RenameMetadataObjectTool.class.getDeclaredConstructor(
            RenameMetadataObjectTool.CascadeSettler.class, BackgroundJobs.class);
        ctor.setAccessible(true);
        return ctor.newInstance((RenameMetadataObjectTool.CascadeSettler)(project, timeout) -> null, jobs);
    }

    private static String start(RenameMetadataObjectTool tool, String fqn, String name,
        RenameMetadataObjectTool.IRenameAction action) throws Exception
    {
        Method method = RenameMetadataObjectTool.class.getDeclaredMethod("startOrAttachJob",
            String.class, String.class, String.class, boolean.class, int.class, int.class, long.class,
            RenameMetadataObjectTool.IRenameAction.class);
        method.setAccessible(true);
        return (String)method.invoke(tool, "Demo", fqn, name, false, 20, 0, 5_000L, action);
    }

    private static String jobId(String answer)
    {
        Matcher matcher = Pattern.compile("(?m)^\\| jobId \\| ([^|]+) \\|\\s*$").matcher(answer);
        assertTrue("Missing jobId: " + answer, matcher.find());
        return matcher.group(1).trim();
    }
}
