/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.md.MdPlugin;
import com._1c.g5.v8.dt.md.pictures.MdPictureManifestProvider;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.platform.pictures.IPictureManifest;
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
 * single-image picture, NOT a corrupt one. This slice reads only the multi-variant
 * {@code Picture.zip} container, so such a picture is reported here as an empty variant
 * list (and {@link #exportPng} returns {@code null}); exporting a single-image picture
 * is a documented follow-up, not a defect.</li>
 * <li>Variants come from {@link IZipPictureContent#getPictureEntries()} as
 * {@link IZipPictureManifestEntry} (the {@code manifest.xml} bookkeeping entry is
 * skipped). Bytes come from {@code getBufferedImageByName} (raster) or, for the
 * vector variant, {@code getInputStreamByName} + {@link SVGUtils}.</li>
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
        IZipPictureContent content = zipContentOf(commonPicture);
        if (content == null)
        {
            return result;
        }
        for (IZipPictureManifestEntry entry : content.getPictureEntries())
        {
            if (entry == null || isManifestEntry(entry.getName()))
            {
                continue;
            }
            result.add(toVariantInfo(entry, sizeOf(content, entry.getName())));
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
        IZipPictureContent content = zipContentOf(commonPicture);
        if (content == null)
        {
            return null;
        }
        List<String> names = variantNames(content);
        String selected = selectVariantName(names, variant.trim());
        if (selected == null)
        {
            return null;
        }
        byte[] png = decodeToPng(content, selected);
        if (png == null)
        {
            return null;
        }
        return new PngResult(selected, CONTENT_TYPE_PNG, png.length, Base64.getEncoder().encodeToString(png));
    }

    // ---------------------------------------------------------------------
    // Picture-API access (the only methods that touch the model / picture jars)
    // ---------------------------------------------------------------------

    /**
     * Resolves the {@link IZipPictureContent} of a picture, or {@code null} when the
     * picture has no zip (multi-variant) content.
     *
     * @param commonPicture the resolved model object (an {@code mcore.Picture})
     * @return the zip content, or {@code null} when the manifest is a plain
     *         {@link IPictureManifest} (an ordinary single-image, variant-less picture —
     *         a valid case; this slice does not export single-image pictures)
     * @throws Exception on I/O or model-access failure
     */
    private IZipPictureContent zipContentOf(EObject commonPicture) throws Exception
    {
        if (!(commonPicture instanceof Picture))
        {
            return null;
        }
        IPictureManifest manifest = manifestProvider.getPictureManifest((Picture)commonPicture);
        if (!(manifest instanceof IZipPictureManifest))
        {
            return null;
        }
        return ((IZipPictureManifest)manifest).getZipPictureContent();
    }

    /**
     * Returns the byte size of one entry's raw content, or {@code 0} when it cannot
     * be read.
     *
     * @param content the zip content
     * @param name the entry name
     * @return the size in bytes, or {@code 0}
     */
    private static long sizeOf(IZipPictureContent content, String name)
    {
        Optional<ByteArrayInputStream> raw = content.getInputStreamByName(name);
        if (raw.isPresent())
        {
            return raw.get().available();
        }
        return 0L;
    }

    /**
     * Decodes one entry to PNG bytes: a raster entry via {@code getBufferedImageByName}
     * → {@link ImageIO}; a vector entry via {@code getInputStreamByName} +
     * {@link SVGUtils#renderSvgToPng(InputStream)}.
     * <p>
     * A raster (non-SVG) entry is decoded by {@code getBufferedImageByName}, which is
     * {@code ImageIO.read} under the hood: it handles raster formats but returns
     * {@code null} for an SVG stream, so the vector branch below cannot be folded into
     * it. That vector branch is the ONLY use of {@code com.e1c.g5.v8.dt.svg} in this
     * reader.
     * <p>
     * <b>Target portability (2025.2 → 2026.1):</b> the 1-arg
     * {@code SVGUtils.renderSvgToPng(InputStream)} exists only in
     * {@code com.e1c.g5.v8.dt.svg 1.0.x} (the current 2025.2 target); the 2026.1 target
     * ships {@code svg 2.0.x}, which removed it in favour of the 3-arg
     * {@code renderSvgToPng(InputStream, boolean, String)}. The MANIFEST therefore pins
     * the {@code com.e1c.g5.v8.dt.svg} import to {@code [1.0.0,2.0.0)} so a retarget
     * fails loudly at build time instead of silently resolving 2.0.x and breaking this
     * call. On the 2026.1 retarget this branch must be updated to the 3-arg form (pass
     * {@code false, null} to keep the plain PNGTranscoder behaviour) and the import
     * range widened.
     *
     * @param content the zip content
     * @param name the entry name
     * @return PNG bytes, or {@code null} when the entry cannot be decoded
     * @throws Exception on I/O failure
     */
    private static byte[] decodeToPng(IZipPictureContent content, String name) throws Exception
    {
        if (!isSvgName(name))
        {
            BufferedImage image = content.getBufferedImageByName(name);
            if (image != null)
            {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, PNG_FORMAT, baos);
                return baos.toByteArray();
            }
        }
        Optional<ByteArrayInputStream> raw = content.getInputStreamByName(name);
        if (!raw.isPresent())
        {
            return null;
        }
        // Vector branch: SVGUtils 1.0.x, pinned in the MANIFEST (see this method's
        // javadoc). Update to the 3-arg renderSvgToPng on the 2026.1 (svg 2.0.x) retarget.
        try (InputStream svgIn = raw.get(); InputStream pngIn = SVGUtils.INSTANCE.renderSvgToPng(svgIn))
        {
            if (pngIn == null)
            {
                return null;
            }
            return readAll(pngIn);
        }
    }

    /**
     * Collects the (non-manifest) variant entry names of a zip content.
     *
     * @param content the zip content
     * @return the entry names, in enumeration order
     */
    private static List<String> variantNames(IZipPictureContent content)
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

    /**
     * Builds a {@link PictureVariantInfo} from a manifest entry and its byte size.
     *
     * @param entry the (non-manifest) picture variant entry
     * @param sizeBytes the entry's raw byte size
     * @return the variant descriptor
     */
    private static PictureVariantInfo toVariantInfo(IZipPictureManifestEntry entry, long sizeBytes)
    {
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
     * Resolves a {@code variant} selector against the available entry names.
     * <ul>
     * <li>{@code "svg"} → the first SVG entry (or {@code null} when the picture has
     * no vector variant);</li>
     * <li>{@code "best"} → the last non-SVG entry, i.e. the densest raster (entries
     * are enumerated in ascending density order); falls back to the first entry when
     * every entry is SVG;</li>
     * <li>any other value → that value when it is an exact entry name, else
     * {@code null}.</li>
     * </ul>
     * The selector is compared case-insensitively for the {@code svg}/{@code best}
     * keywords; exact entry names must match exactly. Returns {@code null} for a
     * {@code null}/blank selector or an empty entry list.
     *
     * @param names the available (non-manifest) entry names
     * @param variant the selector: {@code "svg"}, {@code "best"} or an exact name
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
        if (VARIANT_BEST.equalsIgnoreCase(selector))
        {
            String best = null;
            for (String name : names)
            {
                if (!isSvgName(name))
                {
                    best = name;
                }
            }
            return best != null ? best : names.get(0);
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
