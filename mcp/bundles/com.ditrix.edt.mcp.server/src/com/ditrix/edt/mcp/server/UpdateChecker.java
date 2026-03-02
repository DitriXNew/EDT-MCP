/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.McpConstants;

/**
 * Checks GitHub Releases for a newer version of the EDT MCP Server plugin.
 *
 * <p>The check interval is controlled by the preference
 * {@link PreferenceConstants#PREF_UPDATE_CHECK_INTERVAL}:
 * <ul>
 *   <li>{@code on_startup} – once per EDT session, 60 s after startup</li>
 *   <li>{@code hourly}     – first check after 60 s, then every hour</li>
 *   <li>{@code daily}      – first check after 60 s, then every 24 h</li>
 *   <li>{@code never}      – disabled</li>
 * </ul>
 */
public final class UpdateChecker
{
    /** GitHub API URL for the latest release. */
    private static final String RELEASES_API_URL =
        "https://api.github.com/repos/DitriXNew/EDT-MCP/releases/latest"; //$NON-NLS-1$

    /** GitHub Releases page URL opened in a browser on "Download". */
    public static final String RELEASES_PAGE_URL =
        "https://github.com/DitriXNew/EDT-MCP/releases/latest"; //$NON-NLS-1$

    /** Initial delay before the very first check (ms). */
    private static final long INITIAL_DELAY_MS = 10_000L;

    /** HTTP connect/read timeout (ms). */
    private static final int TIMEOUT_MS = 10_000;

    private static final UpdateChecker INSTANCE = new UpdateChecker();

    private final AtomicBoolean updateAvailable  = new AtomicBoolean(false);
    private final AtomicReference<String> latestVersion = new AtomicReference<>("");
    private final AtomicReference<String> releaseNotes  = new AtomicReference<>("");
    private final AtomicReference<String> releaseUrl    = new AtomicReference<>(RELEASES_PAGE_URL);

    private ScheduledExecutorService scheduler;

    private UpdateChecker()
    {
        // private constructor – use getInstance()
    }

    /** Returns the singleton instance. */
    public static UpdateChecker getInstance()
    {
        return INSTANCE;
    }

