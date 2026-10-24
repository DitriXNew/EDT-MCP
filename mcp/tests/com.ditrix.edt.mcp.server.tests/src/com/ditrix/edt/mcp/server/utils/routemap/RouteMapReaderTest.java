/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils.routemap;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

import com._1c.g5.v8.dt.bp.scheme.model.*;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;

public class RouteMapReaderTest
{
    private static final ModelFactory FACTORY = ModelFactory.eINSTANCE;

    @Test
    public void testBranchIsDerivedFromSourcePortAgainstTheConditionTruePortNotFromTheDefault()
    {
        var scheme = FACTORY.createGraphicalScheme();
        RouteMapWriter.replace(scheme, RouteMapRequest.parse(
            "[{\"name\":\"Check\",\"type\":\"Condition\"},{\"name\":\"Done\",\"type\":\"Completion\"}]",
            "[{\"from\":\"Check\",\"to\":\"Done\",\"branch\":\"false\"},"
                + "{\"from\":\"Check\",\"to\":\"Done\",\"branch\":\"true\"}]"), "en", null, bp -> List.of());
        ((GraphicalSchemeCondition)scheme.getItems().get(0)).setTruePortIndex(1);
        ((GraphicalSchemeCondition)scheme.getItems().get(0)).setFalsePortIndex(3);
        var connections = RouteMapReader.read(scheme, "en").getAsJsonArray("connections");
        assertEquals("true", connections.get(0).getAsJsonObject().get("branch").getAsString());
        assertEquals("false", connections.get(1).getAsJsonObject().get("branch").getAsString());
        assertEquals("Check", connections.get(0).getAsJsonObject().get("from").getAsString());
        assertEquals(scheme.getItems().get(0).getId(), connections.get(0).getAsJsonObject().get("fromId").getAsInt());
    }

    @Test
    public void testTitlesAreReadByLanguageCode()
    {
        var scheme = FACTORY.createGraphicalScheme();
        var point = FACTORY.createGraphicalSchemeFigure();
        point.setName("Begin");
        point.setItemType(GraphicalSchemeItemType.START);
        point.getTitle().put("en", "Begin title");
        point.getTitle().put("ru", "Second title");
        point.getTitle().put("English", "Wrong key");
        scheme.getItems().add(point);
        assertEquals("Begin title", RouteMapReader.read(scheme, "en").getAsJsonArray("points")
            .get(0).getAsJsonObject().get("title").getAsString());
        assertEquals("Second title", RouteMapReader.read(scheme, "ru").getAsJsonArray("points")
            .get(0).getAsJsonObject().get("title").getAsString());
        assertFalse(RouteMapReader.read(scheme, null).getAsJsonArray("points").get(0).getAsJsonObject().has("title"));
    }

    @Test
    public void testAnAbsentTopObjectReadsRouteMapExistsFalseWithTheDefaultsTable()
    {
        var absent = RouteMapReader.read(null, null);
        assertFalse(absent.get("routeMapExists").getAsBoolean());
        assertTrue(absent.getAsJsonArray("points").isEmpty());
        assertTrue(absent.getAsJsonArray("connections").isEmpty());
        assertEquals(100, absent.getAsJsonObject("defaults").getAsJsonObject("sizes")
            .getAsJsonArray("Condition").get(0).getAsInt());
    }

    @Test
    public void testEditorAuthoredCasesHandlersAndActivityAddressingAreReadable()
    {
        var scheme = FACTORY.createGraphicalScheme();
        var selection = FACTORY.createGraphicalSchemeSwitch();
        selection.setName("Choose");
        selection.setItemType(GraphicalSchemeItemType.SWITCH);
        for (String name : List.of("FirstCase", "SecondCase"))
        {
            var branch = FACTORY.createCase();
            branch.setName(name);
            selection.getCases().add(branch);
        }
        var event = McoreFactory.eINSTANCE.createEvent();
        event.setName("BeforeExecute");
        var handler = FACTORY.createEventHandler();
        handler.setEvent(event);
        handler.setName("HandlePoint");
        selection.getEventHandlers().add(handler);
        selection.getEventHandlers().add(FACTORY.createEventHandler());
        scheme.getItems().add(selection);
        var activity = FACTORY.createGraphicalSchemeActivity();
        activity.setName("Task");
        activity.setItemType(GraphicalSchemeItemType.ACTIVITY);
        activity.setTaskDescription("Review");
        activity.setGroup(true);
        var ref = MdClassFactory.eINSTANCE.createAddressingAttribute();
        ref.setName("Performer");
        var attribute = FACTORY.createSchemeItemAddressingAttribute();
        attribute.setRef(ref);
        activity.getAddressingAttributes().add(attribute);
        scheme.getItems().add(activity);
        for (int i = 0; i < 3; i++)
        {
            var connection = FACTORY.createGraphicalSchemeConnection();
            connection.setSourceItemName("Choose");
            connection.setTargetItemName("Task");
            scheme.getItems().add(connection);
        }
        var result = RouteMapReader.read(scheme, "en");
        var point = result.getAsJsonArray("points").get(0).getAsJsonObject();
        assertEquals("FirstCase", point.getAsJsonArray("cases").get(0).getAsString());
        assertEquals(1, point.getAsJsonArray("eventHandlers").size());
        assertEquals("BeforeExecute", point.getAsJsonArray("eventHandlers").get(0).getAsJsonObject()
            .get("event").getAsString());
        assertEquals("HandlePoint", point.getAsJsonArray("eventHandlers").get(0).getAsJsonObject()
            .get("procedure").getAsString());
        var task = result.getAsJsonArray("points").get(1).getAsJsonObject();
        assertEquals("Performer", task.getAsJsonArray("addressingAttributes").get(0).getAsString());
        assertTrue(task.get("group").getAsBoolean());
        var connections = result.getAsJsonArray("connections");
        assertEquals("FirstCase", connections.get(0).getAsJsonObject().get("branch").getAsString());
        assertEquals("SecondCase", connections.get(1).getAsJsonObject().get("branch").getAsString());
        assertFalse("malformed editor graph must still be readable", connections.get(2).getAsJsonObject().has("branch"));
    }
}
