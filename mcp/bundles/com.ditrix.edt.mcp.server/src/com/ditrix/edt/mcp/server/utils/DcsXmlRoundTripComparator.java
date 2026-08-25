/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Namespace prefixes, attribute order and inter-element formatting whitespace are not content
 * identities.
 */
public final class DcsXmlRoundTripComparator
{
    private static final Pattern LEADING_QNAME = Pattern.compile(
        "^([A-Za-z_][A-Za-z0-9_.-]*):(\\S.*)$"); //$NON-NLS-1$

    /** Caps adversarial repeated-sibling matching inside the model write transaction. */
    private static final long MAX_WORK_UNITS = 20_000_000L;

    private DcsXmlRoundTripComparator()
    {
        // utility class
    }

    /**
     * Returns the first submitted XML path whose content cannot be found in the re-serialization.
     * Extra elements and attributes in {@code serialized} are allowed, except a newly introduced
     * {@code xsi:nil="true"}, which changes the submitted element's value.
     *
     * @param submitted caller's complete XML document
     * @param serialized EDT serialization after import
     * @return first missing path, or {@code null} when no submitted content was lost
     * @throws IllegalArgumentException when an input is not well-formed XML or the bounded
     * comparison budget is exceeded
     */
    public static String firstMissingPath(String submitted, String serialized)
    {
        Element expected = parse(submitted, "submitted").getDocumentElement(); //$NON-NLS-1$
        Element actual = parse(serialized, "re-serialized").getDocumentElement(); //$NON-NLS-1$
        Comparison comparison = new Comparison();
        if (comparison.matches(expected, actual))
        {
            return null;
        }
        return comparison.firstMissing(expected, actual, "/" + localName(expected)); //$NON-NLS-1$
    }

    private static final class Comparison
    {
        private final IdentityHashMap<Element, NodeInfo> infos = new IdentityHashMap<>();
        private final IdentityHashMap<Element, IdentityHashMap<Element, MatchResult>> results =
            new IdentityHashMap<>();
        private long workUnits;

        boolean matches(Element expected, Element actual)
        {
            IdentityHashMap<Element, MatchResult> actualResults = results.computeIfAbsent(expected,
                ignored -> new IdentityHashMap<>());
            MatchResult cached = actualResults.get(actual);
            if (cached != null)
            {
                return cached.matches;
            }

            consume(1);
            NodeInfo expectedInfo = info(expected);
            NodeInfo actualInfo = info(actual);
            if (!sameName(expected, actual) || firstAttributeMismatch(expected, actual) != null
                || newlyNil(expected, actual) || !expectedInfo.text.equals(actualInfo.text))
            {
                actualResults.put(actual, MatchResult.NO_MATCH);
                return false;
            }

            // This is the degenerate asymmetric case: the submitted element asserts its own name,
            // attributes and exact direct text, but no descendant content. Once those assertions
            // pass above, every non-nil child EDT materializes is enrichment by definition. Do not
            // send the empty side through descendant fingerprinting or augmentation.
            if (expectedInfo.children.isEmpty())
            {
                int[] actualMatches = new int[actualInfo.children.size()];
                Arrays.fill(actualMatches, -1);
                MatchResult result = new MatchResult(true,
                    new ChildMatching(actualMatches, new boolean[0]));
                actualResults.put(actual, result);
                return true;
            }

            ChildMatching children = matchChildren(expectedInfo.children, actualInfo.children);
            MatchResult result = new MatchResult(children.allExpectedMatched(), children);
            actualResults.put(actual, result);
            return result.matches;
        }

