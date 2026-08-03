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

    /**
     * Per-file cap (bytes) on a SINGLE {@code <lang>.html} page read into memory before HTML->Markdown
     * conversion. 2 MB is already far beyond any genuine authored help page; a file past this is
     * skipped WITHOUT reading its bytes or running the converter, and reported with a placeholder page
     * naming its real size instead of silently vanishing.
     */
    static final long MAX_HELP_FILE_BYTES = 2_000_000L;

    /**
     * Total cap (bytes) across every language page for ONE object. Several pages can each sit under
     * {@link #MAX_HELP_FILE_BYTES} yet still multiply the read+convert cost per requested language;
     * once the running total would exceed this, remaining pages are skipped (reported once, not
     * per-file) rather than read.
     */
    static final long MAX_TOTAL_HELP_BYTES = 5_000_000L;

    private ObjectHelpReader()
    {
    }

    /** One authored help page: its language code (from the file name) and the help text as Markdown. */
    public static final class HelpPage
    {
        public final String lang;
        public final String markdown;

        public HelpPage(String lang, String markdown)
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
     * <p>
     * Resolves the object's own resource folder via {@link IResourceLookup#getPlatformResource(EObject)}.
     * For a TOP metadata object (Catalog / Document / Report / ... its own {@code .mdo}) this is the
     * object's own folder. For an OWNED form ({@code BasicForm}, e.g. {@code Catalog.Foo.Form.Bar}),
     * {@code getPlatformResource} resolves to the form's OWNER's {@code .mdo} instead of the form's own
     * {@code Forms/<FormName>/} folder - callers that need an owned form's OWN help must resolve its
     * folder path themselves (e.g. via {@code MetadataPathResolver.resolveFormFolderPath}) and call
     * {@link #readFromFolder(IProject, String)} instead.
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
        return readHelpPages(helpDir, project);
    }

    /**
     * The authored help pages under an EXPLICIT object folder, relative to the project root - for an
     * object whose own resource folder cannot be found via {@link #read(EObject)}'s generic
     * {@link IResourceLookup} hop. An OWNED form's {@code BasicForm} resolves, through
     * {@code getPlatformResource}, to its OWNER's {@code .mdo} (the catalog/document/etc.), not the
     * form's own {@code Forms/<FormName>/} folder - the caller resolves that folder itself (e.g. via
     * {@code MetadataPathResolver.resolveFormFolderPath}) and passes it here. Same symlink protection and
     * empty-list contract as {@link #read(EObject)}.
     *
     * @param project the owning project; {@code null} or an unlocated project yields an empty list
     * @param objectFolderRelativePath the object's own folder, relative to the project root (e.g.
     *     {@code "src/Catalogs/Foo/Forms/Bar"}); {@code null} yields an empty list
     */
    public static List<HelpPage> readFromFolder(IProject project, String objectFolderRelativePath)
    {
        if (project == null || project.getLocation() == null || objectFolderRelativePath == null)
        {
            return Collections.emptyList();
        }
        File helpDir = project.getLocation().append(objectFolderRelativePath).append(HELP_DIR).toFile();
        return readHelpPages(helpDir, project);
    }

    /**
     * Reads every {@code <lang>.html} page under {@code helpDir}, guarding against a symlink escape:
     * both {@code helpDir} itself and each individual file must resolve (via {@code toRealPath()}) to
     * a location actually INSIDE the project, and each file's real parent must be the resolved
     * {@code helpDir} itself (not merely "somewhere under the project" - see the per-file check below).
     */
    private static List<HelpPage> readHelpPages(File helpDir, IProject project)
    {
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
        long totalBytesRead = 0;
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
                // Checked BEFORE any read: a large or corrupt file must not be pulled fully into memory
                // (and run through HTML->Markdown conversion) just to be truncated afterwards by
                // OutputSizeGuard on the final response text - that wastes memory/CPU on the UI thread,
                // multiplied by however many language pages exist.
                long size = Files.size(real);
                if (size > MAX_HELP_FILE_BYTES)
                {
                    pages.add(new HelpPage(lang, "_(this help page is " + size + " bytes, over the " //$NON-NLS-1$ //$NON-NLS-2$
                        + MAX_HELP_FILE_BYTES + "-byte per-file limit; not rendered - open Help/" //$NON-NLS-1$
                        + name + " directly)_")); //$NON-NLS-1$
                    continue;
                }
                if (totalBytesRead + size > MAX_TOTAL_HELP_BYTES)
                {
                    pages.add(new HelpPage(lang, "_(skipped: the total authored-help size for this " //$NON-NLS-1$
                        + "object exceeds the " + MAX_TOTAL_HELP_BYTES + "-byte budget across all " //$NON-NLS-1$ //$NON-NLS-2$
                        + "languages; open Help/" + name + " directly)_")); //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }
                String html = new String(Files.readAllBytes(real), StandardCharsets.UTF_8);
                totalBytesRead += size;
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
