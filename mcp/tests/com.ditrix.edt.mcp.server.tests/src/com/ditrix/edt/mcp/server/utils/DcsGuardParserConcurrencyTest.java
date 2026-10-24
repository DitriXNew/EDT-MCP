/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.google.gson.JsonParser;

/** Ordered interleavings protect the same parser scratch state as the synchronized planners. */
public class DcsGuardParserConcurrencyTest
{
    @Test public void testConcurrentGuardsCannotClearEachOthersParserError() throws Exception { race(false); }
    @Test public void testGuardCannotClearAnActivePlannersParserError() throws Exception { race(true); }

    @Test
    public void testNoNewStaticMutableParserScratchFields()
    {
        assertEquals(Set.of("objectError", "arrayError", "arrayObjectError", "selectedKindError",
            "userFieldKindError", "filterKindError", "orderKindError", "stringError", "booleanError",
            "indexError", "primitiveStringError"), mutableFields(DcsSettingsWriter.class));
        assertEquals(Set.of(), mutableFields(DcsWriter.class));
        assertEquals(Set.of(), mutableFields(DcsPresentationParser.class));
    }

    private static Set<String> mutableFields(Class<?> type)
    {
        return Arrays.stream(type.getDeclaredFields())
            .filter(field -> !field.isSynthetic())
            .filter(field -> Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()))
            .map(field -> field.getName()).collect(Collectors.toSet());
    }

    private static void race(boolean planner) throws Exception
    {
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondFinished = new CountDownLatch(1);
        var before = new AtomicReference<String>();
        var after = new AtomicReference<String>();
        var scratch = DcsSettingsWriter.class.getDeclaredField("primitiveStringError");
        scratch.setAccessible(true);
        var validValue = McoreFactory.eINSTANCE.createStringValue();
        validValue.setValue("valid");
        var secondSchema = DcsStoredXmlTestSupport.operand(true, validValue);
        var firstSettings = DcsFactory.eINSTANCE.createDataCompositionSettings();
        firstSettings.setOutputParameters(DcsFactory.eINSTANCE.createDataCompositionOutputParameterValues());
        var item = DcsFactory.eINSTANCE.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("Known"));
        var text = McoreFactory.eINSTANCE.createStringValue();
        text.setValue("text");
        item.getValues().add(text);
        firstSettings.getOutputParameters().getItems().add(item);
        Runnable pauseWithParserError = () -> {
            try
            {
                var bad = DcsSettingsWriter.buildValue(JsonParser.parseString("{kind:'field',value:''}"), "first");
                assertNotNull(bad.error);
                before.set((String)scratch.get(null));
                assertNotNull(before.get());
                ready.countDown();
                assertTrue("release ordered parser operation", release.await(10, TimeUnit.SECONDS));
                after.set((String)scratch.get(null));
            }
            catch (ReflectiveOperationException | InterruptedException e)
            {
                throw new AssertionError(e);
            }
        };
        var context = new DcsWriterCatalogues(DcsStoredXmlTestSupport.VERSION, AppearanceCatalogue.SCHEMA, "en",
            (kind, version, language) -> {
                pauseWithParserError.run();
                return DcsReplaceTestCatalogues.available("Known", "Alias", McoreFactory.eINSTANCE.createStringValue());
            });
        var firstTask = new FutureTask<String>(() -> {
            if (!planner)
                return DcsMutationGuard.replaceError(firstSettings, DcsAddress.parse("Report.First").address(), context);
            // Static synchronized planner entries own this exact monitor, including nested calls.
            synchronized (DcsSettingsWriter.class)
            {
                var planned = DcsSettingsWriter.planSettings(null, List.of(), "replace", "selection",
                    JsonParser.parseString("{items:[{field:{kind:'field',value:''}}]}").getAsJsonObject(), null);
                assertFalse(planned.isSuccess());
                pauseWithParserError.run();
                return null;
            }
        });
        var secondTask = new FutureTask<String>(() -> {
            secondStarted.countDown();
            try { return DcsStoredXmlTestSupport.guard(secondSchema); }
            finally { secondFinished.countDown(); }
        });
        var first = new Thread(firstTask, "dcs-first-parser");
        var second = new Thread(secondTask, "dcs-second-guard");
        first.setDaemon(true);
        second.setDaemon(true);
        try
        {
            first.start();
            assertTrue("first operation reached its parser error", ready.await(10, TimeUnit.SECONDS));
            second.start();
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            awaitBlockedOrFinished(second, secondFinished);
        }
        finally
        {
            release.countDown();
            first.join(10000);
            if (second.getState() != Thread.State.NEW) second.join(10000);
        }
        assertNull(firstTask.get(1, TimeUnit.SECONDS));
        assertNull(secondTask.get(1, TimeUnit.SECONDS));
        assertEquals("the competing guard must not clear the active parser's error", before.get(), after.get());
    }

    private static void awaitBlockedOrFinished(Thread second, CountDownLatch finished)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var threads = ManagementFactory.getThreadMXBean();
        while (System.nanoTime() < deadline)
        {
            if (finished.getCount() == 0) return;
            var info = threads.getThreadInfo(second.getId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(DcsSettingsWriter.class)) return;
            Thread.yield();
        }
        fail("second guard must finish or wait on the planner's exact monitor");
    }
}
