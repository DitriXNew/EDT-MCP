/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

/**
 * Tests for {@link EditorScreenshotHelper}.
 * Covers pure-Java logic that does not require SWT or a running EDT instance.
 */
public class EditorScreenshotHelperTest
{
    // ========== buildBitmapInfoHeader ==========

    @Test
    public void testBitmapInfoHeaderSize()
    {
        byte[] bmi = EditorScreenshotHelper.buildBitmapInfoHeader(100, -200, (short)32);
        assertEquals("BITMAPINFOHEADER must be exactly 40 bytes", 40, bmi.length);
    }

    @Test
    public void testBitmapInfoHeaderBiSize()
    {
        byte[] bmi = EditorScreenshotHelper.buildBitmapInfoHeader(1, -1, (short)32);
        ByteBuffer buf = ByteBuffer.wrap(bmi).order(ByteOrder.LITTLE_ENDIAN);
        int biSize = buf.getInt(0);
        assertEquals("biSize must be 40", 40, biSize);
    }

    @Test
    public void testBitmapInfoHeaderWidth()
    {
        byte[] bmi = EditorScreenshotHelper.buildBitmapInfoHeader(1280, -720, (short)32);
        ByteBuffer buf = ByteBuffer.wrap(bmi).order(ByteOrder.LITTLE_ENDIAN);
        int biWidth = buf.getInt(4);
        assertEquals("biWidth must match", 1280, biWidth);
    }

    @Test
    public void testBitmapInfoHeaderNegativeHeight()
    {
        // Negative height = top-down DIB (required for correct pixel order from GetDIBits)
        byte[] bmi = EditorScreenshotHelper.buildBitmapInfoHeader(100, -720, (short)32);
        ByteBuffer buf = ByteBuffer.wrap(bmi).order(ByteOrder.LITTLE_ENDIAN);
        int biHeight = buf.getInt(8);
        assertEquals("biHeight must be negative for top-down DIB", -720, biHeight);
    }

    @Test
    public void testBitmapInfoHeaderPlanes()
    {
        byte[] bmi = EditorScreenshotHelper.buildBitmapInfoHeader(1, -1, (short)32);
        ByteBuffer buf = ByteBuffer.wrap(bmi).order(ByteOrder.LITTLE_ENDIAN);
        short biPlanes = buf.getShort(12);
        assertEquals("biPlanes must be 1", 1, biPlanes);
    }

    @Test
    public void testBitmapInfoHeaderBitCount()
    {
        byte[] bmi = EditorScreenshotHelper.buildBitmapInfoHeader(1, -1, (short)32);
        ByteBuffer buf = ByteBuffer.wrap(bmi).order(ByteOrder.LITTLE_ENDIAN);
        short biBitCount = buf.getShort(14);
        assertEquals("biBitCount must be 32", 32, biBitCount);
    }

    @Test
    public void testBitmapInfoHeaderCompressionIsRgb()
    {
        byte[] bmi = EditorScreenshotHelper.buildBitmapInfoHeader(1, -1, (short)32);
        ByteBuffer buf = ByteBuffer.wrap(bmi).order(ByteOrder.LITTLE_ENDIAN);
        int biCompression = buf.getInt(16);
        assertEquals("biCompression must be BI_RGB (0)", 0, biCompression);
    }

    @Test
    public void testBitmapInfoHeaderRemainingFieldsAreZero()
    {
        byte[] bmi = EditorScreenshotHelper.buildBitmapInfoHeader(100, -100, (short)32);
        ByteBuffer buf = ByteBuffer.wrap(bmi).order(ByteOrder.LITTLE_ENDIAN);
        // biSizeImage, biXPelsPerMeter, biYPelsPerMeter, biClrUsed, biClrImportant
        assertEquals(0, buf.getInt(20));
        assertEquals(0, buf.getInt(24));
        assertEquals(0, buf.getInt(28));
        assertEquals(0, buf.getInt(32));
        assertEquals(0, buf.getInt(36));
    }
}
