// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import com.ditrix.edt.mcp.server.protocol.JsonUtils;

/** Validated per-call window options; no platform resources are acquired here. */
public record WindowRequest(Mode mode, Integer screen)
{
    public enum Mode
    {
        NORMAL("normal"), MINIMIZED("minimized"), OTHER_SCREEN("otherScreen"), ISOLATED("isolated");
        private final String token;
        Mode(String token) { this.token = token; }
        public String token() { return token; }
    }

    public static WindowRequest parse(String mode, String screen)
    {
        Mode selected = Mode.NORMAL;
        if (mode != null)
        {
            selected = java.util.Arrays.stream(Mode.values()).filter(value -> value.token().equals(mode))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                    "Unknown windowMode '" + mode + "'. Use normal, minimized, otherScreen, or isolated."));
        }
        Integer index = screen == null ? null : JsonUtils.parseIntegralInt(screen);
        if (screen != null && (index == null || index < 1))
        {
            throw new IllegalArgumentException("screen must be a positive, 1-based integer monitor index.");
        }
        if (screen != null && selected != Mode.OTHER_SCREEN)
        {
            throw new IllegalArgumentException("screen is only valid with windowMode 'otherScreen'.");
        }
        return new WindowRequest(selected, index);
    }
}
