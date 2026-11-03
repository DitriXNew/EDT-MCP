/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils.routemap;

import static org.junit.Assert.*;

import org.junit.Test;

public class RouteMapRequestTest
{
    private static final String POINTS = "[{\"name\":\"Check\",\"type\":\"Condition\"},"
        + "{\"name\":\"Done\",\"type\":\"Completion\"}]";

    @Test
    public void testAConditionConnectionWithoutBranchIsRefusedNamingTheConnection()
    {
        assertEquals("Connection 'Check -> Done' requires branch='true' or 'false' for Condition 'Check'. "
            + "Pass branch explicitly.", assertThrows(IllegalArgumentException.class,
                () -> RouteMapRequest.parse(POINTS, "[{\"from\":\"Check\",\"to\":\"Done\"}]")).getMessage());
    }

    @Test
    public void testBranchTrueMapsToPort3AndFalseToPort1AndTwoTrueBranchesAreRefused()
    {
        var request = RouteMapRequest.parse(POINTS, "[{\"from\":\"Check\",\"to\":\"Done\",\"branch\":\"true\"},"
            + "{\"from\":\"Check\",\"to\":\"Done\",\"branch\":\"false\"}]");
        assertEquals(3, request.connections().get(0).fromPort());
        assertEquals(1, request.connections().get(1).fromPort());
        assertThrows(IllegalArgumentException.class, () -> RouteMapRequest.parse(POINTS,
            "[{\"from\":\"Check\",\"to\":\"Done\",\"branch\":\"true\"},"
                + "{\"from\":\"Check\",\"to\":\"Done\",\"branch\":\"true\"}]"));
    }

    @Test
    public void testAConditionFromPortMustAgreeWithItsBranchNamingTheConnection()
    {
        for (String branch : new String[] {"true", "false"})
        {
            int expected = branch.equals("true") ? 3 : 1;
            for (int port = 1; port <= 4; port++)
            {
                String connections = "[{\"from\":\"Check\",\"to\":\"Done\",\"branch\":\"" + branch
                    + "\",\"fromPort\":" + port + "}]";
                if (port == expected)
                {
                    assertEquals(expected, RouteMapRequest.parse(POINTS, connections).connections().get(0).fromPort());
                }
                else
                {
                    assertEquals("Connection 'Check -> Done' has fromPort=" + port + " for branch='" + branch
                        + "'. Use fromPort=" + expected + " or omit it.",
                        assertThrows(IllegalArgumentException.class,
                            () -> RouteMapRequest.parse(POINTS, connections)).getMessage());
                }
            }
        }
    }

    @Test
    public void testTwoConditionBranchesCannotShareOnePort()
    {
        for (String connections : new String[] {
            "[{\"from\":\"Check\",\"to\":\"Done\",\"branch\":\"true\",\"fromPort\":1},"
                + "{\"from\":\"Check\",\"to\":\"Done\",\"branch\":\"false\"}]",
            "[{\"from\":\"Check\",\"to\":\"Done\",\"branch\":\"false\",\"fromPort\":3},"
                + "{\"from\":\"Check\",\"to\":\"Done\",\"branch\":\"true\"}]"})
        {
            assertTrue(assertThrows(IllegalArgumentException.class,
                () -> RouteMapRequest.parse(POINTS, connections)).getMessage().contains("Check -> Done"));
        }
    }

    @Test
    public void testDefaultPortsAreBottom4ToTop2AndAnExplicitPortOutside1To4IsRefused()
    {
        String points = POINTS.replace("Condition", "Start");
        var connection = RouteMapRequest.parse(points, "[{\"from\":\"Check\",\"to\":\"Done\"}]")
            .connections().get(0);
        assertEquals(4, connection.fromPort());
        assertEquals(2, connection.toPort());
        for (String port : new String[] {"fromPort", "toPort"})
        {
            for (String value : new String[] {"0", "5", "-1", "1.5", "\"2\""})
            {
                assertThrows(port + "=" + value, IllegalArgumentException.class, () -> RouteMapRequest.parse(points,
                    "[{\"from\":\"Check\",\"to\":\"Done\",\"" + port + "\":" + value + "}]"));
            }
        }
        var explicit = RouteMapRequest.parse(points,
            "[{\"from\":\"Check\",\"to\":\"Done\",\"fromPort\":1,\"toPort\":3}]").connections().get(0);
        assertEquals(1, explicit.fromPort());
        assertEquals(3, explicit.toPort());
    }

