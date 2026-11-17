// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils.clientwindow;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import com.ditrix.edt.mcp.server.utils.ExternalProcess;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;

/** These tests acquire real Win32 objects and always clean up their own process tree. */
public class WindowsClientNativeTest
{
    @Test(timeout = 30000) public void realDesktopJobAndAtomicProcessAreClosed() throws Exception
    {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        WindowsIsolatedClient client = WindowsIsolatedClient.start(List.of(
            System.getenv("SystemRoot") + "\\System32\\cmd.exe", "/c", "ping", "-n", "30", "127.0.0.1"));
        try
        {
            assertTrue(client.pid() > 0);
            assertTrue(client.alive());
            assertNotNull(client.windows());
        }
        finally { client.close(); }
        assertFalse(client.alive());
        assertFalse(ProcessHandle.of(client.pid()).map(ProcessHandle::isAlive).orElse(false));
        client.close();
    }

    @Test(timeout = 30000) public void realWindowMinimizesWithoutActivation() throws Exception
    {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        String script = "Add-Type -AssemblyName System.Windows.Forms; "
            + "Add-Type -ReferencedAssemblies System.Windows.Forms -TypeDefinition '"
            + "public class QuietWindow : System.Windows.Forms.Form { protected override bool ShowWithoutActivation { get { return true; } } }'; "
            + "$form = New-Object QuietWindow; $form.Text = 'EDT-MCP window-mode test'; [System.Windows.Forms.Application]::Run($form)";
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try
        {
            WindowsNative api = new WindowsNative();
            long deadline = System.nanoTime() + 15_000_000_000L;
            List<WindowController.Window> windows;
            do
            {
                windows = api.windows(Set.of(process.pid()));
                if (!windows.isEmpty()) { break; }
                Thread.sleep(100);
            }
            while (System.nanoTime() < deadline && process.isAlive());
            assertFalse("The fixture did not create its WinForms window", windows.isEmpty());
            var window = windows.stream().filter(w -> w.title().equals("EDT-MCP window-mode test")).findFirst().orElseThrow();
            var foreground = User32.INSTANCE.GetForegroundWindow();
            assertTrue(new WindowsWindowController(api).adjust(window, WindowRequest.Mode.MINIMIZED, null));
            var after = User32.INSTANCE.GetForegroundWindow();
            assertEquals(Pointer.nativeValue(foreground == null ? null : foreground.getPointer()),
                Pointer.nativeValue(after == null ? null : after.getPointer()));
        }
        finally { ExternalProcess.destroyTree(process); }
    }
}