    /**
     * Reads the current preference and schedules the update check accordingly.
     * Safe to call on every EDT startup – cancels any previous scheduler first.
     */
    public void scheduleCheck()
    {
        String interval = readIntervalPref();

        if (PreferenceConstants.UPDATE_CHECK_NEVER.equals(interval))
        {
            Activator.logInfo("EDT MCP Server update check disabled (preference: never)"); //$NON-NLS-1$
            return;
        }

        // Cancel previous scheduler if any (e.g. server restart)
        stopScheduler();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MCP-Update-Checker"); //$NON-NLS-1$
            t.setDaemon(true);
            return t;
        });

        if (PreferenceConstants.UPDATE_CHECK_ON_STARTUP.equals(interval))
        {
            // Single shot – only once per session
            scheduler.schedule(this::performCheck, INITIAL_DELAY_MS, TimeUnit.MILLISECONDS);
        }
        else if (PreferenceConstants.UPDATE_CHECK_HOURLY.equals(interval))
        {
            scheduler.scheduleAtFixedRate(
                this::performCheck,
                INITIAL_DELAY_MS, TimeUnit.HOURS.toMillis(1),
                TimeUnit.MILLISECONDS);
        }
        else if (PreferenceConstants.UPDATE_CHECK_DAILY.equals(interval))
        {
            scheduler.scheduleAtFixedRate(
                this::performCheck,
                INITIAL_DELAY_MS, TimeUnit.DAYS.toMillis(1),
                TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Runs the update check synchronously on the calling thread (no delay).
     * Can be called from a background thread (e.g. from the UI "Check now" button handler).
     */
    public void checkNow()
    {
        performCheck();
    }

    /** Cancels any running scheduled checks. */
    public void stopScheduler()
    {
        if (scheduler != null && !scheduler.isShutdown())
        {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** @return {@code true} if a newer release was found on GitHub. */
    public boolean isUpdateAvailable()
    {
        return updateAvailable.get();
    }

    /**
     * @return the latest version string (e.g. {@code "1.25.0"}), or an empty
     *         string if the check has not completed yet or failed.
     */
    public String getLatestVersion()
    {
        return latestVersion.get();
    }

    /**
     * @return the release notes (markdown text) of the latest release, or an empty string.
     */
    public String getReleaseNotes()
    {
        return releaseNotes.get();
    }

    /**
     * @return the HTML URL of the latest GitHub release page.
     */
    public String getReleaseUrl()
    {
        return releaseUrl.get();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private void performCheck()
    {
        Activator.logInfo("EDT MCP Server update check started (current: " + McpConstants.PLUGIN_VERSION + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            String[] info = fetchReleaseInfo();
            if (info == null || info[0] == null || info[0].isEmpty())
            {
                Activator.logInfo("EDT MCP Server update check: no release info returned from GitHub API"); //$NON-NLS-1$
                return;
            }

            Activator.logInfo("EDT MCP Server update check: GitHub returned tag_name='" + info[0] + "'"); //$NON-NLS-1$ //$NON-NLS-2$

            // Strip leading 'v' / 'V' prefix and any immediately following dot (e.g. "v.1.24.7" → "1.24.7")
            String remote = info[0];
            if (remote.startsWith("v") || remote.startsWith("V")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                remote = remote.substring(1);
            }
            if (remote.startsWith(".")) //$NON-NLS-1$
            {
                remote = remote.substring(1);
            }

            latestVersion.set(remote);
            if (info[1] != null) releaseNotes.set(info[1]);
            if (info[2] != null) releaseUrl.set(info[2]);

            boolean newer = isNewer(remote, McpConstants.PLUGIN_VERSION);
            Activator.logInfo("EDT MCP Server update check: remote=" + remote //$NON-NLS-1$
                + " current=" + McpConstants.PLUGIN_VERSION //$NON-NLS-1$
                + " isNewer=" + newer); //$NON-NLS-1$

            if (newer)
            {
                if (!updateAvailable.getAndSet(true))
                {
                    Activator.logInfo("New EDT MCP Server version available: " + remote //$NON-NLS-1$
                        + " (current: " + McpConstants.PLUGIN_VERSION + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            else
            {
                // Reset flag in case the user installed an update mid-session
                updateAvailable.set(false);
                Activator.logInfo("EDT MCP Server is up to date: " + McpConstants.PLUGIN_VERSION); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            // Network errors are not critical – just log and continue
            Activator.logInfo("EDT MCP Server update check failed: " + e.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Fetches release info from the GitHub Releases API.
     * @return String[3]: [0]=tag_name, [1]=body (release notes), [2]=html_url; or null on error.
     */
    private String[] fetchReleaseInfo() throws Exception
    {
        URL url = new URL(RELEASES_API_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET"); //$NON-NLS-1$
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/vnd.github+json"); //$NON-NLS-1$ //$NON-NLS-2$
        connection.setRequestProperty(
            "User-Agent", //$NON-NLS-1$
            "EDT-MCP-Plugin/" + McpConstants.PLUGIN_VERSION); //$NON-NLS-1$

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK)
        {
            Activator.logInfo("EDT MCP Server update check: GitHub API returned HTTP " + responseCode); //$NON-NLS-1$
            return null;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(connection.getInputStream(), "UTF-8"))) //$NON-NLS-1$
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line);
            }
        }
        finally
        {
            connection.disconnect();
        }

        com.google.gson.JsonElement element =
            com.google.gson.JsonParser.parseString(sb.toString());
        if (element.isJsonObject())
        {
            com.google.gson.JsonObject obj = element.getAsJsonObject();
            String tagName = null;
            String body    = null;
            String htmlUrl = null;

            com.google.gson.JsonElement tagEl = obj.get("tag_name"); //$NON-NLS-1$
            if (tagEl != null && !tagEl.isJsonNull()) tagName = tagEl.getAsString();

            com.google.gson.JsonElement bodyEl = obj.get("body"); //$NON-NLS-1$
            if (bodyEl != null && !bodyEl.isJsonNull()) body = bodyEl.getAsString();

            com.google.gson.JsonElement urlEl = obj.get("html_url"); //$NON-NLS-1$
            if (urlEl != null && !urlEl.isJsonNull()) htmlUrl = urlEl.getAsString();

            if (tagName != null)
            {
                return new String[] { tagName, body, htmlUrl };
            }
        }
        return null;
    }

    /**
     * Reads the update-check-interval preference.
     * Falls back to {@code on_startup} when the plugin/store is unavailable.
     */
    private static String readIntervalPref()
    {
        try
        {
            if (Activator.getDefault() != null)
            {
                String val = Activator.getDefault().getPreferenceStore()
                    .getString(PreferenceConstants.PREF_UPDATE_CHECK_INTERVAL);
                if (val != null && !val.isEmpty())
                {
                    return val;
                }
            }
        }
        catch (Exception ignored)
        {
            // ignore
        }
        return PreferenceConstants.DEFAULT_UPDATE_CHECK_INTERVAL;
    }

    /**
     * Returns {@code true} if {@code remote} is semantically newer than
     * {@code current}.  Both are expected to be in {@code major.minor.patch}
     * format.
     */
    static boolean isNewer(String remote, String current)
    {
        int[] r = parseParts(remote);
        int[] c = parseParts(current);
        for (int i = 0; i < Math.min(r.length, c.length); i++)
        {
            if (r[i] > c[i])
            {
                return true;
            }
            if (r[i] < c[i])
            {
                return false;
            }
        }
        return r.length > c.length;
    }

    private static int[] parseParts(String version)
    {
        String[] parts = version.split("\\."); //$NON-NLS-1$
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++)
        {
            try
            {
                result[i] = Integer.parseInt(parts[i].trim());
            }
            catch (NumberFormatException e)
            {
                result[i] = 0;
            }
        }
        return result;
    }
}
