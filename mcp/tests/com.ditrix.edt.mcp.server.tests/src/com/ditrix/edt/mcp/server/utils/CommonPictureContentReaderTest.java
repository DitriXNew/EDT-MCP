/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.Test;

/**
 * Tests for {@link CommonPictureContentReader}'s pure, Display-free static helpers:
 * the nullable-enum → literal mapping, the manifest/SVG entry-name predicates, and
 * the {@code best}/{@code svg}/exact variant-selection logic. These need no live
 * model and no picture-jar services, so the whole class is headless (it does not
 * construct the reader, which would require the MD Guice injector).
 */
public class CommonPictureContentReaderTest
{
    /** A local enum standing in for the picture enums, so the test needs no picture jar. */
    private enum SampleEnum
    {
        LIGHT, DARK
    }

    // --- mapEnumLiteral ---------------------------------------------------

    @Test
    public void mapEnumLiteralReturnsNameForValue()
    {
        assertEquals("LIGHT", CommonPictureContentReader.mapEnumLiteral(SampleEnum.LIGHT)); //$NON-NLS-1$
        assertEquals("DARK", CommonPictureContentReader.mapEnumLiteral(SampleEnum.DARK)); //$NON-NLS-1$
    }

    @Test
    public void mapEnumLiteralReturnsDashForNull()
    {
        assertEquals("-", CommonPictureContentReader.mapEnumLiteral(null)); //$NON-NLS-1$
    }

    // --- isManifestEntry --------------------------------------------------

    @Test
    public void isManifestEntryDetectsManifestCaseInsensitively()
    {
        assertTrue(CommonPictureContentReader.isManifestEntry("manifest.xml")); //$NON-NLS-1$
        assertTrue(CommonPictureContentReader.isManifestEntry("MANIFEST.XML")); //$NON-NLS-1$
    }

    @Test
    public void isManifestEntryRejectsOthersAndNull()
    {
        assertFalse(CommonPictureContentReader.isManifestEntry("picture.png")); //$NON-NLS-1$
        assertFalse(CommonPictureContentReader.isManifestEntry("manifest.png")); //$NON-NLS-1$
        assertFalse(CommonPictureContentReader.isManifestEntry(null));
    }

    // --- isSvgName --------------------------------------------------------

    @Test
    public void isSvgNameDetectsSvgSuffixCaseInsensitively()
    {
        assertTrue(CommonPictureContentReader.isSvgName("icon.svg")); //$NON-NLS-1$
        assertTrue(CommonPictureContentReader.isSvgName("icon.SVG")); //$NON-NLS-1$
    }

    @Test
    public void isSvgNameRejectsRasterAndNull()
    {
        assertFalse(CommonPictureContentReader.isSvgName("icon.png")); //$NON-NLS-1$
        assertFalse(CommonPictureContentReader.isSvgName("svg.png")); //$NON-NLS-1$
        assertFalse(CommonPictureContentReader.isSvgName(null));
    }

    // --- selectVariantName: svg ------------------------------------------

