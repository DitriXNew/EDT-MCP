/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.ParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.google.gson.JsonParser;

/** Exact blank output items do not need the platform's typed catalogue. */
public class DcsBlankOutputCatalogueGuardTest
{
    @Test
    public void testBlankItemsInAllSixOutputHoldersSkipAFailingCatalogue()
    {
        for (ParameterValues holder : holders())
        {
            holder.getItems().add(DcsFactory.eINSTANCE.createSettingsParameterValue());
            var calls = new AtomicInteger();
            var context = failing(calls);
            var snapshot = EcoreUtil.copy(holder);
            String refusal = guard(holder, context);
            assertNull(holder.eClass().getName() + ": " + refusal, refusal);
            assertEquals("no catalogue is needed for a blank item", 0, calls.get());
            assertTrue(EcoreUtil.equals(snapshot, holder));
        }
    }

    @Test
    public void testNonBlankOutputStillRefusesTheSameCatalogueFailure()
    {
        for (ParameterValues holder : holders())
        {
            var item = DcsFactory.eINSTANCE.createSettingsParameterValue();
            item.setParameter(DcsReplaceTestCatalogues.parameter("Known"));
            holder.getItems().add(item);
            var calls = new AtomicInteger();
            var snapshot = EcoreUtil.copy(holder);
            String refusal = guard(holder, failing(calls));
            assertNotNull(refusal);
            assertTrue(refusal, refusal.contains("catalogue temporarily unavailable"));
            assertEquals(1, calls.get());
            assertTrue(EcoreUtil.equals(snapshot, holder));
        }
    }

    @Test
    public void testAppearanceHasNoBlankItemShortcut()
    {
        var appearance = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionAppearance();
        appearance.getItems().add(DcsFactory.eINSTANCE.createSettingsParameterValue());
        var calls = new AtomicInteger();
        String refusal = guard(appearance, failing(calls));
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("catalogue temporarily unavailable"));
        assertEquals(1, calls.get());
    }

    @Test
    public void testExactBlankReplacementAlsoPassesTheRealPlannerWithoutACatalogue()
    {
        var settings = DcsFactory.eINSTANCE.createDataCompositionSettings();
        settings.setOutputParameters(DcsFactory.eINSTANCE.createDataCompositionOutputParameterValues());
        settings.getOutputParameters().getItems().add(DcsFactory.eINSTANCE.createSettingsParameterValue());
        var calls = new AtomicInteger();
        assertNull(guard(settings, failing(calls)));
        assertEquals(0, calls.get());
        var plan = DcsSettingsWriter.planSettings(settings, List.of("outputParameters", "items", "0"),
            "replace", "outputParameter", JsonParser.parseString("{}").getAsJsonObject(), null,
            DcsStoredXmlTestSupport.VERSION);
        assertTrue(plan.error(), plan.isSuccess());
        var blank = plan.settings().getOutputParameters().getItems().get(0);
        assertNull(blank.getParameter());
        assertTrue(blank.getValues().isEmpty());
    }

    @Test
    public void testValuesAndNestedItemsAreNotBlank()
    {
        for (boolean nested : new boolean[] {false, true})
        {
            var holder = DcsFactory.eINSTANCE.createDataCompositionOutputParameterValues();
            var item = DcsFactory.eINSTANCE.createSettingsParameterValue();
            if (nested) item.getNestedParameterValues().add(DcsFactory.eINSTANCE.createSettingsParameterValue());
            else item.getValues().add(McoreFactory.eINSTANCE.createStringValue());
            holder.getItems().add(item);
            assertNotNull(guard(holder, failing(new AtomicInteger())));
        }
    }

    private static List<ParameterValues> holders()
    {
        return List.of(DcsFactory.eINSTANCE.createDataCompositionOutputParameterValues(),
            DcsFactory.eINSTANCE.createDataCompositionGroupOutputParameterValues(),
            DcsFactory.eINSTANCE.createDataCompositionTableOutputParameterValues(),
            DcsFactory.eINSTANCE.createDataCompositionTableGroupOutputParameterValues(),
            DcsFactory.eINSTANCE.createDataCompositionChartOutputParameterValues(),
            DcsFactory.eINSTANCE.createDataCompositionChartGroupOutputParameterValues());
    }

    private static DcsWriterCatalogues failing(AtomicInteger calls)
    {
        return new DcsWriterCatalogues(DcsStoredXmlTestSupport.VERSION, AppearanceCatalogue.SCHEMA, "en",
            (kind, version, language) -> {
                calls.incrementAndGet();
                throw new IllegalStateException("catalogue temporarily unavailable");
            });
    }

    private static String guard(EObject root, DcsWriterCatalogues context)
    {
        return DcsMutationGuard.replaceError(root, DcsAddress.parse("Report.Sales").address(), context);
    }
}
