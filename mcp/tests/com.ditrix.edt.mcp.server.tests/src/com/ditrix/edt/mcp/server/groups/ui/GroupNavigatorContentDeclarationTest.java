/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;

import org.eclipse.ui.navigator.Priority;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.ditrix.edt.mcp.server.utils.SecureXml;

/**
 * Ratchet for the two attributes of the groups navigator content extension that decide WHERE the
 * virtual group nodes appear, and whether claiming that place is safe (issue #521).
 *
 * <p>Neither is reachable from a mock: the placement is produced by the platform sorting the tree,
 * and what the platform sorts by is declared in {@code plugin.xml}. {@code CommonViewerSorter} asks
 * {@code category(element)} for each element, which returns the SEQUENCE NUMBER of the descriptor
 * that contributed it, seeded from that descriptor's declared {@code priority}; a lower number
 * sorts first. So the priority in the manifest IS the placement, and the existing
 * {@link NavigatorEnhancementManagerTest} - which verifies that the right CNF services are called -
 * cannot see it. Group nodes silently sank to the bottom of every collection when the priority was
 * lowered, and no test failed.
 *
 * <p>The assertions live in one file on purpose: the priority and the claim are one contract. The
 * priority is the floor CNF offers, so it ties with any peer extension that also asks for it, and
 * CNF orders such a tie by extension-id hash. What bounds that is not disjointness - a peer
 * claiming {@code IWorkbenchAdapter} as a possible child DOES match a group node, since
 * {@code GroupNavigatorAdapter} extends {@code WorkbenchAdapter} - but direction and semantics:
 * {@code getParent} returns the FIRST NON-NULL answer among the matching extensions, our own
 * provider answers {@code null} for anything that is not a group node, and a peer that outranks us
 * would be asked first every time rather than half the time. Keeping the claim narrow is what
 * stops us from contesting THEIR nodes, which is the asymmetry that settled #476.
 */
public class GroupNavigatorContentDeclarationTest
{
    private static final String GROUPS_CONTENT_ID = "com.ditrix.edt.mcp.server.groups.navigatorContent"; //$NON-NLS-1$

    private static final String GROUP_NODE_TYPE = "com.ditrix.edt.mcp.server.groups.ui.GroupNavigatorAdapter"; //$NON-NLS-1$

    @Test
    public void groupNodesTakeTheOnlyPriorityNothingCanOutrank() throws Exception
    {
        Element groupsContent = groupsNavigatorContent();
        String declared = groupsContent.getAttribute("priority"); //$NON-NLS-1$

        assertTrue("the groups content must declare a priority - CNF defaults an omitted one to " //$NON-NLS-1$
            + "NORMAL, which would sort group nodes below the tree's ordinary contents", //$NON-NLS-1$
            !declared.isEmpty());

        // Asserted against the FLOOR rather than against EDT's declared value. Comparing with
        // "higher" - what com._1c.g5.v8.dt.navigator.ui.v8model declares today - would make this
        // ratchet depend on a constant copied out of another product's manifest: were EDT to move
        // its own content up, the comparison would still pass while the guarantee was gone. At
        // HIGHEST there is nothing below to be outranked BY, whatever any other extension declares
        // now or later. It is also the only value that satisfies the placement at all, since EDT's
        // is one step above the floor.
        assertEquals("group nodes must take the lowest sequence number CNF offers, or the tree's " //$NON-NLS-1$
            + "ordinary contents can sort above them; this declares '" + declared //$NON-NLS-1$
            + "'. CommonViewerSorter sorts by the contributing descriptor's sequence number, so a " //$NON-NLS-1$
            + "HIGHER number means LOWER in the tree.", //$NON-NLS-1$
            Priority.HIGHEST_PRIORITY_VALUE, Priority.get(declared).getValue());
    }

