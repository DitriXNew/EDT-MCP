/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils.routemap;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.bp.scheme.model.*;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;

public class RouteMapWriterTest
{
    private static final ModelFactory FACTORY = ModelFactory.eINSTANCE;
    private static final String POINTS = "[{\"name\":\"Begin\",\"type\":\"Start\"},"
        + "{\"name\":\"Task\",\"type\":\"Activity\"},{\"name\":\"Done\",\"type\":\"Completion\"}]";
    private static final String CONNECTIONS = "[{\"from\":\"Begin\",\"to\":\"Task\",\"name\":\"First\"},"
        + "{\"from\":\"Task\",\"to\":\"Done\",\"name\":\"Last\"}]";

    private static GraphicalScheme write(GraphicalScheme scheme, String points, String connections)
    {
        RouteMapWriter.replace(scheme, RouteMapRequest.parse(points, connections), "en", null, bp -> List.of());
        return scheme;
    }

    private static GraphicalSchemeFigure figure(GraphicalScheme scheme, String name)
    {
        return (GraphicalSchemeFigure)scheme.getItems().stream().filter(item -> name.equals(item.getName()))
            .findFirst().orElseThrow();
    }

    @Test
    public void testIdsAreUniqueAcrossFiguresAndConnectionsAndStartAfterTheExistingMaximum()
    {
        GraphicalScheme scheme = FACTORY.createGraphicalScheme();
        var oldFigure = FACTORY.createGraphicalSchemeFigure();
        oldFigure.setId(32);
        var oldConnection = FACTORY.createGraphicalSchemeConnection();
        oldConnection.setId(700);
        scheme.getItems().add(oldFigure);
        scheme.getItems().add(oldConnection);
        write(scheme, POINTS, CONNECTIONS);
        var ids = new HashSet<Integer>();
        scheme.getItems().forEach(item -> {
            assertTrue(item.getId() > 700);
            assertTrue(ids.add(item.getId()));
        });
        assertEquals(701, scheme.getItems().get(0).getId());
        assertEquals(5, ids.size());
    }

    @Test
    public void testEveryFigureGetsAPointUuidTheDefaultLineAndItsBounds()
    {
        var scheme = write(FACTORY.createGraphicalScheme(), POINTS, CONNECTIONS);
        var identities = new HashSet<UUID>();
        for (GraphicalSchemeItem item : scheme.getItems())
        {
            assertEquals(GraphicalSchemeLineType.SOLID, item.getLine().getStyle());
            assertEquals(1, item.getLine().getWidth());
            assertFalse(item.getLine().isGap());
            if (item instanceof GraphicalSchemeFigure point)
            {
                assertNotNull(point.getPointUUID());
                assertTrue(identities.add(point.getPointUUID()));
                assertEquals(60, point.getBounds().getWidth());
                assertEquals(60, point.getBounds().getHeight());
            }
        }
        assertEquals(3, identities.size());
    }

    @Test
    public void testReplaceKeepsThePointUuidOfASameNamedExistingPointAndRegeneratesOthers()
    {
        var scheme = write(FACTORY.createGraphicalScheme(), POINTS, CONNECTIONS);
        UUID previous = figure(scheme, "Task").getPointUUID();
        UUID begin = figure(scheme, "Begin").getPointUUID();
        write(scheme, POINTS.replace("Begin", "NewBegin"), CONNECTIONS.replace("Begin", "NewBegin"));
        assertEquals(previous, figure(scheme, "Task").getPointUUID());
        assertNotEquals(begin, figure(scheme, "NewBegin").getPointUUID());
    }

    @Test
    public void testReplaceClearsItemsButKeepsPrintPropsGridAndBackColor()
    {
        GraphicalScheme scheme = FACTORY.createGraphicalScheme();
        var print = FACTORY.createPrintPropItem();
        var color = McoreFactory.eINSTANCE.createColorDef();
        scheme.getPrintPropItems().add(print);
        scheme.setGridEnabled(true);
        scheme.setGridHorizontalStep(25);
        scheme.setGridVerticalStep(45);
        scheme.setBackColor(color);
        write(scheme, POINTS, CONNECTIONS);
        write(scheme, "[{\"name\":\"Only\",\"type\":\"Processing\"}]", "[]");
        assertEquals(1, scheme.getItems().size());
        assertEquals("Only", scheme.getItems().get(0).getName());
        assertEquals(List.of(print), scheme.getPrintPropItems());
        assertSame(color, scheme.getBackColor());
        assertTrue(scheme.isGridEnabled());
        assertEquals(25, scheme.getGridHorizontalStep());
        assertEquals(45, scheme.getGridVerticalStep());
    }

