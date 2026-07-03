/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.md.MdPlugin;
import com._1c.g5.v8.dt.md.pictures.MdPictureManifestProvider;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.platform.pictures.IPictureManifest;
import com._1c.g5.v8.dt.platform.pictures.IPictureManifestQuery;
import com._1c.g5.v8.dt.platform.pictures.PictureDirectionVariant;
import com._1c.g5.v8.dt.platform.pictures.PictureInterfaceVariant;
import com._1c.g5.v8.dt.platform.pictures.PictureThemeVariant;
import com._1c.g5.v8.dt.platform.pictures.PictureVariantScreenDensity;
import com._1c.g5.v8.dt.platform.pictures.zip.IZipPictureContent;
import com._1c.g5.v8.dt.platform.pictures.zip.IZipPictureManifest;
import com._1c.g5.v8.dt.platform.pictures.zip.IZipPictureManifestEntry;
import com.e1c.g5.v8.dt.svg.SVGUtils;
import com.google.inject.Injector;

/**
 * The single class that touches the EDT picture-content API. It reads a
 * {@code CommonPicture}'s {@code Picture.zip} container: enumerates its variants
 * and decodes any one of them to PNG.
 * <p>
 * Clean-room from the public EDT API. Inspired by keyfire/edt-bridge's
 * {@code edt_picture_export} (Apache-2.0) for the idea of exporting a common
 * picture's content to an image for AI review; no source was copied.
 * <p>
 * Content access chain (E1-A, pinned against the target jars via {@code javap} of
 * {@code com._1c.g5.v8.dt.md.pictures.MdPictureManifestProvider},
 * {@code com._1c.g5.v8.dt.platform.pictures.*} and
 * {@code com._1c.g5.v8.dt.metadata.mdclass.CommonPicture}):
 * <ol>
 * <li>{@link MdPictureManifestProvider} is obtained from the {@link MdPlugin}
 * Guice injector (the same injector {@code CreateMetadataTool} uses for the MD
 * model-object factory). Its ctor is {@code (IBmModelManager,
 * ITopObjectFqnGenerator)} — both are bound in the MD injector, so Guice can
 * construct it.</li>
 * <li>{@code getPictureManifest(mcore.Picture)} — a {@code CommonPicture}
 * <em>is</em> an {@code mcore.PictureDef} (which extends {@code mcore.Picture}),
 * so the resolved model object is passed straight through.</li>
 * <li>A picture with several variants yields an {@link IZipPictureManifest};
 * {@code getZipPictureContent()} returns the {@link IZipPictureContent}. A single
 * (variant-less) picture yields a plain {@link IPictureManifest} — a valid, ordinary
 * single-image picture whose image is stored as a loose {@code Picture.png}/{@code .svg}
 * next to the {@code .mdo} (NOT inside a {@code Picture.zip}). Both cases are read here
 * through the internal {@link PictureContent} abstraction: the multi-variant one wraps the
 * {@link IZipPictureContent}; the single-image one exposes exactly one synthetic variant whose
 * bytes come from {@code IPictureManifest.getInputStream(IPictureManifestQuery.DEFAULT)}. So a
 * single-image picture yields a one-element variant list and {@link #exportPng} returns its PNG
 * (previously it was silently reported as empty — the defect this class now fixes).</li>
 * <li>Multi-variant entries come from {@link IZipPictureContent#getPictureEntries()} as
 * {@link IZipPictureManifestEntry} (the {@code manifest.xml} bookkeeping entry is
 * skipped). Bytes come from {@code getBufferedImageByName} (raster) or, for the
 * vector variant, {@code getInputStreamByName} + {@link SVGUtils}. In all cases decoding
 * additionally falls back to {@link ImageIO#read(InputStream)} on the raw entry bytes when the
 * zip API declares an entry it will not itself decode (e.g. a loose single-image {@code Picture.png}).</li>
 * </ol>
 * <p>
 * The provider reads through the <em>current</em> BM transaction, so every call
 * MUST run inside a {@code BmTransactions.read} boundary opened by the caller;
 * this class receives the already-resolved model object and never opens a
 * transaction itself. It never touches SWT/JFace and always emits PNG.
 */
public final class CommonPictureContentReader
{
    /** The bookkeeping entry inside {@code Picture.zip}; never a real variant. */
    private static final String MANIFEST_ENTRY_NAME = IZipPictureContent.MANIFEST_ENTRY_NAME;

    /** PNG media type emitted for every decoded/rasterized variant. */
    private static final String CONTENT_TYPE_PNG = "image/png"; //$NON-NLS-1$

    /** Rendered literal for a nullable/absent 1C enum value. */
    private static final String NONE = "-"; //$NON-NLS-1$

    /** {@code variant} selector: the best (densest) raster variant. */
    private static final String VARIANT_BEST = "best"; //$NON-NLS-1$

