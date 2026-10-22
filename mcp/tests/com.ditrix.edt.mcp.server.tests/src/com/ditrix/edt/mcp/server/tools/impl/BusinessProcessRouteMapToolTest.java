/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalScheme;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.BusinessProcessRouteMapTool.MapPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.BuildUtils.ValidationState;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers;
import com.google.gson.JsonParser;

public class BusinessProcessRouteMapToolTest
{
    @Test
    public void testActionSetAndSchemaParametersMatchExecute()
    {
        var tool = new BusinessProcessRouteMapTool();
        assertEquals("business_process_route_map", tool.getName());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertEquals(Boolean.TRUE, tool.getAnnotations().getDestructiveHint());
        assertEquals(Boolean.FALSE, tool.getAnnotations().getReadOnlyHint());
        assertTrue(tool.getDescription().contains("get_tool_guide('business_process_route_map')"));
        var schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject();
        assertEquals(Set.of("projectName", "fqn", "action", "points", "connections", "language"),
            schema.getAsJsonObject("properties").keySet());
        assertEquals(JsonParser.parseString("[\"projectName\",\"fqn\",\"action\"]"), schema.get("required"));
        assertEquals(JsonParser.parseString("[\"get\",\"replace\",\"remove\"]"),
            schema.getAsJsonObject("properties").getAsJsonObject("action").get("enum"));
        var output = JsonParser.parseString(tool.getOutputSchema()).getAsJsonObject().getAsJsonObject("properties");
        for (String key : List.of("routeMapExists", "routeMapFqn", "file", "points", "connections", "defaults",
            "persisted", "writtenProjects", "markersIncomplete", "markersIncompleteReason", "markerCount", "markers"))
        {
            assertTrue(key, output.has(key));
        }
    }

    @Test
    public void testANonBusinessProcessFqnIsRefusedNamingTheActualType()
    {
        String message = assertThrows(IllegalArgumentException.class,
            () -> BusinessProcessRouteMapTool.requireBusinessProcess(MdClassFactory.eINSTANCE.createCatalog(),
                true, "Catalog.Products")).getMessage();
        assertEquals("'Catalog.Products' is Catalog, not a top-level BusinessProcess. Pass BusinessProcess.Name.", message);
        assertThrows(IllegalArgumentException.class,
            () -> BusinessProcessRouteMapTool.requireBusinessProcess(null, false, "BusinessProcess.Missing"));
        assertThrows(IllegalArgumentException.class, () -> BusinessProcessRouteMapTool.requireBusinessProcess(
            MdClassFactory.eINSTANCE.createBusinessProcess(), false, "BusinessProcess.Nested"));
    }

    @Test
    public void testReplaceOverANonEmptyMapAsksConsentAndADeniedDecisionWritesNothing()
    {
        List<String> events = new ArrayList<>();
        var tool = new BusinessProcessRouteMapTool((name, preview) -> {
            events.add("consent");
            assertEquals("business_process_route_map", name);
            assertEquals("Replace route map of BusinessProcess.Process", preview.getTitle());
            assertEquals("2 points and 1 connections are discarded; infobase tasks keep their point identity "
                + "only for same-named points", preview.getSubtitle());
            assertEquals(2, preview.getTotalCount());
            assertEquals(List.of("Begin", "Done"), preview.getTopNames());
            return ConsentDecision.REJECT;
        });
        String result = tool.consentAndWrite("replace", "BusinessProcess.Process",
            new MapPreview(true, 3, 2, 1, List.of("Begin", "Done")),
            () -> { events.add("write"); return "{}"; });
        assertEquals(List.of("consent"), events);
        assertFalse(JsonParser.parseString(result).getAsJsonObject().get("success").getAsBoolean());
        assertEquals("Operation declined by user",
            JsonParser.parseString(result).getAsJsonObject().get("error").getAsString());
    }

    @Test
    public void testReplaceOverAnAbsentOrEmptyMapNeedsNoConsent()
    {
        var tool = new BusinessProcessRouteMapTool((name, preview) -> {
            throw new AssertionError("An absent/empty map must not request consent");
        });
        for (boolean exists : new boolean[] {false, true})
        {
            assertEquals("written", tool.consentAndWrite("replace", "BusinessProcess.Process",
                new MapPreview(exists, 0, 0, 0, List.of()), () -> "written"));
        }
    }

