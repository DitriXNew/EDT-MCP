/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.BusinessProcessRouteMapTool.MapPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;

/** The preview must describe the transaction's effect on unsaved editor work. */
public class BusinessProcessRouteMapRemovalConsentTest
{
    private static final String CONTENT = "1 points and 0 connections are discarded; "
        + "infobase tasks keep their point identity only for same-named points";
    private static final String WARNING = " An open editor of this route map with unsaved changes "
        + "has them all saved with the delete, not only its changes to this route map, "
        + "and loses its undo history.";

    @Test
    public void testRemoveConsentDisclosesSavingAllEditorChangesAndClearingUndo()
    {
        assertPreview("remove", new MapPreview(true, 1, 1, 0, List.of("Check")), "1 points and 0 connections are discarded; "
            + "every point identity is discarded, so infobase tasks referring to these points lose them; "
            + "a later replace creates new identities even for the same names." + WARNING);
    }

    @Test
    public void testEvenAnEmptyExistingMapDisclosesTheGlobalContextSave()
    {
        assertPreview("remove", new MapPreview(true, 0, 0, 0, List.of()),
            "0 points and 0 connections are discarded; "
                + "every point identity is discarded, so infobase tasks referring to these points lose them; "
                + "a later replace creates new identities even for the same names." + WARNING);
    }

    @Test
    public void testReplaceConsentDoesNotClaimAGlobalContextSave()
    {
        assertPreview("replace", new MapPreview(true, 1, 1, 0, List.of("Check")), CONTENT);
    }

    private static void assertPreview(String action, MapPreview previous, String expected)
    {
        AtomicInteger decisions = new AtomicInteger();
        var tool = new BusinessProcessRouteMapTool((name, preview) -> {
            decisions.incrementAndGet();
            assertEquals(BusinessProcessRouteMapTool.NAME, name);
            assertEquals(expected, preview.getSubtitle());
            return ConsentDecision.REJECT;
        });
        tool.consentAndWrite(action, "BusinessProcess.Process", previous, () -> {
            throw new AssertionError("Rejected consent must never write");
        });
        assertEquals(1, decisions.get());
    }
}
