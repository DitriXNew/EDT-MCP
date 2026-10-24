/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.BusinessProcessRouteMapTool.MapPreview;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.google.gson.JsonParser;

/** Removal discards point identities; only replacement can preserve a same-named point. */
public class BusinessProcessRouteMapIdentityConsentTest
{
    private static final String PRESERVATION =
        "infobase tasks keep their point identity only for same-named points";
    private static final String IDENTITY_LOSS = "every point identity is discarded, so "
        + "infobase tasks referring to these points lose them; "
        + "a later replace creates new identities even for the same names.";
    private static final String EDITOR_NOTICE = " An open editor of this route map with unsaved changes "
        + "has them all saved with the delete, not only its changes to this route map, "
        + "and loses its undo history.";
    private static final MapPreview MIXED = new MapPreview(true, 6, 3, 3, List.of("Begin", "Done"),
        "2 points and 3 connections and 1 decoration");

    @Test
    public void testRemoveConsentDiscardsEveryIdentityAndWarnsTasksLoseTheirPoints()
    {
        String subtitle = rejectedPreview("remove", MIXED).getSubtitle();
        assertTrue(subtitle, subtitle.contains("every point identity is discarded"));
        assertTrue(subtitle, subtitle.contains("infobase tasks referring to these points lose them"));
        assertFalse(subtitle, subtitle.contains(PRESERVATION));
    }

    @Test
    public void testRemoveConsentWarnsALaterReplaceCreatesNewIdentitiesEvenForTheSameNames()
    {
        String subtitle = rejectedPreview("remove", MIXED).getSubtitle();
        assertTrue(subtitle, subtitle.contains(
            "a later replace creates new identities even for the same names."));
    }

    @Test
    public void testRemoveConsentKeepsDiscardedItemCountsAndTheGlobalContextNotice()
    {
        ConsentPreview preview = rejectedPreview("remove", MIXED);
        assertEquals("Remove route map of BusinessProcess.Process", preview.getTitle());
        assertEquals(6, preview.getTotalCount());
        assertEquals(List.of("Begin", "Done"), preview.getTopNames());
        assertEquals("2 points and 3 connections and 1 decoration are discarded; "
            + IDENTITY_LOSS + EDITOR_NOTICE, preview.getSubtitle());
    }

    @Test
    public void testEmptyMapRemovalStillDisclosesIdentityLossWithoutPromisingPreservation()
    {
        ConsentPreview preview = rejectedPreview("remove", new MapPreview(true, 0, 0, 0, List.of()));
        assertEquals(0, preview.getTotalCount());
        assertEquals("0 points and 0 connections are discarded; " + IDENTITY_LOSS + EDITOR_NOTICE,
            preview.getSubtitle());
        assertFalse(preview.getSubtitle(), preview.getSubtitle().contains(PRESERVATION));
    }

    @Test
    public void testReplaceConsentKeepsSameNamePreservationWithoutTheRemovalWarning()
    {
        String subtitle = rejectedPreview("replace", MIXED).getSubtitle();
        assertEquals("2 points and 3 connections and 1 decoration are discarded; " + PRESERVATION, subtitle);
        assertFalse(subtitle, subtitle.contains("every point identity is discarded"));
        assertFalse(subtitle, subtitle.contains("a later replace creates new identities"));
        assertFalse(subtitle, subtitle.contains(EDITOR_NOTICE));
    }

    private static ConsentPreview rejectedPreview(String action, MapPreview previous)
    {
        ConsentPreview[] captured = new ConsentPreview[1];
        AtomicInteger decisions = new AtomicInteger();
        var tool = new BusinessProcessRouteMapTool((name, preview) -> {
            decisions.incrementAndGet();
            assertEquals(BusinessProcessRouteMapTool.NAME, name);
            captured[0] = preview;
            return ConsentDecision.REJECT;
        });
        String result = tool.consentAndWrite(action, "BusinessProcess.Process", previous, () -> {
            throw new AssertionError("Rejected consent must never write");
        });
        assertEquals(1, decisions.get());
        assertFalse(JsonParser.parseString(result).getAsJsonObject().get("success").getAsBoolean());
        assertNotNull(captured[0]);
        return captured[0];
    }
}
