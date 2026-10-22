/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.preferences;

import java.util.Set;

/** Keeps historical fixtures independent of unrelated, separately tested newer migrations. */
final class ToolMigrationTestSupport
{
    private ToolMigrationTestSupport()
    {
    }

    /** Historical steps remain literal assertions; append only later declared obligations. */
    static void addReadOnlyAdditionsAfter(int testedVersion, ToolPreset preset, Set<String> expected)
    {
        if (!preset.isReadOnly())
        {
            return;
        }
        var additions = preset == ToolPreset.ANALYSIS_ONLY
            ? ToolSettingsService.ANALYSIS_ONLY_MIGRATION_ADDITIONS_BY_VERSION
            : ToolSettingsService.CODE_REVIEW_MIGRATION_ADDITIONS_BY_VERSION;
        additions.forEach((version, names) -> {
            if (version > testedVersion)
            {
                expected.addAll(names);
            }
        });
    }
}
