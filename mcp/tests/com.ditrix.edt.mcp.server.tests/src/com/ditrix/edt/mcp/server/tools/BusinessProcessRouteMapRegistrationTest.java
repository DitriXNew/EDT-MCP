/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.*;

import org.junit.Test;
import com.ditrix.edt.mcp.server.preferences.ToolGroup;
import com.ditrix.edt.mcp.server.preferences.ToolPreset;
import com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;

public class BusinessProcessRouteMapRegistrationTest
{
    private static final String TOOL = "business_process_route_map";

    @Test
    public void testToolIsInTheRefactoringGroupMetadataToolsetAndConsentCheckboxList()
    {
        assertEquals(ToolGroup.REFACTORING, ToolGroup.getGroupForTool(TOOL));
        assertTrue(Toolsets.isExplicitlyMapped(TOOL));
        assertEquals(Toolsets.METADATA, Toolsets.toolsetOf(TOOL));
        assertTrue(DestructiveConsentGate.GATED_TOOLS.contains(TOOL));
    }

    @Test
    public void testTheCentralClassifierPublishesTheDestructiveToolHints()
    {
        assertEquals(Boolean.TRUE, ToolAnnotationClassifier.classify(TOOL).getDestructiveHint());
        assertEquals(Boolean.FALSE, ToolAnnotationClassifier.classify(TOOL).getReadOnlyHint());
    }

    @Test
    public void testReadOnlyPresetsExcludeTheWholeToolIncludingGet()
    {
        assertTrue(ToolPreset.CODE_REVIEW.getDisabledTools().contains(TOOL));
        assertTrue(ToolPreset.ANALYSIS_ONLY.getDisabledTools().contains(TOOL));
        assertFalse(ToolPreset.DEVELOPMENT.getDisabledTools().contains(TOOL));
        assertFalse(ToolPreset.ALL_TOOLS.getDisabledTools().contains(TOOL));
    }
}