    @Test
    public void testAConnectionOnlyMapStillNeedsConsentAndAllowedWritesFollowTheGate()
    {
        List<String> events = new ArrayList<>();
        var tool = new BusinessProcessRouteMapTool((name, preview) -> {
            events.add("consent");
            return ConsentDecision.ALLOW;
        });
        assertEquals("written", tool.consentAndWrite("replace", "BusinessProcess.Process",
            new MapPreview(true, 1, 0, 1, List.of()), () -> { events.add("write"); return "written"; }));
        assertEquals(List.of("consent", "write"), events);
    }

    @Test
    public void testRemoveWithoutARouteMapIsAnErrorAndExistingRemoveAlwaysAsksConsent()
    {
        List<String> events = new ArrayList<>();
        var tool = new BusinessProcessRouteMapTool((name, preview) -> {
            events.add("consent");
            assertEquals("Remove route map of BusinessProcess.Process", preview.getTitle());
            return ConsentDecision.ALLOW;
        });
        String absent = tool.consentAndWrite("remove", "BusinessProcess.Process", new MapPreview(false, 0, 0, 0, List.of()),
            () -> { throw new AssertionError("Absent map cannot be removed"); });
        assertEquals("No route map exists on 'BusinessProcess.Process'. Use action='replace' to create one.",
            JsonParser.parseString(absent).getAsJsonObject().get("error").getAsString());
        assertTrue(events.isEmpty());
        events.clear();
        assertEquals("removed", tool.consentAndWrite("remove", "BusinessProcess.Process",
            new MapPreview(true, 0, 0, 0, List.of()), () -> { events.add("write"); return "removed"; }));
        assertEquals(List.of("consent", "write"), events);
    }

    @Test
    public void testAnAdoptedBusinessProcessIsRefusedButAnOwnedOneIsAllowed()
    {
        var owner = MdClassFactory.eINSTANCE.createBusinessProcess();
        owner.setObjectBelonging(ObjectBelonging.ADOPTED);
        assertEquals("Route maps of adopted BusinessProcess objects are deferred to a follow-up. "
            + "Use the EDT route-map editor; an extension-owned BusinessProcess is supported.",
            assertThrows(IllegalArgumentException.class,
                () -> BusinessProcessRouteMapTool.requireSupportedOwner(owner)).getMessage());
        owner.setObjectBelonging(ObjectBelonging.NATIVE);
        BusinessProcessRouteMapTool.requireSupportedOwner(owner);
    }

