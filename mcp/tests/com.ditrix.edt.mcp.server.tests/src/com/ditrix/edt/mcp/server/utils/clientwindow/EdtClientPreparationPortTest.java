// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import static org.junit.Assert.*;

import java.util.Optional;

import org.junit.Test;

/** An isolated debug launch passes EDT's local target manager a concrete port, chosen in EDT's own order. */
public class EdtClientPreparationPortTest
{
    @Test
    public void theConfiguredPortWins()
    {
        assertEquals(1561, EdtClientPreparation.localDebugPort(1561, () -> Optional.of("1550"), () -> 40000));
    }

    @Test
    public void theApplicationsStoredPortIsUsedWithoutAConfiguredOne()
    {
        assertEquals(1550, EdtClientPreparation.localDebugPort(-1, () -> Optional.of(" 1550 "), () -> 40000));
    }

    @Test
    public void aFreePortIsFoundWhenNothingIsStored()
    {
        assertEquals(40000, EdtClientPreparation.localDebugPort(-1, Optional::empty, () -> 40000));
        assertEquals(40000, EdtClientPreparation.localDebugPort(0, () -> Optional.of("not-a-port"), () -> 40000));
        assertEquals(40000, EdtClientPreparation.localDebugPort(-1, () -> Optional.of("-5"), () -> 40000));
    }
}
