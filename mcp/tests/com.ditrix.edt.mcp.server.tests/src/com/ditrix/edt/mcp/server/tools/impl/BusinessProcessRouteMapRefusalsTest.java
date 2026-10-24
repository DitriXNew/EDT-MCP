/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com.ditrix.edt.mcp.server.utils.Refusals;
import com.ditrix.edt.mcp.server.utils.RefusalsTestAccess;
import com.google.gson.JsonParser;

/** Drives the real handler through its project-resolution boundary. */
public class BusinessProcessRouteMapRefusalsTest
{
    @Test
    public void testUnmarkedIllegalStateFromProjectResolutionReachesTheBaseLogger()
    {
        assertReachesBaseLogger(new IllegalStateException("Cannot open more than one transaction in one thread"));
    }

    @Test
    public void testUnmarkedIllegalArgumentFromProjectResolutionReachesTheBaseLogger()
    {
        assertReachesBaseLogger(new IllegalArgumentException("Platform feature is unchangeable"));
    }

    @Test
    public void testMarkedRefusalsKeepTheirVerbatimMessageAndLogAtInfo()
    {
        List<IStatus> statuses = new ArrayList<>();
        RefusalsTestAccess.setSink(statuses::add);
        try
        {
            for (RuntimeException refusal : List.of(Refusals.argument("Invalid route map"),
                Refusals.state("Route-map services unavailable")))
            {
                var result = JsonParser.parseString(failingTool(refusal).executeOnUiThread(parameters()))
                    .getAsJsonObject();
                assertFalse(result.get("success").getAsBoolean());
                assertEquals(refusal.getMessage(), result.get("error").getAsString());
                assertEquals(1, statuses.size());
                IStatus status = statuses.remove(0);
                assertEquals(IStatus.INFO, status.getSeverity());
                assertNull(status.getException());
                assertTrue(status.getMessage().endsWith(": " + refusal.getMessage()));
            }
        }
        finally
        {
            RefusalsTestAccess.setSink(null);
        }
    }

    @Test
    public void testAWrappedRefusalRemainsAnUnexpectedFailure()
    {
        assertReachesBaseLogger(new IllegalStateException("Platform failure", Refusals.state("Inner refusal")));
    }

    @Test
    public void testOwnerValidationMarksItsOwnRefusalsWithoutChangingTheirText()
    {
        var missing = assertThrows(IllegalArgumentException.class,
            () -> BusinessProcessRouteMapTool.requireBusinessProcess(null, false, "BusinessProcess.Missing"));
        assertEquals("Metadata object 'BusinessProcess.Missing' not found. "
            + "Use get_metadata_objects to list BusinessProcess objects.", Refusals.messageOf(missing));
        var wrongType = assertThrows(IllegalArgumentException.class,
            () -> BusinessProcessRouteMapTool.requireBusinessProcess(MdClassFactory.eINSTANCE.createCatalog(),
                true, "Catalog.Products"));
        assertEquals("'Catalog.Products' is Catalog, not a top-level BusinessProcess. Pass BusinessProcess.Name.",
            Refusals.messageOf(wrongType));
        var owner = MdClassFactory.eINSTANCE.createBusinessProcess();
        owner.setObjectBelonging(ObjectBelonging.ADOPTED);
        var adopted = assertThrows(IllegalArgumentException.class,
            () -> BusinessProcessRouteMapTool.requireSupportedOwner(owner));
        assertEquals("Route maps of adopted BusinessProcess objects are deferred to a follow-up. "
            + "Use the EDT route-map editor; an extension-owned BusinessProcess is supported.",
            Refusals.messageOf(adopted));
    }

    private static void assertReachesBaseLogger(RuntimeException failure)
    {
        List<IStatus> statuses = new ArrayList<>();
        RefusalsTestAccess.setSink(statuses::add);
        try
        {
            // AbstractMetadataWriteTool catches this exact exception and logs it with its stack.
            assertSame(failure, assertThrows(failure.getClass(),
                () -> failingTool(failure).executeOnUiThread(parameters())));
            assertTrue("Unexpected failures must not enter the refusal logger", statuses.isEmpty());
        }
        finally
        {
            RefusalsTestAccess.setSink(null);
        }
    }

    private static BusinessProcessRouteMapTool failingTool(RuntimeException failure)
    {
        return new BusinessProcessRouteMapTool()
        {
            @Override
            protected ProjectContext resolveProjectAndScope(String projectName)
            {
                assertEquals("TestConfiguration", projectName);
                throw failure;
            }
        };
    }

    private static Map<String, String> parameters()
    {
        return Map.of("projectName", "TestConfiguration", "fqn", "BusinessProcess.Process", "action", "get");
    }
}
