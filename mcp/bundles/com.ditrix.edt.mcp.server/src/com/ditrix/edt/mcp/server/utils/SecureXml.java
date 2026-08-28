/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * A single hardened {@link DocumentBuilderFactory} configuration for every place that parses XML
 * this server did not author - project files on disk, tool output, exported {@code .mdo}.
 *
 * <p>The hardening is not optional decoration: a {@code DocumentBuilderFactory} straight out of
 * {@code newInstance()} resolves DOCTYPE declarations and external entities, so a file the server
 * merely READS can make it open other files or network resources (XXE). Every switch below is off
 * for that reason, and they are kept together here so a new call site cannot pick up half of
 * them.</p>
 */
public final class SecureXml
{
    private SecureXml()
    {
    }

    /**
     * A namespace-aware factory with DOCTYPE declarations rejected and every external-entity,
     * DTD and schema access path switched off.
     *
     * @return a fresh factory (callers may configure it further; it is not shared)
     * @throws ParserConfigurationException if the underlying parser rejects one of the switches -
     *     which must fail loudly rather than silently yield an unhardened parser
     */
    public static DocumentBuilderFactory documentBuilderFactory() throws ParserConfigurationException
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false); //$NON-NLS-1$
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); //$NON-NLS-1$
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); //$NON-NLS-1$
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); //$NON-NLS-1$
        return factory;
    }
}
