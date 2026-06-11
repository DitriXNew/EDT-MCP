/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Collections;

/**
 * Auto-confirms EDT's blocking <em>"Application update"</em> launch modal while
 * a YAXUnit tool is spawning a launch via {@code workingCopy.launch()}.
 *
 * <p>This is a thin static facade over a shared {@link EdtDialogAutoConfirmer}
 * configured for the single {@link #APPLICATION_UPDATE_TITLE} dialog, kept for
 * the existing launch call sites. {@code update_database} uses its own
 * {@link EdtDialogAutoConfirmer} instance (it may face additional dialog
 * titles), so the two never share arm/disarm state.
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
    /**
     * Exact title of EDT's launch-delegate "update infobase before launch?"
     * modal ({@code ApplicationUiSupport_Application_update}). The EDT-MCP
     * surface is English-only and so is the target EDT build, so an exact-title
     * match is sufficient and keeps the filter from touching other dialogs.
     */
    static final String APPLICATION_UPDATE_TITLE = "Application update"; //$NON-NLS-1$

    private static final EdtDialogAutoConfirmer CONFIRMER =
        new EdtDialogAutoConfirmer(Collections.singleton(APPLICATION_UPDATE_TITLE));

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
