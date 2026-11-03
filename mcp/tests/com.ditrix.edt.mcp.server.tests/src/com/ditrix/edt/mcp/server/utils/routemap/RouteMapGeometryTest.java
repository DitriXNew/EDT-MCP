/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils.routemap;

import static org.junit.Assert.*;

import org.junit.Test;
import com._1c.g5.v8.dt.bp.scheme.model.GraphicalSchemeItemType;

public class RouteMapGeometryTest
{
    @Test
    public void testDefaultSizesComeFromThePlatformTable()
    {
        for (String kind : new String[] {"Start", "Completion", "Activity", "Processing", "Condition", "Split", "Join"})
        {
            var size = RouteMapGeometry.defaultSize(GraphicalSchemeItemType.get(kind));
            assertEquals(kind, kind.equals("Condition") || kind.equals("Split") || kind.equals("Join") ? 100 : 60,
                size.getX());
            assertEquals(kind, kind.equals("Condition") ? 40 : 60, size.getY());
            var pair = RouteMapGeometry.defaults().getAsJsonObject("sizes").getAsJsonArray(kind);
            assertEquals(size.getX(), pair.get(0).getAsInt());
            assertEquals(size.getY(), pair.get(1).getAsInt());
        }
        var ports = RouteMapGeometry.defaults().getAsJsonObject("ports");
        assertEquals(4, ports.get("source").getAsInt());
        assertEquals(2, ports.get("target").getAsInt());
        assertEquals(3, ports.get("conditionTrue").getAsInt());
        assertEquals(1, ports.get("conditionFalse").getAsInt());
    }

    @Test
    public void testAnExplicitSizeOverridesTheDefaultAndAnOmittedOneDoesNot()
    {
        var placed = RouteMapGeometry.place(RouteMapRequest.parse(
            "[{\"name\":\"Check\",\"type\":\"Condition\",\"width\":140,\"height\":61,\"x\":53,\"y\":81},"
                + "{\"name\":\"Task\",\"type\":\"Activity\"}]", "[]"));
        assertEquals(new RouteMapGeometry.Rectangle(53, 81, 140, 61), placed.get("Check"));
        assertEquals(60, placed.get("Task").width());
        assertEquals(60, placed.get("Task").height());
    }

    @Test
    public void testOmittedCoordinatesAreLayeredByDepthFromStartAndSnappedToTheGrid()
    {
        var request = RouteMapRequest.parse("[{\"name\":\"Begin\",\"type\":\"Start\"},"
            + "{\"name\":\"A\",\"type\":\"Activity\"},{\"name\":\"B\",\"type\":\"Activity\"},"
            + "{\"name\":\"Done\",\"type\":\"Completion\"},{\"name\":\"Unreachable\",\"type\":\"Processing\"}]",
            "[{\"from\":\"Begin\",\"to\":\"A\"},{\"from\":\"Begin\",\"to\":\"B\"},"
                + "{\"from\":\"A\",\"to\":\"Done\"},{\"from\":\"B\",\"to\":\"Done\"},"
                + "{\"from\":\"Done\",\"to\":\"Begin\"}]");
        var placed = RouteMapGeometry.place(request);
        assertEquals(40, placed.get("Begin").y());
        assertEquals(160, placed.get("A").y());
        assertEquals(160, placed.get("B").y());
        assertEquals(40, placed.get("A").x());
        assertEquals(200, placed.get("B").x());
        assertEquals(280, placed.get("Done").y());
        assertEquals(40, placed.get("Unreachable").y());
        placed.values().forEach(rectangle -> {
            assertEquals(0, rectangle.x() % 20);
            assertEquals(0, rectangle.y() % 20);
        });
        assertEquals(placed, RouteMapGeometry.place(request));
    }
}
