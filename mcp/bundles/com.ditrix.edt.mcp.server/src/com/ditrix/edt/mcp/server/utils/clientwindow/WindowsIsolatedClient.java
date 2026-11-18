// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.WinBase.PROCESS_INFORMATION;
import com.sun.jna.platform.win32.WinBase.STARTUPINFO;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/** A private desktop and kill-on-close job, assigned atomically by STARTUPINFOEX. */
public final class WindowsIsolatedClient implements IsolatedClientLauncher.OwnedClient
{
    public interface Jobs extends StdCallLibrary
    {
        HANDLE CreateJobObjectW(Pointer security, WString name);
        boolean SetInformationJobObject(HANDLE job, int kind, Pointer value, int size);
        boolean QueryInformationJobObject(HANDLE job, int kind, Pointer value, int size, Pointer returned);
        boolean TerminateJobObject(HANDLE job, int exitCode);
        boolean InitializeProcThreadAttributeList(Pointer attributes, int count, int flags, Pointer size);
        boolean UpdateProcThreadAttribute(Pointer attributes, int flags, ULONG_PTR attribute,
            Pointer value, SIZE_T size, Pointer previous, Pointer returned);
        void DeleteProcThreadAttributeList(Pointer attributes);
        boolean CreateProcessW(WString application, char[] command, Pointer processSecurity,
            Pointer threadSecurity, boolean inherit, int flags, Pointer environment, WString directory,
            StartupInfoEx startup, PROCESS_INFORMATION process);
    }

    public interface Processes extends StdCallLibrary
    {
        boolean CloseHandle(HANDLE handle);
        int WaitForSingleObject(HANDLE handle, int milliseconds);
        boolean GetExitCodeProcess(HANDLE handle, IntByReference code);
    }

    private static final class NativeLibraries
    {
        private static final Jobs JOBS = Native.load("kernel32", Jobs.class, W32APIOptions.UNICODE_OPTIONS);
        private static final Processes PROCESSES = Native.load("kernel32", Processes.class, W32APIOptions.UNICODE_OPTIONS);
    }

    @Structure.FieldOrder({"startup", "attributes"})
    public static class StartupInfoEx extends Structure
    {
        public STARTUPINFO startup = new STARTUPINFO();
        public Pointer attributes;
    }

    @Structure.FieldOrder({"processTime", "jobTime", "flags", "minWorkingSet", "maxWorkingSet",
        "activeProcessLimit", "affinity", "priorityClass", "schedulingClass"})
    public static class BasicLimits extends Structure
    {
        public long processTime, jobTime;
        public int flags;
        public SIZE_T minWorkingSet = new SIZE_T(), maxWorkingSet = new SIZE_T();
        public int activeProcessLimit;
        public ULONG_PTR affinity = new ULONG_PTR();
        public int priorityClass, schedulingClass;
    }

    @Structure.FieldOrder({"basic", "ioCounters", "processMemory", "jobMemory", "peakProcessMemory", "peakJobMemory"})
    public static class ExtendedLimits extends Structure
    {
        public BasicLimits basic = new BasicLimits();
        public long[] ioCounters = new long[6];
        public SIZE_T processMemory = new SIZE_T(), jobMemory = new SIZE_T();
        public SIZE_T peakProcessMemory = new SIZE_T(), peakJobMemory = new SIZE_T();
    }

    private final Jobs jobs;
    private final WindowsNative.Desktops desktops;
    private final Processes kernel;
    private HANDLE desktop;
    private HANDLE job;
    private HANDLE process;
    private long pid;
    private int exitCode = 1;
    /** Guarded by this; true while one thread runs {@link #release()}. */
    private boolean closing;

    private WindowsIsolatedClient(Jobs jobs, WindowsNative.Desktops desktops, Processes kernel)
    {
        this.jobs = jobs; this.desktops = desktops; this.kernel = kernel;
    }

    public static WindowsIsolatedClient start(List<String> command)
    {
        return start(command, NativeLibraries.JOBS, WindowsNative.desktops(), NativeLibraries.PROCESSES);
    }

