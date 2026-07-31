/**
 * Copyright (C) 2024, DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
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
        IResourceLookup lookup = ServiceAccess.get(IResourceLookup.class);
        if (lookup == null)
        {
            return Collections.emptyList();
        }
        IFile mdo = lookup.getPlatformResource(mdObject);
        if (mdo == null || mdo.getLocation() == null)
        {
            return Collections.emptyList();
        }
        IProject project = mdo.getProject();
        if (project == null || project.getLocation() == null)
        {
            return Collections.emptyList();
        }
        File helpDir = mdo.getLocation().removeLastSegments(1).append(HELP_DIR).toFile();
        if (!helpDir.isDirectory())
        {
            return Collections.emptyList();
        }
        Path projectRoot;
        Path helpDirReal;
        try
        {
            projectRoot = project.getLocation().toFile().toPath().toRealPath();
            // Resolved SEPARATELY from the project root: the Help/ directory itself could be a
            // symlink/junction (not just an individual file inside it) - resolving it here means the
            // per-file check below (real parent == helpDirReal) actually pins each file to THIS help
            // directory, not just "somewhere under the project".
            helpDirReal = helpDir.toPath().toRealPath();
            if (!helpDirReal.startsWith(projectRoot))
            {
                Activator.logWarning("Skipped the Help directory itself: it resolves outside the " //$NON-NLS-1$
                    + "project (symlink escape?): " + helpDir.getAbsolutePath()); //$NON-NLS-1$
                return Collections.emptyList();
            }
        }
        catch (IOException e)
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
                // toRealPath, not normalize: a help file that is (or sits under) a symlink/junction is
                // lexically inside the project's Help/ dir while its content may live outside it - follow
                // the link to see where it ACTUALLY resolves before reading, the same guard GitTool uses
                // for a pathspec escape. Pinned to the RESOLVED Help directory itself (not merely "still
                // somewhere under the project"), or a symlinked help file could point at another,
                // unrelated file elsewhere in the SAME project (e.g. '../../../../.env') and still pass
                // a project-root-only check.
                Path real = file.toPath().toRealPath();
                if (!helpDirReal.equals(real.getParent()))
                {
                    Activator.logWarning("Skipped an object help page outside its Help directory " //$NON-NLS-1$
                        + "(symlink escape?): " + file.getAbsolutePath()); //$NON-NLS-1$
                    continue;
                }
                String html = new String(Files.readAllBytes(real), StandardCharsets.UTF_8);
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
}
