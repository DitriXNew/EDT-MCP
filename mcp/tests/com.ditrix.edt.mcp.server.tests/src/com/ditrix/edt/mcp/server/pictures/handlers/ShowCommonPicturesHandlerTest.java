// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.pictures.handlers;

import static org.junit.Assert.*;
import com.ditrix.edt.mcp.server.utils.HeadlessReadFixture;
import org.junit.Test;

public class ShowCommonPicturesHandlerTest
{
    @Test public void initialLanguageSelectionRunsInsideTheBmBoundary() throws Exception
    {
        try (var fixture = new HeadlessReadFixture())
        {
            assertEquals("en", ShowCommonPicturesHandler.resolveLanguageCode(fixture.project(), fixture.config));
            assertEquals(1, fixture.reads);
            assertEquals(0, fixture.depth);
        }
    }
}
