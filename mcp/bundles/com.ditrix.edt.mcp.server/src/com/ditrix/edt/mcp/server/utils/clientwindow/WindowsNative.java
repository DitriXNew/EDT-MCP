// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/** Loaded only on Windows; desktop enumeration always runs on a disposable thread. */
public final class WindowsNative implements WindowsWindowController.Api
{
    public interface Desktops extends StdCallLibrary
    {
        HANDLE CreateDesktopW(WString name, Pointer device, Pointer mode, int flags, int access, Pointer security);
        boolean CloseDesktop(HANDLE desktop);
        boolean SetThreadDesktop(HANDLE desktop);
        boolean EnumDesktopWindows(HANDLE desktop, WinUser.WNDENUMPROC callback, Pointer data);
        boolean EnumWindows(WinUser.WNDENUMPROC callback, Pointer data);
        boolean IsIconic(HWND window);
    }

    private static final class DesktopLibrary
    {
        private static final Desktops INSTANCE = Native.load("user32", Desktops.class);
    }
    static Desktops desktops() { return DesktopLibrary.INSTANCE; }

    static HWND hwnd(String id) { return new HWND(Pointer.createConstant(Long.parseUnsignedLong(id))); }
    static IllegalStateException failure(String operation)
    {
        return new IllegalStateException(operation + " failed (Windows error " + Native.getLastError()
            + "); use an interactive Windows session and verify the runtime permissions.");
    }

    @Override public List<WindowController.Screen> screens()
    {
        List<WindowController.Screen> result = new ArrayList<>();
        if (!User32.INSTANCE.EnumDisplayMonitors(null, null, (monitor, dc, rect, data) -> {
            WinUser.MONITORINFO info = new WinUser.MONITORINFO();
            if (!User32.INSTANCE.GetMonitorInfo(monitor, info).booleanValue()) { return 0; }
            RECT bounds = info.rcMonitor;
            result.add(new WindowController.Screen(bounds.left, bounds.top, bounds.right - bounds.left,
                bounds.bottom - bounds.top, (info.dwFlags & 1) != 0));
            return 1;
        }, new LPARAM(0)).booleanValue()) { throw failure("EnumDisplayMonitors"); }
        return result;
    }

    @Override public List<WindowController.Window> windows(Set<Long> pids)
    {
        List<WindowController.Window> result = new ArrayList<>();
        Desktops api = desktops();
        java.util.concurrent.atomic.AtomicReference<RuntimeException> callbackFailure = new java.util.concurrent.atomic.AtomicReference<>();
        Native.setLastError(0);
        boolean enumerated = api.EnumWindows((window, data) -> {
            try { addWindow(result, window, pids); return true; }
            catch (RuntimeException e) { callbackFailure.set(e); return false; }
        }, null);
        if (callbackFailure.get() != null) { throw callbackFailure.get(); }
        if (!enumerated && Native.getLastError() != 0) { throw failure("EnumWindows"); }
        return result;
    }

    static void addWindow(List<WindowController.Window> result, HWND hwnd, Set<Long> pids)
    {
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
        long processId = Integer.toUnsignedLong(pid.getValue());
        if (!pids.contains(processId) || !User32.INSTANCE.IsWindowVisible(hwnd)) { return; }
        char[] title = new char[2048];
        char[] windowClass = new char[256];
        User32.INSTANCE.GetWindowText(hwnd, title, title.length);
        User32.INSTANCE.GetClassName(hwnd, windowClass, windowClass.length);
        result.add(new WindowController.Window(Long.toUnsignedString(Pointer.nativeValue(hwnd.getPointer())),
            processId, Native.toString(title), Native.toString(windowClass)));
    }

    @Override public void show(String id, int command) { User32.INSTANCE.ShowWindow(hwnd(id), command); }
    @Override public void position(String id, WindowController.Screen screen)
    {
        if (!User32.INSTANCE.SetWindowPos(hwnd(id), null, screen.x(), screen.y(), screen.width(), screen.height(),
            0x0010 | 0x0004)) { throw failure("SetWindowPos"); } // SWP_NOACTIVATE | SWP_NOZORDER
    }
    @Override public boolean minimized(String id) { return desktops().IsIconic(hwnd(id)); }
    @Override public boolean maximizedOn(String id, WindowController.Screen screen)
    {
        WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
        RECT rect = new RECT();
        return User32.INSTANCE.GetWindowPlacement(hwnd(id), placement).booleanValue() && placement.showCmd == 3
            && User32.INSTANCE.GetWindowRect(hwnd(id), rect)
            && (rect.left + rect.right) / 2 >= screen.x()
            && (rect.left + rect.right) / 2 < screen.x() + screen.width()
            && (rect.top + rect.bottom) / 2 >= screen.y()
            && (rect.top + rect.bottom) / 2 < screen.y() + screen.height();
    }
}
