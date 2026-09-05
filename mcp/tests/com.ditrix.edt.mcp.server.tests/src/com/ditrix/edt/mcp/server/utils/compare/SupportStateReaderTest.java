/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Iterator;

import org.eclipse.emf.common.util.BasicEList;
import org.junit.Test;

import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.md.compare.SupportSettingsComparisonNode;

/**
 * Tests for {@link SupportStateReader}, and specifically for how much of a node's child list it
 * reads to answer.
 *
 * <h2>Why the reads are counted</h2>
 * The reader looks for ONE child of a recognised type and stops at it, but it used to ask a helper
 * that copied the whole list into an {@code ArrayList} first - so the answer cost the full width
 * of the level whatever the level held and wherever the match sat. The node it is asked about is a
 * compared metadata object, whose children are its own members, and {@code appendSupport} runs the
 * reader once per rendered node.
 * <p>
 * Nothing about the ANSWER changes, which is why this is pinned as a counted bound: the copying
 * reader and the direct one find the same settings node and read the same values out of it, so no
 * assertion about the returned state could tell them apart. A duration would measure the machine.
 */
public class SupportStateReaderTest
{
    /**
     * The bound: a settings node at the front is found without reading what is behind it.
     * <p>
     * Copying the list read all 201 children to answer with the first, so this reddens by two
     * orders of magnitude on the copying reader rather than by one element.
     */
    @Test
    public void testTheSettingsNodeIsFoundWithoutReadingEveryOtherChild()
    {
        CountingChildren children = new CountingChildren();
        children.add(mock(SupportSettingsComparisonNode.class));
        for (int i = 0; i < 200; i++)
        {
            children.add(mock(ComparisonNode.class));
        }
        ComparisonNode node = nodeWith(children);

        assertNotNull("the settings node is there to be found", SupportStateReader.read(node)); //$NON-NLS-1$

        assertEquals("a reader that stops at the first match may read one child to find it " //$NON-NLS-1$
            + "at index zero - copying the level reads all of it first", //$NON-NLS-1$
            1, children.reads);
    }

    /**
     * The control that keeps the bound above from being satisfied by a reader that stopped
     * looking: a settings node BEHIND other children must still be found, and the walk that
     * reaches it passes a {@code null} element on the way.
     * <p>
     * The {@code null} is not decoration. The child list is the platform's own now, and its
     * elements may be {@code null}; the copy this change removed also filtered those out. Both
     * loops in the reader test each element with {@code instanceof}, and {@code null instanceof X}
     * is {@code false}, so the filter bought nothing - but that is a claim, and this is the test
     * of it.
     */
    @Test
    public void testASettingsNodeBehindOtherChildrenIsStillFound()
    {
        CountingChildren children = new CountingChildren();
        children.add(mock(ComparisonNode.class));
        children.add(null);
        children.add(mock(SupportSettingsComparisonNode.class));
        ComparisonNode node = nodeWith(children);

        assertNotNull("the match is behind two children, not absent", //$NON-NLS-1$
            SupportStateReader.read(node));

        assertEquals("and reaching it reads exactly the children in front of it, the null " //$NON-NLS-1$
            + "included", 3, children.reads); //$NON-NLS-1$
    }

    /**
     * A node carrying no support settings at all - the normal case for an object outside vendor
     * support - reads as no support rather than as an empty state.
     */
    @Test
    public void testANodeWithoutASettingsChildCarriesNoSupportState()
    {
        CountingChildren children = new CountingChildren();
        children.add(mock(ComparisonNode.class));
        children.add(mock(ComparisonNode.class));

        assertNull("no settings child is no support state, not an empty one", //$NON-NLS-1$
            SupportStateReader.read(nodeWith(children)));
    }

    /** A null node is tolerated, because the caller's tree is lazy and may hand one over. */
    @Test
    public void testANullNodeCarriesNoSupportState()
    {
        assertNull("a null node is not a node with empty support settings", //$NON-NLS-1$
            SupportStateReader.read(null));
    }

    /**
     * @param children the child list to hand out
     * @return a node answering with exactly that list
     */
    private static ComparisonNode nodeWith(CountingChildren children)
    {
        ComparisonNode node = mock(ComparisonNode.class);
        when(node.<ComparisonNode> getChildren()).thenReturn(children);
        return node;
    }

    /**
     * A child list that records how many of its elements were handed out.
     * <p>
     * Counted on the ITERATOR rather than on {@code get(int)}, because both the copying reader and
     * the direct one walk the list with a for-each: an override the walk does not go through
     * would count zero for either and pin nothing.
     */
    private static final class CountingChildren
        extends BasicEList<ComparisonNode>
    {
        private static final long serialVersionUID = 1L;

        transient int reads;

        @Override
        public Iterator<ComparisonNode> iterator()
        {
            Iterator<ComparisonNode> delegate = super.iterator();
            return new Iterator<ComparisonNode>()
            {
                @Override
                public boolean hasNext()
                {
                    return delegate.hasNext();
                }

                @Override
                public ComparisonNode next()
                {
                    reads++;
                    return delegate.next();
                }
            };
        }
    }
}