    @Test
    public void testAConnectionNamingAnUnknownPointIsRefused()
    {
        String message = assertThrows(IllegalArgumentException.class, () -> RouteMapRequest.parse(POINTS,
            "[{\"from\":\"Missing\",\"to\":\"Done\"}]")).getMessage();
        assertEquals("Connection 'Missing -> Done' names an unknown point. Use names from points.", message);
    }

    @Test
    public void testDuplicatePointNamesAreRefused()
    {
        assertEquals("Duplicate point name 'Same'. Use unique point names.",
            assertThrows(IllegalArgumentException.class, () -> RouteMapRequest.parse(
                "[{\"name\":\"Same\",\"type\":\"Start\"},{\"name\":\"Same\",\"type\":\"Completion\"}]", "[]"))
                .getMessage());
    }

    @Test
    public void testAPointNameIsJudgedByThePlatformIdentifierRule()
    {
        String cyrillic = new String(new int[] {0x41d, 0x430, 0x447, 0x430, 0x43b, 0x43e}, 0, 6);
        assertEquals(cyrillic, RouteMapRequest.parse("[{\"name\":\"" + cyrillic + "\",\"type\":\"Start\"}]", "[]")
            .points().get(0).name());
        assertEquals("Invalid point name '1Start'. Use a platform identifier starting with a letter or underscore.",
            assertThrows(IllegalArgumentException.class, () -> RouteMapRequest.parse(
                "[{\"name\":\"1Start\",\"type\":\"Start\"}]", "[]")).getMessage());
    }

    @Test
    public void testAnUnknownKindListsTheAcceptedKindsAndTheDeferredOnesByName()
    {
        for (String kind : new String[] {"Switch", "SubBusinessProcess", "Decoration", "Unknown", "ConnectionLine"})
        {
            assertEquals("Unsupported point type '" + kind + "'. Use: Start, Completion, Activity, Condition, "
                + "Split, Join, Processing. Switch, SubBusinessProcess, Decoration, event handlers, "
                + "addressing-attribute values, line points/segments, colours and fonts are deferred to a follow-up. "
                + "Use the EDT route-map editor for these features.",
                assertThrows(IllegalArgumentException.class, () -> RouteMapRequest.parse(
                    "[{\"name\":\"Point\",\"type\":\"" + kind + "\"}]", "[]")).getMessage());
        }
    }

    @Test
    public void testANonPositiveExplicitSizeIsRefused()
    {
        for (String key : new String[] {"x", "y", "width", "height"})
        {
            for (String number : new String[] {"0", "-3", "2.5", "2147483648", "true", "[]"})
            {
                assertThrows(key + "=" + number, IllegalArgumentException.class, () -> RouteMapRequest.parse(
                    "[{\"name\":\"Point\",\"type\":\"Activity\",\"" + key + "\":" + number + "}]", "[]"));
            }
        }
    }

    @Test
    public void testDeferredFieldsAndInvalidJsonAreRefusedBeforeModelAccess()
    {
        for (String key : new String[] {"eventHandlers", "addressingAttributes", "cases", "subprocess", "font", "points"})
        {
            String message = assertThrows(IllegalArgumentException.class, () -> RouteMapRequest.parse(
                "[{\"name\":\"Point\",\"type\":\"Start\",\"" + key + "\":[]}]", "[]")).getMessage();
            assertTrue(message, message.startsWith("Cannot write '" + key + "'. "));
            assertTrue(message, message.endsWith("Use the EDT route-map editor for these features."));
        }
        for (String raw : new String[] {null, "{", "{}", "[null]", "[7]"})
        {
            assertThrows(IllegalArgumentException.class, () -> RouteMapRequest.parse(raw, "[]"));
            assertThrows(IllegalArgumentException.class, () -> RouteMapRequest.parse("[]", raw));
        }
    }

    @Test
    public void testPlanIsImmutableAndDomainChecksRemainWithEdt()
    {
        var request = RouteMapRequest.parse(POINTS, "[]");
        assertEquals(2, request.points().size()); // No Start and no edges: EDT owns graph validity.
        assertThrows(UnsupportedOperationException.class, () -> request.points().clear());
        assertThrows(UnsupportedOperationException.class, () -> request.connections().clear());
    }

    @Test
    public void testActivityPropertiesAreNotSilentlyDroppedOnOtherPointKinds()
    {
        assertEquals("Point 'Begin' is Start. Use taskDescription and group only on Activity points.",
            assertThrows(IllegalArgumentException.class, () -> RouteMapRequest.parse(
                "[{\"name\":\"Begin\",\"type\":\"Start\",\"group\":true}]", "[]")).getMessage());
    }
}
