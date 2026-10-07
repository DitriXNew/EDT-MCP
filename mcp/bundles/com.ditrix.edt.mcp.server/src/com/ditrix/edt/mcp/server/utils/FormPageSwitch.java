/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.viewers.IStructuredSelection;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormItem;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.RenderOutcome;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.ShowElementTarget;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.SyncRender;

/**
 * A capture-scoped page switch in an open form editor (native render): brings the page holding an
 * element into view for one capture, then puts the editor back as far as EDT allows. Create it with
 * {@link #prepare} before anything is rendered, call {@link #show()} inside a {@code try}, capture with
 * no event pumping after it, and call {@link #restore()} in the {@code finally}. UI thread only.
 *
 * <p>Best effort by construction: EDT keeps no readable page state per Pages group (a render takes
 * only select-by-id ids), so the restore re-selects the editor's last domain selection, as
 * {@code FormWysiwygRepresentation.refreshSelection} does; other Pages groups show their default pages.</p>
 */
public final class FormPageSwitch
{
    private static final String LAST_DOMAIN_SELECTION_FIELD = "lastDomainSelection"; //$NON-NLS-1$
    private static final int SWITCH_TIMEOUT_MS = 10000;

    private final Object representation;
    private final ShowElementTarget target;
    private final int[] previousSelection;
    private final long deadline;
    private final Runnable pump;
    /** Whether a render of ours may have changed the editor (a throw counts). */
    private boolean touched;
    private boolean restoreDone;

    private FormPageSwitch(Object representation, ShowElementTarget target, int[] previousSelection,
        long deadline, Runnable pump)
    {
        this.representation = representation;
        this.target = target;
        this.previousSelection = previousSelection;
        this.deadline = deadline;
        this.pump = pump;
    }

    /**
     * Records the editor's last domain selection, which the restore re-selects; renders nothing.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @param target the element resolved by {@link EditorScreenshotHelper#resolveShowElement}
     * @return the switch, not yet shown
     */
    public static FormPageSwitch prepare(Object representation, ShowElementTarget target)
    {
        return prepare(representation, target, SWITCH_TIMEOUT_MS, EditorScreenshotHelper::processCurrentEvents);
    }

    /**
     * @param timeoutMs budget shared by the switch and the restore
     * @param pump drains the event loop while a render waits for the form model
     */
    static FormPageSwitch prepare(Object representation, ShowElementTarget target, int timeoutMs, Runnable pump)
    {
        return new FormPageSwitch(representation, target, selectedIds(representation),
            System.currentTimeMillis() + timeoutMs, pump);
    }

    /**
     * Shows the page holding the element: every Pages group enclosing it switches to the page holding
     * it, and the selection frame is then dropped so it is not painted into the image. An element
     * outside every Pages group is visible on the current pages, so nothing is rendered for it.
     *
     * @return {@code null} when the page holding the element is in the render buffer, else why not
     */
    public String show()
    {
        if (!target.isOnPage())
        {
            return null;
        }
        boolean completed = false;
        try
        {
            String error = switchPages();
            completed = true;
            return error;
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not show form element '" + target.getName() + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return "Element '" + target.getName() + "' could not be shown: the form editor threw while it " //$NON-NLS-1$ //$NON-NLS-2$
                + "was re-rendered; the EDT log has the cause."; //$NON-NLS-1$
        }
        finally
        {
            if (!completed)
            {
                touched = true;
            }
        }
    }

    /** @return whether a render of this switch may have changed the editor, so a restore is due */
    public boolean isTouched()
    {
        return touched;
    }

