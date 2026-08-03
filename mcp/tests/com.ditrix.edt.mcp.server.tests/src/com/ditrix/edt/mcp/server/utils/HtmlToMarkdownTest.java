/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the shared {@link HtmlToMarkdown} converter. The assertions hold whether the CopyDown converter
 * runs (the primary path) or its tag-stripping fallback does, so they are independent of the converter
 * library being resolvable at test time: tags are gone and the visible text survives.
 */
public class HtmlToMarkdownTest
{
    @Test
    public void nullAndEmptyYieldEmptyString()
    {
        assertEquals("", HtmlToMarkdown.convert(null)); //$NON-NLS-1$
        assertEquals("", HtmlToMarkdown.convert("")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void stripsTagsButKeepsText()
    {
        String out = HtmlToMarkdown.convert("<p>Hello <b>world</b></p>"); //$NON-NLS-1$
        assertTrue("visible text must survive: " + out, out.contains("Hello") && out.contains("world")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("HTML tags must be gone: " + out, out.contains("<p>") || out.contains("<b>")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void removesStyleBlockContent()
    {
        String out = HtmlToMarkdown.convert("<style>.x{color:red}</style><p>Body</p>"); //$NON-NLS-1$
        assertTrue("body text must survive: " + out, out.contains("Body")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("style rules must not leak: " + out, out.contains("color:red")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