        private ChildMatching matchChildren(List<Element> expected, List<Element> actual)
        {
            int[] actualMatches = new int[actual.size()];
            Arrays.fill(actualMatches, -1);
            boolean[] fixedActual = new boolean[actual.size()];
            boolean[] expectedMatched = new boolean[expected.size()];

            // A sole same-name sibling has exactly one possible counterpart. Pair it directly,
            // before descendant features are considered; in particular this makes a bare submitted
            // polymorphic item meet the enriched item EDT materializes without depending on a
            // non-empty descendant shortlist.
            Map<String, Integer> expectedNameCounts = nameCounts(expected);
            Map<String, Integer> actualNameCounts = nameCounts(actual);
            Map<String, Integer> soleActualByName = new HashMap<>();
            for (int actualIndex = 0; actualIndex < actual.size(); actualIndex++)
            {
                String name = expandedName(actual.get(actualIndex));
                if (actualNameCounts.get(name) == 1) soleActualByName.put(name, actualIndex);
            }
            for (int expectedIndex = 0; expectedIndex < expected.size(); expectedIndex++)
            {
                String name = expandedName(expected.get(expectedIndex));
                Integer actualIndex = soleActualByName.get(name);
                if (expectedNameCounts.get(name) == 1 && actualIndex != null
                    && matches(expected.get(expectedIndex), actual.get(actualIndex)))
                {
                    actualMatches[actualIndex] = expectedIndex;
                    fixedActual[actualIndex] = true;
                    expectedMatched[expectedIndex] = true;
                }
            }

            // Reserve canonical-equivalent subtrees first. Besides being linear for the normal
            // round trip, this prevents a submitted subset from stealing its richer sibling's exact
            // counterpart.
            Map<Fingerprint, ArrayDeque<Integer>> exactActual = new HashMap<>();
            for (int actualIndex = 0; actualIndex < actual.size(); actualIndex++)
            {
                if (fixedActual[actualIndex]) continue;
                exactActual.computeIfAbsent(info(actual.get(actualIndex)).fingerprint,
                    ignored -> new ArrayDeque<>()).add(actualIndex);
            }
            for (int expectedIndex = 0; expectedIndex < expected.size(); expectedIndex++)
            {
                if (expectedMatched[expectedIndex]) continue;
                ArrayDeque<Integer> candidates = exactActual.get(
                    info(expected.get(expectedIndex)).fingerprint);
                while (candidates != null && !candidates.isEmpty())
                {
                    int actualIndex = candidates.removeFirst();
                    if (matches(expected.get(expectedIndex), actual.get(actualIndex))
                        && matches(actual.get(actualIndex), expected.get(expectedIndex)))
                    {
                        actualMatches[actualIndex] = expectedIndex;
                        fixedActual[actualIndex] = true;
                        expectedMatched[expectedIndex] = true;
                        break;
                    }
                }
            }

            CandidateIndex candidateIndex = new CandidateIndex(expected, actual, fixedActual);
            List<Integer> remaining = new ArrayList<>();
            for (int expectedIndex = 0; expectedIndex < expected.size(); expectedIndex++)
            {
                if (!expectedMatched[expectedIndex]) remaining.add(expectedIndex);
            }
            remaining.sort(Comparator
                .<Integer>comparingInt(index -> info(expected.get(index)).weight).reversed()
                .thenComparingInt(Integer::intValue));

            for (int expectedIndex : remaining)
            {
                boolean[] seen = new boolean[actual.size()];
                augment(expectedIndex, expected, actual, actualMatches, fixedActual, seen,
                    candidateIndex);
            }

            Arrays.fill(expectedMatched, false);
            for (int expectedIndex : actualMatches)
            {
                if (expectedIndex >= 0) expectedMatched[expectedIndex] = true;
            }
            return new ChildMatching(actualMatches, expectedMatched);
        }

        private Map<String, Integer> nameCounts(List<Element> elements)
        {
            Map<String, Integer> result = new HashMap<>();
            for (Element element : elements)
            {
                result.merge(expandedName(element), 1, Integer::sum);
            }
            return result;
        }

        private boolean augment(int expectedIndex, List<Element> expected, List<Element> actual,
            int[] actualMatches, boolean[] fixedActual, boolean[] seen,
            CandidateIndex candidateIndex)
        {
            List<Integer> preferred = candidateIndex.candidates(expectedIndex);
            if (augmentCandidates(expectedIndex, expected, actual, actualMatches, fixedActual, seen,
                candidateIndex, preferred))
            {
                return true;
            }

            // Descendant features are an acceleration hint, not part of the asymmetric contract.
            // Always retain a same-name fallback so an EDT-added child or attribute can never prune
            // the richer counterpart before matches() gets to evaluate it.
            return augmentCandidates(expectedIndex, expected, actual, actualMatches, fixedActual,
                seen, candidateIndex, candidateIndex.fallbackCandidates(expectedIndex, preferred));
        }

        private boolean augmentCandidates(int expectedIndex, List<Element> expected,
            List<Element> actual, int[] actualMatches, boolean[] fixedActual, boolean[] seen,
            CandidateIndex candidateIndex, List<Integer> candidates)
        {
            Element expectedElement = expected.get(expectedIndex);
            // Claim an available close counterpart before attempting a reassignment chain. This
            // keeps large reordered collections linear when their discriminating values are sparse.
            for (int pass = 0; pass < 2; pass++)
            {
                for (int actualIndex : candidates)
                {
                    boolean occupied = actualMatches[actualIndex] >= 0;
                    if (fixedActual[actualIndex] || seen[actualIndex]
                        || pass == 0 && occupied || pass == 1 && !occupied)
                    {
                        continue;
                    }
                    Element actualElement = actual.get(actualIndex);
                    if (!matches(expectedElement, actualElement)) continue;
                    seen[actualIndex] = true;
                    if (!occupied
                        || augment(actualMatches[actualIndex], expected, actual, actualMatches,
                            fixedActual, seen, candidateIndex))
                    {
                        actualMatches[actualIndex] = expectedIndex;
                        return true;
                    }
                }
            }
            return false;
        }