    private String switchPages()
    {
        // A full render selecting the element switches its Pages groups (update-only would reuse
        // HippoLayoutService's singleton layout cache, which may hold another editor's form). The
        // frame clear and the caller's capture follow with no event pumping: another editor rendering
        // in between rebinds the shared NativeRenderService window, which makes EDT turn the
        // update-only clear into a full render and bring the default pages back.
        SyncRender select = render(new int[] { target.getItemId() }, false);
        if (select.outcome != RenderOutcome.RENDERED)
        {
            if (select.outcome == RenderOutcome.NOT_READY)
            {
                return "The form model is still loading, so element '" + target.getName() //$NON-NLS-1$
                    + "' could not be shown. Try again."; //$NON-NLS-1$
            }
            return "Element '" + target.getName() + "' could not be shown: " + cause(select.outcome) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        // Update-only with an empty selection keeps the switched pages and drops the frame.
        SyncRender clear = EditorScreenshotHelper.renderRequestedFormSynchronously(representation, new int[0], true);
        if (clear.outcome != RenderOutcome.RENDERED)
        {
            return "Element '" + target.getName() + "' was shown, but the selection frame could not be " //$NON-NLS-1$ //$NON-NLS-2$
                + "cleared from the image (" + cause(clear.outcome) + "). Try again."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /** One synchronous render, retried (pumping) while the form model loads, in the shared budget. */
    private SyncRender render(int[] ids, boolean updateOnly)
    {
        SyncRender result = EditorScreenshotHelper.renderSyncUntilRendered(representation, ids, updateOnly,
            deadline, pump);
        if (result.outcome == RenderOutcome.RENDERED || result.outcome == RenderOutcome.FAILED)
        {
            touched = true;
        }
        return result;
    }

    /**
     * Puts the editor back, once and only if a render of this switch may have changed it: one full
     * render selecting the editor's last domain selection (which re-opens the pages enclosing it), or a
     * plain full render (the default pages) when nothing was selected. Best effort: other Pages groups
     * show their default pages, and the previous selection frame is not guaranteed.
     *
     * @return {@code null} when nothing needed restoring or the render ran, else the error
     */
    public String restore()
    {
        if (!touched || restoreDone)
        {
            return null;
        }
        restoreDone = true;
        String cause;
        try
        {
            SyncRender restore = render(previousSelection.length == 0 ? null : previousSelection, false);
            if (restore.outcome == RenderOutcome.RENDERED)
            {
                return null;
            }
            Activator.logWarning("Could not restore the form editor after a showElement capture: " //$NON-NLS-1$
                + restore.outcome);
            cause = cause(restore.outcome);
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not restore the form editor after showing '" + target.getName() + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            cause = "the form editor threw while it was re-rendered; the EDT log has the cause"; //$NON-NLS-1$
        }
        return "The form editor could not be returned to the page it showed before the capture (" + cause //$NON-NLS-1$
            + "), so it may still show the page holding element '" + target.getName() //$NON-NLS-1$
            + "', and a later capture without showElement may return that page. Call again with " //$NON-NLS-1$
            + "refresh=true to re-render the editor."; //$NON-NLS-1$
    }

    private static String cause(RenderOutcome outcome)
    {
        switch (outcome)
        {
        case NOT_READY:
            return "the form model is still loading"; //$NON-NLS-1$
        case FAILED:
            return "the platform's synchronous form render failed; the EDT log has the cause"; //$NON-NLS-1$
        default:
            return "the platform's synchronous form render could not be driven; the EDT log has the cause"; //$NON-NLS-1$
        }
    }

    /**
     * The ids of the editor's last domain selection, built the way
     * {@code FormWysiwygRepresentation.manageSelectionFromFormElement} builds them: {@code 0} for the
     * form itself, the item id for a form item.
     */
    static int[] selectedIds(Object representation)
    {
        Object selection;
        try
        {
            selection = ReflectionUtils.getFieldValue(representation, LAST_DOMAIN_SELECTION_FIELD);
        }
        catch (Exception e)
        {
            return new int[0];
        }
        if (!(selection instanceof IStructuredSelection structured))
        {
            return new int[0];
        }
        List<Integer> ids = new ArrayList<>();
        for (Object element : structured.toArray())
        {
            if (element instanceof Form)
            {
                ids.add(Integer.valueOf(0));
            }
            else if (element instanceof FormItem item)
            {
                ids.add(Integer.valueOf(item.getId()));
            }
        }
        int[] result = new int[ids.size()];
        for (int i = 0; i < result.length; i++)
        {
            result[i] = ids.get(i).intValue();
        }
        return result;
    }
}