    /** {@code variant} selector: the vector (SVG) variant. */
    private static final String VARIANT_SVG = "svg"; //$NON-NLS-1$

    /** ImageIO informal format name for PNG. */
    private static final String PNG_FORMAT = "png"; //$NON-NLS-1$

    /** Lowercase suffix identifying an SVG (vector) variant entry. */
    private static final String SVG_SUFFIX = ".svg"; //$NON-NLS-1$

    /**
     * Synthetic entry name for the single image of a variant-less picture. It mirrors the loose file
     * stored next to the {@code .mdo} ({@code Picture.png}) so the reported/selectable variant name is
     * familiar; a single-image SVG is reported as {@code Picture.svg} so the SVG rasterization branch
     * kicks in.
     */
    private static final String SINGLE_IMAGE_ENTRY_PNG = "Picture.png"; //$NON-NLS-1$

    /** Synthetic entry name for the single image of a variant-less SVG picture. */
    private static final String SINGLE_IMAGE_ENTRY_SVG = "Picture.svg"; //$NON-NLS-1$

    /** The MD-injector-provided manifest provider; obtained lazily in the ctor. */
    private final MdPictureManifestProvider manifestProvider;

    /**
     * Creates the reader, resolving {@link MdPictureManifestProvider} from the
     * {@link MdPlugin} Guice injector (mirrors how {@code CreateMetadataTool}
     * obtains its MD model-object factory).
     *
     * @throws Exception if the MD plugin/injector is not available or the provider
     *             cannot be constructed
     */
    public CommonPictureContentReader() throws Exception
    {
        MdPlugin mdPlugin = MdPlugin.getDefault();
        if (mdPlugin == null)
        {
            throw new IllegalStateException("MD plugin is not available"); //$NON-NLS-1$
        }
        Injector injector = mdPlugin.getInjector();
        if (injector == null)
        {
            throw new IllegalStateException("MD plugin injector is not available"); //$NON-NLS-1$
        }
        this.manifestProvider = injector.getInstance(MdPictureManifestProvider.class);
    }

    /**
     * Lists the picture variants of a common picture. The caller must have opened a
     * {@code BmTransactions.read} boundary; the provider reads through the current
     * transaction.
     *
     * @param commonPicture the resolved {@code CommonPicture} model object (an
     *            {@code mcore.Picture}); must not be {@code null}
     * @return the variant inventory (never {@code null}; empty when the picture has no
     *         multi-variant {@code Picture.zip}, e.g. an ordinary single-image picture —
     *         a valid case, not corruption)
     * @throws Exception on I/O or model-access failure
     */
    public List<PictureVariantInfo> listVariants(EObject commonPicture) throws Exception
    {
        List<PictureVariantInfo> result = new ArrayList<>();
        PictureContent content = contentOf(commonPicture);
        if (content == null)
        {
            return result;
        }
        for (String name : content.variantNames())
        {
            result.add(content.variantInfo(name));
        }
        return result;
    }

    /**
     * Decodes one variant of a common picture to PNG. The caller must have opened a
     * {@code BmTransactions.read} boundary.
     *
     * @param commonPicture the resolved {@code CommonPicture} model object; must not
     *            be {@code null}
     * @param variant which variant to decode: {@code "best"} (densest raster),
     *            {@code "svg"} (the vector variant), an exact entry name from
     *            {@link #listVariants}, or {@code null} to skip (returns
     *            {@code null})
     * @return the PNG result, or {@code null} when {@code variant} is {@code null}
     *         or cannot be resolved to a decodable entry
     * @throws Exception on I/O or model-access failure
     */
    public PngResult exportPng(EObject commonPicture, String variant) throws Exception
    {
        if (variant == null || variant.trim().isEmpty())
        {
            return null;
        }
        PictureContent content = contentOf(commonPicture);
        if (content == null)
        {
            return null;
        }
        String selector = variant.trim();
        String selected;
        if (VARIANT_BEST.equalsIgnoreCase(selector))
        {
            // 'best' = the densest RASTER variant by explicit screen-density rank (NOT entry order);
            // fall back to the first entry for an SVG-only picture (and the single synthetic entry of a
            // variant-less single-image picture).
            selected = content.selectBestRasterName();
            if (selected == null)
            {
                List<String> names = content.variantNames();
                selected = names.isEmpty() ? null : names.get(0);
            }
        }
        else
        {
            selected = selectVariantName(content.variantNames(), selector);
        }
        if (selected == null)
        {
            return null;
        }
        byte[] png = content.decodeToPng(selected);
        if (png == null)
        {
            // The entry EXISTS but could not be decoded (unsupported/corrupt content). Distinguish this
            // from an unknown variant (which returns null) so the caller reports it accurately. Name the
            // CommonPicture too (its programmatic name), so a failure in the workspace log identifies the
            // exact picture and not merely the variant (e.g. the shared "Picture.png" entry name).
            throw new IOException("Could not decode variant '" + selected //$NON-NLS-1$
                + "' of CommonPicture " + pictureName(commonPicture) //$NON-NLS-1$
                + " (unsupported or corrupt content)."); //$NON-NLS-1$
        }
        return new PngResult(selected, CONTENT_TYPE_PNG, png.length, Base64.getEncoder().encodeToString(png));
    }

