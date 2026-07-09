/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.history;

import org.eclipse.jface.preference.IPreferenceStore;

/**
 * Declares the preference keys, defaults, and null-guarded typed accessors for the
 * in-EDT MCP request/response history feature.
 *
 * <p>This is a pure declaration/lookup helper (no state of its own). It never
 * reads the {@link org.eclipse.jface.preference.IPreferenceStore} on a per-message
 * path: {@link McpCallHistory} caches the buffer size and recording flag and only
 * refreshes them from here when preferences change. Every accessor tolerates a
 * {@code null} store (e.g. during a shutdown race, or in a headless unit test) by
 * returning the documented default, so a missing store can never toggle recording
 * or corrupt the buffer bound.</p>
 *
 * <p>Register {@link #initializeDefaults(IPreferenceStore)} from the plugin's
 * preference initializer so that, once the store exists, an unset key reads back as
 * its default (in particular {@link #DEFAULT_RECORD} = {@code true}).</p>
 */
public final class HistoryConfig
{
    /** Whether request/response exchanges are recorded into the in-memory ring. */
    public static final String KEY_RECORD = "mcpHistoryRecord"; //$NON-NLS-1$

    /** Maximum number of exchanges retained in the in-memory ring buffer. */
    public static final String KEY_BUFFER_SIZE = "mcpHistoryBufferSize"; //$NON-NLS-1$

    /** Whether recorded exchanges are also appended to an on-disk log file. */
    public static final String KEY_FILE_LOG = "mcpHistoryFileLog"; //$NON-NLS-1$

    /** Absolute path of the on-disk history log <b>folder</b> (empty = feature disabled). */
    public static final String KEY_FILE_PATH = "mcpHistoryFilePath"; //$NON-NLS-1$

    /** Default: recording enabled. */
    public static final boolean DEFAULT_RECORD = true;

    /** Default in-memory ring capacity (number of exchanges). */
    public static final int DEFAULT_BUFFER_SIZE = 500;

    /** Default: file logging disabled. */
    public static final boolean DEFAULT_FILE_LOG = false;

    /** Default file-log path (empty - feature disabled). */
    public static final String DEFAULT_FILE_PATH = ""; //$NON-NLS-1$

    /** Smallest allowed ring capacity; a configured value below this snaps to the default. */
    public static final int MIN_BUFFER_SIZE = 1;

    /**
     * Largest allowed ring capacity. A configured value above this is clamped so a
     * mistyped preference cannot let the bounded ring grow without limit.
     */
    public static final int MAX_BUFFER_SIZE = 100_000;

    private HistoryConfig()
    {
        // Utility class - no instantiation
    }

    /**
     * Registers the store defaults for every history preference. Call once from the
     * plugin's preference initializer so that an unset key reads back as its
     * documented default (notably {@link #DEFAULT_RECORD}). A {@code null} store is
     * ignored.
     *
     * @param store the preference store to seed (may be {@code null})
     */
    public static void initializeDefaults(IPreferenceStore store)
    {
        if (store == null)
        {
            return;
        }
        store.setDefault(KEY_RECORD, DEFAULT_RECORD);
        store.setDefault(KEY_BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
        store.setDefault(KEY_FILE_LOG, DEFAULT_FILE_LOG);
        store.setDefault(KEY_FILE_PATH, DEFAULT_FILE_PATH);
    }

    /**
     * Whether recording is enabled.
     *
     * @param store the preference store (may be {@code null})
     * @return the configured flag, or {@link #DEFAULT_RECORD} when {@code store} is
     *         {@code null}
     */
    public static boolean isRecordEnabled(IPreferenceStore store)
    {
        return store == null ? DEFAULT_RECORD : store.getBoolean(KEY_RECORD);
    }

    /**
     * The in-memory ring capacity, clamped to
     * {@code [MIN_BUFFER_SIZE, MAX_BUFFER_SIZE]}. A missing/zero or below-minimum
     * value falls back to {@link #DEFAULT_BUFFER_SIZE}, so the ring is always
     * bounded by a sane positive capacity.
     *
     * @param store the preference store (may be {@code null})
     * @return the effective buffer size
     */
    public static int getBufferSize(IPreferenceStore store)
    {
        if (store == null)
        {
            return DEFAULT_BUFFER_SIZE;
        }
        return clampBufferSize(store.getInt(KEY_BUFFER_SIZE));
    }

    /**
     * Clamps a raw buffer-size value into the supported range. A value below
     * {@link #MIN_BUFFER_SIZE} (including the {@code 0} an unset int preference
     * yields) resolves to {@link #DEFAULT_BUFFER_SIZE}; a value above
     * {@link #MAX_BUFFER_SIZE} is capped to that maximum. Exposed (package-private)
     * so the ring can reuse the same clamp when a caller sets the size directly.
     *
     * @param raw the raw configured value
     * @return the clamped, always-positive buffer size
     */
    static int clampBufferSize(int raw)
    {
        if (raw < MIN_BUFFER_SIZE)
        {
            return DEFAULT_BUFFER_SIZE;
        }
        if (raw > MAX_BUFFER_SIZE)
        {
            return MAX_BUFFER_SIZE;
        }
        return raw;
    }

    /**
     * Whether file logging is enabled.
     *
     * @param store the preference store (may be {@code null})
     * @return the configured flag, or {@link #DEFAULT_FILE_LOG} when {@code store}
     *         is {@code null}
     */
    public static boolean isFileLogEnabled(IPreferenceStore store)
    {
        return store == null ? DEFAULT_FILE_LOG : store.getBoolean(KEY_FILE_LOG);
    }

    /**
     * The on-disk history log folder path. Never returns {@code null}: an unset value
     * or a {@code null} store yields {@link #DEFAULT_FILE_PATH} (an empty string,
     * meaning file logging is effectively off).
     *
     * @param store the preference store (may be {@code null})
     * @return the configured folder path, or the empty default
     */
    public static String getFilePath(IPreferenceStore store)
    {
        if (store == null)
        {
            return DEFAULT_FILE_PATH;
        }
        String value = store.getString(KEY_FILE_PATH);
        return value != null ? value : DEFAULT_FILE_PATH;
    }
}
