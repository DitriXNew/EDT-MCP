// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils.clientwindow;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.Test;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinNT.HANDLE;

public class IsolatedClientLauncherTest
{
    @Test public void aWaylandHostCannotRedirectTheIsolatedClientToItsVisibleDisplay()
    {
        var environment = new java.util.HashMap<>(java.util.Map.of("DISPLAY", ":0", "WAYLAND_DISPLAY", "wayland-0",
            "QT_QPA_PLATFORM", "wayland", "GDK_BACKEND", "wayland", "XDG_SESSION_TYPE", "wayland"));
        LinuxIsolatedClient.configureDisplay(environment, ":91");
        assertEquals(":91", environment.get("DISPLAY"));
        assertFalse(environment.containsKey("WAYLAND_DISPLAY"));
        assertEquals("xcb", environment.get("QT_QPA_PLATFORM"));
        assertEquals("x11", environment.get("GDK_BACKEND"));
        assertEquals("x11", environment.get("XDG_SESSION_TYPE"));
    }

    @Test public void windowsAssignsJobBeforeProcessCreationAndCleansFailure()
    {
        var jobs = mock(WindowsIsolatedClient.Jobs.class);
        var desktops = mock(WindowsNative.Desktops.class);
        var processes = mock(WindowsIsolatedClient.Processes.class);
        HANDLE desktop = new HANDLE(Pointer.createConstant(11));
        HANDLE job = new HANDLE(Pointer.createConstant(12));
        when(desktops.CreateDesktopW(any(), any(), any(), anyInt(), anyInt(), any())).thenReturn(desktop);
        when(desktops.CloseDesktop(desktop)).thenReturn(true);
        when(jobs.CreateJobObjectW(null, null)).thenReturn(job);
        when(jobs.SetInformationJobObject(eq(job), eq(9), any(), anyInt())).thenAnswer(call -> {
            Pointer info = call.getArgument(2);
            assertEquals(0x2000, info.getInt(16));
            return true;
        });
        when(jobs.InitializeProcThreadAttributeList(any(), eq(1), eq(0), any())).thenAnswer(call -> {
            Pointer size = call.getArgument(3);
            if (Native.SIZE_T_SIZE == 8) { size.setLong(0, 128); }
            else { size.setInt(0, 128); }
            return call.getArgument(0) != null;
        });
        when(jobs.TerminateJobObject(job, 1)).thenReturn(true);
        when(jobs.QueryInformationJobObject(eq(job), eq(1), any(), eq(48), isNull())).thenAnswer(call -> {
            ((Pointer)call.getArgument(2)).clear(48); return true;
        });
        when(processes.CloseHandle(job)).thenReturn(true);
        String error = assertThrows(IllegalStateException.class,
            () -> WindowsIsolatedClient.start(List.of("client.exe"), jobs, desktops, processes)).getMessage();
        assertTrue(error.contains("PROC_THREAD_ATTRIBUTE_JOB_LIST"));
        verify(jobs).DeleteProcThreadAttributeList(any());
        verify(processes).CloseHandle(job);
        verify(desktops).CloseDesktop(desktop);
        verify(jobs, never()).CreateProcessW(any(), any(), any(), any(), anyBoolean(), anyInt(), any(), any(), any(), any());
    }

