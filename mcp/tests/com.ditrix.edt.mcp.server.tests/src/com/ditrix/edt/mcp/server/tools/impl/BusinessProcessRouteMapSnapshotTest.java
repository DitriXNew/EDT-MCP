/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.bp.scheme.model.GraphicalScheme;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeItemType;
import com._1c.g5.v8.dt.bp.scheme.model.ModelFactory;
import com.ditrix.edt.mcp.server.tools.impl.BusinessProcessRouteMapTool.MapPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.routemap.RouteMapRequest;
import com.google.gson.JsonParser;

/** Tests the mapper used by production on its detached read copy, then the consent decision. */
public class BusinessProcessRouteMapSnapshotTest
{
    @Test
    public void testAbsentAndEmptySnapshotsRemainDistinctAndDoNotGateReplacement()
    {
        var tool = new BusinessProcessRouteMapTool((name, preview) -> {
            throw new AssertionError("An absent/empty map cannot ask replacement consent");
        });
        MapPreview absent = BusinessProcessRouteMapTool.mapPreview(null);
        MapPreview empty = BusinessProcessRouteMapTool.mapPreview(EcoreUtil.copy(scheme(0, 0)));
        assertEquals(new MapPreview(false, 0, 0, 0, List.of()), absent);
        assertEquals(new MapPreview(true, 0, 0, 0, List.of()), empty);
        for (MapPreview preview : List.of(absent, empty))
        {
            assertEquals("written", tool.consentAndWrite("replace", "BusinessProcess.Process", preview, () -> "written"));
        }
    }

    @Test
    public void testAbsentSnapshotRefusesRemovalBeforeConsent()
    {
        var tool = new BusinessProcessRouteMapTool((name, preview) -> {
            throw new AssertionError("An absent map cannot ask removal consent");
        });
        var result = JsonParser.parseString(tool.consentAndWrite("remove", "BusinessProcess.Process",
            BusinessProcessRouteMapTool.mapPreview(null), () -> {
                throw new AssertionError("An absent map cannot be removed");
            })).getAsJsonObject();
        assertEquals("No route map exists on 'BusinessProcess.Process'. Use action='replace' to create one.",
            result.get("error").getAsString());
    }

    @Test
    public void testConnectionOnlySnapshotMapsEveryItemAndDenialPreventsWriting()
    {
        MapPreview previous = BusinessProcessRouteMapTool.mapPreview(EcoreUtil.copy(scheme(0, 2)));
        assertEquals(new MapPreview(true, 2, 0, 2, List.of()), previous);
        List<String> events = new ArrayList<>();
        var tool = new BusinessProcessRouteMapTool((name, preview) -> {
            events.add("consent");
            assertEquals("0 points and 2 connections are discarded; infobase tasks keep their point identity "
                + "only for same-named points", preview.getSubtitle());
            return ConsentDecision.REJECT;
        });
        var result = JsonParser.parseString(tool.consentAndWrite("replace", "BusinessProcess.Process", previous,
            () -> { throw new AssertionError("Denied consent must not write a connection-only map"); }))
            .getAsJsonObject();
        assertEquals(List.of("consent"), events);
        assertFalse(result.get("success").getAsBoolean());
    }

    @Test
    public void testPointSnapshotMapsCountsAndImmutableNamesAndRequestsConsent()
    {
        GraphicalScheme original = scheme(2, 1);
        GraphicalScheme readCopy = EcoreUtil.copy(original);
        original.getItems().clear();
        MapPreview previous = BusinessProcessRouteMapTool.mapPreview(readCopy);
        assertEquals(new MapPreview(true, 3, 2, 1, List.of("Point0", "Point1")), previous);
        assertThrows(UnsupportedOperationException.class, () -> previous.names().clear());
        List<String> events = new ArrayList<>();
        var tool = new BusinessProcessRouteMapTool((name, preview) -> {
            events.add("consent");
            assertEquals(2, preview.getTotalCount());
            assertEquals(List.of("Point0", "Point1"), preview.getTopNames());
            return ConsentDecision.ALLOW;
        });
        assertEquals("written", tool.consentAndWrite("replace", "BusinessProcess.Process", previous,
            () -> { events.add("write"); return "written"; }));
        assertEquals(List.of("consent", "write"), events);
    }

    @Test
    public void testAnExistingEmptySnapshotStillGatesRemoval()
    {
        List<String> events = new ArrayList<>();
        var tool = new BusinessProcessRouteMapTool((name, preview) -> {
            events.add("consent");
            return ConsentDecision.ALLOW;
        });
        assertEquals("removed", tool.consentAndWrite("remove", "BusinessProcess.Process",
            BusinessProcessRouteMapTool.mapPreview(EcoreUtil.copy(scheme(0, 0))),
            () -> { events.add("write"); return "removed"; }));
        assertEquals(List.of("consent", "write"), events);
    }

    @Test
    public void testRemoveReportsTheDiscardedCountsAndReplacementReportsTheWrittenCounts()
    {
        MapPreview previous = BusinessProcessRouteMapTool.mapPreview(EcoreUtil.copy(scheme(2, 3)));
        var removed = JsonParser.parseString(BusinessProcessRouteMapTool.writeResult("remove", previous, null)
            .toJson()).getAsJsonObject();
        assertEquals(2, removed.get("points").getAsInt());
        assertEquals(3, removed.get("connections").getAsInt());
        assertFalse(removed.get("routeMapExists").getAsBoolean());
        RouteMapRequest request = RouteMapRequest.parse("[{\"name\":\"Begin\",\"type\":\"Start\"}]", "[]");
        var replaced = JsonParser.parseString(BusinessProcessRouteMapTool.writeResult("replace", previous, request)
            .toJson()).getAsJsonObject();
        assertEquals(1, replaced.get("points").getAsInt());
        assertEquals(0, replaced.get("connections").getAsInt());
        assertTrue(replaced.get("routeMapExists").getAsBoolean());
    }

    private static GraphicalScheme scheme(int points, int connections)
    {
        var factory = ModelFactory.eINSTANCE;
        var scheme = factory.createGraphicalScheme();
        for (int i = 0; i < points; i++)
        {
            var point = factory.createGraphicalSchemeFigure();
            point.setName("Point" + i);
            point.setItemType(GraphicalSchemeItemType.ACTIVITY);
            scheme.getItems().add(point);
        }
        for (int i = 0; i < connections; i++)
        {
            var connection = factory.createGraphicalSchemeConnection();
            connection.setName("Connection" + i);
            scheme.getItems().add(connection);
        }
        return scheme;
    }
}