    @Test
    public void theGroupsContentClaimsOnlyItsOwnNodesAsAPossibleChild() throws Exception
    {
        // The other half of the same contract, and the half that is ours to keep. A peer at this
        // priority can claim our nodes (GroupNavigatorAdapter is a WorkbenchAdapter), and CNF
        // breaks the tie by extension-id hash - but getParent takes the first NON-NULL answer, so
        // what we control is not being the one that answers wrongly for someone else's node. This
        // claim is why our provider is never even asked about them.
        List<String> claimed = instanceOfValuesUnder(groupsNavigatorContent(), "possibleChildren"); //$NON-NLS-1$

        assertEquals("the groups content must claim its own node type and nothing else as a " //$NON-NLS-1$
            + "possible child; a broader claim would have our provider answer for nodes it does not " //$NON-NLS-1$
            + "own, which is the half of #476 that was ours", //$NON-NLS-1$
            List.of(GROUP_NODE_TYPE), claimed);
    }

    @Test
    public void theGroupsContentStillTriggersOnOrdinaryNavigatorNodes() throws Exception
    {
        // Narrowing possibleChildren must not be mistaken for narrowing triggerPoints: the group
        // nodes are CHILDREN contributed to EDT's own collection nodes, and CNF only asks a
        // provider for children of an element its trigger points match. Without this, tightening
        // the claim above would remove the groups from the tree entirely rather than reorder them.
        List<String> triggers = instanceOfValuesUnder(groupsNavigatorContent(), "triggerPoints"); //$NON-NLS-1$

        assertTrue("the groups content must still trigger on the navigator's own nodes, which all " //$NON-NLS-1$
            + "implement IWorkbenchAdapter; found " + triggers, //$NON-NLS-1$
            triggers.contains("org.eclipse.ui.model.IWorkbenchAdapter")); //$NON-NLS-1$
    }

    private static Element groupsNavigatorContent() throws Exception
    {
        Document document = parsePluginXml();
        NodeList candidates = document.getElementsByTagName("navigatorContent"); //$NON-NLS-1$
        for (int i = 0; i < candidates.getLength(); i++)
        {
            Element candidate = (Element)candidates.item(i);
            if (GROUPS_CONTENT_ID.equals(candidate.getAttribute("id"))) //$NON-NLS-1$
            {
                return candidate;
            }
        }
        throw new AssertionError("no navigatorContent with id " + GROUPS_CONTENT_ID //$NON-NLS-1$
            + " in plugin.xml - the virtual groups have no way into the tree at all"); //$NON-NLS-1$
    }

    /**
     * The {@code value} of every {@code <instanceof>} under the named child element, in document
     * order. The {@code <or>} wrapper CNF allows is transparent here: what matters is WHICH types
     * are claimed, not how they are grouped.
     *
     * @param content the navigatorContent element
     * @param childName {@code triggerPoints} or {@code possibleChildren}
     * @return the claimed type names, never {@code null}
     */
    private static List<String> instanceOfValuesUnder(Element content, String childName)
    {
        List<String> values = new ArrayList<>();
        NodeList children = content.getElementsByTagName(childName);
        for (int i = 0; i < children.getLength(); i++)
        {
            collectInstanceOf(children.item(i), values);
        }
        return values;
    }

    private static void collectInstanceOf(Node node, List<String> values)
    {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE)
            {
                continue;
            }
            Element element = (Element)child;
            if ("instanceof".equals(element.getNodeName())) //$NON-NLS-1$
            {
                values.add(element.getAttribute("value")); //$NON-NLS-1$
            }
            else
            {
                collectInstanceOf(element, values);
            }
        }
    }

    private static Document parsePluginXml() throws Exception
    {
        URL url = resolve("plugin.xml"); //$NON-NLS-1$
        assertNotNull("plugin.xml is not reachable from the test fragment", url); //$NON-NLS-1$
        DocumentBuilder builder = SecureXml.documentBuilderFactory().newDocumentBuilder();
        try (InputStream in = url.openStream())
        {
            return builder.parse(in);
        }
    }

    /**
     * Resolves a bundle-root-relative resource: the host bundle entry first (this fragment's
     * classes are loaded by the host, so {@code getBundle} returns it), then the class loader as a
     * plain-classpath fallback. Mirrors {@code MessagesParityTest}.
     */
    private static URL resolve(String bundlePath)
    {
        Bundle bundle = FrameworkUtil.getBundle(GroupNavigatorContentDeclarationTest.class);
        if (bundle != null)
        {
            URL url = bundle.getEntry(bundlePath);
            if (url != null)
            {
                return url;
            }
        }
        return GroupNavigatorContentDeclarationTest.class.getResource("/" + bundlePath); //$NON-NLS-1$
    }
}
