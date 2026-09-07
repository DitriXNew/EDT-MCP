/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.ui.navigator.Priority;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.ditrix.edt.mcp.server.groups.model.Group;
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

    private static final String EDT_NAVIGATOR_VIEWER_ID = "com._1c.g5.v8.dt.ui2.navigator"; //$NON-NLS-1$

    private static final String GROUP_NODE_TYPE = GroupNavigatorAdapter.class.getName();

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
    public void groupNodesOutrankEveryContentBoundToTheSameViewer()
    {
        // The floor above proves nothing can outrank us; it does not prove we outrank the tree's
        // ordinary contents, because a peer is free to take the floor as well. That is the part
        // #521 is actually about.
        //
        // Asked of the RUNNING platform, and asked structurally: not "is EDT's v8model still
        // 'higher'" - an id we would be copying, which a future EDT is free to retire while
        // leaving the old contribution registered - but "does anything BOUND TO THIS VIEWER
        // declare a priority we do not beat". That is the population whose nodes share a tree with
        // ours, however the platform names them.
        // CNF keeps ONE Binding per viewer and feeds every viewerContentBinding element into it
        // (NavigatorViewerDescriptor.consumeContentBinding -> Binding.consumeIncludes/Excludes),
        // so includes and excludes accumulate viewer-wide and an exclude wins over any include -
        // which is what contentIdsBoundTo mirrors. The one part of that model it does NOT mirror
        // is binding inheritance: a viewer may pull another viewer's bindings in wholesale, and
        // those competitors would be invisible here. Nothing declares it for this viewer today,
        // and if that changes this fails rather than quietly comparing against too small a set.
        assertNull("the viewer now inherits bindings from another viewer, which this resolver " //$NON-NLS-1$
            + "does not follow - contents bound over there would be missing from the comparison", //$NON-NLS-1$
            inheritedBindingSource(EDT_NAVIGATOR_VIEWER_ID));

        Map<String, String> priorities = declaredPriorities();
        Set<String> bound = contentIdsBoundTo(EDT_NAVIGATOR_VIEWER_ID, priorities.keySet());

        assertTrue("this test cannot see its own content extension bound to " //$NON-NLS-1$
            + EDT_NAVIGATOR_VIEWER_ID + ", so it would be vacuous - the navigator extension " //$NON-NLS-1$
            + "points are not populated in this runtime. Bound: " + bound, //$NON-NLS-1$
            bound.contains(GROUPS_CONTENT_ID));

        Set<String> others = new TreeSet<>(bound);
        others.remove(GROUPS_CONTENT_ID);
        assertFalse("nothing but our own content is bound to " + EDT_NAVIGATOR_VIEWER_ID //$NON-NLS-1$
            + " in this runtime, so there is no ordinary content to outrank and the comparison " //$NON-NLS-1$
            + "would prove nothing", others.isEmpty()); //$NON-NLS-1$

        int ours = Priority.get(priorities.get(GROUPS_CONTENT_ID)).getValue();
        for (String other : others)
        {
            int theirs = Priority.get(priorities.get(other)).getValue();
            assertTrue("group nodes must sort strictly above every other content bound to the " //$NON-NLS-1$
                + "same viewer: ours is " + ours + ", '" + other + "' declares " + theirs //$NON-NLS-1$ //$NON-NLS-2$
                + ". Equal values land in the same band, where CNF orders by extension-id hash " //$NON-NLS-1$
                + "and the other side can come first - which the declared floor alone cannot rule " //$NON-NLS-1$
                + "out. If something else takes the floor too, no priority can fix the placement " //$NON-NLS-1$
                + "and it needs a different mechanism.", ours < theirs); //$NON-NLS-1$
        }
    }

    /**
     * Every {@code navigatorContent} the running platform declares, mapped to its declared
     * {@code priority} (empty string when omitted, which CNF reads as NORMAL).
     *
     * @return id to priority, never {@code null}
     */
    private static Map<String, String> declaredPriorities()
    {
        Map<String, String> priorities = new HashMap<>();
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null)
        {
            return priorities;
        }
        for (IConfigurationElement element : registry
            .getConfigurationElementsFor("org.eclipse.ui.navigator.navigatorContent")) //$NON-NLS-1$
        {
            if ("navigatorContent".equals(element.getName()) && element.getAttribute("id") != null) //$NON-NLS-1$ //$NON-NLS-2$
            {
                String priority = element.getAttribute("priority"); //$NON-NLS-1$
                priorities.put(element.getAttribute("id"), priority == null ? "" : priority); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return priorities;
    }

    /**
     * The content extension ids bound to a viewer, resolved the way CNF resolves them: every
     * {@code <includes>} pattern of every {@code viewerContentBinding} for that viewer is a REGEX
     * matched against the declared ids, minus anything an {@code <excludes>} pattern matches.
     *
     * @param viewerId the viewer to resolve bindings for
     * @param declaredIds the ids to match the patterns against
     * @return the bound subset, never {@code null}
     */
    private static Set<String> contentIdsBoundTo(String viewerId, Set<String> declaredIds)
    {
        Set<String> included = new TreeSet<>();
        Set<String> excluded = new TreeSet<>();
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null)
        {
            return included;
        }
        for (IConfigurationElement binding : registry
            .getConfigurationElementsFor("org.eclipse.ui.navigator.viewer")) //$NON-NLS-1$
        {
            if (!"viewerContentBinding".equals(binding.getName()) //$NON-NLS-1$
                || !viewerId.equals(binding.getAttribute("viewerId"))) //$NON-NLS-1$
            {
                continue;
            }
            collectPatternMatches(binding, "includes", declaredIds, included); //$NON-NLS-1$
            collectPatternMatches(binding, "excludes", declaredIds, excluded); //$NON-NLS-1$
        }
        included.removeAll(excluded);
        return included;
    }

    /**
     * The viewer this one inherits its bindings from, or {@code null} when it declares none.
     *
     * @param viewerId the viewer to inspect
     * @return the inherited-from viewer id, or {@code null}
     */
    private static String inheritedBindingSource(String viewerId)
    {
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null)
        {
            return null;
        }
        for (IConfigurationElement viewer : registry
            .getConfigurationElementsFor("org.eclipse.ui.navigator.viewer")) //$NON-NLS-1$
        {
            if ("viewer".equals(viewer.getName()) && viewerId.equals(viewer.getAttribute("viewerId"))) //$NON-NLS-1$ //$NON-NLS-2$
            {
                String inherited = viewer.getAttribute("inheritBindingsFromViewer"); //$NON-NLS-1$
                if (inherited != null)
                {
                    return inherited;
                }
            }
        }
        return null;
    }

    private static void collectPatternMatches(IConfigurationElement binding, String section,
        Set<String> declaredIds, Set<String> into)
    {
        for (IConfigurationElement group : binding.getChildren(section))
        {
            for (IConfigurationElement contentExtension : group.getChildren("contentExtension")) //$NON-NLS-1$
            {
                String pattern = contentExtension.getAttribute("pattern"); //$NON-NLS-1$
                if (pattern == null)
                {
                    continue;
                }
                for (String id : declaredIds)
                {
                    if (id.equals(pattern) || matchesPattern(pattern, id))
                    {
                        into.add(id);
                    }
                }
            }
        }
    }

    private static boolean matchesPattern(String pattern, String id)
    {
        try
        {
            return Pattern.matches(pattern, id);
        }
        catch (PatternSyntaxException e)
        {
            // A binding CNF itself would not compile cannot bind anything; the literal comparison
            // in the caller has already had its say.
            return false;
        }
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

    @Test
    public void theOrderingContractBelongsToTheProviderThatMakesTheGroupNodes() throws Exception
    {
        // Everything else here describes WHERE this extension's children land. None of it says
        // whose children they are: rewire contentProvider to another valid ICommonContentProvider
        // and the id, the priority, the binding and both expressions still check out while the
        // tree produces no GroupNavigatorAdapter at all - the "groups disappeared" that #521 was
        // first reported as, under a ratchet that would not notice.
        //
        // Compared against the CLASS rather than a string literal, so renaming the provider
        // without updating plugin.xml fails here too, as does a typo that no compiler would see.
        assertEquals("the ordering contract must be attached to the provider that actually " //$NON-NLS-1$
            + "creates the group nodes", GroupContentProvider.class.getName(), //$NON-NLS-1$
            groupsNavigatorContent().getAttribute("contentProvider")); //$NON-NLS-1$
    }

    @Test
    public void theDeclaredTriggerMatchesTheCollectionAdapterTheNodesHangFrom() throws Exception
    {
        // The trigger is checked as a NAME above; here it is checked against the class it has to
        // match. EDT's collection adapters are what group nodes hang from, and CNF only asks a
        // provider for children of an element its trigger points match - so if a target upgrade
        // stopped that base class implementing IWorkbenchAdapter, GroupContentProvider would never
        // be called for a collection and the groups WOULD disappear, with every string assertion
        // in this file still green. The class name comes from CollectionAdapterUtils, the matcher
        // that recognises those adapters at runtime, rather than from a second literal here.
        Class<?> collectionAdapter = loadPlatformClass(CollectionAdapterUtils.COLLECTION_ADAPTER_CLASS_NAME);
        assertNotNull("the platform class the group nodes hang from is not loadable: " //$NON-NLS-1$
            + CollectionAdapterUtils.COLLECTION_ADAPTER_CLASS_NAME //$NON-NLS-1$
            + " - either it was renamed, or CollectionAdapterUtils is matching a name that no " //$NON-NLS-1$
            + "longer exists", collectionAdapter); //$NON-NLS-1$

        List<String> triggers = instanceOfValuesUnder(groupsNavigatorContent(), "triggerPoints"); //$NON-NLS-1$
        boolean matched = false;
        for (String trigger : triggers)
        {
            Class<?> declared = loadPlatformClass(trigger);
            if (declared != null && declared.isAssignableFrom(collectionAdapter))
            {
                matched = true;
                break;
            }
        }
        assertTrue("no declared trigger point actually matches " //$NON-NLS-1$
            + collectionAdapter.getName() + " - CNF would never ask GroupContentProvider for the " //$NON-NLS-1$
            + "children of a collection, and the groups would be absent rather than misplaced. " //$NON-NLS-1$
            + "Declared: " + triggers, matched); //$NON-NLS-1$
    }

    @Test
    public void noSorterBoundToTheViewerTakesAPositionOnGroupNodes() throws Exception
    {
        // The one seam the declared priority cannot cover on its own. CommonViewerSorter delegates
        // to a bound commonSorter when one applies and only falls back to the descriptor category -
        // the thing the priority decides - when none does. EDT DOES bind one
        // (com._1c.g5.v8.dt.navigator.ui.NavigatorSorter on v8model), so the priority governs the
        // outcome for one reason only: that sorter's compare returns 0 for every pair, leaving the
        // stable sort to preserve the contribution order, which follows the sequence numbers.
        //
        // That is a property of someone else's code, so it is asserted rather than assumed: every
        // sorter bound to this viewer must stay neutral about a group node. If one ever starts
        // taking a position, the placement stops being ours to control by priority and this says
        // so instead of the tree quietly changing.
        Object groupNode = new GroupNavigatorAdapter(new Group("g", "/g"), //$NON-NLS-1$ //$NON-NLS-2$
            ResourcesPlugin.getWorkspace().getRoot().getProject("probe"), null); //$NON-NLS-1$
        Object ordinaryNode = new Object();

        int sorters = 0;
        for (IConfigurationElement sorter : boundSorters())
        {
            sorters++;
            ViewerComparator comparator = (ViewerComparator)sorter.createExecutableExtension("class"); //$NON-NLS-1$
            assertEquals("the sorter " + sorter.getAttribute("class") //$NON-NLS-1$ //$NON-NLS-2$
                + " bound to this viewer orders a group node against an ordinary one, so the " //$NON-NLS-1$
                + "declared priority no longer decides where group nodes land", //$NON-NLS-1$
                0, comparator.compare(null, groupNode, ordinaryNode));
            assertEquals("the same sorter, with the arguments swapped", //$NON-NLS-1$
                0, comparator.compare(null, ordinaryNode, groupNode));
        }
        assertTrue("no bound sorter was found at all, so this check proved nothing - the viewer " //$NON-NLS-1$
            + "bindings are not populated in this runtime", sorters > 0); //$NON-NLS-1$
    }

    /**
     * The {@code commonSorter} elements declared by the content extensions bound to the EDT
     * Navigator.
     *
     * @return the bound sorter declarations, never {@code null}
     * @throws Exception if the registry cannot be read
     */
    private static List<IConfigurationElement> boundSorters() throws Exception
    {
        List<IConfigurationElement> sorters = new ArrayList<>();
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null)
        {
            return sorters;
        }
        Set<String> bound = contentIdsBoundTo(EDT_NAVIGATOR_VIEWER_ID, declaredPriorities().keySet());
        for (IConfigurationElement content : registry
            .getConfigurationElementsFor("org.eclipse.ui.navigator.navigatorContent")) //$NON-NLS-1$
        {
            if ("navigatorContent".equals(content.getName()) //$NON-NLS-1$
                && bound.contains(content.getAttribute("id"))) //$NON-NLS-1$
            {
                sorters.addAll(List.of(content.getChildren("commonSorter"))); //$NON-NLS-1$
            }
        }
        return sorters;
    }

    /**
     * Loads a class contributed by any resolved bundle, or {@code null} when no bundle exports it.
     * Used for platform classes this fragment does not import.
     *
     * @param className the fully qualified name
     * @return the class, or {@code null}
     */
    private static Class<?> loadPlatformClass(String className)
    {
        Bundle self = FrameworkUtil.getBundle(GroupNavigatorContentDeclarationTest.class);
        if (self != null)
        {
            for (Bundle bundle : self.getBundleContext().getBundles())
            {
                try
                {
                    return bundle.loadClass(className);
                }
                catch (ClassNotFoundException | IllegalStateException e)
                {
                    // Not this bundle's class; keep looking.
                }
            }
        }
        try
        {
            return Class.forName(className);
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }
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
     * order - and a refusal for any operator that would change what the expression MEANS.
     * <p>
     * Flattening to type names alone is not enough: wrapping the expected predicate in
     * {@code <not>} leaves the collected list identical while inverting the claim, so
     * {@code possibleChildren} would claim every node EXCEPT a group node and the ratchet would
     * still be green. Only {@code <or>} is treated as transparent - it is the one wrapper CNF's
     * schema needs here and the one that cannot change the meaning of a single-element list.
     * Anything else ({@code not}, {@code and}, {@code adapt}, {@code test}, ...) fails the test by
     * name rather than being walked through.
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
            collectInstanceOf(children.item(i), childName, values);
        }
        return values;
    }

    private static void collectInstanceOf(Node node, String childName, List<String> values)
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
            String name = element.getNodeName();
            if ("instanceof".equals(name)) //$NON-NLS-1$
            {
                values.add(element.getAttribute("value")); //$NON-NLS-1$
            }
            else if ("or".equals(name)) //$NON-NLS-1$
            {
                collectInstanceOf(element, childName, values);
            }
            else
            {
                throw new AssertionError("<" + name + "> under <" + childName //$NON-NLS-1$ //$NON-NLS-2$
                    + ">: this ratchet reads the claimed types, and only <or> can be walked " //$NON-NLS-1$
                    + "through without changing what they mean. An operator like <not> would " //$NON-NLS-1$
                    + "leave the type list identical while inverting the claim. If the expression " //$NON-NLS-1$
                    + "really needs this, teach the test what it means before allowing it."); //$NON-NLS-1$
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
