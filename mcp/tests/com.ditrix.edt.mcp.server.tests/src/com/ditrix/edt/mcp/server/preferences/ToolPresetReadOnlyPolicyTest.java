/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.BuiltInToolRegistrar;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;

/** Read-only policy uses effective hints, with explicit, bidirectionally checked exceptions. */
public class ToolPresetReadOnlyPolicyTest
{
    private static final List<ToolPreset> READ_ONLY =
        List.of(ToolPreset.ANALYSIS_ONLY, ToolPreset.CODE_REVIEW);

    private final McpToolRegistry registry = McpToolRegistry.getInstance();

    @Before
    public void setUp()
    {
        BuiltInToolRegistrar.registerAll(registry);
    }

    @After
    public void tearDown()
    {
        registry.clear();
    }

    @Test
    public void testReadOnlyPresetsDisableTheWorkspaceAndGitWriters()
    {
        Set<String> writers = Set.of("switch_git_branch", "create_git_branch", "set_branch_infobase",
            "create_project", "resync_to_disk", "clean_project");
        Set<String> kept = Set.of("revalidate_objects", "list_git_branches", "compare_configurations",
            "enable_toolset", "get_comparison_node");
        for (ToolPreset preset : READ_ONLY)
        {
            assertTrue(preset + " must disable every workspace/git writer",
                preset.getDisabledTools().containsAll(writers));
            for (String name : kept)
            {
                assertFalse(preset + " must keep " + name, preset.getDisabledTools().contains(name));
            }
        }
        for (String name : writers)
        {
            assertFalse("Development must keep " + name,
                ToolPreset.DEVELOPMENT.getDisabledTools().contains(name));
        }
    }

    /**
     * Every registered tool lacking a true read-only hint must be disabled unless the preset
     * explicitly justifies keeping it. Own annotations take precedence over the classifier.
     * Destructive hints and the consent gate remain unconditional exclusions.
     */
    @Test
    public void testEveryToolWithoutAReadOnlyHintIsDisabledInTheReadOnlyPresetsUnlessKept()
        throws Exception
    {
        Set<String> withoutReadOnlyHint = new TreeSet<>();
        Set<String> destructiveOrGated = new TreeSet<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            ToolAnnotations annotations = effectiveAnnotations(tool);
            if (!Boolean.TRUE.equals(annotations.getReadOnlyHint()))
            {
                withoutReadOnlyHint.add(tool.getName());
            }
            if (Boolean.TRUE.equals(annotations.getDestructiveHint())
                || DestructiveConsentGate.GATED_TOOLS.contains(tool.getName()))
            {
                destructiveOrGated.add(tool.getName());
            }
        }
        assertTrue("the scan must find a plain non-destructive writer",
            withoutReadOnlyHint.contains("switch_git_branch"));
        assertTrue("the scan must find a destructive writer",
            withoutReadOnlyHint.contains("delete_metadata"));
        assertTrue("destructive/gated positive control", destructiveOrGated.contains("delete_metadata"));
        assertFalse("the plain writer control must not rely on a destructive hint or gate",
            destructiveOrGated.contains("switch_git_branch"));

        Set<String> excluded = new TreeSet<>(withoutReadOnlyHint);
        excluded.removeAll(keepList());
        excluded.addAll(destructiveOrGated);
        List<String> violators = new ArrayList<>();
        for (ToolPreset preset : READ_ONLY)
        {
            for (String name : excluded)
            {
                if (!preset.getDisabledTools().contains(name))
                {
                    violators.add(name + " (" + preset + ")");
                }
            }
        }
        assertTrue("tools without read-only hints left enabled: " + violators, violators.isEmpty());
    }

    @Test
    public void testTheReadOnlyKeepListNamesOnlyEnabledToolsWithoutAReadOnlyHint() throws Exception
    {
        Set<String> kept = keepList();
        assertEquals(Set.of("compare_configurations", "enable_toolset", "revalidate_objects"), kept);
        for (String name : kept)
        {
            IMcpTool tool = registry.getTool(name);
            assertNotNull("stale keep-list entry: " + name, tool);
            assertFalse("read-only hinted tools need no exception: " + name,
                Boolean.TRUE.equals(effectiveAnnotations(tool).getReadOnlyHint()));
            for (ToolPreset preset : READ_ONLY)
            {
                assertFalse("hidden keep-list entry: " + name + " in " + preset,
                    preset.getDisabledTools().contains(name));
            }
        }
    }

    private static ToolAnnotations effectiveAnnotations(IMcpTool tool)
    {
        ToolAnnotations own = tool.getAnnotations();
        return own != null ? own : ToolAnnotationClassifier.classify(tool.getName());
    }

    /** Reflection keeps every behavioral regression runnable on base, where this field is absent. */
    @SuppressWarnings("unchecked")
    private static Set<String> keepList()
    {
        return ToolPreset.READ_ONLY_KEEPS_DESPITE_WRITE_HINT;
    }
}
