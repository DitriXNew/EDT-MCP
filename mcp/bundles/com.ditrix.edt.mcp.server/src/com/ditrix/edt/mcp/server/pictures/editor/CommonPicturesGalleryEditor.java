/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.pictures.editor;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.EditorPart;

/**
 * A read-only editor part that renders a self-contained HTML document (supplied via
 * {@link StringHtmlEditorInput}) in an SWT {@link Browser}.
 * <p>
 * This is the host for the "Common Pictures" preview gallery: the handler builds the HTML (with
 * inline base64 data-URI thumbnails) off the UI thread and opens it here. The editor holds no model
 * or EMF state — it only carries the HTML string from its input and pushes it into the browser on
 * the UI thread. It is never dirty and cannot be saved.
 * <p>
 * The editor id {@code com.ditrix.edt.mcp.server.pictures.galleryEditor} is registered in
 * {@code plugin.xml} against this class (that wiring lives in the handler/plugin.xml slice).
 */
public final class CommonPicturesGalleryEditor extends EditorPart
{
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
        browser.setText(htmlInput.getHtml());
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
