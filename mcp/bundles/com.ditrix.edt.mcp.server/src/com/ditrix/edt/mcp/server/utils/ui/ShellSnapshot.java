/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;

/** Shared dialog control readers. Callers must remain on the owning SWT thread. */
public record ShellSnapshot(String title, String message, Map<String, List<Button>> buttons, boolean complete)
{
    public static ShellSnapshot read(org.eclipse.swt.widgets.Shell shell)
    {
        StringBuilder text = new StringBuilder();
        Map<String, List<Button>> buttons = new java.util.LinkedHashMap<>();
        boolean complete = readControls(shell, 0, new int[] {500}, text, buttons);
        return new ShellSnapshot(shell.getText(), text.toString().strip(), buttons, complete);
    }

    public static boolean readControls(Control control, int depth, int[] budget, StringBuilder text,
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

    public static void appendLabelTexts(Control control, int depth, StringBuilder sb, int[] budget, int maxDepth, int maxChars)
    {
        if (control == null || control.isDisposed() || depth > maxDepth || budget[0] <= 0
            || sb.length() >= maxChars)
        {
            return;
        }
        budget[0]--;
        String own = labelLikeText(control);
        if (own != null && !own.isEmpty())
        {
            int room = maxChars - sb.length();
            sb.append(own, 0, Math.min(own.length(), room)).append('\n');
        }
        if (control instanceof Composite)
        {
            for (Control child : ((Composite)control).getChildren())
            {
                appendLabelTexts(child, depth + 1, sb, budget, maxDepth, maxChars);
            }
        }
    }

    public static String labelLikeText(Control control)
    {
        try
        {
            if (control instanceof Label)
            {
                return ((Label)control).getText();
            }
            if (control instanceof CLabel)
            {
                return ((CLabel)control).getText();
            }
            if (control instanceof Text)
            {
                return ((Text)control).getText();
            }
            if (control instanceof Link)
            {
                return ((Link)control).getText();
            }
        }
        catch (RuntimeException e)
        {
            return null;
        }
        return null;
    }

    public static String findBodyText(Control control, int depth, int maxDepth,
        java.util.function.Predicate<String> preferred)
    {
        if (control == null || control.isDisposed() || depth > maxDepth) { return null; }
        String text = labelLikeText(control);
        String best = text != null && !text.trim().isEmpty() ? text : null;
        if (best != null && preferred.test(best)) { return best; }
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                String found = findBodyText(child, depth + 1, maxDepth, preferred);
                if (found != null && preferred.test(found)) { return found; }
                if (best == null) { best = found; }
            }
        }
        return best;
    }

    public static Button findButton(Control control, int depth, int maxDepth,
        java.util.function.Predicate<String> accepted)
    {
        if (control == null || control.isDisposed() || depth > maxDepth) { return null; }
        if (control instanceof Button button)
        {
            try { if (accepted.test(button.getText())) { return button; } }
            catch (RuntimeException e) { /* Keep scanning other controls. */ }
        }
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                Button found = findButton(child, depth + 1, maxDepth, accepted);
                if (found != null) { return found; }
            }
        }
        return null;
    }
}
