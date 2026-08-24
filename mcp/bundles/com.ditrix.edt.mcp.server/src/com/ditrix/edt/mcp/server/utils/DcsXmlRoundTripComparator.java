/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Asymmetric semantic comparison for DCS XML imports. The submitted document must embed into the
 * EDT re-serialization, while elements/attributes/defaults that EDT adds are deliberately ignored.
 * Namespace prefixes, attribute order and formatting whitespace are not content identities.
 */
public final class DcsXmlRoundTripComparator
{
    private static final Pattern LEADING_QNAME = Pattern.compile(
        "^([A-Za-z_][A-Za-z0-9_.-]*):(\\S.*)$"); //$NON-NLS-1$

    private static final Pattern WHITESPACE = Pattern.compile("\\s+"); //$NON-NLS-1$

    private DcsXmlRoundTripComparator()
    {
        // utility class
    }

    /**
     * Returns the first submitted XML path whose content cannot be found in the re-serialization.
     * Extra content in {@code serialized} is always allowed.
     *
     * @param submitted caller's complete XML document
     * @param serialized EDT serialization after import
     * @return first missing path, or {@code null} when no submitted content was lost
     * @throws IllegalArgumentException when either comparison input is not well-formed XML
     */
    public static String firstMissingPath(String submitted, String serialized)
    {
        Element expected = parse(submitted, "submitted").getDocumentElement(); //$NON-NLS-1$
        Element actual = parse(serialized, "re-serialized").getDocumentElement(); //$NON-NLS-1$
        String rootPath = "/" + localName(expected); //$NON-NLS-1$
        return contains(expected, actual, rootPath);
    }