        String firstMissing(Element expected, Element actual, String path)
        {
            if (!sameName(expected, actual)) return path;

            String attribute = firstAttributeMismatch(expected, actual);
            if (attribute != null) return path + "/@" + attribute; //$NON-NLS-1$
            if (newlyNil(expected, actual)) return path + "/@nil"; //$NON-NLS-1$
            if (!info(expected).text.equals(info(actual).text)) return path;

            // Feature pruning can identify an impossible pair without evaluating it. Build its
            // child matching now because the diagnostic still needs the closest surviving shape.
            matches(expected, actual);
            MatchResult result = results.get(expected).get(actual);
            ChildMatching matching = result.children;
            List<Element> expectedChildren = info(expected).children;
            List<Element> actualChildren = info(actual).children;
            for (int expectedIndex = 0; expectedIndex < expectedChildren.size(); expectedIndex++)
            {
                if (matching.expectedMatched[expectedIndex]) continue;
                Element missing = expectedChildren.get(expectedIndex);
                String missingPath = childPath(expectedChildren, expectedIndex, path);
                int counterpart = closestUnmatched(missing, actualChildren, matching.actualMatches);
                return counterpart < 0 ? missingPath
                    : firstMissing(missing, actualChildren.get(counterpart), missingPath);
            }
            return path;
        }

        private int closestUnmatched(Element expected, List<Element> actual, int[] actualMatches)
        {
            Set<Long> expectedFeatures = features(expected);
            int bestIndex = -1;
            int bestMissingFeatures = Integer.MAX_VALUE;
            int bestWeightDifference = Integer.MAX_VALUE;
            for (int actualIndex = 0; actualIndex < actual.size(); actualIndex++)
            {
                Element candidate = actual.get(actualIndex);
                if (actualMatches[actualIndex] >= 0 || !sameName(expected, candidate)) continue;
                Set<Long> actualFeatures = features(candidate);
                int missingFeatures = 0;
                for (long feature : expectedFeatures)
                {
                    if (!actualFeatures.contains(feature)) missingFeatures++;
                }
                int weightDifference = Math.abs(info(expected).weight - info(candidate).weight);
                if (missingFeatures < bestMissingFeatures
                    || missingFeatures == bestMissingFeatures
                        && weightDifference < bestWeightDifference)
                {
                    bestIndex = actualIndex;
                    bestMissingFeatures = missingFeatures;
                    bestWeightDifference = weightDifference;
                }
            }
            return bestIndex;
        }

