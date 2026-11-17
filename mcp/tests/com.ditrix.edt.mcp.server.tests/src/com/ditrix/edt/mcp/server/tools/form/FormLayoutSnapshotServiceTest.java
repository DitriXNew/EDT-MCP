// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.tools.form;
import static org.junit.Assert.*;
import org.junit.Test;

public class FormLayoutSnapshotServiceTest
{
    public static class BrokenMember
    {
        public Object getBounds() { throw new IllegalStateException("native failure"); }
        public Object getModel(Object value) { throw new IllegalStateException("native failure"); }
    }
    @Test public void inaccessibleMembersRemainUnknownAndWarnOncePerMember() throws Exception
    {
        var service = new FormLayoutSnapshotService();
        var noArg = FormLayoutSnapshotService.class.getDeclaredMethod("invokeNoArg", Object.class, String.class);
        var oneArg = FormLayoutSnapshotService.class.getDeclaredMethod("invokeOneArg", Object.class, String.class, Object.class);
        noArg.setAccessible(true); oneArg.setAccessible(true);
        java.util.List<org.eclipse.core.runtime.IStatus> logs = new java.util.ArrayList<>();
        org.eclipse.core.runtime.ILogListener listener = (status, plugin) -> {
            if (status.getMessage().startsWith("Cannot read form layout member")) logs.add(status);
        };
        org.eclipse.core.runtime.Platform.addLogListener(listener);
        try
        {
            for (int i = 0; i < 2; i++)
            {
                assertNull(noArg.invoke(service, new BrokenMember(), "getBounds"));
                assertNull(oneArg.invoke(service, new BrokenMember(), "getModel", new Object()));
            }
            assertEquals(2, logs.size());
            assertTrue(logs.stream().allMatch(log -> log.getSeverity() == org.eclipse.core.runtime.IStatus.WARNING
                && log.getMessage().contains("unknown")));
        }
        finally { org.eclipse.core.runtime.Platform.removeLogListener(listener); }
    }

    @Test public void absentOptionalMembersStayAbsentAndRequiredMembersWarn() throws Exception
    {
        var service = new FormLayoutSnapshotService();
        var describe = FormLayoutSnapshotService.class.getDeclaredMethod("describeInspectableValue", Object.class);
        describe.setAccessible(true);
        assertNotNull(describe.invoke(service, new Object()));
        var failures = FormLayoutSnapshotService.class.getDeclaredField("reflectiveFailures");
        failures.setAccessible(true);
        assertTrue(((java.util.Set<?>)failures.get(service)).isEmpty());
        var required = FormLayoutSnapshotService.class.getDeclaredMethod("invokeNoArg", Object.class, String.class);
        required.setAccessible(true);
        assertNull(required.invoke(service, new Object(), "getBounds"));
        assertEquals(1, ((java.util.Set<?>)failures.get(service)).size());
    }
}
