/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.reference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.BasicEList;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmEngine;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.EventSubscription;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com.ditrix.edt.mcp.server.tools.reference.MetadataReferenceService.ReferenceCollector;

/** String-valued handlers do not appear in BM's back-reference index (#998). */
public class EventSubscriptionReferencesTest
{
    private static final String FQN = "CommonModule.MyEventHandlers"; //$NON-NLS-1$

    @Test
    public void handlerAppearsInReportWithoutAnIndexedBackReference()
    {
        EventSubscription handler = subscription("MyBeforeWrite", FQN + ".BeforeWriteDocument"); //$NON-NLS-1$ //$NON-NLS-2$
        EventSubscription sameNamePrefix = subscription("PrefixOnly", "CommonModule.MyEventHandlers2.Run"); //$NON-NLS-1$ //$NON-NLS-2$
        EventSubscription wrongType = subscription("WrongType", "Document.MyEventHandlers.Run"); //$NON-NLS-1$ //$NON-NLS-2$
        EventSubscription broken = subscription("MissingMethod", FQN + "."); //$NON-NLS-1$ //$NON-NLS-2$
        EventSubscription bilingual = subscription("Localized", "ОбщийМодуль.myeventhandlers.Run"); //$NON-NLS-1$ //$NON-NLS-2$
        ReferenceCollector collector = run(ReferenceSearchBound.of(10, 1000, () -> 0L),
            handler, sameNamePrefix, wrongType, broken, bilingual);

        assertEquals(2, collector.getTotalCount());
        assertSame(handler, collector.getAllReferences().get(0).sourceObject);
        String report = MetadataReferenceService.formatOutput(FQN, collector);
        assertTrue(report, report.contains("- EventSubscription.MyBeforeWrite - Handler\n")); //$NON-NLS-1$
        assertTrue(report, report.contains("- EventSubscription.Localized - Handler\n")); //$NON-NLS-1$
        assertFalse(report, report.contains("PrefixOnly")); //$NON-NLS-1$
        assertFalse(report, report.contains("WrongType")); //$NON-NLS-1$
        assertFalse(report, report.contains("MissingMethod")); //$NON-NLS-1$
    }

    @Test
    public void limitStopsHandlerTraversalAndReportsThatMoreReferencesExist()
    {
        EventSubscription first = subscription("First", FQN + ".Run"); //$NON-NLS-1$ //$NON-NLS-2$
        EventSubscription second = subscription("Second", FQN + ".Run"); //$NON-NLS-1$ //$NON-NLS-2$
        EventSubscription unseen = subscription("Unseen", FQN + ".Run"); //$NON-NLS-1$ //$NON-NLS-2$
        ReferenceCollector collector = run(ReferenceSearchBound.of(1, 1000, () -> 0L), first, second, unseen);

        verify(unseen, never()).getHandler();
        String report = MetadataReferenceService.formatOutput(FQN, collector);
        assertTrue(report, report.contains("more than 1 reference(s) exist")); //$NON-NLS-1$
        assertTrue(report, report.contains("EventSubscription.First")); //$NON-NLS-1$
        assertFalse(report, report.contains("EventSubscription.Second")); //$NON-NLS-1$
    }

    @Test
    public void noMatchStillPaysTimeBudgetAndCannotTurnPartialScanIntoZero()
    {
        AtomicLong nanos = new AtomicLong();
        EventSubscription first = subscription("First", "AnotherModule.Run"); //$NON-NLS-1$ //$NON-NLS-2$
        when(first.getHandler()).thenAnswer(call -> {
            nanos.set(TimeUnit.MILLISECONDS.toNanos(2));
            return "AnotherModule.Run"; //$NON-NLS-1$
        });
        EventSubscription unseen = subscription("Unseen", FQN + ".Run"); //$NON-NLS-1$ //$NON-NLS-2$
        ReferenceSearchBound bound = ReferenceSearchBound.of(10, 1, nanos::get);
        ReferenceCollector collector = run(bound, first, unseen);

        verify(unseen, never()).getHandler();
        assertEquals(ReferenceSearchBound.StopReason.TIME_BUDGET, bound.stopReason());
        assertTrue(MetadataReferenceService.formatOutput(FQN, collector).contains("Search stopped early")); //$NON-NLS-1$
    }

    private static ReferenceCollector run(ReferenceSearchBound bound, EventSubscription... subscriptions)
    {
        Configuration config = mock(Configuration.class);
        when(config.getEventSubscriptions()).thenReturn(new BasicEList<>(List.of(subscriptions)));
        IBmModel model = mock(IBmModel.class);
        IBmEngine engine = mock(IBmEngine.class);
        CommonModule module = mock(CommonModule.class, withSettings().extraInterfaces(IBmObject.class));
        when(module.getName()).thenReturn("MyEventHandlers"); //$NON-NLS-1$
        when(module.eClass()).thenReturn(MdClassPackage.Literals.COMMON_MODULE);
        when(model.getEngine()).thenReturn(engine);
        when(engine.getBackReferences((IBmObject)module)).thenReturn(List.of());

        ReferenceCollector collector = new ReferenceCollector(mock(IProject.class), model, (IBmObject)module,
            bound.limit(), bound, config);
        collector.collect(() -> null, project -> null);
        return collector;
    }

    private static EventSubscription subscription(String name, String value)
    {
        // Mockito's OSGi class loader cannot mock private nested interfaces. Only use public EDT types.
        EventSubscription subscription = mock(EventSubscription.class, withSettings().extraInterfaces(IBmObject.class));
        when(subscription.getName()).thenReturn(name);
        when(subscription.getHandler()).thenReturn(value);
        when(subscription.eClass()).thenReturn(MdClassPackage.Literals.EVENT_SUBSCRIPTION);
        return subscription;
    }
}