    /**
     * Picks the densest raster candidate: the highest density rank wins, ties break on the larger
     * raw size then first-seen. Returns {@code null} for an empty list. Pure (no model access) so it
     * is unit-testable in isolation; {@link ZipPictureContent#selectBestRasterName} builds the
     * candidates from the zip content (skipping SVG/manifest entries) — this is the ONLY {@code "best"}
     * raster-selection logic the tool runs.
     *
     * @param candidates the raster candidates (already SVG/manifest-filtered)
     * @return the densest candidate's name, or {@code null} when the list is empty
     */
    static String pickDensest(List<RasterCandidate> candidates)
    {
        String bestName = null;
        int bestRank = Integer.MIN_VALUE;
        long bestSize = -1L;
        for (RasterCandidate c : candidates)
        {
            if (c.densityRank > bestRank || (c.densityRank == bestRank && c.size > bestSize))
            {
                bestRank = c.densityRank;
                bestSize = c.size;
                bestName = c.name;
            }
        }
        return bestName;
    }

    /**
     * One raster candidate for the {@code "best"} density ranking: the entry name plus its screen-
     * density rank ({@link PictureVariantScreenDensity} ordinal, or {@code -1} when unset) and raw
     * byte size (the tie-breaker). A small value holder so {@link #pickDensest} stays pure/testable.
     */
    static final class RasterCandidate
    {
        final String name;
        final int densityRank;
        final long size;

        RasterCandidate(String name, int densityRank, long size)
        {
            this.name = name;
            this.densityRank = densityRank;
            this.size = size;
        }
    }

    // ---------------------------------------------------------------------
    // Picture-API access (the only methods that touch the model / picture jars)
    // ---------------------------------------------------------------------

    /**
     * Resolves the {@link PictureContent} of a picture: a {@link ZipPictureContent} wrapping the
     * multi-variant {@link IZipPictureContent}, or a {@link SinglePictureContent} for an ordinary
     * variant-less single-image picture (a plain {@link IPictureManifest} whose one image is a loose
     * {@code Picture.png}/{@code .svg} next to the {@code .mdo}). Returns {@code null} when the object
     * is not a {@link Picture}.
     *
     * @param commonPicture the resolved model object (an {@code mcore.Picture})
     * @return the unified picture content, or {@code null} when the object is not a picture
     * @throws Exception on I/O or model-access failure
     */
    private PictureContent contentOf(EObject commonPicture) throws Exception
    {
        if (!(commonPicture instanceof Picture))
        {
            return null;
        }
        IPictureManifest manifest = manifestProvider.getPictureManifest((Picture)commonPicture);
        if (manifest instanceof IZipPictureManifest)
        {
            IZipPictureContent zip = ((IZipPictureManifest)manifest).getZipPictureContent();
            return zip == null ? null : new ZipPictureContent(zip);
        }
        if (manifest == null || manifest == IPictureManifest.UNRESOLVED_PICTURE_MANIFEST)
        {
            return null;
        }
        // A variant-less single-image picture: a plain IPictureManifest. Its image bytes come from
        // getInputStream(IPictureManifestQuery.DEFAULT) (the raw loose Picture.png/.svg). Previously
        // this arm was skipped, so single-image pictures showed "No variants" and failed to export.
        return new SinglePictureContent(manifest);
    }

