/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.pictures;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.pictures.CommonPicturesHtmlGenerator.GalleryPage;
import com.ditrix.edt.mcp.server.pictures.CommonPicturesHtmlGenerator.PicturePageEntry;
import com.ditrix.edt.mcp.server.pictures.CommonPicturesHtmlGenerator.Variant;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.CommonPictureContentReader;
import com.ditrix.edt.mcp.server.utils.CommonPictureContentReader.PictureVariantInfo;
import com.ditrix.edt.mcp.server.utils.CommonPictureContentReader.PngResult;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * The shared "render one gallery page" seam, reused by BOTH the initial open
 * ({@code ShowCommonPicturesHandler}) and every subsequent Prev/Next/search navigation
 * ({@code CommonPicturesGalleryEditor}'s {@code LocationListener}).
 * <p>
 * Given {@code (projectName, language, query, page, pageSize)} it re-resolves the project + live
 * {@link Configuration} FRESH (via {@link ProjectContext} — no live {@code Configuration} EObject is
 * ever held across UI events), then, inside a {@code BmTransactions.read} boundary on a background
 * {@link Job}:
 * <ol>
 * <li>filters the configuration's {@code CommonPictures} by NAME <b>and</b> synonym, case-insensitive
 * and bilingual (a blank query = all);</li>
 * <li>pages the FILTERED list (skip {@code page*pageSize}, take {@code pageSize});</li>
 * <li>decompresses ONLY that page's pictures through the shared {@link CommonPictureContentReader}
 * (same per-picture collect/error lifecycle the initial handler used);</li>
 * <li>hands a detached {@link GalleryPage} to the generator and delivers the HTML to the caller's
 * consumer.</li>
 * </ol>
 * The heavy work is entirely off the UI thread; the caller's {@code htmlConsumer} is invoked on the
 * Job thread and is responsible for hopping back to the UI thread (e.g. {@code Display.asyncExec}) to
 * push the HTML into the browser. Every failure is logged and never crashes the Job.
 * <p>
 * This class has no SWT/JFace dependency (the UI hop lives in the caller's consumer); the pure HTML
 * generation stays in {@link CommonPicturesHtmlGenerator}.
 */
public final class CommonPicturesGalleryRenderer
{
    /**
     * Page size = the pictures decompressed per page. Mirrors the {@code list_common_pictures} default
     * page size; only this page's pictures are ever decompressed, so a 2000+ picture configuration
     * stays bounded regardless of the filter.
     */
    public static final int PAGE_SIZE = 100;

    /** The best-thumbnail selector understood by {@link CommonPictureContentReader#exportPng}. */
    private static final String VARIANT_BEST = "best"; //$NON-NLS-1$

    private CommonPicturesGalleryRenderer()
    {
        // Static seam.
    }

    /**
     * Schedules the background {@link Job} that renders the requested gallery page and delivers its
     * HTML to {@code htmlConsumer} (invoked on the Job thread). Re-resolves the project + configuration
     * fresh, so a stale {@code Configuration} is never held across navigations.
     *
     * @param projectName the owning workspace project name (re-resolved here)
     * @param titlePrefix the editor tab title prefix; the configuration name is appended
     * @param language the synonym language CODE (may be {@code null})
     * @param query the search query (may be {@code null}/empty = all)
     * @param page the requested 0-based page index
     * @param htmlConsumer receives the rendered HTML on success (on the Job thread); never invoked on
     *            an unresolved project / model-unavailable failure (those are logged)
     */
    public static void scheduleRender(String projectName, String titlePrefix, String language, String query,
        int page, Consumer<RenderedPage> htmlConsumer)
    {
        Job job = new Job("Building Common Pictures overview: " + projectName) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    RenderedPage rendered = render(projectName, titlePrefix, language, query, page, monitor);
                    if (rendered != null)
                    {
                        htmlConsumer.accept(rendered);
                    }
                    return Status.OK_STATUS;
                }
                catch (Exception e) // NOSONAR any model/reader/UI failure must be logged, never crash the Job
                {
                    Activator.logError("Failed to build the Common Pictures overview for " + projectName, e); //$NON-NLS-1$
                    return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                        "Could not build the Common Pictures overview: " + e.getMessage(), e); //$NON-NLS-1$
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    /**
     * Re-resolves the project + configuration and renders one page to HTML inside a
     * {@code BmTransactions.read} boundary. Package-visible so the seam is directly exercisable; the
     * public entry point is {@link #scheduleRender}.
     *
     * @param projectName the owning workspace project name
     * @param titlePrefix the editor tab title prefix
     * @param language the synonym language CODE (may be {@code null})
     * @param query the search query (may be {@code null}/empty)
     * @param page the requested 0-based page index
     * @param monitor the progress monitor (may be {@code null})
     * @return the rendered page (HTML + title), or {@code null} when the project/configuration/model
     *         could not be resolved
     */
    static RenderedPage render(String projectName, String titlePrefix, String language, String query, int page,
        IProgressMonitor monitor)
    {
        ProjectContext.ConfigurationResult resolved = ProjectContext.resolveConfiguration(projectName);
        if (!resolved.ok())
        {
            Activator.logWarning("Show Common Pictures Overview: could not resolve the configuration for " //$NON-NLS-1$
                + projectName);
            return null;
        }
        IProject project = resolved.project();
        Configuration config = resolved.configuration();
        String configName = config.getName() != null ? config.getName() : projectName;

        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
        if (bmModelManager == null)
        {
            Activator.logWarning("Show Common Pictures Overview: IBmModelManager is not available."); //$NON-NLS-1$
            return null;
        }
        IBmModel bmModel = bmModelManager.getModel(project);
        if (bmModel == null)
        {
            Activator.logWarning("Show Common Pictures Overview: BM model is not available for " //$NON-NLS-1$
                + project.getName());
            return null;
        }

        // The synonym map is keyed by the language CODE; resolve the configuration default once.
        String lang = language != null ? language : MetadataLanguageUtils.resolveLanguageCode(config, null);

        // Every model read happens ONLY inside the BM read boundary (CLAUDE.md hard rule).
        GalleryPage galleryPage = BmTransactions.read(bmModel, "ShowCommonPictures", (tx, mon) -> //$NON-NLS-1$
            buildPage(config, configName, lang, query, page, monitor));

        String html = CommonPicturesHtmlGenerator.render(galleryPage);
        return new RenderedPage(html, titlePrefix + configName);
    }

    /**
     * Builds one {@link GalleryPage} inside the open read transaction: filter by name+synonym, page
     * the filtered list, and decompress only that page's pictures. Pictures outside the page are never
     * decompressed (only counted toward the filtered total, which drives «Всего» and the pager).
     *
     * @param config the resolved configuration
     * @param configName the configuration name for the page
     * @param language the resolved synonym language code (may be {@code null})
     * @param query the search query (may be {@code null}/empty)
     * @param page the requested 0-based page index
     * @param monitor the progress monitor (may be {@code null})
     * @return the detached gallery page
     */
    private static GalleryPage buildPage(Configuration config, String configName, String language, String query,
        int page, IProgressMonitor monitor)
    {
        CommonPictureContentReader reader = createReaderQuietly();

        // 1. Filter the WHOLE configuration by name + synonym (case-insensitive, bilingual).
        List<CommonPicture> filtered = filterPictures(config.getCommonPictures(), language, query);
        int filteredTotal = filtered.size();

        // 2. Page the filtered list (clamp the requested page into range).
        int pageCount = filteredTotal <= 0 ? 1 : (filteredTotal + PAGE_SIZE - 1) / PAGE_SIZE;
        int pageIndex = Math.min(Math.max(page, 0), pageCount - 1);
        int from = pageIndex * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, filteredTotal);

        beginTask(monitor, "Rendering common pictures", Math.max(0, to - from)); //$NON-NLS-1$

        // 3. Decompress ONLY this page's pictures (same collect/error lifecycle as before).
        List<PicturePageEntry> entries = new ArrayList<>();
        for (int i = from; i < to; i++)
        {
            if (monitor != null && monitor.isCanceled())
            {
                break;
            }
            entries.add(buildEntry(reader, filtered.get(i), language));
            worked(monitor);
        }
        done(monitor);

        return new GalleryPage(configName, entries, filteredTotal, pageIndex, PAGE_SIZE, query);
    }

    /**
     * Filters the configuration's common pictures by NAME and synonym: a case-insensitive substring
     * match against either the programmatic {@code Name} or the chosen-language synonym. A blank query
     * matches everything (returns a defensive copy in model order).
     *
     * @param pictures the configuration's common pictures (model order)
     * @param language the resolved synonym language code (may be {@code null})
     * @param query the search query (may be {@code null}/empty)
     * @return the matching pictures, in model order (never {@code null})
     */
    private static List<CommonPicture> filterPictures(List<CommonPicture> pictures, String language, String query)
    {
        List<CommonPicture> result = new ArrayList<>();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT); //$NON-NLS-1$
        for (CommonPicture picture : pictures)
        {
            if (needle.isEmpty() || matches(picture, language, needle))
            {
                result.add(picture);
            }
        }
        return result;
    }

    /**
     * Whether a picture's name OR chosen-language synonym contains the (already-lowercased) needle.
     *
     * @param picture the common picture
     * @param language the resolved synonym language code (may be {@code null})
     * @param needle the lowercased search needle (non-empty)
     * @return {@code true} on a name or synonym substring match
     */
    private static boolean matches(CommonPicture picture, String language, String needle)
    {
        String name = picture.getName();
        if (name != null && name.toLowerCase(Locale.ROOT).contains(needle))
        {
            return true;
        }
        String synonym = synonymForLanguage(picture, language);
        return synonym != null && synonym.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * Builds one detached {@link PicturePageEntry} from a common picture: name + chosen-language
     * synonym, the best inline thumbnail, and one thumbnail per variant. A picture whose content
     * cannot be read is captured as a per-entry error and never aborts the page.
     *
     * @param reader the shared content reader, or {@code null} when it could not be constructed
     * @param picture the common picture (read inside the open transaction)
     * @param language the resolved synonym language code (may be {@code null})
     * @return the detached entry
     */
    private static PicturePageEntry buildEntry(CommonPictureContentReader reader, CommonPicture picture,
        String language)
    {
        PicturePageEntry entry = new PicturePageEntry();
        entry.name = picture.getName();
        entry.synonym = synonymForLanguage(picture, language);
        entry.variants = new ArrayList<>();

        if (reader == null)
        {
            entry.error = "The common-picture content reader is not available (MD plugin / injector missing)."; //$NON-NLS-1$
            return entry;
        }

        try
        {
            // 'best' inline thumbnail (densest raster / the synthetic Picture.png for a single-image
            // picture). A null result (a picture with no readable content) renders nothing.
            entry.best = toThumb(VARIANT_BEST, reader.exportPng(picture, VARIANT_BEST));

            // One thumbnail per variant so mismatched icons within one Picture.zip are visible.
            for (PictureVariantInfo variant : reader.listVariants(picture))
            {
                entry.variants.add(toVariantThumb(reader, picture, variant));
            }
        }
        catch (Exception e) // NOSONAR one unreadable/corrupt Picture.zip is surfaced per-picture, never aborts the page
        {
            Activator.logError("Could not read variants of common picture " //$NON-NLS-1$
                + (entry.name != null ? entry.name : "<unnamed>"), e); //$NON-NLS-1$
            entry.error = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        return entry;
    }

    /**
     * Decodes one variant of a picture to a {@link Variant}. A variant that fails to decode is still
     * listed with a {@code null} base64 image and the failure captured on its {@link Variant#error}, so
     * a single bad variant never hides the picture's other variants.
     *
     * @param reader the shared content reader
     * @param picture the common picture (read inside the open transaction)
     * @param variant the variant metadata already read from the reader
     * @return the variant thumbnail (never {@code null})
     */
    private static Variant toVariantThumb(CommonPictureContentReader reader, CommonPicture picture,
        PictureVariantInfo variant)
    {
        try
        {
            Variant decoded = toThumb(variant.name, reader.exportPng(picture, variant.name));
            return decoded != null ? decoded : new Variant(variant.name, null);
        }
        catch (Exception e) // NOSONAR one undecodable variant is logged and surfaced per-variant, never aborts the picture
        {
            Activator.logError("Could not decode variant " //$NON-NLS-1$
                + (variant.name != null ? variant.name : "<unnamed>") //$NON-NLS-1$
                + " of CommonPicture " + (picture.getName() != null ? picture.getName() : "<unnamed>"), e); //$NON-NLS-1$ //$NON-NLS-2$
            Variant thumb = new Variant();
            thumb.label = variant.name;
            thumb.base64Png = null;
            thumb.error = e.getMessage() != null ? e.getMessage() : e.toString();
            return thumb;
        }
    }

    /**
     * Builds a {@link Variant} from a decoded PNG result. A {@code null} result yields {@code null}.
     *
     * @param label the variant label / entry name
     * @param png the decoded PNG result, or {@code null}
     * @return the thumbnail, or {@code null} when {@code png} is {@code null}
     */
    private static Variant toThumb(String label, PngResult png)
    {
        if (png == null)
        {
            return null;
        }
        Variant thumb = new Variant();
        thumb.label = label != null ? label : png.name;
        thumb.base64Png = png.base64;
        return thumb;
    }

    /**
     * Reads a picture's synonym for the given language CODE out of its code-keyed synonym map.
     *
     * @param picture the common picture
     * @param language the preferred language code (may be {@code null})
     * @return the resolved synonym, or {@code ""} when none is available
     */
    private static String synonymForLanguage(CommonPicture picture, String language)
    {
        return MetadataLanguageUtils.getSynonymForLanguage(
            picture.getSynonym() == null ? null : picture.getSynonym().map(), language);
    }

    /**
     * Constructs the shared {@link CommonPictureContentReader}, degrading to {@code null} (and logging)
     * when the MD plugin / injector is unavailable — every picture then renders as a per-entry error
     * rather than the whole page aborting.
     *
     * @return the reader, or {@code null} when it could not be built
     */
    private static CommonPictureContentReader createReaderQuietly()
    {
        try
        {
            return new CommonPictureContentReader();
        }
        catch (Exception e) // NOSONAR a missing injector/plugin degrades every picture to an error, never aborts
        {
            Activator.logError("Could not create the common-picture content reader", e); //$NON-NLS-1$
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Progress-monitor helpers (null-safe)
    // ---------------------------------------------------------------------

    private static void beginTask(IProgressMonitor monitor, String name, int totalWork)
    {
        if (monitor != null)
        {
            monitor.beginTask(name, totalWork);
        }
    }

    private static void worked(IProgressMonitor monitor)
    {
        if (monitor != null)
        {
            monitor.worked(1);
        }
    }

    private static void done(IProgressMonitor monitor)
    {
        if (monitor != null)
        {
            monitor.done();
        }
    }

    /**
     * The rendered HTML of one gallery page plus its editor title. A detached value object handed to
     * the caller's consumer (which pushes it into the browser on the UI thread).
     */
    public static final class RenderedPage
    {
        private final String html;
        private final String title;

        /**
         * Creates a rendered page.
         *
         * @param html the rendered HTML document
         * @param title the editor tab title / part name
         */
        public RenderedPage(String html, String title)
        {
            this.html = html;
            this.title = title;
        }

        /** @return the rendered HTML document */
        public String html()
        {
            return html;
        }

        /** @return the editor tab title / part name */
        public String title()
        {
            return title;
        }
    }
}
