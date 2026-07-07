/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.privacy;

/**
 * Personal-data sensitivity class of a detected value, driving how it is redacted
 * (152-FZ terminology). {@link #NORMAL} personal data is replaced with a stable,
 * non-reversible pseudonym; special-category and biometric data are fully masked
 * (never pseudonymised - a stable token would still be a linkable identifier).
 * <p>
 * The declaration order is also the severity order (increasing ordinal), so the
 * redactor can report the most severe class it masked in an audit line.
 */
public enum Sensitivity
{
    /** Ordinary personal data (name, tax/insurance number, passport, address, contacts). */
    NORMAL,

    /**
     * Special category of personal data (health, diagnosis, disability, criminal
     * record, ethnicity, religion...): fully masked, never pseudonymised.
     */
    SPECIAL,

    /** Biometric personal data (photo, fingerprint, scans): fully masked. */
    BIOMETRIC;

    /**
     * Whether a value of this class must be FULLY masked rather than replaced with a
     * stable pseudonym. Only {@link #NORMAL} is pseudonymised; special-category and
     * biometric data are hidden outright.
     *
     * @return {@code true} for {@link #SPECIAL} / {@link #BIOMETRIC}
     */
    public boolean isFullMask()
    {
        return this != NORMAL;
    }
}
