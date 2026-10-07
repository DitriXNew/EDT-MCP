/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.graphics.ImageData;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormItem;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.RenderOutcome;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.ShowElementTarget;
import com.ditrix.edt.mcp.server.utils.EditorScreenshotHelper.SyncRender;

/**
 * A capture-scoped page switch in an open form editor (native render): brings the page holding an
 * element into view for one capture, then puts the editor back. Create it with {@link #prepare} before
 * anything is rendered, call {@link #show()} inside a {@code try} and {@link #restore()} in its
 * {@code finally}. Must be used on the UI thread.
 *
 * <p>EDT keeps no readable per-Pages-group page state: a render takes only an event (select-by-id ids)
 * and returns the selected ids. So the restore re-selects the editor's last domain selection (as
 * {@code FormWysiwygRepresentation.refreshSelection} does) and checks the result against the image the
 * editor held before the switch.</p>
 */
public final class FormPageSwitch
{
    private static final String LAST_DOMAIN_SELECTION_FIELD = "lastDomainSelection"; //$NON-NLS-1$
    private static final int SWITCH_TIMEOUT_MS = 10000;
    /** A foreign editor rebuild between our renders resets the pages; retry the switch this often. */
    private static final int MAX_ATTEMPTS = 3;

    private final Object representation;
    private final ShowElementTarget target;
    private final int[] previousSelection;
    /** A copy of the image the editor held before the switch ({@code null} if it held none). */
    private final ImageData previousImage;
    private final long deadline;
    private final Runnable pump;
    /** Whether a render of ours may have changed the editor (a throw counts). */
    private boolean touched;
    private boolean restoreDone;
    private String error;

    private FormPageSwitch(Object representation, ShowElementTarget target, int[] previousSelection,
        ImageData previousImage, long deadline, Runnable pump)
    {
        this.representation = representation;
        this.target = target;
        this.previousSelection = previousSelection;
        this.previousImage = previousImage;
        this.deadline = deadline;
        this.pump = pump;
    }

    /**
     * Records the editor state the restore returns to; renders nothing.
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
     * @param pump drains the event loop after each render; a foreign rebuild runs there
     */
    static FormPageSwitch prepare(Object representation, ShowElementTarget target, int timeoutMs, Runnable pump)
    {
        ImageData image = EditorScreenshotHelper.getFormImageDataField(representation);
        // The native render reuses the ImageData instance, so keep a copy of its pixels.
        ImageData copy = image != null && image.width > 0 && image.height > 0 ? (ImageData)image.clone() : null;
        return new FormPageSwitch(representation, target, selectedIds(representation), copy,
            System.currentTimeMillis() + timeoutMs, pump);
    }