    @Test public void linuxAllocatedDisplayIsReadyBeforeClientAndBothTreesAreOwned() throws Exception
    {
        var commands = new PlatformWindowControllerTest.Commands();
        Process display = process(91);
        Process client = process(42);
        when(display.getInputStream()).thenReturn(new ByteArrayInputStream("91\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        AtomicBoolean ready = new AtomicBoolean();
        LinuxIsolatedClient launcher = new LinuxIsolatedClient(commands, new LinuxIsolatedClient.Backend()
        {
            @Override public Process startDisplay() { return display; }
            @Override public boolean acceptsConnections(int number) { assertEquals(91, number); ready.set(true); return true; }
            @Override public Process startClient(List<String> command, String address)
            {
                assertTrue(ready.get()); assertEquals(":91", address); assertEquals(List.of("client"), command); return client;
            }
        }, 1000);
        var owner = launcher.start(List.of("client"));
        assertEquals(42L, owner.pid());
        assertTrue(owner.alive());
        commands.absent = "xwininfo";
        assertTrue(assertThrows(IllegalArgumentException.class, owner::windows).getMessage().contains("xwininfo"));
        owner.close();
        owner.close();
        assertEquals(List.of(List.of("kill", "-KILL", "--", "-42")), commands.calls);
        verify(client, times(1)).destroyForcibly();
        verify(display, times(1)).destroyForcibly();
    }

    @Test public void failedLinuxDisplayNeverStartsClientAndIsCleaned() throws Exception
    {
        var commands = new PlatformWindowControllerTest.Commands();
        Process display = process(91);
        when(display.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        LinuxIsolatedClient.Backend backend = mock(LinuxIsolatedClient.Backend.class);
        when(backend.startDisplay()).thenReturn(display);
        var launcher = new LinuxIsolatedClient(commands, backend, 1000);
        assertThrows(Exception.class, () -> launcher.start(List.of("client")));
        verify(backend, never()).startClient(any(), any());
        verify(display).destroyForcibly();
    }

    @Test public void macHiddenLaunchUsesBundleArgumentsAndTracksActualClientPid() throws Exception
    {
        var commands = new PlatformWindowControllerTest.Commands();
        commands.answer = new WindowCommands.Answer(0, "{\"pid\":42,\"hidden\":true,\"active\":false}", "");
        ProcessHandle process = mock(ProcessHandle.class);
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.isAlive()).thenReturn(true);
        when(process.descendants()).thenAnswer(call -> Stream.of(child));
        when(process.onExit()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(process));
        var launcher = new MacIsolatedClient(commands, pid -> { assertEquals(42L, pid); return process; });
        var owner = launcher.start(List.of("/Applications/1cv8c.app/Contents/MacOS/1cv8c", "ENTERPRISE", "/P", "secret"));
        assertEquals(42L, owner.pid());
        String script = commands.calls.get(0).get(4);
        assertTrue(script.contains("NSWorkspaceLaunchWithoutActivation"));
        assertTrue(script.contains("NSWorkspaceLaunchAndHide"));
        assertTrue(script.contains("NSWorkspaceLaunchNewInstance"));
        assertTrue(script.contains("NSWorkspaceLaunchAsync"));
        assertTrue(script.contains("secret"));
        assertTrue(owner.limitation().contains("hidden"));
        owner.close();
        verify(child).destroyForcibly();
        verify(process).destroyForcibly();
    }

    @Test public void macRefusesAndTerminatesWhenHiddenStateIsNotConfirmed() throws Exception
    {
        var commands = new PlatformWindowControllerTest.Commands();
        commands.answer = new WindowCommands.Answer(0, "{\"pid\":42,\"hidden\":false,\"active\":true}", "");
        ProcessHandle process = mock(ProcessHandle.class);
        when(process.descendants()).thenAnswer(call -> Stream.empty());
        when(process.onExit()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(process));
        String error = assertThrows(IllegalStateException.class, () -> new MacIsolatedClient(commands, pid -> process)
            .start(List.of("/Applications/1cv8c.app/Contents/MacOS/1cv8c"))).getMessage();
        assertTrue(error.contains("did not confirm"));
        verify(process).destroyForcibly();
        commands.answer = new WindowCommands.Answer(1, "", "script with secret password");
        error = assertThrows(IllegalStateException.class, () -> new MacIsolatedClient(commands, pid -> process)
            .start(List.of("/Applications/1cv8c.app/Contents/MacOS/1cv8c", "/P", "secret"))).getMessage();
        assertFalse(error.contains("secret"));
    }

    private static Process process(long pid)
    {
        Process process = mock(Process.class);
        when(process.pid()).thenReturn(pid);
        when(process.isAlive()).thenReturn(true);
        when(process.descendants()).thenAnswer(call -> Stream.empty());
        return process;
    }
}