    /**
     * Rasterizes non-SVG raw bytes to PNG via {@link ImageIO}, returning the re-encoded PNG (or the
     * original bytes when they already are a decodable PNG), or {@code null} when {@link ImageIO} cannot
     * decode them. Shared by both content kinds: it is the fallback for a raster entry the zip API
     * declares but will not itself decode (e.g. a loose single-image {@code Picture.png}).
     *
     * @param raw the raw entry bytes (may be {@code null})
     * @return the PNG bytes, or {@code null} when {@code raw} is {@code null}/empty or not decodable
     * @throws IOException on an I/O failure while re-encoding
     */
    static byte[] rasterBytesToPng(byte[] raw) throws IOException
    {
        if (raw == null || raw.length == 0)
        {
            return null;
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
        if (image == null)
        {
            return null;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, PNG_FORMAT, baos);
        return baos.toByteArray();
    }

    /**
     * Rasterizes SVG raw bytes to PNG bytes via {@link SVGUtils} (reflectively, see
     * {@link #renderSvgToPngCompat(InputStream)}). Returns {@code null} when the SVG cannot be
     * rasterized. Shared by both content kinds.
     *
     * @param raw the raw SVG bytes (may be {@code null})
     * @return the PNG bytes, or {@code null} when {@code raw} is {@code null}/empty or not rasterizable
     * @throws Exception on an I/O or reflective failure
     */
    private static byte[] svgBytesToPng(byte[] raw) throws Exception
    {
        if (raw == null || raw.length == 0)
        {
            return null;
        }
        // Vector branch: rasterize via SVGUtils, called REFLECTIVELY so the reader works on both
        // EDT targets (see renderSvgToPngCompat / its javadoc).
        try (InputStream svgIn = new ByteArrayInputStream(raw))
        {
            InputStream pngIn = renderSvgToPngCompat(svgIn);
            if (pngIn == null)
            {
                return null;
            }
            try
            {
                return readAll(pngIn);
            }
            finally
            {
                pngIn.close();
            }
        }
    }

    /**
     * Decodes one entry's raw bytes to PNG bytes: a raster entry via {@link #rasterBytesToPng(byte[])}
     * ({@link ImageIO}); an SVG entry (by name) via {@link #svgBytesToPng(byte[])} ({@link SVGUtils}).
     * <p>
     * A raster (non-SVG) entry is decoded by {@link ImageIO}: it handles raster formats but returns
     * {@code null} for an SVG stream, so the vector branch cannot be folded into it. That vector branch
     * is the ONLY use of {@code com.e1c.g5.v8.dt.svg} in this reader.
     * <p>
     * <b>Target portability (2025.2 AND 2026.1):</b> the SVG rasterizer signature differs by EDT
     * version, so it is invoked reflectively via {@link #renderSvgToPngCompat(InputStream)} (2025.2's
     * 1-arg {@code renderSvgToPng(InputStream)} or 2026.1's 3-arg form); the MANIFEST import spans
     * both ranges ({@code com.e1c.g5.v8.dt.svg [1.0.0,3.0.0)}).
     *
     * @param name the entry name (its {@code .svg} suffix routes to the vector branch)
     * @param raw the raw entry bytes (may be {@code null})
     * @return PNG bytes, or {@code null} when the entry cannot be decoded
     * @throws Exception on I/O or reflective failure
     */
    private static byte[] decodeBytesToPng(String name, byte[] raw) throws Exception
    {
        if (!isSvgName(name))
        {
            byte[] png = rasterBytesToPng(raw);
            if (png != null)
            {
                return png;
            }
        }
        return svgBytesToPng(raw);
    }

    /**
     * Rasterizes an SVG stream to a PNG stream via {@link SVGUtils}, invoked REFLECTIVELY to stay
     * portable across EDT targets: 2025.2 ({@code svg 1.0.x}) exposes
     * {@code renderSvgToPng(InputStream)}; 2026.1 ({@code svg 2.0.x}) replaced it with
     * {@code renderSvgToPng(InputStream, boolean, String)}. Whichever signature the running platform
     * provides is called (the 3-arg form gets {@code false, null} to keep the plain PNGTranscoder
     * behaviour). The MANIFEST import spans both ranges ({@code [1.0.0,3.0.0)}).
     *
     * @param svgIn the SVG input stream
     * @return the PNG input stream, or {@code null} when neither signature is present
     * @throws Exception on reflective invocation failure
     */
    private static InputStream renderSvgToPngCompat(InputStream svgIn) throws Exception
    {
        Object svgUtils = SVGUtils.INSTANCE;
        Class<?> cls = svgUtils.getClass();
        try
        {
            Method oneArg = cls.getMethod("renderSvgToPng", InputStream.class); //$NON-NLS-1$
            return (InputStream)oneArg.invoke(svgUtils, svgIn);
        }
        catch (NoSuchMethodException e)
        {
            try
            {
                Method threeArg =
                    cls.getMethod("renderSvgToPng", InputStream.class, boolean.class, String.class); //$NON-NLS-1$
                return (InputStream)threeArg.invoke(svgUtils, svgIn, Boolean.FALSE, null);
            }
            catch (NoSuchMethodException e2)
            {
                return null;
            }
        }
    }

    // ---------------------------------------------------------------------
    // Unified picture content (zip multi-variant OR single-image)
    // ---------------------------------------------------------------------

    /**
     * The content of a common picture, abstracting over the two shapes a picture takes on disk:
     * <ul>
     * <li>a multi-variant picture stored in a {@code Picture.zip} ({@link ZipPictureContent}); and</li>
     * <li>an ordinary variant-less single-image picture stored as a loose {@code Picture.png}/{@code .svg}
     * next to the {@code .mdo} ({@link SinglePictureContent}).</li>
     * </ul>
     * {@link #listVariants} and {@link #exportPng} drive both through this one interface, so a
     * single-image picture yields exactly one variant instead of being silently skipped.
     */
    private interface PictureContent
    {
        /** @return the (non-manifest) variant/entry names, in enumeration order (never {@code null}). */
        List<String> variantNames();

        /**
         * @param name a variant/entry name
         * @return the variant descriptor for {@code name}
         */
        PictureVariantInfo variantInfo(String name);

        /**
         * @return the densest raster variant name for the {@code "best"} selector, or {@code null} when
         *         there is no raster variant (e.g. an SVG-only picture)
         */
        String selectBestRasterName();

        /**
         * Decodes one variant/entry to PNG bytes.
         *
         * @param name the variant/entry name
         * @return the PNG bytes, or {@code null} when the entry cannot be decoded
         * @throws Exception on I/O or reflective failure
         */
        byte[] decodeToPng(String name) throws Exception;
    }

    /**
     * {@link PictureContent} over the multi-variant {@link IZipPictureContent} of a {@code Picture.zip}
     * (the pre-existing behaviour, unchanged: entries, {@code "best"} density ranking, raster/SVG decode).
     */
    private static final class ZipPictureContent implements PictureContent
    {
        private final IZipPictureContent content;

        ZipPictureContent(IZipPictureContent content)
        {
            this.content = content;
        }

        @Override
        public List<String> variantNames()
        {
            List<String> names = new ArrayList<>();
            for (IZipPictureManifestEntry entry : content.getPictureEntries())
            {
                if (entry == null || isManifestEntry(entry.getName()))
                {
                    continue;
                }
                names.add(entry.getName());
            }
            return names;
        }

        @Override
        public PictureVariantInfo variantInfo(String name)
        {
            IZipPictureManifestEntry entry = entryByName(name);
            long sizeBytes = sizeOf(name);
            if (entry == null)
            {
                PictureVariantInfo info = new PictureVariantInfo();
                info.name = name;
                info.dpi = NONE;
                info.theme = NONE;
                info.interfaceVariant = NONE;
                info.pictureDirection = NONE;
                info.contentType = CONTENT_TYPE_PNG;
                info.sizeBytes = sizeBytes;
                return info;
            }
            // Typed locals so each nullable 1C enum is read explicitly (and the enum
            // types are used, not merely referenced from javadoc).
            PictureVariantScreenDensity density = entry.getScreenDensity();
            PictureThemeVariant theme = entry.getTheme();
            PictureInterfaceVariant interfaceVariant = entry.getInterfaceVariant();
            PictureDirectionVariant direction = entry.getPictureDirection();

            PictureVariantInfo info = new PictureVariantInfo();
            info.name = entry.getName();
            info.dpi = mapEnumLiteral(density);
            info.theme = mapEnumLiteral(theme);
            info.interfaceVariant = mapEnumLiteral(interfaceVariant);
            info.pictureDirection = mapEnumLiteral(direction);
            info.template = entry.isTemplate();
            info.glyphWidth = entry.getGlyphWidth();
            info.glyphHeight = entry.getGlyphHeight();
            info.contentType = CONTENT_TYPE_PNG;
            info.sizeBytes = sizeBytes;
            return info;
        }

        @Override
        public String selectBestRasterName()
        {
            List<RasterCandidate> candidates = new ArrayList<>();
            for (IZipPictureManifestEntry entry : content.getPictureEntries())
            {
                if (entry == null)
                {
                    continue;
                }
                String name = entry.getName();
                if (name == null || isManifestEntry(name) || isSvgName(name))
                {
                    continue;
                }
                PictureVariantScreenDensity density = entry.getScreenDensity();
                int rank = density != null ? density.ordinal() : -1;
                candidates.add(new RasterCandidate(name, rank, sizeOf(name)));
            }
            return pickDensest(candidates);
        }

        @Override
        public byte[] decodeToPng(String name) throws Exception
        {
            // Prefer the zip API's own raster decode (getBufferedImageByName), preserving byte-identical
            // multi-variant behaviour; fall back to raw-bytes ImageIO/SVG when it declares an entry it will
            // not itself decode. Both the raster and raw-bytes reads probe case-variant spellings so a
            // manifest/byte-entry case mismatch (manifest "Picture.png" vs stored "picture.png") still
            // decodes instead of throwing.
            if (!isSvgName(name))
            {
                BufferedImage image = bufferedImage(name);
                if (image != null)
                {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(image, PNG_FORMAT, baos);
                    return baos.toByteArray();
                }
            }
            return decodeBytesToPng(name, rawBytes(name));
        }

        /**
         * Reads one entry's decoded raster image, tolerating a manifest/byte-entry case mismatch: it
         * probes the enumerated name first, then its lower/upper-cased spellings (see
         * {@link #candidateNames(String)}). Returns {@code null} when no spelling yields a decodable image.
         *
         * @param name the enumerated (manifest) variant name
         * @return the decoded image, or {@code null} when none of the case spellings resolves
         */
        private BufferedImage bufferedImage(String name)
        {
            for (String candidate : candidateNames(name))
            {
                BufferedImage image = content.getBufferedImageByName(candidate);
                if (image != null)
                {
                    return image;
                }
            }
            return null;
        }

        /**
         * Finds the entry with the given name.
         *
         * @param name the entry name
         * @return the entry, or {@code null} when not present
         */
        private IZipPictureManifestEntry entryByName(String name)
        {
            for (IZipPictureManifestEntry entry : content.getPictureEntries())
            {
                if (entry != null && name != null && name.equals(entry.getName()))
                {
                    return entry;
                }
            }
            return null;
        }

        /**
         * Reads one entry's raw bytes fully, or {@code null} when the entry cannot be read. Tolerates a
         * manifest/byte-entry case mismatch by probing case-variant spellings (see
         * {@link #resolveInputStream(String)}).
         *
         * @param name the entry name
         * @return the raw bytes, or {@code null}
         * @throws Exception on I/O failure
         */
        private byte[] rawBytes(String name) throws Exception
        {
            Optional<ByteArrayInputStream> raw = resolveInputStream(name);
            if (!raw.isPresent())
            {
                return null;
            }
            try (InputStream in = raw.get())
            {
                return readAll(in);
            }
        }

        /**
         * Returns the byte size of one entry's raw content, or {@code 0} when it cannot be read. Tolerates
         * a manifest/byte-entry case mismatch by probing case-variant spellings (see
         * {@link #resolveInputStream(String)}).
         *
         * @param name the entry name
         * @return the size in bytes, or {@code 0}
         */
        private long sizeOf(String name)
        {
            Optional<ByteArrayInputStream> raw = resolveInputStream(name);
            if (raw.isPresent())
            {
                return raw.get().available();
            }
            return 0L;
        }

        /**
         * Fetches an entry's raw byte stream, probing the enumerated name first and then its lower/upper-
         * cased spellings ({@link #candidateNames(String)}). The zip content's
         * {@code getInputStreamByName} is an exact, case-sensitive map lookup keyed by the ACTUAL byte-
         * entry name, so this is what recovers the bytes of a picture whose {@code manifest.xml} declares
         * a variant {@code "Picture.png"} while the zip stores it as {@code "picture.png"}. Returns the
         * first spelling that yields bytes, else {@link Optional#empty()}.
         *
         * @param name the enumerated (manifest) variant name
         * @return the byte stream of the first matching case spelling, or {@link Optional#empty()}
         */
        private Optional<ByteArrayInputStream> resolveInputStream(String name)
        {
            for (String candidate : candidateNames(name))
            {
                Optional<ByteArrayInputStream> raw = content.getInputStreamByName(candidate);
                if (raw.isPresent())
                {
                    return raw;
                }
            }
            return Optional.empty();
        }
    }

    /**
     * {@link PictureContent} over a variant-less single-image picture (a plain {@link IPictureManifest}
     * whose one image is a loose {@code Picture.png}/{@code .svg}). It exposes exactly one synthetic
     * variant ({@link #SINGLE_IMAGE_ENTRY_PNG}, or {@link #SINGLE_IMAGE_ENTRY_SVG} when the image is an
     * SVG) whose bytes come from {@code IPictureManifest.getInputStream(IPictureManifestQuery.DEFAULT)}.
     */
    private static final class SinglePictureContent implements PictureContent
    {
        private final IPictureManifest manifest;
        private byte[] cachedBytes;
        private boolean bytesRead;

        SinglePictureContent(IPictureManifest manifest)
        {
            this.manifest = manifest;
        }

        @Override
        public List<String> variantNames()
        {
            List<String> names = new ArrayList<>(1);
            names.add(entryName());
            return names;
        }

        @Override
        public PictureVariantInfo variantInfo(String name)
        {
            PictureVariantInfo info = new PictureVariantInfo();
            info.name = name != null ? name : entryName();
            // A single-image picture carries no per-variant dpi/theme/interface/direction bookkeeping;
            // report the neutral literal for each (matching the "-" the zip path uses for unset enums).
            info.dpi = NONE;
            info.theme = NONE;
            info.interfaceVariant = NONE;
            info.pictureDirection = NONE;
            info.contentType = CONTENT_TYPE_PNG;
            byte[] raw = rawBytesQuietly();
            info.sizeBytes = raw != null ? raw.length : 0L;
            return info;
        }

        @Override
        public String selectBestRasterName()
        {
            // The single image is the best (and only) raster variant unless it is an SVG (then 'best'
            // falls back to the first/only entry via the caller).
            String name = entryName();
            return isSvgName(name) ? null : name;
        }

        @Override
        public byte[] decodeToPng(String name) throws Exception
        {
            return decodeBytesToPng(name != null ? name : entryName(), rawBytes());
        }

        /**
         * The synthetic entry name: {@link #SINGLE_IMAGE_ENTRY_SVG} when the loose image decodes as SVG
         * (i.e. {@link ImageIO} cannot read it), else {@link #SINGLE_IMAGE_ENTRY_PNG}.
         *
         * @return the synthetic entry name
         */
        private String entryName()
        {
            byte[] raw = rawBytesQuietly();
            if (raw != null && raw.length > 0)
            {
                try
                {
                    if (ImageIO.read(new ByteArrayInputStream(raw)) == null)
                    {
                        // ImageIO cannot decode it -> treat as the vector (SVG) single image.
                        return SINGLE_IMAGE_ENTRY_SVG;
                    }
                }
                catch (IOException e) // NOSONAR an undecodable probe just falls through to the SVG name
                {
                    return SINGLE_IMAGE_ENTRY_SVG;
                }
            }
            return SINGLE_IMAGE_ENTRY_PNG;
        }

        /**
         * Reads (and caches) the single image's raw bytes from the manifest, or {@code null} when absent.
         *
         * @return the raw bytes, or {@code null}
         * @throws Exception on I/O failure
         */
        private byte[] rawBytes() throws Exception
        {
            if (!bytesRead)
            {
                Optional<ByteArrayInputStream> raw = manifest.getInputStream(IPictureManifestQuery.DEFAULT);
                if (raw.isPresent())
                {
                    try (InputStream in = raw.get())
                    {
                        cachedBytes = readAll(in);
                    }
                }
                bytesRead = true;
            }
            return cachedBytes;
        }

        /**
         * Reads the raw bytes swallowing I/O failure (returns {@code null}); used where a checked
         * exception would be noise (size, entry-name probe).
         *
         * @return the raw bytes, or {@code null}
         */
        private byte[] rawBytesQuietly()
        {
            try
            {
                return rawBytes();
            }
            catch (Exception e) // NOSONAR a size/name probe degrades to "unknown", never aborts the read
            {
                return null;
            }
        }
    }

    // ---------------------------------------------------------------------
    // Pure static helpers (unit-tested; no model / picture-jar dependency)
    // ---------------------------------------------------------------------

    /**
     * Maps a nullable 1C enum to a stable literal: {@code enum.name()} or {@code "-"}
     * when {@code null}. Kept generic (over {@link Enum}) so it is testable without
     * the picture-enum types and so all four nullable variant enums
     * ({@link PictureVariantScreenDensity}, {@link PictureThemeVariant},
     * {@link PictureInterfaceVariant}, {@link PictureDirectionVariant}) share one
     * mapping.
     *
     * @param value the enum value, may be {@code null}
     * @return the enum literal name, or {@code "-"} when {@code value} is
     *         {@code null}
     */
    static String mapEnumLiteral(Enum<?> value)
    {
        return value == null ? NONE : value.name();
    }

    /**
     * Whether an entry name is the {@code manifest.xml} bookkeeping entry (never a
     * real picture variant). Null-safe.
     *
     * @param name the entry name, may be {@code null}
     * @return {@code true} for the manifest entry
     */
    static boolean isManifestEntry(String name)
    {
        return name != null && MANIFEST_ENTRY_NAME.equalsIgnoreCase(name);
    }

    /**
     * Whether an entry name denotes a vector (SVG) variant, by its {@code .svg}
     * suffix (case-insensitive). Null-safe.
     *
     * @param name the entry name, may be {@code null}
     * @return {@code true} for an SVG entry
     */
    static boolean isSvgName(String name)
    {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(SVG_SUFFIX);
    }

    /**
     * The ordered list of name spellings to probe when fetching an entry's raw bytes, tolerating a
     * case mismatch between the {@code manifest.xml} variant name and the actual byte-entry name inside
     * {@code Picture.zip}. The zip content's {@code getInputStreamByName}/{@code getBufferedImageByName}
     * are an exact, case-sensitive map lookup keyed by the ACTUAL byte-entry name, whereas the enumerated
     * variant name comes from the manifest — and real 1C configurations ship pictures whose manifest
     * declares e.g. {@code "Picture.png"} while the zip stores the bytes as {@code "picture.png"} (seen
     * in ERP common pictures with the DPI-percentage variant scheme {@code 85/100/.../400.png}). Probing
     * the exact name first (so a correctly-cased picture stays byte-identical), then the lower/upper-cased
     * forms, recovers those bytes. Distinct spellings only, in probe order; never {@code null}.
     *
     * @param name the enumerated (manifest) variant name, may be {@code null}
     * @return the distinct case spellings to try, exact-first (empty when {@code name} is {@code null})
     */
    static List<String> candidateNames(String name)
    {
        List<String> candidates = new ArrayList<>(3);
        if (name == null)
        {
            return candidates;
        }
        candidates.add(name);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!candidates.contains(lower))
        {
            candidates.add(lower);
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (!candidates.contains(upper))
        {
            candidates.add(upper);
        }
        return candidates;
    }

    /**
     * Resolves a {@code variant} selector against the available entry names.
     * <ul>
     * <li>{@code "svg"} → the first SVG entry (or {@code null} when the picture has
     * no vector variant);</li>
     * <li>any other value → that value when it is an exact entry name, else
     * {@code null}.</li>
     * </ul>
     * The {@code svg} keyword is compared case-insensitively; exact entry names must match
     * exactly. Returns {@code null} for a {@code null}/blank selector or an empty entry list.
     * <p>
     * The {@code "best"} selector is NOT handled here — {@link #exportPng} routes it to the
     * density-ranked {@link ZipPictureContent#selectBestRasterName}; this method covers only
     * {@code svg}/exact.
     *
     * @param names the available (non-manifest) entry names
     * @param variant the selector: {@code "svg"} or an exact name
     * @return the resolved entry name, or {@code null}
     */
    static String selectVariantName(List<String> names, String variant)
    {
        if (names == null || names.isEmpty() || variant == null || variant.trim().isEmpty())
        {
            return null;
        }
        String selector = variant.trim();
        if (VARIANT_SVG.equalsIgnoreCase(selector))
        {
            for (String name : names)
            {
                if (isSvgName(name))
                {
                    return name;
                }
            }
            return null;
        }
        for (String name : names)
        {
            if (selector.equals(name))
            {
                return name;
            }
        }
        return null;
    }

    /**
     * The programmatic name of a picture model object for diagnostics, read from its EMF {@code name}
     * structural feature (present on {@code mdclass.CommonPicture}); {@code "<unknown>"} when the object
     * is {@code null}, has no {@code name} feature, or the value is blank. Read via EMF reflection so the
     * reader keeps its clean-room picture-API surface (no {@code mdclass} compile dependency) and works
     * whether it is handed the {@code mcore.Picture} view or the concrete {@code CommonPicture}.
     *
     * @param object the picture model object (may be {@code null})
     * @return the picture name, or {@code "<unknown>"} when it cannot be read
     */
    private static String pictureName(EObject object)
    {
        if (object == null)
        {
            return "<unknown>"; //$NON-NLS-1$
        }
        EStructuralFeature nameFeature = object.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
        if (nameFeature != null)
        {
            Object value = object.eGet(nameFeature);
            if (value instanceof String && !((String)value).trim().isEmpty())
            {
                return (String)value;
            }
        }
        return "<unknown>"; //$NON-NLS-1$
    }

    /**
     * Reads a stream fully into a byte array.
     *
     * @param in the stream (closed by the caller)
     * @return the bytes
     * @throws Exception on I/O failure
     */
    private static byte[] readAll(InputStream in) throws Exception
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1)
        {
            baos.write(buffer, 0, read);
        }
        return baos.toByteArray();
    }

    /**
     * Metadata about one picture variant (one entry inside {@code Picture.zip}).
     * Nullable 1C enums are already mapped to stable literal strings or {@code "-"};
     * {@code contentType} is always {@code image/png} (the format this reader emits).
     */
    public static final class PictureVariantInfo
    {
        /** The variant/entry name (as stored in the zip). */
        public String name;

        /** Screen-density literal ({@code MDPI}, {@code HDPI}, …) or {@code "-"}. */
        public String dpi;

        /** Theme-variant literal ({@code LIGHT}, {@code DARK}, …) or {@code "-"}. */
        public String theme;

        /** Interface-variant literal ({@code TAXI}, {@code VERSION8_5}, …) or {@code "-"}. */
        public String interfaceVariant;

        /** Direction-variant literal ({@code LTR}, {@code RTL}, …) or {@code "-"}. */
        public String pictureDirection;

        /** Whether the variant is a template (recolourable glyph). */
        public boolean template;

        /** Declared glyph width in pixels ({@code 0} when unset). */
        public int glyphWidth;

        /** Declared glyph height in pixels ({@code 0} when unset). */
        public int glyphHeight;

        /** Emitted media type; always {@code image/png}. */
        public String contentType;

        /** Raw content size of the variant in bytes ({@code 0} when unreadable). */
        public long sizeBytes;
    }

    /**
     * A single decoded variant, always PNG, base64-encoded.
     */
    public static final class PngResult
    {
        /** The resolved variant/entry name that was decoded. */
        public final String name;

        /** Emitted media type; always {@code image/png}. */
        public final String contentType;

        /** PNG byte size. */
        public final long sizeBytes;

        /** Base64-encoded PNG bytes. */
        public final String base64;

        /**
         * Creates a PNG result.
         *
         * @param name the resolved variant name
         * @param contentType the media type ({@code image/png})
         * @param sizeBytes the PNG byte size
         * @param base64 the base64-encoded PNG
         */
        public PngResult(String name, String contentType, long sizeBytes, String base64)
        {
            this.name = name;
            this.contentType = contentType;
            this.sizeBytes = sizeBytes;
            this.base64 = base64;
        }
    }
}
