/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    // --- selectVariantName: best -----------------------------------------

    @Test
    public void selectBestReturnsDensestRasterSkippingSvg()
    {
        // Entries are enumerated in ascending density order; "best" is the last raster.
        List<String> names = Arrays.asList("mdpi.png", "hdpi.png", "vector.svg"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("hdpi.png", CommonPictureContentReader.selectVariantName(names, "best")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void selectBestFallsBackToFirstWhenOnlySvg()
    {
        List<String> names = Arrays.asList("vector.svg"); //$NON-NLS-1$
        assertEquals("vector.svg", CommonPictureContentReader.selectVariantName(names, "best")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void selectBestIsCaseInsensitive()
    {
        List<String> names = Arrays.asList("mdpi.png", "xdpi.png"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("xdpi.png", CommonPictureContentReader.selectVariantName(names, "BEST")); //$NON-NLS-1$ //$NON-NLS-2$
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

    // --- selectVariantName: null/empty ------------------------------------

    @Test
    public void selectReturnsNullForEmptyOrNullInputs()
    {
        assertNull(CommonPictureContentReader.selectVariantName(new ArrayList<>(), "best")); //$NON-NLS-1$
        assertNull(CommonPictureContentReader.selectVariantName(null, "best")); //$NON-NLS-1$
        assertNull(CommonPictureContentReader.selectVariantName(Arrays.asList("mdpi.png"), null)); //$NON-NLS-1$
        assertNull(CommonPictureContentReader.selectVariantName(Arrays.asList("mdpi.png"), "   ")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