    /**
     * Shows the page holding the element: every Pages group enclosing it switches to the page holding
     * it, and the selection frame is then dropped so it is not painted into the image.
     *
     * @return {@code null} when the page holding the element is in the render buffer, else why not
     */
    public String show()
    {
        boolean completed = false;
        try
        {
            run(target.getItemId());
            completed = true;
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not show form element '" + target.getName() + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            error = "Element '" + target.getName() + "' could not be shown: the form editor threw while it " //$NON-NLS-1$ //$NON-NLS-2$
                + "was re-rendered; the EDT log has the cause."; //$NON-NLS-1$
        }
        finally
        {
            if (!completed)
            {
                // The throw may come from the event loop pumped after a render that already ran.
                touched = true;
            }
        }
        return error;
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
            // Full, not update-only: update-only reuses HippoLayoutService's singleton layout cache, which
            // may hold the form another editor rendered last.
            SyncRender select = render(new int[] { itemId }, false);
            if (select.outcome != RenderOutcome.RENDERED)
            {
                error = switchFailure(select.outcome);
                return;
            }
            if (isCurrent(select))
            {
                // Update-only with an empty selection keeps the switched pages and drops the frame; a
                // full render would bring the default pages back.
                SyncRender clear = render(new int[0], true);
                if (clear.outcome != RenderOutcome.RENDERED)
                {
                    error = "Element '" + target.getName() + "' was shown, but the selection frame could not " //$NON-NLS-1$ //$NON-NLS-2$
                        + "be cleared from the image (" + cause(clear.outcome) + "). Try again."; //$NON-NLS-1$ //$NON-NLS-2$
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
        error = "The form editor re-rendered on its own while element '" + target.getName() //$NON-NLS-1$
            + "' was being shown, so its page could not be captured reliably. Try again."; //$NON-NLS-1$
    }

    /** One synchronous render (retried while the model loads) in the shared budget. */
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

    /** Whether the render buffer still holds the given render, i.e. no other rebuild replaced it. */
    private boolean isCurrent(SyncRender render)
    {
        return EditorScreenshotHelper.getHippoSession(representation) == render.session;
    }

    /**
     * Puts the editor back. Runs only when a render of this switch may have changed the editor, and only
     * once. Tries, in order, the renders that can reproduce the pre-switch image and stops at the first
     * that does: the last domain selection re-selected (EDT's {@code refreshSelection} state), the same
     * without the selection frame, then the default pages. If none reproduces it (the old buffer was
     * older than the model), the editor is left on the last domain selection's page.
     *
     * @return {@code null} when nothing needed restoring or the restore renders ran, else the error
     */
    public String restore()
    {
        if (!touched || restoreDone)
        {
            return null;
        }
        restoreDone = true;
        try
        {
            return restoreRenders();
        }
        catch (RuntimeException e)
        {
            Activator.logError("Could not restore the form editor after showing '" + target.getName() + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
            return restoreFailure("the form editor threw while it was re-rendered; the EDT log has the cause"); //$NON-NLS-1$
        }
    }

    private String restoreRenders()
    {
        List<int[]> selections = new ArrayList<>();
        List<Boolean> updateOnly = new ArrayList<>();
        if (previousSelection.length > 0)
        {
            selections.add(previousSelection);
            updateOnly.add(Boolean.FALSE);
            selections.add(new int[0]);
            updateOnly.add(Boolean.TRUE);
        }
        selections.add(null);
        updateOnly.add(Boolean.FALSE);

        for (int i = 0; i < selections.size(); i++)
        {
            SyncRender step = render(selections.get(i), updateOnly.get(i).booleanValue());
            if (step.outcome != RenderOutcome.RENDERED)
            {
                Activator.logWarning("Could not restore the form editor after a showElement capture: " //$NON-NLS-1$
                    + step.outcome);
                return restoreFailure(cause(step.outcome));
            }
            if (!isCurrent(step))
            {
                // EDT re-rendered on its own after our render, starting from the restored pages: its
                // render is newer than the state being restored, so it stands.
                return null;
            }
            if (previousImage == null
                || sameImage(previousImage, EditorScreenshotHelper.getFormImageDataField(representation)))
            {
                return null;
            }
        }
        if (selections.size() > 1)
        {
            // Nothing reproduced the old buffer: settle on EDT's own refreshSelection state.
            SyncRender settle = render(previousSelection, false);
            if (settle.outcome != RenderOutcome.RENDERED)
            {
                return restoreFailure(cause(settle.outcome));
            }
        }
        return null;
    }

    private String restoreFailure(String cause)
    {
        return "The form editor could not be returned to the page it showed before the capture (" + cause //$NON-NLS-1$
            + "), so it may still show the page holding element '" + target.getName() //$NON-NLS-1$
            + "', and a later capture without showElement may return that page. Call again with " //$NON-NLS-1$
            + "refresh=true to re-render the editor."; //$NON-NLS-1$
    }

    /** Pixel equality of two images; {@code false} when either is missing. */
    static boolean sameImage(ImageData expected, ImageData actual)
    {
        return expected != null && actual != null && expected.width == actual.width
            && expected.height == actual.height && expected.depth == actual.depth
            && expected.bytesPerLine == actual.bytesPerLine && Arrays.equals(expected.data, actual.data)
            && Arrays.equals(expected.alphaData, actual.alphaData);
    }

    private String switchFailure(RenderOutcome outcome)
    {
        if (outcome == RenderOutcome.NOT_READY)
        {
            return "The form model is still loading, so element '" + target.getName() //$NON-NLS-1$
                + "' could not be shown. Try again."; //$NON-NLS-1$
        }
        return "Element '" + target.getName() + "' could not be shown: " + cause(outcome) + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
