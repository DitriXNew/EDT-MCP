/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tags;

import java.util.Set;

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Utility class for formatting tag decorations in the Navigator tree.
 *
 * <p>Extracted from {@code TagLabelDecorator} so the formatting logic
 * can be tested independently of the Eclipse UI runtime.</p>
 */
public final class TagDecorationUtils {

    private TagDecorationUtils() {
        // Utility class
    }

    /**
     * Formats tags into a suffix string according to the given decoration style.
     *
     * @param tags  the set of tags to format (must not be null)
     * @param style one of {@link PreferenceConstants#TAGS_STYLE_SUFFIX},
     *              {@link PreferenceConstants#TAGS_STYLE_FIRST_TAG},
     *              {@link PreferenceConstants#TAGS_STYLE_COUNT}
     * @return formatted suffix string, e.g. {@code " [tag1, tag2]"}, or {@code ""}
     *         if the tag set is empty
     */
    public static String formatTags(Set<Tag> tags, String style) {
        if (tags == null || tags.isEmpty()) {
            return ""; //$NON-NLS-1$
        }

        if (PreferenceConstants.TAGS_STYLE_COUNT.equals(style)) {
            return " [" + tags.size() + " tags]"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (PreferenceConstants.TAGS_STYLE_FIRST_TAG.equals(style)) {
            Tag first = tags.iterator().next();
            return " [" + first.getName() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Default: TAGS_STYLE_SUFFIX — show all tags
        StringBuilder sb = new StringBuilder(" ["); //$NON-NLS-1$
        boolean first = true;
        for (Tag tag : tags) {
            if (!first) {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(tag.getName());
            first = false;
        }
        sb.append("]"); //$NON-NLS-1$
        return sb.toString();
    }
}
