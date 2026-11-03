/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.history;

/** Bounded completion metadata, separate from the response delivered to the client. */
public final class BackgroundOutcome
{
    private final String status;
    private final String message;

    private BackgroundOutcome(String status, String message)
    {
        this.status = status;
        this.message = message;
    }

    static BackgroundOutcome fromResponse(String response)
    {
        boolean error = response == null || StatsAggregator.isErrorResponse(response);
        String message = error ? "Background operation failed." : "Background operation completed successfully."; //$NON-NLS-1$ //$NON-NLS-2$
        return new BackgroundOutcome(error ? "error" : "success", message); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public String getStatus()
    {
        return status;
    }

    public String getMessage()
    {
        return message;
    }
}
