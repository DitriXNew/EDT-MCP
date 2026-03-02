/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tags;

import static org.junit.Assert.*;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Tests for {@link TagDecorationUtils#formatTags(Set, String)}.
 */
public class TagDecorationUtilsTest {

    // ========== Empty / null ==========

    @Test
    public void testEmptyTagSet() {
        assertEquals("", TagDecorationUtils.formatTags(Set.of(), PreferenceConstants.TAGS_STYLE_SUFFIX));
    }

    @Test
    public void testNullTagSet() {
        assertEquals("", TagDecorationUtils.formatTags(null, PreferenceConstants.TAGS_STYLE_SUFFIX));
    }

    // ========== TAGS_STYLE_SUFFIX (default) ==========

    @Test
    public void testSuffixStyleSingleTag() {
        Set<Tag> tags = Set.of(new Tag("important"));
        assertEquals(" [important]", TagDecorationUtils.formatTags(tags, PreferenceConstants.TAGS_STYLE_SUFFIX));
    }

    @Test
    public void testSuffixStyleMultipleTags() {
        // LinkedHashSet preserves insertion order for predictable output
        Set<Tag> tags = new LinkedHashSet<>();
        tags.add(new Tag("bug"));
        tags.add(new Tag("critical"));
        assertEquals(" [bug, critical]", TagDecorationUtils.formatTags(tags, PreferenceConstants.TAGS_STYLE_SUFFIX));
    }

    @Test
    public void testSuffixStyleIsDefault() {
        Set<Tag> tags = Set.of(new Tag("review"));
        // Unknown/null style should default to suffix
        assertEquals(" [review]", TagDecorationUtils.formatTags(tags, "unknownStyle"));
        assertEquals(" [review]", TagDecorationUtils.formatTags(tags, null));
    }

    // ========== TAGS_STYLE_FIRST_TAG ==========

    @Test
    public void testFirstTagStyleSingleTag() {
        Set<Tag> tags = Set.of(new Tag("wip"));
        assertEquals(" [wip]", TagDecorationUtils.formatTags(tags, PreferenceConstants.TAGS_STYLE_FIRST_TAG));
    }

    @Test
    public void testFirstTagStyleMultipleTags() {
        Set<Tag> tags = new LinkedHashSet<>();
        tags.add(new Tag("first"));
        tags.add(new Tag("second"));
        tags.add(new Tag("third"));
        // Only the first tag should appear
        assertEquals(" [first]", TagDecorationUtils.formatTags(tags, PreferenceConstants.TAGS_STYLE_FIRST_TAG));
    }

    // ========== TAGS_STYLE_COUNT ==========

    @Test
    public void testCountStyleSingleTag() {
        Set<Tag> tags = Set.of(new Tag("todo"));
        assertEquals(" [1 tags]", TagDecorationUtils.formatTags(tags, PreferenceConstants.TAGS_STYLE_COUNT));
    }

    @Test
    public void testCountStyleMultipleTags() {
        Set<Tag> tags = new LinkedHashSet<>();
        tags.add(new Tag("a"));
        tags.add(new Tag("b"));
        tags.add(new Tag("c"));
        assertEquals(" [3 tags]", TagDecorationUtils.formatTags(tags, PreferenceConstants.TAGS_STYLE_COUNT));
    }

    // ========== Style constant values (regression) ==========

    @Test
    public void testStyleConstantValues() {
        // Ensure constants have expected values used in YAML/preferences
        assertEquals("suffix", PreferenceConstants.TAGS_STYLE_SUFFIX);
        assertEquals("firstTag", PreferenceConstants.TAGS_STYLE_FIRST_TAG);
        assertEquals("count", PreferenceConstants.TAGS_STYLE_COUNT);
    }
}
