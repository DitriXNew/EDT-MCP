/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Auto-confirms EDT's blocking <em>"Application update"</em> launch modal while
 * a YAXUnit tool is spawning a launch via {@code workingCopy.launch()}.
 *
 * <p>This is a thin static facade over a shared {@link EdtDialogAutoConfirmer}
 * configured for the launch update modal's {@linkplain #APPLICATION_UPDATE_TITLES
 * localized titles}, kept for the existing launch call sites. {@code update_database}
 * reuses the same titles for its own {@link EdtDialogAutoConfirmer} instance, so
 * the two never share arm/disarm state.
 *
 * <p>For YAXUnit runs the modal is structural: the dependent test extension
 * reports {@code INCREMENTAL_UPDATE_REQUIRED}, which
 * {@code InfobaseApplicationProvisionDelegate.getUpdateState} propagates to the
 * whole application, and a plain {@code IApplicationManager.update} does not
 * durably clear it — so the launch delegate pops "Application update" on every
 * launch and there is no attribute/preference to suppress it. Pressing the
 * default ("Update then run") button lets the launch proceed unattended.
 */
public final class LaunchUpdateDialogAutoConfirmer
{
    /** English EDT build title of the launch-delegate "update before launch?" modal. */
    static final String APPLICATION_UPDATE_TITLE_EN = "Application update"; //$NON-NLS-1$

    /**
     * The same modal in a Russian EDT build. The title is localized to the EDT
     * UI language, so an exact-title match must enumerate each locale: a Russian
     * EDT shows this title, which the English one never matched — the launch
     * then hung on the modal. Justified Cyrillic per CLAUDE.md #7 (a real EDT UI
     * string the code matches, not a regex; the Tycho build is UTF-8).
     */
    static final String APPLICATION_UPDATE_TITLE_RU = "Обновление приложения"; //$NON-NLS-1$

    /**
     * Known localized titles of the launch "update before launch?" modal. Add
     * more here as other EDT UI locales are confirmed live. Shared with
     * {@code update_database}'s confirmer so both honour the same locale set.
     */
    public static final Set<String> APPLICATION_UPDATE_TITLES =
        Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            APPLICATION_UPDATE_TITLE_EN, APPLICATION_UPDATE_TITLE_RU)));

    private static final EdtDialogAutoConfirmer CONFIRMER =
        new EdtDialogAutoConfirmer(APPLICATION_UPDATE_TITLES);

    private LaunchUpdateDialogAutoConfirmer()
    {
        // Utility class
    }

    /**
     * Pure decision used by tests: is the given shell title the "Application
     * update" modal this facade auto-confirms?
     */
    static boolean isTargetTitle(String shellTitle)
    {
        return CONFIRMER.isTargetTitle(shellTitle);
    }

    /**
     * Arms the auto-confirmer. MUST be paired with {@link #disarm()} in a
     * {@code finally} block around the {@code workingCopy.launch()} call.
     * Reentrant. No-op in a headless environment.
     */
    public static void arm()
    {
        CONFIRMER.arm();
    }

    /**
     * Disarms the auto-confirmer once the last paired {@link #arm()} is released.
     */
    public static void disarm()
    {
        CONFIRMER.disarm();
    }
}
