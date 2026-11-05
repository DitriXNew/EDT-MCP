/*
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Observes SWT modal shells; only an explicit press may answer one. */
public class EdtDialogs
{
    private static final EdtDialogs INSTANCE = new EdtDialogs();
    private static final String HUMAN_KEY = EdtDialogs.class.getName() + ".humanOnly";
    private static final Object HUMAN_IDENTITY = new Object();
    private static final String HUMAN_REASON = "ask the user to answer it in EDT";
    private static final long UI_TIMEOUT_MS = 500;
    private final AtomicReference<Display> installing = new AtomicReference<>();
    private final Map<Shell, Entry> entries = new IdentityHashMap<>();
    private volatile List<JsonObject> snapshot = List.of();
    private volatile Display installedDisplay;
    private final java.util.function.Supplier<Display> displaySource;

    public EdtDialogs()
    {
        this(LaunchLifecycleUtils::workbenchDisplayOrNull);
    }

    public EdtDialogs(java.util.function.Supplier<Display> displaySource)
    {
        this.displaySource = displaySource;
    }

    private static final class Entry
    {
        final String id = UUID.randomUUID().toString();
        JsonObject data;
    }

    public static EdtDialogs getInstance()
    {
        return INSTANCE;
    }

    /** Called by human-only dialog owners before their shell becomes visible. */
    public static void protect(Shell shell)
    {
        shell.setData(HUMAN_KEY, HUMAN_IDENTITY);
    }

    public void ensureInstalled()
    {
        Display current = installedDisplay;
        if (current != null && !current.isDisposed())
        {
            return;
        }
        Display display = displaySource.get();
        Display pending = installing.get();
        if (pending != null && pending.isDisposed())
        {
            installing.compareAndSet(pending, null);
        }
        if (display == null || display.isDisposed() || !installing.compareAndSet(null, display))
        {
            return;
        }
        try
        {
            var dispatch = BoundedUiCall.dispatch(BoundedUiCall.executorOrNull(display), () -> {
                try
                {
                    install(display);
                }
                finally
                {
                    installing.compareAndSet(display, null);
                }
            }, 50, BoundedUiCall.UiThreadPolicy.RUN_INLINE);
            if (dispatch.outcome() == BoundedUiCall.Outcome.NO_UI
                || dispatch.outcome() == BoundedUiCall.Outcome.SUBMISSION_FAILED)
            {
                installing.compareAndSet(display, null);
            }
        }
        catch (RuntimeException e)
        {
            installing.compareAndSet(display, null);
        }
    }

    void install(Display display)
    {
        if (display.isDisposed() || installedDisplay == display)
        {
            return;
        }
        entries.clear();
        snapshot = List.of();
        for (int type : new int[] {SWT.Show, SWT.Activate, SWT.Hide, SWT.Dispose})
        {
            display.addFilter(type, event -> observe(display, event));
        }
        installedDisplay = display;
        sweep(display);
    }

    private void observe(Display display, Event event)
    {
        if (!(event.widget instanceof Shell))
        {
            return;
        }
        Shell shell = (Shell)event.widget;
        if (event.type == SWT.Hide || event.type == SWT.Dispose)
        {
            entries.remove(shell);
            publish();
            return;
        }
        // Show fires before visibility changes; read once inside the dialog's nested event loop.
        display.asyncExec(() -> capture(shell));
    }

    private void sweep(Display display)
    {
        entries.keySet().removeIf(shell -> !openModal(shell));
        for (Shell shell : display.getShells())
        {
            capture(shell);
        }
        publish();
    }

    private static boolean openModal(Shell shell)
    {
        return !shell.isDisposed() && shell.isVisible() && (shell.getStyle() & SWT.APPLICATION_MODAL) != 0;
    }

    private static boolean answerable(Shell shell, Map<String, List<Button>> buttons, boolean complete)
    {
        return shell.getData(HUMAN_KEY) == null
            && (LaunchUpdateDialogAutoConfirmer.isTargetTitle(shell.getText())
                || LaunchUpdateDialogAutoConfirmer.isConflictTitle(shell.getText())
                || LaunchUpdateDialogAutoConfirmer.isExclusiveLockQuestion(shell.getText(), buttons, complete));
    }

    private void capture(Shell shell)
    {
        if (!openModal(shell))
        {
            entries.remove(shell);
            publish();
            return;
        }
        Entry entry = entries.computeIfAbsent(shell, ignored -> new Entry());
        JsonObject data = new JsonObject();
        data.addProperty("dialogId", entry.id);
        data.addProperty("title", shell.getText());
        StringBuilder message = new StringBuilder();
        Map<String, List<Button>> buttons = new LinkedHashMap<>();
        boolean complete = readControls(shell, 0, new int[] {500}, message, buttons);
        data.addProperty("message", message.toString().strip());
        JsonArray labels = new JsonArray();
        buttons.keySet().forEach(labels::add);
        data.add("buttons", labels);
        boolean allowed = answerable(shell, buttons, complete);
        data.addProperty("answerable", allowed);
        if (!allowed)
        {
            data.addProperty("reason", HUMAN_REASON);
        }
        if (entry.data != null && !entry.data.equals(data))
        {
            entry = new Entry();
            entries.put(shell, entry);
            data.addProperty("dialogId", entry.id);
        }
        entry.data = data;
        publish();
    }

