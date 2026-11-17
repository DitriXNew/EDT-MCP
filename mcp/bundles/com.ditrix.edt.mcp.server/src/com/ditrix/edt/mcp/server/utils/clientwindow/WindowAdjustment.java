// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bounded polling also catches a main window replacing the client's splash. */
public final class WindowAdjustment
{
    public record Outcome(boolean applied, String reason) { }
    @FunctionalInterface public interface Pause { void sleep(long millis) throws InterruptedException; }

    private WindowAdjustment() { }

    public static Outcome apply(WindowController controller, WindowRequest request,
        WindowController.Screen screen, Supplier<Set<Long>> pids, long timeoutMillis)
    {
        return apply(controller, request, screen, pids, timeoutMillis, System::nanoTime, Thread::sleep);
    }

    static Outcome apply(WindowController controller, WindowRequest request,
        WindowController.Screen screen, Supplier<Set<Long>> pids, long timeoutMillis,
        LongSupplier clock, Pause pause)
    {
        long deadline = clock.getAsLong() + timeoutMillis * 1_000_000;
        Set<String> adjusted = new HashSet<>();
        boolean reapplied = false;
        try
        {
            do
            {
                List<WindowController.Window> windows = controller.windows(pids.get());
                for (WindowController.Window window : windows)
                {
                    if (adjusted.add(window.id()))
                    {
                        // A new window may still be transitioning; the final reapply verifies its state.
                        controller.adjust(window, request.mode(), screen);
                    }
                }
                long remaining = deadline - clock.getAsLong();
                if (remaining <= 500_000_000 && !windows.isEmpty())
                {
                    // Reapply once, including when an OS reuses the splash window's identifier.
                    reapplied = true;
                    for (WindowController.Window window : windows)
                    {
                        if (!controller.adjust(window, request.mode(), screen))
                        {
                            return new Outcome(false, "The client's final window did not keep the requested state.");
                        }
                    }
                }
                if (remaining <= 0 || reapplied) { break; }
                pause.sleep(200);
            }
            while (true);
            return reapplied ? new Outcome(true, null)
                : new Outcome(false, "No client window was found before the deadline; check debug_status and the client process.");
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return new Outcome(false, "Window adjustment was interrupted; the EDT launch is still tracked.");
        }
        catch (Exception | LinkageError e)
        {
            return new Outcome(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