    static WindowsIsolatedClient start(List<String> command, Jobs jobs, WindowsNative.Desktops desktops, Processes kernel)
    {
        WindowsIsolatedClient owner = new WindowsIsolatedClient(jobs, desktops, kernel);
        try
        {
            String name = "EDT_MCP_" + UUID.randomUUID().toString().replace("-", "");
            owner.desktop = desktops.CreateDesktopW(
                new WString(name), null, null, 0, 0x01ff, null);
            if (owner.desktop == null) { throw WindowsNative.failure("CreateDesktopW"); }
            owner.job = jobs.CreateJobObjectW(null, null);
            if (owner.job == null) { throw WindowsNative.failure("CreateJobObjectW"); }
            ExtendedLimits limits = new ExtendedLimits();
            limits.basic.flags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            limits.write();
            if (!jobs.SetInformationJobObject(owner.job, 9, limits.getPointer(), limits.size()))
            {
                throw WindowsNative.failure("SetInformationJobObject");
            }
            owner.create(command, "WinSta0\\" + name);
            return owner;
        }
        catch (RuntimeException | Error e)
        {
            try { owner.close(); }
            catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private void create(List<String> command, String desktopName)
    {
        try (Memory size = new Memory(Native.SIZE_T_SIZE))
        {
            size.clear();
            jobs.InitializeProcThreadAttributeList(null, 1, 0, size);
            long bytes = Native.SIZE_T_SIZE == 8 ? size.getLong(0) : Integer.toUnsignedLong(size.getInt(0));
            if (bytes == 0) { throw WindowsNative.failure("InitializeProcThreadAttributeList size"); }
            try (Memory attributes = new Memory(bytes); Memory jobHandles = new Memory(Native.POINTER_SIZE))
            {
                if (!jobs.InitializeProcThreadAttributeList(attributes, 1, 0, size))
                {
                    throw WindowsNative.failure("InitializeProcThreadAttributeList");
                }
                try
                {
                    jobHandles.setPointer(0, job.getPointer());
                    if (!jobs.UpdateProcThreadAttribute(attributes, 0, new ULONG_PTR(0x0002000d),
                        jobHandles, new SIZE_T(Native.POINTER_SIZE), null, null))
                    {
                        throw WindowsNative.failure("PROC_THREAD_ATTRIBUTE_JOB_LIST (requires Windows 10 or newer)");
                    }
                    StartupInfoEx startup = new StartupInfoEx();
                    startup.startup.cb = new DWORD(startup.size());
                    startup.startup.lpDesktop = desktopName;
                    startup.attributes = attributes;
                    PROCESS_INFORMATION info = new PROCESS_INFORMATION();
                    char[] mutable = Native.toCharArray(commandLine(command));
                    if (!jobs.CreateProcessW(new WString(command.get(0)), mutable, null, null, false,
                        0x00080000 | 0x08000000, null, null, startup, info))
                    {
                        throw WindowsNative.failure("CreateProcessW on the isolated desktop");
                    }
                    process = info.hProcess;
                    pid = info.dwProcessId.longValue();
                    kernel.CloseHandle(info.hThread);
                }
                finally { jobs.DeleteProcThreadAttributeList(attributes); }
            }
        }
    }

    /** Windows argv quoting, including empty arguments and trailing backslashes. */
    public static String commandLine(List<String> arguments)
    {
        List<String> quoted = new ArrayList<>();
        for (String argument : arguments)
        {
            boolean needsQuotes = argument.isEmpty() || argument.indexOf(' ') >= 0 || argument.indexOf('\t') >= 0;
            StringBuilder value = new StringBuilder(needsQuotes ? "\"" : "");
            int backslashes = 0;
            for (char ch : argument.toCharArray())
            {
                if (ch == '\\') { backslashes++; continue; }
                value.append("\\".repeat(ch == '"' ? backslashes * 2 + 1 : backslashes));
                backslashes = 0;
                value.append(ch);
            }
            value.append("\\".repeat(needsQuotes ? backslashes * 2 : backslashes));
            if (needsQuotes) { value.append('"'); }
            quoted.add(value.toString());
        }
        return String.join(" ", quoted);
    }

    @Override public long pid() { return pid; }
    @Override public synchronized boolean alive()
    {
        if (process == null) { return false; }
        int result = kernel.WaitForSingleObject(process, 0);
        if (result == 258) { return true; }
        if (result != 0) { throw WindowsNative.failure("WaitForSingleObject"); }
        IntByReference code = new IntByReference();
        if (!kernel.GetExitCodeProcess(process, code)) { throw WindowsNative.failure("GetExitCodeProcess"); }
        exitCode = code.getValue();
        return false;
    }
    @Override public synchronized int exitCode()
    {
        if (alive()) { throw new IllegalThreadStateException("The isolated client is still running."); }
        return exitCode;
    }

    @Override public synchronized List<WindowController.Window> windows() throws Exception
    {
        if (desktop == null) { return List.of(); }
        HANDLE ownedDesktop = desktop;
        Set<Long> pids = new java.util.HashSet<>();
        pids.add(pid);
        ProcessHandle.of(pid).ifPresent(handle -> handle.descendants().forEach(child -> pids.add(child.pid())));
        FutureTask<List<WindowController.Window>> enumeration = new FutureTask<>(() -> {
            if (!desktops.SetThreadDesktop(ownedDesktop))
            {
                throw WindowsNative.failure("SetThreadDesktop");
            }
            List<WindowController.Window> result = new ArrayList<>();
            java.util.concurrent.atomic.AtomicReference<RuntimeException> callbackFailure = new java.util.concurrent.atomic.AtomicReference<>();
            Native.setLastError(0);
            boolean enumerated = desktops.EnumDesktopWindows(ownedDesktop, (hwnd, data) -> {
                try { WindowsNative.addWindow(result, hwnd, pids); return true; }
                catch (RuntimeException e) { callbackFailure.set(e); return false; }
            }, null);
            if (callbackFailure.get() != null) { throw callbackFailure.get(); }
            if (!enumerated && Native.getLastError() != 0) { throw WindowsNative.failure("EnumDesktopWindows"); }
            return result;
        });
        Thread worker = new Thread(enumeration, "EDT-MCP isolated desktop windows");
        worker.setDaemon(true);
        worker.start();
        try { return enumeration.get(3, TimeUnit.SECONDS); }
        finally { worker.join(1000); }
    }

    /**
     * Waiting for the job's processes to end releases this client's monitor ({@code wait}, not a sleep), so
     * {@link #alive()} or {@link #windows()} from another thread is not held for the whole termination. The
     * {@code closing} flag keeps teardown exclusive: a second {@code close()} waits for the first instead of
     * terminating the job again or closing handles the first is still polling.
     */
    @Override public synchronized void close()
    {
        while (closing)
        {
            try { wait(); }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while another thread was closing the isolated Windows client.", e);
            }
        }
        closing = true;
        try { release(); }
        finally
        {
            closing = false;
            notifyAll();
        }
    }

    /** Synchronized on its own: the {@code wait(20)} below needs this client's monitor, whoever calls it. */
    private synchronized void release()
    {
        RuntimeException failure = null;
        if (job != null)
        {
            try
            {
                if (!jobs.TerminateJobObject(job, 1)) { throw WindowsNative.failure("TerminateJobObject"); }
                try (Memory accounting = new Memory(48))
                {
                    long deadline = System.nanoTime() + 5_000_000_000L;
                    while (true)
                    {
                        if (!jobs.QueryInformationJobObject(job, 1, accounting, 48, null))
                        {
                            throw WindowsNative.failure("QueryInformationJobObject");
                        }
                        if (accounting.getInt(40) == 0) { break; }
                        if (System.nanoTime() >= deadline)
                        {
                            throw new IllegalStateException("The isolated Windows job still has active processes; retry terminate_launch.");
                        }
                        try { wait(20); }
                        catch (InterruptedException e)
                        {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while terminating the isolated Windows job.", e);
                        }
                    }
                }
            }
            catch (RuntimeException e) { failure = e; }
            // Kill-on-close remains the final barrier even when accounting or explicit termination fails.
            if (kernel.CloseHandle(job)) { job = null; }
            else { failure = combine(failure, WindowsNative.failure("CloseHandle(job)")); }
        }
        if (process != null)
        {
            if (kernel.WaitForSingleObject(process, 5000) == 0)
            {
                IntByReference code = new IntByReference();
                if (kernel.GetExitCodeProcess(process, code)) { exitCode = code.getValue(); }
            }
            if (kernel.CloseHandle(process)) { process = null; }
            else { failure = combine(failure, WindowsNative.failure("CloseHandle(process)")); }
        }
        if (desktop != null)
        {
            if (desktops.CloseDesktop(desktop)) { desktop = null; }
            else { failure = combine(failure, WindowsNative.failure("CloseDesktop")); }
        }
        if (failure != null) { throw failure; }
    }

    private static RuntimeException combine(RuntimeException first, RuntimeException next)
    {
        if (first == null) { return next; }
        first.addSuppressed(next);
        return first;
    }
}