    @Test
    public void selectSvgReturnsTheSvgEntry()
    {
        List<String> names = Arrays.asList("mdpi.png", "hdpi.png", "vector.svg"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("vector.svg", CommonPictureContentReader.selectVariantName(names, "svg")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void selectSvgReturnsNullWhenNoVectorVariant()
    {
        List<String> names = Arrays.asList("mdpi.png", "hdpi.png"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(CommonPictureContentReader.selectVariantName(names, "svg")); //$NON-NLS-1$
    }

    // --- pickDensest (the real "best" density ranking selectBestRasterName runs) ----------

    @Test
    public void pickDensestReturnsHighestDensityRank()
    {
        // Highest screen-density rank wins, independent of candidate order.
        List<CommonPictureContentReader.RasterCandidate> candidates = Arrays.asList(
            new CommonPictureContentReader.RasterCandidate("mdpi.png", 0, 100L), //$NON-NLS-1$
            new CommonPictureContentReader.RasterCandidate("hdpi.png", 2, 100L), //$NON-NLS-1$
            new CommonPictureContentReader.RasterCandidate("ldpi.png", 1, 100L)); //$NON-NLS-1$
        assertEquals("hdpi.png", CommonPictureContentReader.pickDensest(candidates)); //$NON-NLS-1$
    }

    @Test
    public void pickDensestTieBreaksOnLargerSize()
    {
        // Same density rank -> the larger raw size wins (not first-seen / entry order).
        List<CommonPictureContentReader.RasterCandidate> candidates = Arrays.asList(
            new CommonPictureContentReader.RasterCandidate("a.png", 3, 100L), //$NON-NLS-1$
            new CommonPictureContentReader.RasterCandidate("b.png", 3, 500L), //$NON-NLS-1$
            new CommonPictureContentReader.RasterCandidate("c.png", 3, 200L)); //$NON-NLS-1$
        assertEquals("b.png", CommonPictureContentReader.pickDensest(candidates)); //$NON-NLS-1$
    }

    @Test
    public void pickDensestReturnsNullForEmpty()
    {
        assertNull(CommonPictureContentReader.pickDensest(new ArrayList<>()));
    }

    // --- selectVariantName: exact ----------------------------------------

    @Test
    public void selectExactReturnsTheMatchingEntry()
    {
        List<String> names = Arrays.asList("mdpi.png", "hdpi.png", "vector.svg"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("mdpi.png", CommonPictureContentReader.selectVariantName(names, "mdpi.png")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void selectExactIsCaseSensitiveForEntryNames()
    {
        List<String> names = Arrays.asList("MdPi.png"); //$NON-NLS-1$
        assertNull(CommonPictureContentReader.selectVariantName(names, "mdpi.png")); //$NON-NLS-1$
    }

    @Test
    public void selectUnknownExactReturnsNull()
    {
        List<String> names = Arrays.asList("mdpi.png"); //$NON-NLS-1$
        assertNull(CommonPictureContentReader.selectVariantName(names, "nope.png")); //$NON-NLS-1$
    }

    // --- candidateNames: the manifest/byte-entry case-mismatch fallback --------------------------
    // The heart of the DPI-named-picture fix: a picture whose manifest.xml declares a variant
    // "Picture.png" while the zip stores its bytes as "picture.png" (case mismatch) must still decode.
    // candidateNames drives the case-tolerant byte lookup: exact name first (so a correctly-cased
    // picture stays byte-identical), then the lower/upper-cased spellings.

    @Test
    public void candidateNamesProbesExactThenLowerThenUpper()
    {
        // The real ERP case: manifest "Picture.png" -> the "picture.png" lowercase spelling recovers the
        // bytes. Exact is probed first (byte-identical for a correctly-cased entry), then lower, then upper.
        List<String> candidates = CommonPictureContentReader.candidateNames("Picture.png"); //$NON-NLS-1$
        assertEquals(Arrays.asList("Picture.png", "picture.png", "PICTURE.PNG"), candidates); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void candidateNamesDeduplicatesAlreadyLowercaseName()
    {
        // A correctly-cased lowercase entry (the common density scheme, e.g. "l.png") yields only the two
        // distinct spellings, exact first - so the fallback never probes a spelling twice.
        List<String> candidates = CommonPictureContentReader.candidateNames("100.png"); //$NON-NLS-1$
        assertEquals(Arrays.asList("100.png", "100.PNG"), candidates); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void candidateNamesReturnsEmptyForNull()
    {
        assertTrue(CommonPictureContentReader.candidateNames(null).isEmpty());
    }

    // --- selectVariantName: null/empty ------------------------------------

    @Test
    public void selectReturnsNullForEmptyOrNullInputs()
    {
        assertNull(CommonPictureContentReader.selectVariantName(new ArrayList<>(), "best")); //$NON-NLS-1$
        assertNull(CommonPictureContentReader.selectVariantName(null, "best")); //$NON-NLS-1$
        assertNull(CommonPictureContentReader.selectVariantName(Arrays.asList("mdpi.png"), null)); //$NON-NLS-1$
        assertNull(CommonPictureContentReader.selectVariantName(Arrays.asList("mdpi.png"), "   ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- rasterBytesToPng: the single-image / loose-PNG ImageIO fallback ---------------------------
    // This is the heart of the single-image fix: a variant-less picture's loose Picture.png is decoded
    // from its RAW bytes via ImageIO (the zip API's getBufferedImageByName does not decode it). These
    // tests exercise that raw-bytes path with no model / picture-jar dependency.

    @Test
    public void rasterBytesToPngDecodesARealPng() throws Exception
    {
        // A genuine 2x2 PNG (built in-memory, exactly like a loose single-image Picture.png) round-trips
        // through the ImageIO raster fallback to a decodable PNG.
        byte[] pngBytes = buildTinyPng();
        byte[] out = CommonPictureContentReader.rasterBytesToPng(pngBytes);
        assertNotNull("A real PNG's raw bytes must decode via the ImageIO fallback", out); //$NON-NLS-1$
        assertNotNull("The re-encoded bytes must themselves be a readable image", //$NON-NLS-1$
            ImageIO.read(new java.io.ByteArrayInputStream(out)));
    }

    @Test
    public void rasterBytesToPngReturnsNullForNonImageBytes()
    {
        // Non-image bytes (e.g. an SVG's XML) are NOT decodable by ImageIO -> null, so the caller can
        // route to the SVG rasterizer instead of mis-reporting a decode.
        byte[] svgish = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        try
        {
            assertNull(CommonPictureContentReader.rasterBytesToPng(svgish));
        }
        catch (Exception e)
        {
            throw new AssertionError("rasterBytesToPng must return null (not throw) for non-image bytes", e); //$NON-NLS-1$
        }
    }

    @Test
    public void rasterBytesToPngReturnsNullForNullOrEmpty() throws Exception
    {
        assertNull(CommonPictureContentReader.rasterBytesToPng(null));
        assertNull(CommonPictureContentReader.rasterBytesToPng(new byte[0]));
    }

    // --- looksLikeSvg: single-image SVG-vs-corrupt detection by content signature (#224 review) ---

    @Test
    public void looksLikeSvgDetectsSvgBySignature()
    {
        assertTrue(CommonPictureContentReader.looksLikeSvg(
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>".getBytes(StandardCharsets.UTF_8))); //$NON-NLS-1$
        // With an <?xml prolog / leading whitespace before the <svg root.
        assertTrue(CommonPictureContentReader.looksLikeSvg(
            "\n<?xml version=\"1.0\"?>\n<SVG width=\"16\"></SVG>".getBytes(StandardCharsets.UTF_8))); //$NON-NLS-1$
    }

    @Test
    public void looksLikeSvgRejectsRasterAndCorruptBytes() throws Exception
    {
        assertFalse(CommonPictureContentReader.looksLikeSvg(buildTinyPng())); // a real PNG
        assertFalse(CommonPictureContentReader.looksLikeSvg(new byte[] {0x00, 0x01, (byte)0xFF, 0x42})); // corrupt raster
        assertFalse(CommonPictureContentReader.looksLikeSvg("<html><body>not svg</body></html>".getBytes(StandardCharsets.UTF_8))); //$NON-NLS-1$
    }

    @Test
    public void looksLikeSvgNullOrEmptyIsFalse()
    {
        assertFalse(CommonPictureContentReader.looksLikeSvg(null));
        assertFalse(CommonPictureContentReader.looksLikeSvg(new byte[0]));
    }

    /**
     * Builds a tiny valid PNG in memory (no test resource file), standing in for a loose single-image
     * {@code Picture.png}.
     *
     * @return the PNG bytes
     * @throws Exception on an ImageIO failure
     */
    private static byte[] buildTinyPng() throws Exception
    {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFF112233);
        img.setRGB(1, 1, 0xFF445566);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos); //$NON-NLS-1$
        return baos.toByteArray();
    }
}
