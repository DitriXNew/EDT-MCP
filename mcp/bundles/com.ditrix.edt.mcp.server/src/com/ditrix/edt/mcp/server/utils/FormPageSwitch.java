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
 * element into view for one capture, then puts the editor back. The way back is the one EDT itself
 * uses after a render ({@code FormWysiwygRepresentation.refreshSelection}): a render that selects the
 * editor's last domain selection, which also re-opens the page the user clicked. Must be used on the UI
 * thread; call {@link #restore()} from a {@code finally}.
 */
public final class FormPageSwitch
{
    private static final String LAST_DOMAIN_SELECTION_FIELD = "lastDomainSelection"; //$NON-NLS-1$
    private static final int SWITCH_TIMEOUT_MS = 10000;
    /** A foreign editor rebuild between our renders resets the pages; retry the switch this often. */
    private static final int MAX_ATTEMPTS = 3;

    private final Object representation;
    private final String elementName;
    private final int[] previousSelection;
    private final long deadline;
    private final Runnable pump;
    /** Whether a render of ours may have changed the editor (a throw inside the render counts). */
    private boolean touched;
    private boolean restoreDone;
    private String error;

    private FormPageSwitch(Object representation, String elementName, int[] previousSelection, long deadline,
        Runnable pump)
    {
        this.representation = representation;
        this.elementName = elementName;
        this.previousSelection = previousSelection;
        this.deadline = deadline;
        this.pump = pump;
    }

    /**
     * Shows the page holding a resolved element: every Pages group enclosing it switches to the page
     * holding it, and the selection frame is then dropped so it is not painted into the image.
     *
     * @param representation the {@code FormWysiwygRepresentation} instance
     * @param target the element resolved by {@link EditorScreenshotHelper#resolveShowElement}
     * @return the switch; {@link #getError()} tells whether the page is shown
     */
    public static FormPageSwitch show(Object representation, ShowElementTarget target)
    {
        return show(representation, target, SWITCH_TIMEOUT_MS, EditorScreenshotHelper::processCurrentEvents);
    }

    /**
     * @param timeoutMs budget shared by the switch and the restore
     * @param pump drains the event loop after each render; a foreign rebuild runs there
     */
    static FormPageSwitch show(Object representation, ShowElementTarget target, int timeoutMs, Runnable pump)
    {
        FormPageSwitch pageSwitch = new FormPageSwitch(representation, target.getName(),
            selectedIds(representation), System.currentTimeMillis() + timeoutMs, pump);
        pageSwitch.run(target.getItemId());
        return pageSwitch;
    }

    /** @return {@code null} when the page holding the element is in the render buffer, else why not */
    public String getError()
    {
        return error;
    }

    /** @return whether a render of this switch may have changed the editor, so a restore is due */
    public boolean isTouched()
    {
        return touched;
    }

    private void run(int itemId)
    {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++)
        {
            // Only the synchronous render is driven: the editor's own setSelection schedules an async
            // select-by-id rebuild that could land after the frame-clearing render and repaint the frame.
            SyncRender select = EditorScreenshotHelper.renderSyncUntilRendered(representation,
                new int[] { itemId }, false, deadline, pump);
            if (select.outcome == RenderOutcome.FAILED)
            {
                touched = true;
            }
            if (select.outcome != RenderOutcome.RENDERED)
            {
                error = switchFailure(select.outcome);
                return;
            }
            touched = true;
            if (isCurrent(select))
            {
                // Update-only with an empty selection keeps the switched pages and drops the frame; a
                // full render would bring the default pages back.
                SyncRender clear = EditorScreenshotHelper.renderSyncUntilRendered(representation, new int[0],
                    true, deadline, pump);
                if (clear.outcome != RenderOutcome.RENDERED)
                {
                    error = "Element '" + elementName + "' was shown, but the selection frame could not be " //$NON-NLS-1$ //$NON-NLS-2$
                        + "cleared from the image (" + cause(clear.outcome) + "). Try again."; //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                if (isCurrent(clear))
                {
                    error = null;
                    return;
                }
            }
            // Another rebuild of this editor ran while the event loop was pumped and may have reset the
            // pages: the buffer cannot be trusted to show the element, so switch again.
            if (System.currentTimeMillis() >= deadline)
            {
                break;
            }
        }
        error = "The form editor re-rendered on its own while element '" + elementName //$NON-NLS-1$
            + "' was being shown, so its page could not be captured reliably. Try again."; //$NON-NLS-1$
    }

    /** Whether the render buffer still holds the given render, i.e. no other rebuild replaced it. */
    private boolean isCurrent(SyncRender render)
    {
        return EditorScreenshotHelper.getHippoSession(representation) == render.session;
    }

    /**
     * Puts the editor back: a full render selecting the editor's last domain selection (which re-opens
     * the page it lies on, as EDT does after its own renders), or a plain full render when nothing was
     * selected. Runs only when a render of this switch may have changed the editor, and only once.
     *
     * @return {@code null} when nothing needed restoring or the restore render ran, else the error
     */
    public String restore()
    {
        if (!touched || restoreDone)
        {
            return null;
        }
        restoreDone = true;
        SyncRender restore = EditorScreenshotHelper.renderSyncUntilRendered(representation,
            previousSelection.length == 0 ? null : previousSelection, false, deadline, pump);
        if (restore.outcome == RenderOutcome.RENDERED)
        {
            return null;
        }
        Activator.logWarning("Could not restore the form editor after a showElement capture: " //$NON-NLS-1$
            + restore.outcome);
        return "The form editor could not be returned to the page it showed before the capture (" //$NON-NLS-1$
            + cause(restore.outcome) + "), so it may still show the page holding element '" + elementName //$NON-NLS-1$
            + "', and a later capture without showElement may return that page. Call again with " //$NON-NLS-1$
            + "refresh=true to re-render the editor."; //$NON-NLS-1$
    }

    private String switchFailure(RenderOutcome outcome)
    {
        if (outcome == RenderOutcome.NOT_READY)
        {
            return "The form model is still loading, so element '" + elementName //$NON-NLS-1$
                + "' could not be shown. Try again."; //$NON-NLS-1$
        }
        return "Element '" + elementName + "' could not be shown: " + cause(outcome) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
