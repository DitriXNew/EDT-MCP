// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils.clientwindow;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

public class EdtPreLaunchUpdateTest
{
    @Test public void optOutNeverInvokesTheEdtUpdatePath() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        EdtPreLaunchUpdate.run(false, () -> { calls.incrementAndGet(); return Status.OK_STATUS; });
        assertEquals(0, calls.get());
        EdtPreLaunchUpdate.run(true, () -> { calls.incrementAndGet(); return Status.OK_STATUS; });
        assertEquals(1, calls.get());
    }

    @Test public void cancelledFailedOrMissingUpdateResultRefusesClientStart()
    {
        for (var result : new org.eclipse.core.runtime.IStatus[] {Status.CANCEL_STATUS, Status.error("Update failed"), null})
        {
            String message = assertThrows(IllegalStateException.class,
                () -> EdtPreLaunchUpdate.run(true, () -> result)).getMessage();
            assertTrue(message.contains("pre-launch preparation"));
            assertTrue(message.contains("edt_dialog"));
        }
    }

    @Test public void unavailableInjectorOwnerNamesTheMissingPlugin()
    {
        String error = assertThrows(IllegalStateException.class, () ->
            com.ditrix.edt.mcp.server.utils.PlatformServicesSupport.resolve("edt.mcp.missing.plugin",
                "MissingPlugin", Object.class)).getMessage();
        assertTrue(error.contains("edt.mcp.missing.plugin"));
        assertTrue(error.contains("enable"));
    }

    @Test public void fileAndClusterInfobasesLeaveStandaloneServerPortDialogsAlone()
    {
        var requested = com.ditrix.edt.mcp.server.utils.StandaloneServerPortConflictPolicy.REASSIGN;
        assertNull(EdtClientPreparation.portPolicyFor("8a45e00b-8904-4264-b998-a08ea5620001", requested));
        assertNull(EdtClientPreparation.portPolicyFor(null, requested));
        assertSame(requested, EdtClientPreparation.portPolicyFor("ServerApplication.fixture", requested));
        assertNull(EdtClientPreparation.portPolicyFor("ServerApplication.fixture", null));
    }
}