    @Test
    public void testConditionGetsTruePort3FalsePort1AndItsBranchesUseThosePorts()
    {
        var scheme = write(FACTORY.createGraphicalScheme(),
            "[{\"name\":\"Check\",\"type\":\"Condition\"},{\"name\":\"Done\",\"type\":\"Completion\"}]",
            "[{\"from\":\"Check\",\"to\":\"Done\",\"branch\":\"true\"},"
                + "{\"from\":\"Check\",\"to\":\"Done\",\"branch\":\"false\"}]");
        var condition = (GraphicalSchemeCondition)figure(scheme, "Check");
        assertEquals(3, condition.getTruePortIndex());
        assertEquals(1, condition.getFalsePortIndex());
        assertEquals(3, ((GraphicalSchemeConnection)scheme.getItems().get(2)).getSourcePortIndex());
        assertEquals(1, ((GraphicalSchemeConnection)scheme.getItems().get(3)).getSourcePortIndex());
        assertEquals(100, condition.getBounds().getWidth());
        assertEquals(40, condition.getBounds().getHeight());
    }

    @Test
    public void testActivityGetsTaskDescriptionAndOneAddressingAttributePerServiceResult()
    {
        var owner = MdClassFactory.eINSTANCE.createBusinessProcess();
        var first = MdClassFactory.eINSTANCE.createAddressingAttribute();
        first.setName("Performer");
        var second = MdClassFactory.eINSTANCE.createAddressingAttribute();
        second.setName("Department");
        var scheme = FACTORY.createGraphicalScheme();
        RouteMapWriter.replace(scheme, RouteMapRequest.parse(
            "[{\"name\":\"Task\",\"type\":\"Activity\",\"taskDescription\":\"Review\",\"group\":true},"
                + "{\"name\":\"Other\",\"type\":\"Activity\"}]", "[]"), "en", owner, bp -> {
                    assertSame(owner, bp);
                    return List.of(first, second);
                });
        var activity = (GraphicalSchemeActivity)figure(scheme, "Task");
        assertEquals("Review", activity.getTaskDescription());
        assertTrue(activity.isGroup());
        assertEquals(2, activity.getAddressingAttributes().size());
        assertSame(first, activity.getAddressingAttributes().get(0).getRef());
        assertSame(second, activity.getAddressingAttributes().get(1).getRef());
        activity.getAddressingAttributes().forEach(attribute ->
            assertTrue(attribute.getValue() instanceof com._1c.g5.v8.dt.mcore.UndefinedValue));
        assertEquals("Other", ((GraphicalSchemeActivity)figure(scheme, "Other")).getTaskDescription());
        assertNotSame(activity.getAddressingAttributes().get(0),
            ((GraphicalSchemeActivity)figure(scheme, "Other")).getAddressingAttributes().get(0));
    }

    @Test
    public void testFiguresPrecedeConnectionsAndConnectionsCarrySourceAndTargetNameAndId()
    {
        var scheme = write(FACTORY.createGraphicalScheme(), POINTS, CONNECTIONS);
        for (int index = 0; index < 3; index++)
        {
            assertTrue(scheme.getItems().get(index) instanceof GraphicalSchemeFigure);
        }
        for (int index = 3; index < 5; index++)
        {
            var connection = (GraphicalSchemeConnection)scheme.getItems().get(index);
            assertEquals(figure(scheme, connection.getSourceItemName()).getId(), connection.getSourceItemId());
            assertEquals(figure(scheme, connection.getTargetItemName()).getId(), connection.getTargetItemId());
            assertEquals(GraphicalSchemeItemType.CONNECTION_LINE, connection.getItemType());
            assertEquals(GraphicalSchemeConnectionTextLocation.FIRST_SEGMENT, connection.getTextLocation());
            assertEquals(ArrowStyle.BLANK, connection.getEndArrow());
            assertEquals(4, connection.getSourcePortIndex());
            assertEquals(2, connection.getTargetPortIndex());
        }
        assertEquals("First", scheme.getItems().get(3).getName());
    }

    @Test
    public void testMissingExternalContentIsReadByFqnAndAnAttachedDocumentUsesThatFqn()
    {
        IBmTransaction tx = mock(IBmTransaction.class);
        assertNull(RouteMapWriter.existing(tx, "BusinessProcess.Process.Flowchart"));
        var attached = RouteMapWriter.attach(tx, "BusinessProcess.Process.Flowchart", com._1c.g5.v8.dt.platform.version.Version.V8_3_23);
        verify(tx).attachTopObject((IBmObject)attached, "BusinessProcess.Process.Flowchart");
        assertFalse("the platform factory seeds print settings", attached.getPrintPropItems().isEmpty());
    }
}