        private String firstAttributeMismatch(Element expected, Element actual)
        {
            NamedNodeMap attributes = expected.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++)
            {
                Attr attribute = (Attr)attributes.item(i);
                if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI()))
                {
                    continue;
                }
                Attr counterpart = counterpart(actual, attribute);
                if (counterpart == null
                    || !semanticText(attribute.getValue(), expected)
                        .equals(semanticText(counterpart.getValue(), actual)))
                {
                    return localName(attribute);
                }
            }
            return null;
        }

        private NodeInfo info(Element element)
        {
            NodeInfo cached = infos.get(element);
            if (cached != null) return cached;

            consume(1);
            List<Element> children = childElements(element);
            List<String> attributes = canonicalAttributes(element);
            String text = directText(element);
            int weight = 1 + attributes.size() + (text.isEmpty() ? 0 : 1);
            List<Fingerprint> childFingerprints = new ArrayList<>(children.size());
            for (Element child : children)
            {
                NodeInfo childInfo = info(child);
                weight += childInfo.weight;
                childFingerprints.add(childInfo.fingerprint);
            }
            Collections.sort(childFingerprints);

            long first = hash(0xcbf29ce484222325L, expandedName(element));
            long second = hash(0x9e3779b97f4a7c15L, text);
            first = hash(first, text);
            for (String attribute : attributes)
            {
                first = mix(first, attribute.hashCode());
                second = hash(second, attribute);
            }
            for (Fingerprint child : childFingerprints)
            {
                first = mix(first, child.first);
                second = mix(second, child.second);
            }
            Fingerprint fingerprint = new Fingerprint(first, second, weight, children.size(),
                attributes.size());
            NodeInfo result = new NodeInfo(children, text, weight, fingerprint);
            infos.put(element, result);
            return result;
        }

        private Set<Long> features(Element root)
        {
            Set<Long> result = new HashSet<>();
            ArrayDeque<Element> pending = new ArrayDeque<>();
            pending.push(root);
            while (!pending.isEmpty())
            {
                Element element = pending.pop();
                consume(1);
                NodeInfo node = info(element);
                long name = hash(0x84222325cbf29ce4L, expandedName(element));
                result.add(name);
                if (!node.text.isEmpty()) result.add(mix(name, node.text.hashCode()));
                for (String attribute : canonicalAttributes(element))
                {
                    result.add(mix(name, attribute.hashCode()));
                }
                for (Element child : node.children) pending.push(child);
            }
            return result;
        }

        private void consume(long units)
        {
            workUnits += units;
            if (workUnits > MAX_WORK_UNITS)
            {
                throw new IllegalArgumentException("XML comparison exceeded its safe work limit of " //$NON-NLS-1$
                    + MAX_WORK_UNITS + " units"); //$NON-NLS-1$
            }
        }

        private final class CandidateIndex
        {
            private final List<Element> expected;
            private final List<Element> actual;
            private final Map<String, List<Integer>> byName = new HashMap<>();
            private final Map<Long, List<Integer>> byFeature = new HashMap<>();

            CandidateIndex(List<Element> expected, List<Element> actual, boolean[] fixedActual)
            {
                this.expected = expected;
                this.actual = actual;
                for (int actualIndex = 0; actualIndex < actual.size(); actualIndex++)
                {
                    if (fixedActual[actualIndex]) continue;
                    Element candidate = actual.get(actualIndex);
                    byName.computeIfAbsent(expandedName(candidate), ignored -> new ArrayList<>())
                        .add(actualIndex);
                    for (long feature : features(candidate))
                    {
                        byFeature.computeIfAbsent(feature, ignored -> new ArrayList<>())
                            .add(actualIndex);
                    }
                }
            }

            List<Integer> candidates(int expectedIndex)
            {
                Element expectedElement = expected.get(expectedIndex);
                List<Integer> named = byName.get(expandedName(expectedElement));
                if (named == null) return Collections.emptyList();

                List<Integer> result = named;
                for (long feature : features(expectedElement))
                {
                    List<Integer> featured = byFeature.get(feature);
                    if (featured == null)
                    {
                        result = Collections.emptyList();
                        break;
                    }
                    if (featured.size() < result.size()) result = featured;
                }
                return ordered(expectedIndex, expectedElement, result);
            }

            List<Integer> fallbackCandidates(int expectedIndex, List<Integer> preferred)
            {
                Element expectedElement = expected.get(expectedIndex);
                List<Integer> named = byName.get(expandedName(expectedElement));
                if (named == null || named.size() == preferred.size())
                {
                    return Collections.emptyList();
                }

                Set<Integer> selected = new HashSet<>(preferred);
                List<Integer> fallback = new ArrayList<>();
                for (int actualIndex : named)
                {
                    if (!selected.contains(actualIndex)) fallback.add(actualIndex);
                }
                return ordered(expectedIndex, expectedElement, fallback);
            }

            private List<Integer> ordered(int expectedIndex, Element expectedElement,
                List<Integer> source)
            {
                List<Integer> ordered = new ArrayList<>();
                for (int actualIndex : source)
                {
                    if (sameName(expectedElement, actual.get(actualIndex))) ordered.add(actualIndex);
                }
                ordered.sort(Comparator
                    .comparingInt((Integer actualIndex) -> Math.abs(
                        info(expectedElement).weight - info(actual.get(actualIndex)).weight))
                    .thenComparingInt(actualIndex -> Math.abs(expectedIndex - actualIndex)));
                return ordered;
            }
        }
    }

    private static final class NodeInfo
    {
        final List<Element> children;
        final String text;
        final int weight;
        final Fingerprint fingerprint;

        NodeInfo(List<Element> children, String text, int weight, Fingerprint fingerprint)
        {
            this.children = children;
            this.text = text;
            this.weight = weight;
            this.fingerprint = fingerprint;
        }
    }

    private static final class MatchResult
    {
        static final MatchResult NO_MATCH = new MatchResult(false, null);
        final boolean matches;
        final ChildMatching children;

        MatchResult(boolean matches, ChildMatching children)
        {
            this.matches = matches;
            this.children = children;
        }
    }

    private static final class ChildMatching
    {
        final int[] actualMatches;
        final boolean[] expectedMatched;

        ChildMatching(int[] actualMatches, boolean[] expectedMatched)
        {
            this.actualMatches = actualMatches;
            this.expectedMatched = expectedMatched;
        }

        boolean allExpectedMatched()
        {
            for (boolean matched : expectedMatched)
            {
                if (!matched) return false;
            }
            return true;
        }
    }

    private static final class Fingerprint implements Comparable<Fingerprint>
    {
        final long first;
        final long second;
        final int weight;
        final int children;
        final int attributes;

        Fingerprint(long first, long second, int weight, int children, int attributes)
        {
            this.first = first;
            this.second = second;
            this.weight = weight;
            this.children = children;
            this.attributes = attributes;
        }

        @Override
        public int compareTo(Fingerprint other)
        {
            int result = Long.compare(first, other.first);
            return result != 0 ? result : Long.compare(second, other.second);
        }

        @Override
        public int hashCode()
        {
            return Long.hashCode(first) * 31 + Long.hashCode(second);
        }

        @Override
        public boolean equals(Object object)
        {
            if (!(object instanceof Fingerprint)) return false;
            Fingerprint other = (Fingerprint)object;
            return first == other.first && second == other.second && weight == other.weight
                && children == other.children && attributes == other.attributes;
        }
    }

    private static String childPath(List<Element> siblings, int childIndex, String parentPath)
    {
        Element child = siblings.get(childIndex);
        int ordinal = 0;
        int count = 0;
        for (int i = 0; i < siblings.size(); i++)
        {
            if (sameName(child, siblings.get(i)))
            {
                count++;
                if (i == childIndex) ordinal = count;
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

    private static List<String> canonicalAttributes(Element element)
    {
        List<String> result = new ArrayList<>();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++)
        {
            Attr attribute = (Attr)attributes.item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI()))
            {
                result.add(expandedName(attribute) + "=" //$NON-NLS-1$
                    + semanticText(attribute.getValue(), element));
            }
        }
        Collections.sort(result);
        return result;
    }

    private static Attr counterpart(Element element, Attr attribute)
    {
        return attribute.getNamespaceURI() == null ? element.getAttributeNode(attribute.getName())
            : element.getAttributeNodeNS(attribute.getNamespaceURI(), localName(attribute));
    }

    private static boolean newlyNil(Element expected, Element actual)
    {
        Attr expectedNil = expected.getAttributeNodeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI,
            "nil"); //$NON-NLS-1$
        Attr actualNil = actual.getAttributeNodeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI,
            "nil"); //$NON-NLS-1$
        return expectedNil == null && actualNil != null
            && ("true".equals(actualNil.getValue()) || "1".equals(actualNil.getValue())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String directText(Element element)
    {
        StringBuilder value = new StringBuilder();
        NodeList children = element.getChildNodes();
        boolean hasElementChild = false;
        for (int i = 0; i < children.getLength(); i++)
        {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE)
            {
                hasElementChild = true;
                break;
            }
        }
        for (int i = 0; i < children.getLength(); i++)
        {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
            {
                String text = child.getNodeValue();
                if (hasElementChild && text.trim().isEmpty()) continue;
                value.append(text);
            }
        }
        return semanticText(value.toString(), element);
    }

    /** Canonicalizes a leading QName against this document's own in-scope namespace bindings. */
    private static String semanticText(String raw, Element context)
    {
        String value = raw == null ? "" : raw; //$NON-NLS-1$
        Matcher qname = LEADING_QNAME.matcher(value);
        if (!qname.matches()) return value;
        String uri = context.lookupNamespaceURI(qname.group(1));
        return uri == null ? value : "{" + uri + "}:" + qname.group(2); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean sameName(Node left, Node right)
    {
        return expandedName(left).equals(expandedName(right));
    }

    private static String expandedName(Node node)
    {
        String uri = node.getNamespaceURI() == null ? "" : node.getNamespaceURI(); //$NON-NLS-1$
        return "{" + uri + "}" + localName(node); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String localName(Node node)
    {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private static long hash(long seed, String value)
    {
        long result = seed;
        for (int i = 0; i < value.length(); i++)
        {
            result ^= value.charAt(i);
            result *= 0x100000001b3L;
        }
        return result;
    }

    private static long mix(long seed, long value)
    {
        long result = seed ^ value;
        result ^= result >>> 33;
        result *= 0xff51afd7ed558ccdL;
        result ^= result >>> 33;
        result *= 0xc4ceb9fe1a85ec53L;
        return result ^ result >>> 33;
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
