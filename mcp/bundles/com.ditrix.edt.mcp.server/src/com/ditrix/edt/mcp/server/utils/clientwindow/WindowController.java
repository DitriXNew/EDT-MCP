// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.util.List;
import java.util.Set;

/** OS operations are separate from polling and launch ownership for deterministic tests. */
public interface WindowController
{
    record Screen(int x, int y, int width, int height, boolean primary) { }
    record Window(String id, long pid, String title, String windowClass) { }

    List<Screen> screens() throws Exception;
    List<Window> windows(Set<Long> pids) throws Exception;
    boolean adjust(Window window, WindowRequest.Mode mode, Screen screen) throws Exception;

    static Screen selectScreen(List<Screen> screens, Integer index)
    {
        if (screens.size() < 2)
        {
            throw new IllegalArgumentException("otherScreen requires two monitors; connect another monitor or use minimized.");
        }
        if (index != null)
        {
            if (index < 1 || index > screens.size())
            {
                throw new IllegalArgumentException("screen " + index + " is out of range; use 1.." + screens.size() + ".");
            }
            return screens.get(index - 1);
        }
        return screens.stream().filter(screen -> !screen.primary()).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No non-primary monitor found; pass screen explicitly."));
    }
}