    private static String contains(Element expected, Element actual, String path)
    {
        if (!sameName(expected, actual))
        {
            return path;
        }

        NamedNodeMap attributes = expected.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++)
        {
            Attr attribute = (Attr)attributes.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI()))
            {
                continue;
            }
            Attr counterpart = attribute.getNamespaceURI() == null
                ? actual.getAttributeNode(attribute.getName())
                : actual.getAttributeNodeNS(attribute.getNamespaceURI(), localName(attribute));
            if (counterpart == null
                || !semanticText(attribute.getValue(), expected)
                    .equals(semanticText(counterpart.getValue(), actual)))
            {
                return path + "/@" + localName(attribute); //$NON-NLS-1$
            }
        }

        String expectedText = directText(expected);
        if (!expectedText.isEmpty() && !expectedText.equals(directText(actual)))
        {
            return path;
        }

        List<Element> expectedChildren = childElements(expected);
        List<Element> actualChildren = childElements(actual);
        int[] actualMatches = new int[actualChildren.size()];
        java.util.Arrays.fill(actualMatches, -1);
        boolean[] expectedMatched = new boolean[expectedChildren.size()];

        for (int expectedIndex = 0; expectedIndex < expectedChildren.size(); expectedIndex++)
        {
            boolean[] seen = new boolean[actualChildren.size()];
            expectedMatched[expectedIndex] = augment(expectedIndex, expectedChildren, actualChildren,
                actualMatches, seen, path);
        }

        // Do not report the first failed augmentation: a later augment can reshuffle an injective
        // match and make that earlier node innocent. Reconstruct the FINAL matched expected set.
        java.util.Arrays.fill(expectedMatched, false);
        for (int expectedIndex : actualMatches)
        {
            if (expectedIndex >= 0)
            {
                expectedMatched[expectedIndex] = true;
            }
        }
        for (int expectedIndex = 0; expectedIndex < expectedChildren.size(); expectedIndex++)
        {
            if (!expectedMatched[expectedIndex])
            {
                Element missing = expectedChildren.get(expectedIndex);
                String missingPath = childPath(expected, missing, path);
                String deepest = null;
                int deepestSegments = -1;
                // Prefer an unmatched same-name serialized subtree. That is the counterpart which
                // survived structurally but lost a deep child; matched candidates belong to innocent
                // submitted siblings and can point the caller at the wrong node.
                for (int actualIndex = 0; actualIndex < actualChildren.size(); actualIndex++)
                {
                    if (actualMatches[actualIndex] >= 0)
                    {
                        continue;
                    }
                    Element candidate = actualChildren.get(actualIndex);
                    if (sameName(missing, candidate))
                    {
                        String nested = contains(missing, candidate, missingPath);
                        int segments = pathSegments(nested);
                        if (nested != null && segments > deepestSegments)
                        {
                            deepest = nested;
                            deepestSegments = segments;
                        }
                    }
                }
                return deepest == null ? missingPath : deepest;
            }
        }
        return null;
    }

    private static int pathSegments(String path)
    {
        if (path == null)
        {
            return -1;
        }
        int result = 0;
        for (int i = 0; i < path.length(); i++)
        {
            if (path.charAt(i) == '/') result++;
        }
        return result;
    }

    /** Maximum bipartite matching avoids a greedy false refusal for repeated same-name elements. */
    private static boolean augment(int expectedIndex, List<Element> expectedChildren,
        List<Element> actualChildren, int[] actualMatches, boolean[] seen, String parentPath)
    {
        Element expected = expectedChildren.get(expectedIndex);
        String path = childPath((Element)expected.getParentNode(), expected, parentPath);
        for (int actualIndex = 0; actualIndex < actualChildren.size(); actualIndex++)
        {
            if (seen[actualIndex])
            {
                continue;
            }
            Element actual = actualChildren.get(actualIndex);
            if (!sameName(expected, actual) || contains(expected, actual, path) != null)
            {
                continue;
            }
            seen[actualIndex] = true;
            if (actualMatches[actualIndex] < 0
                || augment(actualMatches[actualIndex], expectedChildren, actualChildren,
                    actualMatches, seen, parentPath))
            {
                actualMatches[actualIndex] = expectedIndex;
                return true;
            }
        }
        return false;
    }

    private static String childPath(Element parent, Element child, String parentPath)
    {
        int ordinal = 0;
        int count = 0;
        for (Element sibling : childElements(parent))
        {
            if (sameName(child, sibling))
            {
                count++;
                if (sibling == child)
                {
                    ordinal = count;
                }
            }
        }
        return parentPath + "/" + localName(child) //$NON-NLS-1$
            + (count > 1 ? "[" + ordinal + "]" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static List<Element> childElements(Element parent)
    {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++)
        {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE)
            {
                children.add((Element)nodes.item(i));
            }
        }
        return children;
    }

    private static String directText(Element element)
    {
        StringBuilder value = new StringBuilder();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
            {
                value.append(child.getNodeValue()).append(' ');
            }
        }
        return semanticText(value.toString(), element);
    }

    /** Canonicalizes a leading QName against this document's own in-scope namespace bindings. */
    private static String semanticText(String raw, Element context)
    {
        String normalized = WHITESPACE.matcher(raw == null ? "" : raw.trim()).replaceAll(" "); //$NON-NLS-1$ //$NON-NLS-2$
        Matcher qname = LEADING_QNAME.matcher(normalized);
        if (!qname.matches())
        {
            return normalized;
        }
        String uri = context.lookupNamespaceURI(qname.group(1));
        return uri == null ? normalized : "{" + uri + "}:" + qname.group(2); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean sameName(Node left, Node right)
    {
        String leftUri = left.getNamespaceURI() == null ? "" : left.getNamespaceURI(); //$NON-NLS-1$
        String rightUri = right.getNamespaceURI() == null ? "" : right.getNamespaceURI(); //$NON-NLS-1$
        return leftUri.equals(rightUri) && localName(left).equals(localName(right));
    }

    private static String localName(Node node)
    {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private static Document parse(String xml, String label)
    {
        if (xml == null)
        {
            throw new IllegalArgumentException("The " + label + " XML is required"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try
        {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false); //$NON-NLS-1$
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); //$NON-NLS-1$
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); //$NON-NLS-1$
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); //$NON-NLS-1$
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8)));
        }
        catch (ParserConfigurationException | SAXException | IOException e)
        {
            throw new IllegalArgumentException("Could not parse the " + label + " XML: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage(), e);
        }
    }
}
