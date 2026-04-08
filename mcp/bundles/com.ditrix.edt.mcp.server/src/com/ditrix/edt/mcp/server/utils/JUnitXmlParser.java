/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.File;
import java.io.InputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses a JUnit XML report file into a {@link JUnitTestResults} model.
 *
 * Supports the standard {@code testsuite}/{@code testcase} layout, including
 * the {@code testsuites} root wrapper. The parser is hardened against XXE,
 * SSRF and entity-expansion attacks.
 */
public final class JUnitXmlParser
{
    private JUnitXmlParser()
    {
        // utility class
    }

    /**
     * Parses a JUnit XML file. Throws on parse failure.
     */
    public static JUnitTestResults parse(File junitXml) throws Exception
    {
        DocumentBuilder builder = newSecureBuilder();
        Document doc = builder.parse(junitXml);
        return parseDocument(doc);
    }

    /**
     * Parses JUnit XML from an input stream. Useful for unit tests.
     */
    public static JUnitTestResults parse(InputStream in) throws Exception
    {
        DocumentBuilder builder = newSecureBuilder();
        Document doc = builder.parse(in);
        return parseDocument(doc);
    }

    private static JUnitTestResults parseDocument(Document doc)
    {
        doc.getDocumentElement().normalize();

        JUnitTestResults results = new JUnitTestResults();

        NodeList suiteNodes = doc.getElementsByTagName("testsuite"); //$NON-NLS-1$
        for (int i = 0; i < suiteNodes.getLength(); i++)
        {
            Element suite = (Element) suiteNodes.item(i);

            results.addToTotals(
                    getIntAttr(suite, "tests", 0), //$NON-NLS-1$
                    getIntAttr(suite, "failures", 0), //$NON-NLS-1$
                    getIntAttr(suite, "errors", 0), //$NON-NLS-1$
                    getIntAttr(suite, "skipped", 0)); //$NON-NLS-1$

            NodeList caseNodes = suite.getElementsByTagName("testcase"); //$NON-NLS-1$
            for (int j = 0; j < caseNodes.getLength(); j++)
            {
                Element testCase = (Element) caseNodes.item(j);
                String className = testCase.getAttribute("classname"); //$NON-NLS-1$
                String testName = testCase.getAttribute("name"); //$NON-NLS-1$
                String fullName = (className != null && !className.isEmpty())
                        ? className + "." + testName //$NON-NLS-1$
                        : testName;

                NodeList failureNodes = testCase.getElementsByTagName("failure"); //$NON-NLS-1$
                if (failureNodes.getLength() > 0)
                {
                    Element failure = (Element) failureNodes.item(0);
                    results.addFailure(new JUnitTestResults.TestCase(fullName,
                            failure.getAttribute("message"), //$NON-NLS-1$
                            failure.getTextContent()));
                }

                NodeList errorNodes = testCase.getElementsByTagName("error"); //$NON-NLS-1$
                if (errorNodes.getLength() > 0)
                {
                    Element error = (Element) errorNodes.item(0);
                    results.addError(new JUnitTestResults.TestCase(fullName,
                            error.getAttribute("message"), //$NON-NLS-1$
                            error.getTextContent()));
                }

                NodeList skippedNodes = testCase.getElementsByTagName("skipped"); //$NON-NLS-1$
                if (skippedNodes.getLength() > 0)
                {
                    Element skip = (Element) skippedNodes.item(0);
                    results.addSkipped(new JUnitTestResults.TestCase(fullName,
                            skip.getAttribute("message"), //$NON-NLS-1$
                            null));
                }
            }
        }

        // Standalone testcases without a wrapping testsuite — count them only.
        if (suiteNodes.getLength() == 0)
        {
            NodeList caseNodes = doc.getElementsByTagName("testcase"); //$NON-NLS-1$
            results.setTotal(caseNodes.getLength());
        }

        return results;
    }

    private static DocumentBuilder newSecureBuilder() throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Harden XML parsing against XXE/SSRF and entity-expansion attacks.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false); //$NON-NLS-1$
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); //$NON-NLS-1$
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); //$NON-NLS-1$
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); //$NON-NLS-1$
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); //$NON-NLS-1$
        return factory.newDocumentBuilder();
    }

    private static int getIntAttr(Element element, String attr, int defaultValue)
    {
        String value = element.getAttribute(attr);
        if (value == null || value.isEmpty())
        {
            return defaultValue;
        }
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }
}
