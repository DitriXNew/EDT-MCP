// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.*;
import java.util.ArrayList;
import org.junit.Test;
import com.google.gson.JsonParser;

/** Every declared parameter must be discoverable in its on-demand guide. */
public class ToolGuideConsistencyTest
{
    @Test
    public void everySchemaPropertyIsNamedInItsGuide()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        try
        {
            BuiltInToolRegistrar.registerAll(registry);
            var missing = new ArrayList<String>();
            for (IMcpTool tool : registry.getAllTools())
            {
                String guide = tool.getGuide();
                var properties = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject().getAsJsonObject("properties");
                if (properties == null) { continue; }
                for (String key : properties.keySet())
                {
                    if ((guide == null || !java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])"
                        + java.util.regex.Pattern.quote(key) + "(?![A-Za-z0-9_])").matcher(guide).find()))
                    {
                        missing.add(tool.getName() + "." + key);
                    }
                }
            }
            assertEquals("Name these parameters in their guides", java.util.List.of(), missing);
        }
        finally { registry.clear(); }
    }
}
