/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.pictures.editor;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.EditorPart;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.pictures.CommonPicturesGalleryRenderer;
import com.ditrix.edt.mcp.server.pictures.CommonPicturesGalleryRenderer.RenderedPage;

/**
 * A read-only editor part that renders a self-contained HTML document (supplied via
 * {@link StringHtmlEditorInput}) in an SWT {@link Browser}, and — for the Common Pictures gallery —
 * intercepts its custom {@code edtmcp:gallery?q=...&page=...} navigation to re-render a new page
 * off the UI thread (server-side search + pagination).
 * <p>
 * The editor holds no live model or EMF state — it carries the HTML string plus the project context
 * (project name + synonym language) from its input. On a Prev/Next/search click the browser tries to
 * navigate to an {@code edtmcp:} URL; a {@link LocationListener} cancels that navigation, parses the
 * {@code q}/{@code page} query, and delegates to {@link CommonPicturesGalleryRenderer} which
 * re-resolves the project + configuration FRESH (never holding a live {@code Configuration} across
 * events) and re-renders the requested page inside a background {@code BmTransactions.read} Job. The
 * new HTML is pushed back with {@code browser.setText} on the UI thread (guarded against a disposed
 * browser/editor). The editor is never dirty and cannot be saved.
 * <p>
 * The editor id {@code com.ditrix.edt.mcp.server.pictures.galleryEditor} is registered in
 * {@code plugin.xml} against this class.
 */
public final class CommonPicturesGalleryEditor extends EditorPart
{
    /** The custom scheme+path the pager/search controls navigate to; kept in sync with the generator. */
    private static final String NAV_URL_PREFIX = "edtmcp:gallery"; //$NON-NLS-1$

    private StringHtmlEditorInput htmlInput;
    private Browser browser;

    @Override
    public void init(IEditorSite site, IEditorInput input) throws PartInitException
    {
        if (!(input instanceof StringHtmlEditorInput stringHtmlInput))
        {
            throw new PartInitException(
                "CommonPicturesGalleryEditor requires a StringHtmlEditorInput but received: " //$NON-NLS-1$
                    + (input == null ? "null" : input.getClass().getName())); //$NON-NLS-1$
        }
        this.htmlInput = stringHtmlInput;
        setSite(site);
        setInput(stringHtmlInput);
        setPartName(stringHtmlInput.getName());
    }

    @Override
    public void createPartControl(Composite parent)
    {
        // Runs on the UI thread; Browser.setText must be called here.
        browser = new Browser(parent, SWT.NONE);
        browser.addLocationListener(new NavInterceptor());
        browser.setText(htmlInput.getHtml());
    }

    /**
     * Intercepts the custom {@code edtmcp:gallery?q=...&page=...} navigations the pager/search emit:
     * cancels the real navigation, parses the query, and re-renders the requested page off the UI
     * thread. Any other URL (there is none in this self-contained document) is left alone.
     */
    private final class NavInterceptor implements LocationListener
    {
        @Override
        public void changing(LocationEvent event)
        {
            if (event.location == null || !event.location.startsWith(NAV_URL_PREFIX))
            {
                return;
            }
            // Our own nav pseudo-URL: never let the browser actually navigate to it.
            event.doit = false;
            String query = parseParam(event.location, "q"); //$NON-NLS-1$
            int page = parsePage(event.location);
            reRender(query, page);
        }

        @Override
        public void changed(LocationEvent event)
        {
            // No-op: the interception happens in changing(); setText() drives the content.
        }
    }

    /**
     * Re-renders the requested gallery page and pushes it back into the browser. Re-resolves the
     * project + configuration fresh inside the renderer's background Job (never holds a live
     * {@code Configuration} across events); the browser update hops back to the UI thread and is
     * guarded against a disposed browser/editor.
     *
     * @param query the (decoded) search query
     * @param page the requested 0-based page index
     */
    private void reRender(String query, int page) // NOSONAR S3398: kept on the editor (not the inner
                                                  // interceptor) as it uses the outer instance's input
                                                  // + applyRenderedPage callback; moving it in adds no value
    {
        String projectName = htmlInput.getProjectName();
        if (projectName == null || projectName.isEmpty())
        {
            // A plain HTML-only input (no project context): nothing to re-render against.
            return;
        }
        CommonPicturesGalleryRenderer.scheduleRender(projectName, "Общие картинки: ", //$NON-NLS-1$
            htmlInput.getLanguage(), query, page, this::applyRenderedPage);
    }

    /**
     * Applies a freshly rendered page to the browser on the UI thread, guarding against a disposed
     * browser (the editor may have been closed while the background Job ran).
     *
     * @param rendered the rendered HTML page + title
     */
    private void applyRenderedPage(RenderedPage rendered)
    {
        Browser target = browser;
        if (target == null || target.isDisposed())
        {
            return;
        }
        target.getDisplay().asyncExec(() -> {
            try
            {
                if (browser != null && !browser.isDisposed())
                {
                    browser.setText(rendered.html());
                }
            }
            catch (Exception e) // NOSONAR a disposed browser / SWT failure must not crash the Job thread
            {
                Activator.logError("Could not update the Common Pictures gallery browser", e); //$NON-NLS-1$
            }
        });
    }

    /**
     * Extracts and URL-decodes a single query parameter from an {@code edtmcp:gallery?...} URL. Returns
     * an empty string when the parameter is absent.
     *
     * @param url the nav URL
     * @param name the parameter name
     * @return the decoded value, or {@code ""} when absent
     */
    private static String parseParam(String url, String name)
    {
        int q = url.indexOf('?');
        if (q < 0)
        {
            return ""; //$NON-NLS-1$
        }
        for (String pair : url.substring(q + 1).split("&")) //$NON-NLS-1$
        {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (name.equals(key))
            {
                String raw = eq < 0 ? "" : pair.substring(eq + 1); //$NON-NLS-1$
                return urlDecode(raw);
            }
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * Extracts the {@code page} parameter as a non-negative int, defaulting to 0 on absence / a
     * malformed value (the renderer clamps an out-of-range page into the valid window anyway).
     *
     * @param url the nav URL
     * @return the requested 0-based page, or 0
     */
    private static int parsePage(String url)
    {
        String raw = parseParam(url, "page"); //$NON-NLS-1$
        if (raw.isEmpty())
        {
            return 0;
        }
        try
        {
            return Math.max(0, Integer.parseInt(raw.trim()));
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    /**
     * URL-decodes a query fragment as UTF-8 (the same contract the generator's {@code URLEncoder} used).
     *
     * @param text the raw fragment
     * @return the decoded text (or the raw text on the unreachable UTF-8-missing failure)
     */
    private static String urlDecode(String text)
    {
        try
        {
            return URLDecoder.decode(text, StandardCharsets.UTF_8.name());
        }
        catch (UnsupportedEncodingException | IllegalArgumentException e)
        {
            // Both fall back to the raw text: UnsupportedEncodingException is unreachable (UTF-8 is
            // always available); a malformed %-escape (IllegalArgumentException) is treated as the raw
            // query rather than failing the nav.
            return text;
        }
    }

    @Override
    public void setFocus()
    {
        if (browser != null && !browser.isDisposed())
        {
            browser.setFocus();
        }
    }

    @Override
    public boolean isDirty()
    {
        return false;
    }

    @Override
    public boolean isSaveAsAllowed()
    {
        return false;
    }

    @Override
    public void doSave(IProgressMonitor monitor)
    {
        // Read-only preview: nothing to save.
    }

    @Override
    public void doSaveAs()
    {
        // Read-only preview: save-as is not allowed.
    }
}
