/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.bp.scheme.model.GraphicalScheme;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeItemType;
import com._1c.g5.v8.dt.bp.scheme.model.ModelFactory;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.google.gson.JsonParser;

/** Exercises the real snapshot mapper before consent, including editor-only item kinds. */
public class BusinessProcessRouteMapConsentItemsTest
{
    private static final ModelFactory FACTORY = ModelFactory.eINSTANCE;

    @Test
    public void testConnectionOnlyMapsAskConsentWithEveryConnectionInTheTotal()
    {
        GraphicalScheme scheme = FACTORY.createGraphicalScheme();
        for (int i = 0; i < 3; i++)
        {
            var connection = FACTORY.createGraphicalSchemeConnection();
            connection.setItemType(GraphicalSchemeItemType.CONNECTION_LINE);
            scheme.getItems().add(connection);
        }
        checkBothActions(scheme, preview -> {
            assertEquals(3, preview.getTotalCount());
            assertTrue(preview.getSubtitle(), preview.getSubtitle().contains("3 connections"));
        });
    }

    @Test
    public void testDecorationOnlyMapsAskConsentAndNameTheDiscardedKind()
    {
        GraphicalScheme scheme = FACTORY.createGraphicalScheme();
        var decoration = FACTORY.createGraphicalSchemeDecoration();
        decoration.setItemType(GraphicalSchemeItemType.DECORATION);
        scheme.getItems().add(decoration);
        checkBothActions(scheme, preview -> {
            assertEquals(1, preview.getTotalCount());
            assertTrue(preview.getSubtitle(), preview.getSubtitle().toLowerCase(Locale.ROOT).contains("1 decoration"));
        });
    }

    @Test
    public void testUnprojectedItemsAreCountedAndNamedByTheirStoredKind()
    {
        GraphicalScheme scheme = FACTORY.createGraphicalScheme();
        for (int i = 0; i < 2; i++)
        {
            var item = FACTORY.createGraphicalSchemeItem();
            item.setItemType(GraphicalSchemeItemType.PROCESSING);
            scheme.getItems().add(item);
        }
        checkBothActions(scheme, preview -> {
            assertEquals(2, preview.getTotalCount());
            assertTrue(preview.getSubtitle(), preview.getSubtitle().toLowerCase(Locale.ROOT).contains("2 processing"));
        });
    }

    @Test
    public void testMixedMapsDescribeAllKindsAndKeepPointNames()
    {
        GraphicalScheme scheme = FACTORY.createGraphicalScheme();
        for (String name : List.of("Begin", "Done"))
        {
            var point = FACTORY.createGraphicalSchemeFigure();
            point.setName(name);
            point.setItemType(GraphicalSchemeItemType.START);
            scheme.getItems().add(point);
        }
        for (int i = 0; i < 3; i++)
        {
            scheme.getItems().add(FACTORY.createGraphicalSchemeConnection());
        }
        var decoration = FACTORY.createGraphicalSchemeDecoration();
        decoration.setItemType(GraphicalSchemeItemType.DECORATION);
        scheme.getItems().add(decoration);
        checkBothActions(scheme, preview -> {
            assertEquals(6, preview.getTotalCount());
            assertEquals(List.of("Begin", "Done"), preview.getTopNames());
            String subtitle = preview.getSubtitle().toLowerCase(Locale.ROOT);
            assertTrue(subtitle, subtitle.contains("3 connections"));
            assertTrue(subtitle, subtitle.contains("1 decoration"));
        });
    }

    private static void checkBothActions(GraphicalScheme scheme,
        java.util.function.Consumer<ConsentPreview> assertions)
    {
        for (String action : List.of("replace", "remove"))
        {
            AtomicInteger decisions = new AtomicInteger();
            var tool = new BusinessProcessRouteMapTool((name, preview) -> {
                decisions.incrementAndGet();
                assertEquals(BusinessProcessRouteMapTool.NAME, name);
                assertions.accept(preview);
                return ConsentDecision.REJECT;
            });
            var previous = BusinessProcessRouteMapTool.mapPreview(EcoreUtil.copy(scheme));
            String result = tool.consentAndWrite(action, "BusinessProcess.Process", previous,
                () -> { throw new AssertionError("Denied consent cannot discard any items"); });
            assertEquals(action + " must request consent", 1, decisions.get());
            assertFalse(JsonParser.parseString(result).getAsJsonObject().get("success").getAsBoolean());
        }
    }
}
