/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.pictures.editor;

import java.util.Objects;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPersistableElement;

/**
 * A lightweight {@link IEditorInput} that carries a self-contained HTML document plus a display
 * title, for showing generated HTML in a {@link CommonPicturesGalleryEditor}'s SWT
 * {@link org.eclipse.swt.browser.Browser}.
 * <p>
 * The input holds no workspace resource and no live model object: it is a detached pair of strings
 * (the rendered HTML and the editor title). {@link #exists()} is therefore {@code false} (there is
 * no persisted resource behind it) and there is no {@link IPersistableElement} adapter, so the
 * platform never tries to restore it across sessions.
 */
public final class StringHtmlEditorInput implements IEditorInput
{
    private final String html;
    private final String title;

    /**
     * Creates an input carrying the given HTML document and editor title.
     *
     * @param html the complete HTML document to render in the browser; must not be {@code null}
     * @param title the editor tab title / part name; must not be {@code null}
     */
    public StringHtmlEditorInput(String html, String title)
    {
        this.html = Objects.requireNonNull(html, "html"); //$NON-NLS-1$
        this.title = Objects.requireNonNull(title, "title"); //$NON-NLS-1$
    }

    /**
     * Returns the complete HTML document to render in the browser.
     *
     * @return the HTML document (never {@code null})
     */
    public String getHtml()
    {
        return html;
    }

    @Override
    public boolean exists()
    {
        // No persisted resource backs this input.
        return false;
    }

    @Override
    public ImageDescriptor getImageDescriptor()
    {
        return null;
    }

    @Override
    public String getName()
    {
        return title;
    }

    @Override
    public IPersistableElement getPersistable()
    {
        // Not persistable: the HTML is generated on demand and never restored across sessions.
        return null;
    }

    @Override
    public String getToolTipText()
    {
        return title;
    }

    @Override
    public <T> T getAdapter(Class<T> adapter)
    {
        return null;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(html, title);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (!(obj instanceof StringHtmlEditorInput other))
        {
            return false;
        }
        return html.equals(other.html) && title.equals(other.title);
    }
}
