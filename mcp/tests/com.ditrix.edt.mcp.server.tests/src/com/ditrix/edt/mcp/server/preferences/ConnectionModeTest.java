/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ConnectionModeTest
{
    @Test
    public void allPendingAndLiveCombinations()
    {
        boolean[][] cases = {
            {false, false, false}, {false, false, true},
            {false, true, false}, {false, true, true},
            {true, false, false}, {true, false, true},
            {true, true, false}, {true, true, true}
        };
        ConnectionMode[] expected = {
            ConnectionMode.STDIO, ConnectionMode.STDIO, ConnectionMode.STDIO, ConnectionMode.STDIO,
            ConnectionMode.HTTP, ConnectionMode.HTTP, ConnectionMode.STDIO, ConnectionMode.HTTP
        };
        for (int i = 0; i < cases.length; i++)
        {
            assertEquals("Combination " + i, expected[i],
                ConnectionMode.resolve(cases[i][0], cases[i][1], cases[i][2]));
        }
    }
}