    private static boolean readControls(Control control, int depth, int[] budget, StringBuilder text,
        Map<String, List<Button>> buttons)
    {
        if (control.isDisposed())
        {
            return true;
        }
        if (depth > 6 || budget[0] <= 0)
        {
            return false;
        }
        budget[0]--;
        String value = null;
        if (control instanceof Button && (control.getStyle() & SWT.PUSH) != 0)
        {
            Button button = (Button)control;
            buttons.computeIfAbsent(button.getText(), ignored -> new ArrayList<>()).add(button);
        }
        else if (control instanceof Label)
        {
            value = ((Label)control).getText();
        }
        else if (control instanceof CLabel)
        {
            value = ((CLabel)control).getText();
        }
        else if (control instanceof Link)
        {
            value = ((Link)control).getText();
        }
        else if (control instanceof Text && (control.getStyle() & SWT.READ_ONLY) != 0
            && (control.getStyle() & SWT.PASSWORD) == 0)
        {
            value = ((Text)control).getText();
        }
        if (value != null && !value.isBlank() && text.length() < 8192)
        {
            text.append(value, 0, Math.min(value.length(), 8192 - text.length())).append('\n');
        }
        boolean complete = true;
        if (control instanceof Composite)
        {
            for (Control child : ((Composite)control).getChildren())
            {
                complete &= readControls(child, depth + 1, budget, text, buttons);
            }
        }
        return complete;
    }

    private void publish()
    {
        snapshot = entries.values().stream().filter(entry -> entry.data != null)
            .map(entry -> entry.data).sorted((a, b) -> a.get("dialogId").getAsString()
                .compareTo(b.get("dialogId").getAsString())).toList();
        synchronized (this)
        {
            notifyAll();
        }
    }

    public JsonArray cachedDialogs()
    {
        JsonArray result = new JsonArray();
        snapshot.forEach(data -> result.add(data.deepCopy()));
        return result;
    }

    public String notice()
    {
        List<JsonObject> current = snapshot;
        if (current.isEmpty())
        {
            return null;
        }
        String title = current.get(0).get("title").getAsString();
        title = title.replace('\r', ' ').replace('\n', ' ');
        return "EDT dialog open: \"" + title.substring(0, Math.min(title.length(), 160))
            + "\". Call edt_dialog with action=list.";
    }

    public String list()
    {
        ensureInstalled();
        Display display = displaySource.get();
        return onUi(BoundedUiCall.executorOrNull(display), active -> {
            sweep(display);
            return ToolResult.success().put("dialogs", cachedDialogs()).toJson();
        }, UI_TIMEOUT_MS);
    }

    public String press(String dialogId, String label)
    {
        AtomicBoolean committing = new AtomicBoolean();
        String result = onUi(BoundedUiCall.executorOrNull(displaySource.get()),
            active -> pressOnUi(dialogId, label, active, committing), UI_TIMEOUT_MS);
        return committing.get() ? ToolResult.markErrorWithUnknownMutationOutcome(result) : result;
    }

    private String pressOnUi(String dialogId, String label, java.util.function.BooleanSupplier active,
        AtomicBoolean committing)
    {
        Shell shell = entries.entrySet().stream().filter(e -> e.getValue().id.equals(dialogId))
            .map(Map.Entry::getKey).findFirst().orElse(null);
        if (shell == null || !openModal(shell))
        {
            return ToolResult.error("Dialog '" + dialogId
                + "' has closed or been replaced. Call edt_dialog with action=list again.").toJson();
        }
        capture(shell);
        if (!dialogId.equals(entries.get(shell).id))
        {
            return ToolResult.error("Dialog '" + dialogId
                + "' has changed or been replaced. Call edt_dialog with action=list again.").toJson();
        }
        Map<String, List<Button>> buttons = new LinkedHashMap<>();
        boolean complete = readControls(shell, 0, new int[] {500}, new StringBuilder(), buttons);
        if (!answerable(shell, buttons, complete))
        {
            return ToolResult.error("Dialog '" + dialogId + "': " + HUMAN_REASON + ".").toJson();
        }
        List<Button> matches = buttons.get(label);
        if (matches == null || matches.size() != 1 || !matches.get(0).isEnabled()
            || !matches.get(0).isVisible())
        {
            return ToolResult.error("Cannot press button '" + label + "'. Available labels: "
                + buttons.keySet() + ". Call edt_dialog with action=list and use an exact enabled label.").toJson();
        }
        committing.set(true);
        if (!active.getAsBoolean())
        {
            return ToolResult.error("EDT UI deadline elapsed; list again before pressing.").toJson();
        }
        entries.remove(shell);
        publish();
        Event event = new Event();
        event.widget = matches.get(0);
        matches.get(0).notifyListeners(SWT.Selection, event);
        return ToolResult.success().put("dialogId", dialogId).put("button", label)
            .put("message", "Button pressed; list again to inspect any subsequent dialog.").toJson();
    }

    static String onUi(BoundedUiCall.UiExecutor executor, BoundedUiCall.Operation<String> work,
        long timeoutMs)
    {
        if (executor == null)
        {
            return ToolResult.error("EDT UI is unavailable. Open EDT and call edt_dialog again.").toJson();
        }
        try
        {
            return BoundedUiCall.call(executor, active -> active.getAsBoolean() ? work.run(active)
                : ToolResult.error("EDT UI deadline elapsed; list again before pressing.").toJson(), timeoutMs);
        }
        catch (Exception e)
        {
            return ToolResult.error("EDT UI did not complete edt_dialog: " + e.getClass().getSimpleName()
                + ". Check EDT and call edt_dialog with action=list before retrying.").toJson();
        }
    }

    /** Waits only on cached events, and only when the caller observed an out-of-date application. */
    public synchronized JsonArray awaitDialogs(boolean watch, long timeoutMs)
    {
        if (!watch)
        {
            return new JsonArray();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (snapshot.isEmpty())
        {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0)
            {
                break;
            }
            try
            {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return cachedDialogs();
    }
}
