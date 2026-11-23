// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EmptyModuleRemovalTest
{
    @Test
    public void acceptsEmptyAndWhitespaceButChecksTheRevision()
    {
        EmptyModuleRemoval.requireEmptyRevision("", ContentHash.of(""));
        EmptyModuleRemoval.requireEmptyRevision(" \t\r\n", ContentHash.of(" \t\n"));
        var e = assertThrows(IllegalStateException.class,
            () -> EmptyModuleRemoval.requireEmptyRevision(" ", ContentHash.of("")));
        assertTrue(e.getMessage().contains("expectedHash does not match"));
    }

    @Test
    public void doesNotConsiderCommentsDirectivesOrCodeEmpty()
    {
        for (String text : new String[] { "// only a comment\n", "#Region Named\n#EndRegion\n",
            "Procedure Keep() Export\nEndProcedure\n" })
        {
            var e = assertThrows(IllegalArgumentException.class,
                () -> EmptyModuleRemoval.requireEmptyRevision(text, ContentHash.of(text)));
            assertTrue(e.getMessage().contains("only removes a blank module"));
        }
    }
}
