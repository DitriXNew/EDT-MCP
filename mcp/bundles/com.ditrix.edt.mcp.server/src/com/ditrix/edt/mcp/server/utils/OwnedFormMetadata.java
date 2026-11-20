/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

/**
 * Which properties of an OWNED form's metadata object (the {@code BasicForm} in its owner's {@code forms})
 * {@code modify_metadata} writes (issue #995). One rule for the write's refusal and for the assignable table
 * of {@code get_metadata_details}, so the table never offers a property the write refuses on the same FQN.
 *
 * <p>{@code formType} is not written: the enum alone would say Managed or Ordinary while the form's content
 * in {@code Form.form} stays of the kind it was created with, and nothing converts it. A common form is not
 * covered here - its metadata object is written as before.</p>
 */
public final class OwnedFormMetadata
{
    private OwnedFormMetadata()
    {
        // utility class
    }

    /**
     * @param propertyName a requested or listed property name (may be {@code null})
     * @return {@code false} for a property the owned-form write refuses, {@code true} otherwise
     */
    public static boolean isSettable(String propertyName)
    {
        return !MdClassPackage.Literals.BASIC_FORM__FORM_TYPE.getName().equalsIgnoreCase(propertyName);
    }
}
