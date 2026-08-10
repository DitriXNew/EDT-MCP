/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.ui;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

import com.ditrix.edt.mcp.server.tags.ui.TagSearchFilter;
import com.ditrix.edt.mcp.server.utils.ReflectionUtils;

/**
 * Tells whether a text search is currently narrowing the Navigator tree.
 *
 * <p>Group-aware filters must switch themselves off while a search is running: the search
 * hides the virtual group folders, so an object that is only shown inside a group would
 * become unreachable.</p>
 *
 * <p>The signal is the search pattern itself, not the presence of a search filter on the
 * viewer. The native EDT search filter decides the very same way ("empty pattern = I filter
 * nothing"), so reading its pattern keeps both filters in step no matter who attached the
 * filter or when. Presence-based detection does not: a plugin that keeps the native filter
 * attached permanently (with an empty pattern) would pin the answer to "always searching"
 * and group filtering would stay off forever.</p>
 */
public final class NavigatorSearchState {

    /** Methods that expose the active pattern, on a filter or on its state holder. */
    private static final String[] PATTERN_ACCESSORS = {"getActivePattern", "getPattern", "getSearchText"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** Methods that expose the state holder carrying the active pattern. */
    private static final String[] STATE_ACCESSORS = {"getSearchFilterState", "getSearchHistory", "getState"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** Class-name markers of a text search filter - the last-resort heuristic. */
    private static final String[] SEARCH_FILTER_MARKERS = {"Pattern", "Search", "Quick", "Text"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private static final Map<MethodKey, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    private NavigatorSearchState() {
        // Utility class
    }

    /**
     * Checks whether a text search is narrowing the given viewer.
     *
     * @param viewer the viewer being filtered, may be {@code null}
     * @param self the calling filter, excluded from the scan, may be {@code null}
     * @return {@code true} while a non-empty search pattern is applied
     */
    public static boolean isSearchActive(Viewer viewer, ViewerFilter self) {
        if (viewer == null) {
            return false;
        }
        ViewerFilter[] filters = viewer instanceof StructuredViewer sv ? sv.getFilters() : new ViewerFilter[0];
        return isSearchActive(readNavigatorPattern(viewer), filters, self);
    }

    /**
     * The SWT-free decision, unit-tested directly.
     *
     * @param navigatorPattern the Navigator's own search pattern, or {@code null} when it
     *            could not be read (then the filters themselves are questioned)
     * @param filters the filters currently attached to the viewer
     * @param self the calling filter, excluded from the scan, may be {@code null}
     * @return {@code true} while a non-empty search pattern is applied
     */
    static boolean isSearchActive(String navigatorPattern, ViewerFilter[] filters, ViewerFilter self) {
        if (navigatorPattern != null && !navigatorPattern.isBlank()) {
            return true;
        }

        for (ViewerFilter filter : filters) {
            if (filter == null || filter == self || !isTextSearchFilter(filter)) {
                continue;
            }
            String pattern = readPattern(filter);
            if (pattern != null) {
                if (!pattern.isBlank()) {
                    return true;
                }
            } else if (navigatorPattern == null) {
                // Neither the Navigator nor the filter can be asked - stay on the safe side
                // and keep grouped objects visible in their original location.
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the Navigator's own search pattern - the state the native search filter reads too.
     *
     * @param viewer the viewer being filtered
     * @return the active pattern (possibly empty), or {@code null} if it cannot be read
     */
    static String readNavigatorPattern(Viewer viewer) {
        if (!(viewer instanceof CommonViewer commonViewer)) {
            return null;
        }
        CommonNavigator navigator = commonViewer.getCommonNavigator();
        return navigator == null ? null : readPattern(navigator);
    }

    /**
     * Reads the active pattern from a search filter or a navigator, directly or through its
     * state holder. The types live in EDT-internal packages, hence reflection.
     *
     * @param holder the filter or navigator to question
     * @return the active pattern (possibly empty), or {@code null} if it cannot be read
     */
    static String readPattern(Object holder) {
        if (holder == null) {
            return null;
        }
        String direct = readPatternDirectly(holder);
        if (direct != null) {
            return direct;
        }
        for (String accessor : STATE_ACCESSORS) {
            String pattern = readPatternDirectly(invoke(holder, accessor));
            if (pattern != null) {
                return pattern;
            }
        }
        return null;
    }

    private static String readPatternDirectly(Object holder) {
        if (holder == null) {
            return null;
        }
        for (String accessor : PATTERN_ACCESSORS) {
            if (invoke(holder, accessor) instanceof String pattern) {
                return pattern;
            }
        }
        return null;
    }

    /**
     * Recognises a text search filter by its class name. Only decides whether the filter is
     * worth questioning - the answer always comes from its pattern when there is one.
     */
    private static boolean isTextSearchFilter(ViewerFilter filter) {
        // The tag filter handles groups itself, it must never disable group filtering.
        if (filter instanceof TagSearchFilter) {
            return false;
        }
        String className = filter.getClass().getName();
        for (String marker : SEARCH_FILTER_MARKERS) {
            if (className.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static Object invoke(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        Optional<Method> method = METHOD_CACHE.computeIfAbsent(new MethodKey(target.getClass(), methodName),
                key -> Optional.ofNullable(accessibleMethod(key)));
        if (method.isEmpty()) {
            return null;
        }
        try {
            return method.get().invoke(target);
        } catch (Exception e) { // NOSONAR a filter that cannot answer is "unknown", never a broken tree
            return null;
        }
    }

    private static Method accessibleMethod(MethodKey key) {
        Method method = ReflectionUtils.findMethod(key.type(), key.name());
        if (method == null) {
            return null;
        }
        try {
            method.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
            return method;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record MethodKey(Class<?> type, String name) {
    }
}
