// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils.clientwindow;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import org.junit.Test;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinBase.PROCESS_INFORMATION;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;

/** Teardown of the isolated Windows client: waiting releases the monitor, and teardown runs once. */
public class WindowsIsolatedClientCloseTest
{
    private static final int POLL_CAP = 100;

    private final WindowsIsolatedClient.Jobs jobs = mock(WindowsIsolatedClient.Jobs.class);
    private final WindowsNative.Desktops desktops = mock(WindowsNative.Desktops.class);
    private final WindowsIsolatedClient.Processes processes = mock(WindowsIsolatedClient.Processes.class);
    private final HANDLE desktop = new HANDLE(Pointer.createConstant(21));
    private final HANDLE job = new HANDLE(Pointer.createConstant(22));
    private final HANDLE process = new HANDLE(Pointer.createConstant(23));
    private final AtomicInteger polls = new AtomicInteger();

    /** A started client whose job reports active processes while {@code activeWhile} answers non-zero. */
    private WindowsIsolatedClient start(IntSupplier activeWhile)
    {
        when(desktops.CreateDesktopW(any(), any(), any(), anyInt(), anyInt(), any())).thenReturn(desktop);
        when(desktops.CloseDesktop(desktop)).thenReturn(true);
        when(jobs.CreateJobObjectW(null, null)).thenReturn(job);
        when(jobs.SetInformationJobObject(eq(job), eq(9), any(), anyInt())).thenReturn(true);
        when(jobs.InitializeProcThreadAttributeList(any(), eq(1), eq(0), any())).thenAnswer(call -> {
            Pointer size = call.getArgument(3);
            if (Native.SIZE_T_SIZE == 8) { size.setLong(0, 128); }
            else { size.setInt(0, 128); }
            return call.getArgument(0) != null;
        });
        when(jobs.UpdateProcThreadAttribute(any(), anyInt(), any(), any(), any(), any(), any())).thenReturn(true);
        when(jobs.CreateProcessW(any(), any(), any(), any(), anyBoolean(), anyInt(), any(), any(), any(), any()))
            .thenAnswer(call -> {
                PROCESS_INFORMATION info = call.getArgument(9);
                info.hProcess = process;
                info.dwProcessId = new DWORD(4242);
                return true;
            });
        when(jobs.TerminateJobObject(job, 1)).thenReturn(true);
        when(jobs.QueryInformationJobObject(eq(job), eq(1), any(), eq(48), isNull())).thenAnswer(call -> {
            polls.incrementAndGet();
            Pointer accounting = call.getArgument(2);
            accounting.clear(48);
            accounting.setInt(40, activeWhile.getAsInt());
            return true;
        });
        when(processes.CloseHandle(any())).thenReturn(true);
        when(processes.WaitForSingleObject(process, 0)).thenReturn(258);
        when(processes.WaitForSingleObject(process, 5000)).thenReturn(0);
        when(processes.GetExitCodeProcess(eq(process), any())).thenReturn(true);
        return WindowsIsolatedClient.start(List.of("client.exe"), jobs, desktops, processes);
    }

    @Test(timeout = 20000)
    public void aliveIsAnsweredWhileCloseWaitsForTheJobToEmpty() throws Exception
    {
        AtomicReference<WindowsIsolatedClient> client = new AtomicReference<>();
        AtomicReference<Thread> prober = new AtomicReference<>();
        AtomicBoolean answered = new AtomicBoolean();
        AtomicBoolean answeredBeforeTheJobEmptied = new AtomicBoolean();
        client.set(start(() -> {
            if (prober.get() == null)
            {
                Thread thread = new Thread(() -> { if (client.get().alive()) { answered.set(true); } });
                thread.setDaemon(true);
                prober.set(thread);
                thread.start();
            }
            if (answered.get() || polls.get() > POLL_CAP)
            {
                answeredBeforeTheJobEmptied.set(answered.get());
                return 0;
            }
            return 1;
        }));

        client.get().close();

        assertTrue("alive() must not wait for the whole termination of the job", answeredBeforeTheJobEmptied.get());
        verify(processes).CloseHandle(job);
        verify(desktops).CloseDesktop(desktop);
    }

    @Test(timeout = 20000)
    public void aSecondCloseWaitsForTheFirstAndTearsNothingDownTwice() throws Exception
    {
        AtomicReference<WindowsIsolatedClient> client = new AtomicReference<>();
        AtomicReference<Thread> second = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        client.set(start(() -> {
            if (second.get() == null)
            {
                Thread thread = new Thread(() -> {
                    try { client.get().close(); }
                    catch (Throwable t) { secondFailure.set(t); }
                });
                thread.setDaemon(true);
                second.set(thread);
                thread.start();
            }
            Thread.State state = second.get().getState();
            boolean parked = state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
            return parked && polls.get() > 5 || polls.get() > POLL_CAP ? 0 : 1;
        }));

        client.get().close();
        second.get().join(5000);

        assertFalse("the second close() must return", second.get().isAlive());
        assertNull(secondFailure.get());
        verify(jobs, times(1)).TerminateJobObject(job, 1);
        verify(processes, times(1)).CloseHandle(job);
        verify(processes, times(1)).CloseHandle(process);
        verify(desktops, times(1)).CloseDesktop(desktop);
    }
}
