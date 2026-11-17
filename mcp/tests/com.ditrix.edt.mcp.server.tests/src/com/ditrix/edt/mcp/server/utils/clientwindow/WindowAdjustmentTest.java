// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils.clientwindow;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class WindowAdjustmentTest
{
    @Test public void anInitialTransitionIsVerifiedByTheFinalReapply()
    {
        AtomicLong time = new AtomicLong();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        WindowController controller = new WindowController()
        {
            @Override public List<Screen> screens() { return List.of(); }
            @Override public List<Window> windows(Set<Long> pids) { return List.of(new Window("main", 42, "", "")); }
            @Override public boolean adjust(Window window, WindowRequest.Mode mode, Screen screen)
            {
                return calls.incrementAndGet() > 1;
            }
        };
        var result = WindowAdjustment.apply(controller, WindowRequest.parse("minimized", null), null,
            () -> Set.of(42L), 1000, time::get, millis -> time.addAndGet(millis * 1_000_000));
        assertTrue(result.applied());
        assertEquals(2, calls.get());
    }

    @Test public void splashReplacementAndFinalReapply()
    {
        AtomicLong time = new AtomicLong();
        List<String> adjusted = new ArrayList<>();
        WindowController controller = new WindowController()
        {
            @Override public List<Screen> screens() { return List.of(); }
            @Override public List<Window> windows(Set<Long> pids)
            {
                assertEquals(Set.of(42L), pids);
                return List.of(new Window(time.get() < 400_000_000 ? "splash" : "main", 42, "title", "class"));
            }
            @Override public boolean adjust(Window window, WindowRequest.Mode mode, Screen screen)
            {
                adjusted.add(window.id()); return true;
            }
        };
        var result = WindowAdjustment.apply(controller, WindowRequest.parse("minimized", null), null,
            () -> Set.of(42L), 1200, time::get, millis -> time.addAndGet(millis * 1_000_000));
        assertTrue(result.applied());
        assertEquals(List.of("splash", "main", "main"), adjusted);
    }

    @Test public void noWindowAndNativeRefusalAreNotApplied()
    {
        AtomicLong time = new AtomicLong();
        WindowController empty = new WindowController()
        {
            @Override public List<Screen> screens() { return List.of(); }
            @Override public List<Window> windows(Set<Long> pids) { return List.of(); }
            @Override public boolean adjust(Window window, WindowRequest.Mode mode, Screen screen) { return false; }
        };
        var result = WindowAdjustment.apply(empty, WindowRequest.parse("minimized", null), null,
            Set::of, 600, time::get, millis -> time.addAndGet(millis * 1_000_000));
        assertFalse(result.applied());
        assertTrue(result.reason().contains("deadline"));
        WindowController refusing = new WindowController()
        {
            @Override public List<Screen> screens() { return List.of(); }
            @Override public List<Window> windows(Set<Long> pids) { throw new IllegalStateException("Accessibility denied"); }
            @Override public boolean adjust(Window window, WindowRequest.Mode mode, Screen screen) { return false; }
        };
        result = WindowAdjustment.apply(refusing, WindowRequest.parse("minimized", null), null,
            Set::of, 600, time::get, millis -> time.addAndGet(millis * 1_000_000));
        assertFalse(result.applied());
        assertEquals("Accessibility denied", result.reason());
    }

    @Test public void finalStateRefusalIsReportedAsNotApplied()
    {
        AtomicLong time = new AtomicLong();
        WindowController controller = new WindowController()
        {
            @Override public List<Screen> screens() { return List.of(); }
            @Override public List<Window> windows(Set<Long> pids) { return List.of(new Window("main", 42, "", "")); }
            @Override public boolean adjust(Window window, WindowRequest.Mode mode, Screen screen) { return false; }
        };
        var result = WindowAdjustment.apply(controller, WindowRequest.parse("minimized", null), null,
            () -> Set.of(42L), 1000, time::get, millis -> time.addAndGet(millis * 1_000_000));
        assertFalse(result.applied());
        assertTrue(result.reason().contains("final window"));
    }

    @Test public void windowsShowCommandsAndReadback()
    {
        List<Integer> shows = new ArrayList<>();
        var monitor = new WindowController.Screen(1920, 0, 1920, 1080, false);
        WindowsWindowController controller = new WindowsWindowController(new WindowsWindowController.Api()
        {
            @Override public List<WindowController.Screen> screens() { return List.of(monitor); }
            @Override public List<WindowController.Window> windows(Set<Long> pids) { return List.of(); }
            @Override public void show(String id, int command) { shows.add(command); }
            @Override public void position(String id, WindowController.Screen target) { assertSame(monitor, target); }
            @Override public boolean minimized(String id) { return true; }
            @Override public boolean maximizedOn(String id, WindowController.Screen target) { return false; }
        });
        var window = new WindowController.Window("1", 42, "title", "class");
        assertTrue(controller.adjust(window, WindowRequest.Mode.MINIMIZED, null));
        assertFalse(controller.adjust(window, WindowRequest.Mode.OTHER_SCREEN, monitor));
        assertEquals(List.of(7, 9, 3), shows);
    }
}
