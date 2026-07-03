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
 * The input holds no workspace resource and no live model object: it is a detached bundle of strings
 * (the rendered HTML, the editor title, and — for the gallery's server-side search/pagination
 * round-trip — the owning {@code projectName} and the synonym {@code language} code). {@link #exists()}
 * is therefore {@code false} (there is no persisted resource behind it) and there is no
 * {@link IPersistableElement} adapter, so the platform never tries to restore it across sessions.
 * <p>
 * The {@code projectName}/{@code language} let the {@link CommonPicturesGalleryEditor}'s
 * {@code LocationListener} re-resolve the project + configuration FRESH on each Prev/Next/search nav
 * (it never holds a live {@code Configuration} across events) and re-run the filter/paginate seam.
 * They are {@code null} on the generic HTML-only usage.
 */
public final class StringHtmlEditorInput implements IEditorInput
{
    private final String html;
    private final String title;
    private final String projectName;
    private final String language;

    /**
     * Creates a plain HTML input with no project context (no search/pagination round-trip).
     *
     * @param html the complete HTML document to render in the browser; must not be {@code null}
     * @param title the editor tab title / part name; must not be {@code null}
     */
    public StringHtmlEditorInput(String html, String title)
    {
        this(html, title, null, null);
    }

    /**
     * Creates an input carrying the HTML, title AND the project context the gallery editor needs to
     * re-render on a Prev/Next/search nav.
     *
     * @param html the complete HTML document to render in the browser; must not be {@code null}
     * @param title the editor tab title / part name; must not be {@code null}
     * @param projectName the owning workspace project name (re-resolved on each nav); may be {@code null}
     * @param language the synonym language CODE for the re-render (may be {@code null})
     */
    public StringHtmlEditorInput(String html, String title, String projectName, String language)
    {
        this.html = Objects.requireNonNull(html, "html"); //$NON-NLS-1$
        this.title = Objects.requireNonNull(title, "title"); //$NON-NLS-1$
        this.projectName = projectName;
        this.language = language;
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

    /**
     * Returns the owning workspace project name the gallery editor re-resolves on each nav.
     *
     * @return the project name, or {@code null} for the plain HTML-only usage
     */
    public String getProjectName()
    {
        return projectName;
    }

    /**
     * Returns the synonym language CODE used when the gallery editor re-renders a page.
     *
     * @return the language code, or {@code null}
     */
    public String getLanguage()
    {
        return language;
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
        return Objects.hash(html, title, projectName, language);
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
        return html.equals(other.html) && title.equals(other.title)
            && Objects.equals(projectName, other.projectName) && Objects.equals(language, other.language);
    }
}
