/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils.routemap;

import static org.junit.Assert.*;

import org.junit.Test;

import com._1c.g5.v8.dt.bp.scheme.model.GraphicalScheme;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeItemType;
import com._1c.g5.v8.dt.bp.scheme.model.ModelFactory;
import com.google.gson.JsonObject;

/** Branches describe actual ports, including editor-authored and invalid connections. */
public class RouteMapConditionReadContractTest
{
    private static final ModelFactory FACTORY = ModelFactory.eINSTANCE;

    @Test
    public void testTruePortIsReportedAsTrueUsingTheConditionsConfiguredPort()
    {
        assertEquals("true", edge(scheme(1, 3, 1)).get("branch").getAsString());
    }

    @Test
    public void testFalsePortIsReportedAsFalseUsingTheConditionsConfiguredPort()
    {
        assertEquals("false", edge(scheme(1, 3, 3)).get("branch").getAsString());
    }

    @Test
    public void testUnmatchedPortsHaveNoBranchAndKeepTheirActualPort()
    {
        for (int port : new int[] {2, 4})
        {
            JsonObject edge = edge(scheme(1, 3, port));
            assertFalse(edge.toString(), edge.has("branch"));
            assertEquals(port, edge.get("fromPort").getAsInt());
        }
    }

    @Test
    public void testUnmatchedPortRoundTripRequiresAnExplicitBranchBeforeWriting()
    {
        JsonObject result = RouteMapReader.read(scheme(3, 1, 2), null);
        // get also projects read-only fields; strip the unsupported decorative field for replace.
        result.getAsJsonArray("connections").get(0).getAsJsonObject().remove("decorative");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> RouteMapRequest.parse(result.getAsJsonArray("points").toString(),
                result.getAsJsonArray("connections").toString()));
        assertEquals("Connection 'Check -> Done' requires branch='true' or 'false' for Condition 'Check'. "
            + "Pass branch explicitly.", error.getMessage());
    }

    @Test
    public void testSupportedTrueAndFalsePortsRoundTripToAReplacementPlan()
    {
        for (int port : new int[] {3, 1})
        {
            JsonObject result = RouteMapReader.read(scheme(3, 1, port), null);
            result.getAsJsonArray("connections").get(0).getAsJsonObject().remove("decorative");
            RouteMapRequest request = RouteMapRequest.parse(result.getAsJsonArray("points").toString(),
                result.getAsJsonArray("connections").toString());
            assertEquals(port, request.connections().get(0).fromPort());
            assertEquals(port == 3 ? "true" : "false", request.connections().get(0).branch());
        }
    }

    private static JsonObject edge(GraphicalScheme scheme)
    {
        return RouteMapReader.read(scheme, null).getAsJsonArray("connections").get(0).getAsJsonObject();
    }

    private static GraphicalScheme scheme(int truePort, int falsePort, int sourcePort)
    {
        var scheme = FACTORY.createGraphicalScheme();
        var condition = FACTORY.createGraphicalSchemeCondition();
        condition.setName("Check");
        condition.setItemType(GraphicalSchemeItemType.CONDITION);
        condition.setTruePortIndex(truePort);
        condition.setFalsePortIndex(falsePort);
        var completion = FACTORY.createGraphicalSchemeFigure();
        completion.setName("Done");
        completion.setItemType(GraphicalSchemeItemType.COMPLETION);
        var connection = FACTORY.createGraphicalSchemeConnection();
        connection.setSourceItemName("Check");
        connection.setTargetItemName("Done");
        connection.setSourcePortIndex(sourcePort);
        connection.setTargetPortIndex(2);
        scheme.getItems().add(condition);
        scheme.getItems().add(completion);
        scheme.getItems().add(connection);
        return scheme;
    }
}
