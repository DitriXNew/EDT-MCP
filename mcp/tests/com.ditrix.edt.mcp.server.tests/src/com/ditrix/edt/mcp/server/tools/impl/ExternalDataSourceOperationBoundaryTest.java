/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static com.ditrix.edt.mcp.server.utils.ExternalDataSourceFixtures.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import com.google.gson.JsonParser;

/** Refusal occurs before services, index settling or any mutation can run. */
public class ExternalDataSourceOperationBoundaryTest
{
    @Test public void adoptRefusesEdsMembersBeforeResolvingTheProject() throws Exception
    {
        for (String fqn : addresses())
        {
            assertRefused(new AdoptMetadataObjectTool().executeOnUiThread(Map.of("projectName", "P", "fqn", fqn)),
                "adopt_metadata_object");
        }
    }

    @Test public void renameRefusesEdsMembersBeforeSettlingTheIndex()
    {
        var tool = new RenameMetadataObjectTool((project, timeout) -> { throw new AssertionError("settle reached"); });
        for (String fqn : addresses())
        {
            assertRefused(tool.execute(Map.of("projectName", "P", "objectFqn", fqn, "newName", "Renamed")),
                "rename_metadata_object");
        }
    }

    @Test public void typedPictureAndXdtoToolsRefuseEdsMembers()
    {
        for (String fqn : addresses())
        {
            assertRefused(new ExportCommonPictureTool().execute(Map.of("projectName", "P", "fqn", fqn)),
                "export_common_picture");
            assertRefused(new ValidateXdtoPackageTool().execute(Map.of("projectName", "P", "fqn", fqn)),
                "validate_xdto_package");
        }
    }

    private static List<String> addresses()
    {
        return List.of(TABLE, FIELD, FUNCTION, CUBE, RU_EDS + ".Eds." + RU_TABLE + ".T");
    }

    private static void assertRefused(String json, String operation)
    {
        var result = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(json, result.get("success").getAsBoolean());
        assertTrue(json, json.contains(operation + " does not support ExternalDataSource members"));
        assertTrue(json, json.contains("Use the EDT designer"));
    }
}
