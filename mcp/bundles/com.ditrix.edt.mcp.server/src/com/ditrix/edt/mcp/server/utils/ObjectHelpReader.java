/**
 * Copyright (C) 2024, DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Reads the authored help of a metadata object - the "Справочная информация" HTML that a configuration
 * author fills in for an object (Configuration / Catalog / Document / Report / ... - any type carrying
 * the {@code help} feature). The model ({@code mcore.Help} / {@code HelpPage}) only tracks which
 * language codes exist; the actual HTML lives on disk next to the object's {@code .mdo}, in
 * {@code <ObjectDir>/Help/<lang>.html} (one file per language). This reads those files directly from
 * disk (resolving the object's {@code .mdo} through {@link IResourceLookup}), independent of the
 * in-memory model, and renders each to Markdown via {@link HtmlToMarkdown}.
 */
public final class ObjectHelpReader
{
    private static final String HELP_DIR = "Help"; //$NON-NLS-1$
    private static final String HTML_EXT = ".html"; //$NON-NLS-1$

    private ObjectHelpReader()
    {
    }

    /** One authored help page: its language code (from the file name) and the help text as Markdown. */
    public static final class HelpPage
    {
        public final String lang;
        public final String markdown;

        HelpPage(String lang, String markdown)
        {
            this.lang = lang;
            this.markdown = markdown;
        }
    }

    /**
     * The object's authored help pages (one per {@code <ObjectDir>/Help/<lang>.html} file), rendered to
     * Markdown, ordered by language code. Returns an empty list when the object has no help directory,
     * cannot be located on disk, or every page is empty. Never throws - a read failure of one page is
     * logged and that page is skipped.
     */
    public static List<HelpPage> read(EObject mdObject)
    {
        if (mdObject == null)
        {
            return Collections.emptyList();
        }
        File helpDir = helpDir(mdObject);
        if (helpDir == null || !helpDir.isDirectory())
        {
            return Collections.emptyList();
        }
        File[] files = helpDir.listFiles((FilenameFilter)(dir, name) -> name.toLowerCase().endsWith(HTML_EXT));
        if (files == null || files.length == 0)
        {
            return Collections.emptyList();
        }
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        List<HelpPage> pages = new ArrayList<>();
        for (File file : files)
        {
            String name = file.getName();
            String lang = name.substring(0, name.length() - HTML_EXT.length());
            try
            {
                String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                String markdown = HtmlToMarkdown.convert(html);
                if (!markdown.isEmpty())
                {
                    pages.add(new HelpPage(lang, markdown));
                }
            }
            catch (Exception e)
            {
                Activator.logError("Failed to read object help page " + file.getAbsolutePath(), e); //$NON-NLS-1$
            }
        }
        return pages;
    }

    /** The object's on-disk {@code Help} directory (sibling of its {@code .mdo}), or {@code null}. */
    private static File helpDir(EObject mdObject)
    {
        IResourceLookup lookup = ServiceAccess.get(IResourceLookup.class);
        if (lookup == null)
        {
            return null;
        }
        IFile mdo = lookup.getPlatformResource(mdObject);
        if (mdo == null || mdo.getLocation() == null)
        {
            return null;
        }
        IPath helpPath = mdo.getLocation().removeLastSegments(1).append(HELP_DIR);
        return helpPath.toFile();
    }
}
