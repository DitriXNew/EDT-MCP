// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.util.List;
import java.util.Set;

/** Windows state changes with a read-back, behind an injectable native boundary. */
public final class WindowsWindowController implements WindowController
{
    public interface Api
    {
        List<Screen> screens();
        List<Window> windows(Set<Long> pids);
        void show(String id, int command);
        void position(String id, Screen screen);
        boolean minimized(String id);
        boolean maximizedOn(String id, Screen screen);
    }

    private final Api api;
    public WindowsWindowController(Api api) { this.api = api; }
    @Override public List<Screen> screens() { return api.screens(); }
    @Override public List<Window> windows(Set<Long> pids) { return api.windows(pids); }
    @Override public boolean adjust(Window window, WindowRequest.Mode mode, Screen screen)
    {
        if (mode == WindowRequest.Mode.MINIMIZED)
        {
            api.show(window.id(), 7); // SW_SHOWMINNOACTIVE
            return api.minimized(window.id());
        }
        api.show(window.id(), 9); // SW_RESTORE before moving an already-maximized window
        api.position(window.id(), screen);
        api.show(window.id(), 3); // SW_MAXIMIZE
        return api.maximizedOn(window.id(), screen);
    }
}
