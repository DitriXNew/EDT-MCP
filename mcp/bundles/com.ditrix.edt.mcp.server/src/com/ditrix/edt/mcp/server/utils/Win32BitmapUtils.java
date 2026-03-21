/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure-Java Win32 bitmap utilities. No SWT or OSGi dependencies — safe to test standalone.
 */
final class Win32BitmapUtils
{
    private Win32BitmapUtils()
    {
        // Utility class
    }

    /**
     * Builds a BITMAPINFOHEADER byte array (40 bytes, little-endian) for use with GetDIBits.
     *
     * @param width    bitmap width in pixels
     * @param height   bitmap height (negative = top-down DIB)
     * @param bitCount bits per pixel (e.g. 32)
     * @return 40-byte BITMAPINFOHEADER
     */
    static byte[] buildBitmapInfoHeader(int width, int height, short bitCount)
    {
        ByteBuffer buf = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(40);         // biSize
        buf.putInt(width);      // biWidth
        buf.putInt(height);     // biHeight (negative = top-down)
        buf.putShort((short)1); // biPlanes
        buf.putShort(bitCount); // biBitCount
        buf.putInt(0);          // biCompression = BI_RGB
        buf.putInt(0);          // biSizeImage
        buf.putInt(0);          // biXPelsPerMeter
        buf.putInt(0);          // biYPelsPerMeter
        buf.putInt(0);          // biClrUsed
        buf.putInt(0);          // biClrImportant
        return buf.array();
    }
}
