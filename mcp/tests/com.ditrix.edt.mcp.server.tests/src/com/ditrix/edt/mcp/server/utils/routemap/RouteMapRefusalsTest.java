/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.utils.routemap;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.ditrix.edt.mcp.server.utils.Refusals;

public class RouteMapRefusalsTest
{
    @Test
    public void testInvalidJsonIsAMarkedRefusalWithItsVerbatimMessageAndParsingCause()
    {
        var refusal = assertThrows(IllegalArgumentException.class, () -> RouteMapRequest.parse("[", "[]"));
        assertEquals("Invalid 'points'. Pass a JSON array of objects.", Refusals.messageOf(refusal));
        assertNotNull(refusal.getCause());
    }

    @Test
    public void testUnsupportedPointTypeIsAMarkedRefusal()
    {
        var refusal = assertThrows(IllegalArgumentException.class,
            () -> RouteMapRequest.parse("[{\"name\":\"Begin\",\"type\":\"Switch\"}]", "[]"));
        assertEquals("Unsupported point type 'Switch'. Use: " + RouteMapRequest.ACCEPTED + ". "
            + RouteMapRequest.DEFERRED, Refusals.messageOf(refusal));
    }

    @Test
    public void testUnexpectedRouteMapContentIsAMarkedRefusal()
    {
        var tx = mock(IBmTransaction.class);
        when(tx.getTopObjectByFqn("BusinessProcess.Process.Flowchart"))
            .thenReturn((IBmObject)MdClassFactory.eINSTANCE.createCatalog());
        var refusal = assertThrows(IllegalArgumentException.class,
            () -> RouteMapWriter.existing(tx, "BusinessProcess.Process.Flowchart"));
        assertEquals("Route-map content 'BusinessProcess.Process.Flowchart' is not a GraphicalScheme. "
            + "Refresh the project and retry.", Refusals.messageOf(refusal));
    }
}
