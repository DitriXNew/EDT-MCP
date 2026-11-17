// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;
import static org.junit.Assert.*;
import org.junit.Test;

public class WorkmateGatewayTest
{
    public static class Session { private final java.util.concurrent.atomic.AtomicBoolean isClosed = new java.util.concurrent.atomic.AtomicBoolean(); }
    public static class IncompatibleSession { }
    @Test public void closedStateIsObservedAndUnreadableStateRefusesReuse() throws Exception
    {
        var method = WorkmateGateway.class.getDeclaredMethod("isClosedSession", Object.class);
        method.setAccessible(true);
        var session = new Session();
        assertEquals(false, method.invoke(null, session));
        session.isClosed.set(true);
        assertEquals(true, method.invoke(null, session));
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> method.invoke(null, new IncompatibleSession()));
        assertTrue(failure.getCause() instanceof WorkmateGateway.GatewayException);
        assertTrue(failure.getCause().getMessage().contains("state is unknown"));
    }
}