    @Test
    public void testEveryStoredItemRequiresConsentEvenWhenItHasNoPointProjection()
    {
        List<String> events = new ArrayList<>();
        var tool = new BusinessProcessRouteMapTool((name, preview) -> {
            events.add("consent");
            return ConsentDecision.REJECT;
        });
        String result = tool.consentAndWrite("replace", "BusinessProcess.Process",
            new MapPreview(true, 1, 0, 0, List.of()), () -> {
                throw new AssertionError("Unprojected items are still existing content");
            });
        assertEquals(List.of("consent"), events);
        assertFalse(JsonParser.parseString(result).getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    public void testTheInferredFlowchartProxyIsNeverUsedToDecideExistence()
    {
        var tx = mock(IBmTransaction.class);
        var owner = mock(BusinessProcess.class);
        when(owner.getFlowchart()).thenReturn(mock(GraphicalScheme.class));
        var generator = mock(ITopObjectFqnGenerator.class);
        when(generator.generateExternalPropertyFqn(owner, MdClassPackage.Literals.BUSINESS_PROCESS__FLOWCHART))
            .thenReturn("BusinessProcess.Process.Flowchart");
        var content = BusinessProcessRouteMapTool.readContent(tx, owner, generator);
        assertEquals("BusinessProcess.Process.Flowchart", content.fqn());
        assertNull(content.scheme());
        verify(tx).getTopObjectByFqn("BusinessProcess.Process.Flowchart");
        verify(owner, never()).getFlowchart();
    }

    @Test
    public void testInvalidRequestsAreRefusedBeforeUiOrProjectAccess()
    {
        var tool = new BusinessProcessRouteMapTool();
        for (Map<String, String> params : List.of(Map.<String, String>of(),
            Map.of("projectName", "TestConfiguration", "fqn", "BusinessProcess.Process", "action", "bad"),
            Map.of("projectName", "TestConfiguration", "fqn", "BusinessProcess.Process", "action", "replace"),
            Map.of("projectName", "TestConfiguration", "fqn", "BusinessProcess.Process", "action", "get", "points", "[]")))
        {
            var result = JsonParser.parseString(tool.execute(params)).getAsJsonObject();
            assertFalse(result.get("success").getAsBoolean());
            assertTrue(result.has("error"));
        }
    }

    @Test
    public void testAnEmptySchemeMarkerSliceCannotClaimZeroMarkers()
    {
        var result = JsonParser.parseString("{\"routeMapFqn\":\"BP.Flowchart\",\"markerCount\":0,"
            + "\"markersIncomplete\":false,\"markers\":[]}").getAsJsonObject();
        BusinessProcessRouteMapTool.markEmptySchemeSlice(result);
        assertTrue(result.get("markersIncomplete").getAsBoolean());
        assertEquals("validationUnobservable", result.get("markersIncompleteReason").getAsString());
        assertFalse(result.has("markerCount"));
        assertEquals("No markers were observed for the route-map FQN; validation is incomplete. "
            + "Re-check with get_project_errors or the EDT route-map editor.", result.get("message").getAsString());
    }

    @Test
    public void testRemovalUsesTheGlobalCommitThatSchedulesExternalFileDeletion()
    {
        var model = mock(IBmModel.class, RETURNS_DEEP_STUBS);
        BusinessProcessRouteMapTool.applyMutation(model, "remove", (tx, monitor) -> null);
        verify(model.getGlobalContext()).execute(any());
        verify(model, never()).execute(any());
        var replacement = mock(IBmModel.class);
        BusinessProcessRouteMapTool.applyMutation(replacement, "replace", (tx, monitor) -> null);
        verify(replacement).execute(any());
        verify(replacement, never()).getGlobalContext();
    }

    @Test
    public void testTheMarkerReadIncludesBothOwnerAndExternalFqnAndEmptyDoesNotMeanClean()
    {
        var environment = mock(WrittenObjectMarkers.Environment.class);
        when(environment.resolveTopObjects(eq("TestConfiguration"), anyList(), anyLong()))
            .thenReturn(Map.of("BP", 1L, "BP.Flowchart", 2L));
        when(environment.awaitValidation(eq("TestConfiguration"), anyCollection(), anyLong()))
            .thenReturn(ValidationState.COMPLETE);
        when(environment.read(eq("TestConfiguration"), anyMap(), anyInt(), anyLong()))
            .thenReturn(new WrittenObjectMarkers.ProjectMarkers(0, List.of()));
        var tool = new BusinessProcessRouteMapTool()
        {
            @Override
            protected WrittenObjectMarkers.Environment markerEnvironment()
            {
                return environment;
            }
        };
        var result = JsonParser.parseString(tool.refreshAfterExportAwait(Map.of("projectName", "TestConfiguration"),
            "{\"success\":true,\"persisted\":true,\"fqn\":\"BP\",\"routeMapFqn\":\"BP.Flowchart\"}", true))
            .getAsJsonObject();
        verify(environment).resolveTopObjects(eq("TestConfiguration"), eq(List.of("BP", "BP.Flowchart")), anyLong());
        assertTrue(result.get("markersIncomplete").getAsBoolean());
        assertFalse(result.has("markerCount"));
    }

    @Test
    public void testOwnerMarkersAreNotMistakenForSchemeMarkersAndEarlierReasonsSurvive()
    {
        var result = JsonParser.parseString("{\"routeMapFqn\":\"BP.Flowchart\",\"markerCount\":1,"
            + "\"markersIncomplete\":true,\"markersIncompleteReason\":\"checksDisabled\","
            + "\"markers\":[{\"object\":\"BP\"}]}").getAsJsonObject();
        BusinessProcessRouteMapTool.markEmptySchemeSlice(result);
        assertTrue(result.get("markersIncomplete").getAsBoolean());
        assertEquals("checksDisabled", result.get("markersIncompleteReason").getAsString());
        assertEquals(1, result.get("markerCount").getAsInt());
        result.getAsJsonArray("markers").get(0).getAsJsonObject().addProperty("object", "BP.Flowchart");
        result.addProperty("markersIncomplete", false);
        result.remove("markersIncompleteReason");
        BusinessProcessRouteMapTool.markEmptySchemeSlice(result);
        assertFalse(result.get("markersIncomplete").getAsBoolean());
        assertFalse(result.has("markersIncompleteReason"));
    }
}
