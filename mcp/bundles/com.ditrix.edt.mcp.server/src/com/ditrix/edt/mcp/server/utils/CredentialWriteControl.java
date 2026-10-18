/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.function.BooleanSupplier;

/** Atomic cancellation/admission boundary; never holds its monitor across a native SDK call. */
public final class CredentialWriteControl implements BooleanSupplier, AutoCloseable
{
    private boolean closed;
    private int inProgress;

    /** A pure predicate: publications and preliminary checks never consume write permission. */
    @Override
    public synchronized boolean getAsBoolean()
    {
        return !closed;
    }

    /** A distinct permit is required for every persistent mutation, immediately before entry. */
    public synchronized Permit tryBeginWrite()
    {
        if (closed)
        {
            return null;
        }
        inProgress++;
        return new Permit();
    }

    public synchronized boolean writeInProgress()
    {
        return inProgress != 0;
    }

    /** Closes admission promptly even when an already-admitted native operation is blocked. */
    @Override
    public synchronized void close()
    {
        closed = true;
    }

    public final class Permit implements AutoCloseable
    {
        private boolean finished;

        private Permit()
        {
        }

        @Override
        public void close()
        {
            synchronized (CredentialWriteControl.this)
            {
                if (!finished)
                {
                    finished = true;
                    inProgress--;
                }
            }
        }
    }
}
