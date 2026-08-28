/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.junit.Test;

import com._1c.g5.v8.dt.bsl.compare.BslModuleComparisonNode;
import com._1c.g5.v8.dt.bsl.compare.BslModuleSectionComparisonNode;
import com._1c.g5.v8.dt.bsl.compare.BslModuleSectionType;
import com._1c.g5.v8.dt.compare.core.PotentialMergeProblemDescription;
import com._1c.g5.v8.dt.compare.model.ComparedObjects;
import com._1c.g5.v8.dt.compare.model.ComparisonFlags;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonNodeStatus;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.IComparedObjects;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;
import com._1c.g5.v8.dt.form.compare.FormComparisonNode;
import com._1c.g5.v8.dt.md.compare.ParentSupportModeComparisonNode;
import com._1c.g5.v8.dt.md.compare.SupportSettingsComparisonNode;
import com._1c.g5.v8.dt.md.compare.UserSupportModeComparisonNode;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.e1c.g5.v8.dt.distribution.model.ParentSupportMode;
import com.e1c.g5.v8.dt.distribution.model.UserSupportMode;

/**
 * Renderer tests over STUB node graphs - no EDT comparison engine is started anywhere here.
 *
 * <p>The one assertion this file exists for is the honesty of the lazy tree: a node the engine has
 * not finished comparing must be REPORTED as unfinished and must never carry the words
 * "no differences". A renderer that dropped that guard would describe an uncompared subtree as
 * identical, which is the defect the whole feature is designed around - so the unfinished test is
 * paired with a finished positive control, otherwise it could pass vacuously on a renderer that
 * never emits the phrase at all.</p>
 */
public class ComparisonNodeRendererTest
{
    private static final ModelFixture MODEL = new ModelFixture();

    // ==================== Properties ====================

    @Test
    public void testMdObjectRendersThreeColumnPropertyTable()
    {
        EObject main = mdObject("Products", "main comment"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject other = mdObject("Products", "other comment"); //$NON-NLS-1$ //$NON-NLS-2$
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$

        String text = render(node, ComparisonNodeStatus.FINISHED,
            access(new ComparedObjects<EObject>(main, other, null)));

        assertTrue("the property table must be main/other/ancestor", //$NON-NLS-1$
            text.contains("| Main | Other | Ancestor |")); //$NON-NLS-1$
        String row = rowContaining(text, "main comment"); //$NON-NLS-1$
        assertNotNull("the differing property must be rendered", row); //$NON-NLS-1$
        assertTrue("the other side's value belongs in its own column: " + row, //$NON-NLS-1$
            row.contains("other comment")); //$NON-NLS-1$
        // The ancestor object is absent, so its column is PRESENT and EMPTY - not omitted, and not
        // silently filled with the main side's value.
        assertTrue("the ancestor column must be present and empty: " + row, //$NON-NLS-1$
            row.trim().endsWith("|  |")); //$NON-NLS-1$
    }

    @Test
    public void testPropertyCountReportsHowManyDiffer()
    {
        EObject main = mdObject("Products", "a"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject other = mdObject("Products", "b"); //$NON-NLS-1$ //$NON-NLS-2$
        String text = render(topNode("TopMdObjectComparisonNode"), ComparisonNodeStatus.FINISHED, //$NON-NLS-1$
            access(new ComparedObjects<EObject>(main, other, null)));

        assertTrue("exactly one of the two properties differs: " + text, //$NON-NLS-1$
            text.contains("**Properties:** 2 (1 differing)")); //$NON-NLS-1$
    }

    // ==================== A failed read is not an empty cell ====================

    /**
     * The property table's empty cell means "no value on that side". A property the introspector
     * could not READ arrived as the same empty cell, so a gap in what this server could see was
     * published as a fact about the configuration.
     */
    @Test
    public void testAnUnreadablePropertyIsMarkedRatherThanBlanked()
    {
        EObject main = unreadableComment("Products"); //$NON-NLS-1$
        EObject other = mdObject("Products", "other comment"); //$NON-NLS-1$ //$NON-NLS-2$

        String text = render(topNode("TopMdObjectComparisonNode"), ComparisonNodeStatus.FINISHED, //$NON-NLS-1$
            access(new ComparedObjects<EObject>(main, other, null)));

        String row = rowContaining(text, "other comment"); //$NON-NLS-1$
        assertNotNull("the property row must be rendered", row); //$NON-NLS-1$
        assertTrue("the side that could not be read must say so, not look empty: " + row, //$NON-NLS-1$
            row.contains(ComparisonNodeRenderer.UNREADABLE));
    }

    /**
     * The worse half of the same fold: with BOTH sides unreadable the two blanks matched, the row
     * counted as equal, and the document said the sides agree - about a property nobody read.
     */
    @Test
    public void testTwoUnreadableSidesAreNotReportedAsAgreeing()
    {
        EObject main = unreadableComment("Products"); //$NON-NLS-1$
        EObject other = unreadableComment("Products"); //$NON-NLS-1$

        String text = render(topNode("TopMdObjectComparisonNode"), ComparisonNodeStatus.FINISHED, //$NON-NLS-1$
            access(new ComparedObjects<EObject>(main, other, null)));

        assertFalse("nothing was read, so nothing may be called equal: " + text, //$NON-NLS-1$
            text.contains("_" + ComparisonNodeRenderer.NO_DIFFERENCES //$NON-NLS-1$
                + " in the compared properties._")); //$NON-NLS-1$
        assertTrue("and the reader must be told how many rows could not be read: " + text, //$NON-NLS-1$
            text.contains("1 not readable")); //$NON-NLS-1$
    }

    /**
     * An unreadable side must not HIDE a difference either: what two readable sides establish is
     * established whatever happened on the third.
     */
    @Test
    public void testADifferenceBetweenTwoReadableSidesSurvivesAnUnreadableThird()
    {
        EObject main = mdObject("Products", "a"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject other = mdObject("Products", "b"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject ancestor = unreadableComment("Products"); //$NON-NLS-1$

        String text = render(topNode("TopMdObjectComparisonNode"), ComparisonNodeStatus.FINISHED, //$NON-NLS-1$
            access(new ComparedObjects<EObject>(main, other, ancestor)));

        assertTrue("the difference the readable sides carry must still be counted: " + text, //$NON-NLS-1$
            text.contains("**Properties:** 2 (1 differing)")); //$NON-NLS-1$
    }

    /** The control: with every side readable the count keeps its plain shape. */
    @Test
    public void testAReadableTableSaysNothingAboutUnreadableRows()
    {
        EObject main = mdObject("Products", "a"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject other = mdObject("Products", "b"); //$NON-NLS-1$ //$NON-NLS-2$

        String text = render(topNode("TopMdObjectComparisonNode"), ComparisonNodeStatus.FINISHED, //$NON-NLS-1$
            access(new ComparedObjects<EObject>(main, other, null)));

        assertFalse("nothing failed, so nothing may be reported as unreadable: " + text, //$NON-NLS-1$
            text.contains("not readable")); //$NON-NLS-1$
        assertFalse("and no cell may carry the marker: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.UNREADABLE));
    }

    // ======= the marker is a RENDERING, not a channel the classifier reads back =======
    //
    // "This side was not read" used to be recovered by comparing the rendered cell against the
    // marker literal. Any text a cell can carry is text a metadata property can legitimately hold,
    // so a property whose real value IS that text was classified as unread: a difference it carried
    // came back UNDETERMINED instead of DIFFERENT, the differing count lost it, and the document
    // told the caller the property could not be read - about a property that had been read. The
    // flag now travels beside the cell and the text decides nothing.

    /**
     * The read succeeded, the two sides disagree, and the row must be counted as differing however
     * much its value happens to look like the marker.
     */
    @Test
    public void testAPropertyWhoseValueIsTheMarkerTextIsStillCountedAsDiffering()
    {
        EObject main = mdObject("Products", ComparisonNodeRenderer.UNREADABLE); //$NON-NLS-1$
        EObject other = mdObject("Products", "other comment"); //$NON-NLS-1$ //$NON-NLS-2$

        String text = render(topNode("TopMdObjectComparisonNode"), ComparisonNodeStatus.FINISHED, //$NON-NLS-1$
            access(new ComparedObjects<EObject>(main, other, null)));

        assertTrue("a value that merely LOOKS like the marker was read, so the row differs: " //$NON-NLS-1$
            + text, text.contains("**Properties:** 2 (1 differing)")); //$NON-NLS-1$
    }

    /**
     * The negative half, in its own test because JUnit stops a method at the first failed
     * assertion: no read failed here, so the summary may not report one.
     */
    @Test
    public void testAPropertyWhoseValueIsTheMarkerTextIsNotReportedAsUnreadable()
    {
        EObject main = mdObject("Products", ComparisonNodeRenderer.UNREADABLE); //$NON-NLS-1$
        EObject other = mdObject("Products", "other comment"); //$NON-NLS-1$ //$NON-NLS-2$

        String text = render(topNode("TopMdObjectComparisonNode"), ComparisonNodeStatus.FINISHED, //$NON-NLS-1$
            access(new ComparedObjects<EObject>(main, other, null)));

        assertFalse("every side was read, so nothing may be announced as unreadable: " + text, //$NON-NLS-1$
            text.contains("not readable")); //$NON-NLS-1$
    }

    /**
     * The control the two above need: a read that REALLY failed is still undetermined and still
     * counted, so they cannot be passed by a renderer that stopped classifying failed reads at all.
     */
    @Test
    public void testAGenuineReadFailureIsStillUndeterminedAndStillCounted()
    {
        EObject main = unreadableComment("Products"); //$NON-NLS-1$
        EObject other = mdObject("Products", "other comment"); //$NON-NLS-1$ //$NON-NLS-2$

        String text = render(topNode("TopMdObjectComparisonNode"), ComparisonNodeStatus.FINISHED, //$NON-NLS-1$
            access(new ComparedObjects<EObject>(main, other, null)));

        assertTrue("a side that truly could not be read establishes nothing: " + text, //$NON-NLS-1$
            text.contains("**Properties:** 2 (0 differing, 1 not readable)")); //$NON-NLS-1$
    }

    // ==================== The lazy tree ====================

    @Test
    public void testUnfinishedNodeIsReportedAsUnfinished()
    {
        String text = render(topNode("TopMdObjectComparisonNode"), //$NON-NLS-1$
            ComparisonNodeStatus.UNFINISHED, access(null));

        assertTrue("an unfinished node must open with the not-finished notice", //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.NOT_FINISHED_NOTICE));
    }

    @Test
    public void testUnfinishedNodeNeverSaysNoDifferences()
    {
        String text = render(topNode("TopMdObjectComparisonNode"), //$NON-NLS-1$
            ComparisonNodeStatus.UNFINISHED, access(null));

        assertFalse("an uncompared subtree must NEVER be described as having no differences: " //$NON-NLS-1$
            + text, text.toLowerCase().contains("no differences")); //$NON-NLS-1$
    }

    @Test
    public void testHasUnfinishedChildrenIsAlsoUnfinished()
    {
        String text = render(topNode("TopMdObjectComparisonNode"), //$NON-NLS-1$
            ComparisonNodeStatus.HAS_UNFINISHED_CHILDREN, access(null));

        assertTrue(text.contains(ComparisonNodeRenderer.NOT_FINISHED_NOTICE));
        assertFalse("a partially compared subtree is not an equal one: " + text, //$NON-NLS-1$
            text.toLowerCase().contains("no differences")); //$NON-NLS-1$
    }

    /**
     * Positive control for the two tests above: on a FINISHED node the renderer DOES say
     * "no differences". Without this, a renderer that had lost the phrase entirely would pass them.
     */
    @Test
    public void testFinishedNodeWithNothingToShowSaysNoDifferences()
    {
        String text = render(topNode("TopMdObjectComparisonNode"), //$NON-NLS-1$
            ComparisonNodeStatus.FINISHED, access(null));

        assertTrue("a finished node with no children must say so plainly: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.NO_DIFFERENCES));
        assertFalse("a finished node must not carry the not-finished notice", //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.NOT_FINISHED_NOTICE));
    }

    /**
     * A single side is not a comparison. With the other two objects absent every column but one is
     * empty because the object is MISSING, so calling that "no differences" states an agreement
     * nobody measured - the unfinished lie, one level down.
     */
    @Test
    public void testOneSidedObjectIsNotReportedAsHavingNoDifferences()
    {
        EObject main = mdObject("Products", "only here"); //$NON-NLS-1$ //$NON-NLS-2$
        String text = render(topNode("TopMdObjectComparisonNode"), ComparisonNodeStatus.FINISHED, //$NON-NLS-1$
            access(new ComparedObjects<EObject>(main, null, null)));

        assertFalse("one object is not an agreement between three: " + text, //$NON-NLS-1$
            text.contains("in the compared properties")); //$NON-NLS-1$
        assertTrue("the reader must be told WHY the other columns are empty: " + text, //$NON-NLS-1$
            text.contains("Only one side carries this object")); //$NON-NLS-1$
    }

    // ==================== State decoding ====================

    @Test
    public void testDoubleChangeRendersAsConflict()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasDoubleChanges();
        when(node.getComparisonFlags()).thenReturn(flags);

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null));

        assertTrue("a both-sides change is the conflict the caller must see: " + text, //$NON-NLS-1$
            text.contains("| State | " + ComparisonNodeState.CONFLICT.label() + " |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnflaggedNodeIsNotReportedAsEqual()
    {
        // No flags object at all is the ABSENCE of a verdict. A renderer that fell through to
        // "No differences" here would state a comparison result the engine never produced.
        String text = render(topNode("TopMdObjectComparisonNode"), ComparisonNodeStatus.FINISHED, //$NON-NLS-1$
            access(null));

        assertTrue("a node with no flags must be reported as unjudged: " + text, //$NON-NLS-1$
            text.contains("| State | " + ComparisonNodeState.NOT_REPORTED.label() + " |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFlaggedEqualNodeIsReportedAsEqual()
    {
        // The positive control for the test above: WITH a verdict that says nothing changed, the
        // state really is "no differences" - so the assertion there is about the missing verdict,
        // not about the renderer having lost the phrase.
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null));

        assertTrue("an engine verdict of 'unchanged' renders as such: " + text, //$NON-NLS-1$
            text.contains("| State | " + ComparisonNodeState.IDENTICAL.label() + " |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The three-way defect: main and other carry the SAME edit away from the common ancestor, so
     * they do not differ from EACH OTHER - and this document used to answer "No differences" for a
     * node the comparison report the caller came from calls "changed on both sides".
     * <p>
     * Agreement between the two documents is pinned by {@code ComparisonNodeStateTest}; this test
     * pins the half of it that lives here, and it fails on the old renderer with
     * {@code | State | No differences |}.
     */
    @Test
    public void testANodeBothSidesChangedIsNotReportedAsHavingNoDifferences()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        ComparisonFlags flags = new ComparisonFlags();
        flags.setHasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.MAIN);
        flags.setHasChanged(ComparisonSide.COMMON_ANCESTOR, ComparisonSide.OTHER);
        when(node.getComparisonFlags()).thenReturn(flags);

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null));

        assertFalse("a node that moved away from the ancestor on BOTH sides is not an equal " //$NON-NLS-1$
            + "node: " + text, //$NON-NLS-1$
            text.contains("| State | " + ComparisonNodeRenderer.NO_DIFFERENCES + " |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and it must be named the way the report names it: " + text, //$NON-NLS-1$
            text.contains("| State | " + ComparisonNodeState.CHANGED_ON_BOTH.label() + " |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Form node ====================

    @Test
    public void testFormNodeRendersTheSharedStructuralSnapshot()
    {
        FormComparisonNode node = mock(FormComparisonNode.class);
        when(node.eClass()).thenReturn(MODEL.formNodeClass);
        EObject form = new DynamicEObjectImpl(MODEL.formClass);
        form.eSet(MODEL.formName, "ItemForm"); //$NON-NLS-1$

        String text = render(node, ComparisonNodeStatus.FINISHED,
            access(new ComparedObjects<EObject>(form, null, null)));

        assertTrue("a form node renders the shared form-structure snapshot: " + text, //$NON-NLS-1$
            text.contains("# Form Structure")); //$NON-NLS-1$
        assertTrue("the snapshot is labelled with the side it came from", //$NON-NLS-1$
            text.contains("## Form structure (Main)")); //$NON-NLS-1$
        // The snapshot is MARKDOWN, not the form's XML: a raw tag would mean the reader was
        // bypassed and the file dumped instead.
        assertFalse("the form snapshot must carry no raw XML tag: " + text, text.contains("<")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ============ every side's structure is headed by THAT side's own name ============
    //
    // request.address is how the CALLER reached the node - an FQN or a node id, addressing one
    // side - and it used to head all three form-structure sections. For a form renamed between the
    // sides that attributes structure to the wrong FQN: the Other and Ancestor sections showed
    // their own side's form under the MAIN side's name, and nothing in the section says otherwise,
    // because the heading is the only name in it. The tree answers the question itself, per side,
    // and it is the same value the summary table above already prints.

    /** The symlinks the sides of {@link #renamedFormNode()} carry - deliberately unlike the request address. */
    private static final String MAIN_FORM_SYMLINK = "Catalog.Alpha.Form.ItemForm"; //$NON-NLS-1$

    private static final String OTHER_FORM_SYMLINK = "Catalog.Beta.Form.RenamedForm"; //$NON-NLS-1$

    private static final String ANCESTOR_FORM_SYMLINK = "Catalog.Gamma.Form.OldForm"; //$NON-NLS-1$

    /**
     * A form node whose three sides carry three DIFFERENT names, which is what a rename between the
     * sides looks like in the tree.
     *
     * @return the node
     */
    private static FormComparisonNode renamedFormNode()
    {
        FormComparisonNode node = mock(FormComparisonNode.class);
        when(node.eClass()).thenReturn(MODEL.formNodeClass);
        when(node.getSymlink(ComparisonSide.MAIN)).thenReturn(MAIN_FORM_SYMLINK);
        when(node.getSymlink(ComparisonSide.OTHER)).thenReturn(OTHER_FORM_SYMLINK);
        when(node.getSymlink(ComparisonSide.COMMON_ANCESTOR)).thenReturn(ANCESTOR_FORM_SYMLINK);
        return node;
    }

    /**
     * @param name the form's own name, so the three sides are distinguishable objects
     * @return a form-like object
     */
    private static EObject formNamed(String name)
    {
        EObject form = new DynamicEObjectImpl(MODEL.formClass);
        form.eSet(MODEL.formName, name);
        return form;
    }

    /**
     * @return the document for a form node whose sides are three differently named forms
     */
    private static String renderRenamedForm()
    {
        return render(renamedFormNode(), ComparisonNodeStatus.FINISHED,
            access(new ComparedObjects<EObject>(formNamed("MainForm"), formNamed("OtherForm"), //$NON-NLS-1$ //$NON-NLS-2$
                formNamed("AncestorForm")))); //$NON-NLS-1$
    }

    @Test
    public void testTheMainFormStructureIsHeadedByTheMainSideName()
    {
        String section = sectionOf(renderRenamedForm(), "## Form structure (Main)"); //$NON-NLS-1$

        assertTrue("the main section is headed by the main side's own name: " + section, //$NON-NLS-1$
            section.contains("# Form Structure: " + MAIN_FORM_SYMLINK)); //$NON-NLS-1$
    }

    @Test
    public void testTheOtherFormStructureIsHeadedByTheOtherSideName()
    {
        String section = sectionOf(renderRenamedForm(), "## Form structure (Other)"); //$NON-NLS-1$

        assertTrue("the other side's structure is the other side's form, and must be headed by " //$NON-NLS-1$
            + "its name: " + section, //$NON-NLS-1$
            section.contains("# Form Structure: " + OTHER_FORM_SYMLINK)); //$NON-NLS-1$
    }

    @Test
    public void testTheAncestorFormStructureIsHeadedByTheAncestorSideName()
    {
        String section = sectionOf(renderRenamedForm(), "## Form structure (Ancestor)"); //$NON-NLS-1$

        assertTrue("and the ancestor's, by the ancestor's: " + section, //$NON-NLS-1$
            section.contains("# Form Structure: " + ANCESTOR_FORM_SYMLINK)); //$NON-NLS-1$
    }

    /**
     * The ABSENCE that the three pins above would not catch on their own: a heading that gained the
     * side's name while keeping the request's would still attribute the structure to an FQN that
     * does not hold it. The request address is chosen to share no substring with the symlinks.
     */
    @Test
    public void testAFormStructureSectionDoesNotCarryTheAddressOfAnotherSide()
    {
        String section = sectionOf(renderRenamedForm(), "## Form structure (Other)"); //$NON-NLS-1$

        assertFalse("the address the node was reached by is not this side's name: " + section, //$NON-NLS-1$
            section.contains("Catalog.Products")); //$NON-NLS-1$
    }

    /**
     * The one side that has no name to be headed by borrows the request's OUT LOUD. A heading that
     * cannot be established must not read like one that was - that is the same defect as the one
     * above, just arrived at from the other end.
     */
    @Test
    public void testASideWithNoNameOfItsOwnSaysWhereItsHeadingCameFrom()
    {
        FormComparisonNode node = mock(FormComparisonNode.class);
        when(node.eClass()).thenReturn(MODEL.formNodeClass);
        when(node.getSymlink(ComparisonSide.MAIN)).thenReturn(MAIN_FORM_SYMLINK);
        when(node.getSymlink(ComparisonSide.OTHER)).thenReturn(null);

        String section = sectionOf(render(node, ComparisonNodeStatus.FINISHED,
            access(new ComparedObjects<EObject>(formNamed("MainForm"), formNamed("OtherForm"), //$NON-NLS-1$ //$NON-NLS-2$
                null))),
            "## Form structure (Other)"); //$NON-NLS-1$

        assertTrue("a borrowed heading must say it is borrowed, and from which side: " + section, //$NON-NLS-1$
            section.contains("carries no name of its own") //$NON-NLS-1$
                && section.contains("the address the node was reached by (Main)")); //$NON-NLS-1$
    }

    /**
     * And it still prints the address it borrowed, because it is the only one the document has.
     * Separated from the pin above so that a fallback which dropped the heading entirely fails
     * here rather than passing there.
     */
    @Test
    public void testASideWithNoNameOfItsOwnStillCarriesTheBorrowedAddress()
    {
        FormComparisonNode node = mock(FormComparisonNode.class);
        when(node.eClass()).thenReturn(MODEL.formNodeClass);
        when(node.getSymlink(ComparisonSide.OTHER)).thenReturn(""); //$NON-NLS-1$

        String section = sectionOf(render(node, ComparisonNodeStatus.FINISHED,
            access(new ComparedObjects<EObject>(null, formNamed("OtherForm"), null))), //$NON-NLS-1$
            "## Form structure (Other)"); //$NON-NLS-1$

        assertTrue("the borrowed address is still the heading: " + section, //$NON-NLS-1$
            section.contains("# Form Structure: Catalog.Products")); //$NON-NLS-1$
    }

    /**
     * A name made of whitespace is no name, and it must reach the SAME fallback an absent one
     * does.
     * <p>
     * The defect: the fallback asked whether the symlink was EMPTY. A symlink of spaces or tabs
     * is not empty, so it passed as this side's own name - the notice was suppressed and the
     * heading was rendered as {@code # Form Structure:} followed by whitespace, which reads as a
     * name read off this side and is not one. What decides the fallback is whether the value is
     * an ADDRESS.
     */
    @Test
    public void aSideWhoseNameIsOnlyWhitespaceSaysWhereItsHeadingCameFrom()
    {
        String section = sectionOf(renderWithABlankOtherName(), "## Form structure (Other)"); //$NON-NLS-1$

        assertTrue("whitespace is not a name, so the heading is borrowed and must say so: " //$NON-NLS-1$
            + section,
            section.contains("carries no name of its own") //$NON-NLS-1$
                && section.contains("the address the node was reached by (Main)")); //$NON-NLS-1$
    }

    /**
     * And it heads the section with the address it borrowed, rather than with the whitespace it
     * had. In its own test for the reason the empty-name pair is split: JUnit stops a method at
     * its first failed assertion, so a heading assertion sharing a method with the notice one
     * would only be reached while the notice was already right.
     */
    @Test
    public void aSideWhoseNameIsOnlyWhitespaceIsHeadedByTheBorrowedAddress()
    {
        String section = sectionOf(renderWithABlankOtherName(), "## Form structure (Other)"); //$NON-NLS-1$

        assertTrue("the borrowed address is the heading, not the whitespace: " + section, //$NON-NLS-1$
            section.contains("# Form Structure: Catalog.Products")); //$NON-NLS-1$
    }

    /**
     * @return the document for a node whose Other side is named with whitespace alone
     */
    private static String renderWithABlankOtherName()
    {
        FormComparisonNode node = mock(FormComparisonNode.class);
        when(node.eClass()).thenReturn(MODEL.formNodeClass);
        when(node.getSymlink(ComparisonSide.MAIN)).thenReturn(MAIN_FORM_SYMLINK);
        when(node.getSymlink(ComparisonSide.OTHER)).thenReturn(" \t"); //$NON-NLS-1$
        return render(node, ComparisonNodeStatus.FINISHED,
            access(new ComparedObjects<EObject>(formNamed("MainForm"), formNamed("OtherForm"), //$NON-NLS-1$ //$NON-NLS-2$
                null)));
    }

    // ==================== Module node ====================

    @Test
    public void testModuleNodeRendersItsSectionNames()
    {
        BslModuleSectionComparisonNode section = mock(BslModuleSectionComparisonNode.class);
        when(section.getSectionType()).thenReturn(BslModuleSectionType.PROCEDURE);
        when(section.getName(ComparisonSide.MAIN)).thenReturn("OnCreateAtServer"); //$NON-NLS-1$
        when(section.getName(ComparisonSide.OTHER)).thenReturn("OnCreateAtServer"); //$NON-NLS-1$
        when(section.getName(ComparisonSide.COMMON_ANCESTOR)).thenReturn(null);

        BslModuleComparisonNode module = mock(BslModuleComparisonNode.class);
        when(module.eClass()).thenReturn(MODEL.moduleNodeClass);
        EList<BslModuleSectionComparisonNode> sections = new BasicEList<>();
        sections.add(section);
        when(module.getChildren()).thenReturn(sections);

        String text = render(module, ComparisonNodeStatus.FINISHED, access(null));

        assertTrue(text.contains("## Module sections")); //$NON-NLS-1$
        assertTrue("the section's per-side name must be rendered: " + text, //$NON-NLS-1$
            text.contains("OnCreateAtServer")); //$NON-NLS-1$
        assertTrue("the section type must be rendered by its locale-free literal name: " + text, //$NON-NLS-1$
            text.contains(BslModuleSectionType.PROCEDURE.getName()));
    }

    @Test
    public void testTheFormSnapshotDropsTheRowsTheCallersLimitCannotHold()
    {
        // The snapshot is rendered INSIDE a document that promises "maximum rows per table", so
        // its tables are that document's tables too. Handing the reader no limit left them
        // unbounded: limit=1 still produced every attribute the form has.
        String text = renderForm(formWithAttributes(3), 1);

        assertTrue("the first row must survive the cap: " + text, text.contains("Attr0")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a row past the cap must not be rendered: " + text, //$NON-NLS-1$
            text.contains("Attr2")); //$NON-NLS-1$
    }

    @Test
    public void testAFormSnapshotThatDroppedRowsSaysSo()
    {
        // A cut table that looks whole is the same lie as "no differences" over an uncompared
        // subtree: the reader concludes the form has one attribute.
        String text = renderForm(formWithAttributes(3), 1);

        assertTrue("the cap must be named where it bit: " + text, //$NON-NLS-1$
            text.contains("truncated: only the first 1 are shown")); //$NON-NLS-1$
    }

    @Test
    public void testAFormSnapshotWithinTheLimitCarriesNoTruncationNote()
    {
        // The control that keeps the note from being unconditional: exactly `limit` rows is a
        // complete table, and telling the caller to raise the limit would send them after a page
        // that is already whole.
        String text = renderForm(formWithAttributes(3), 3);

        assertTrue(text.contains("Attr2")); //$NON-NLS-1$
        assertFalse("a complete table must not be flagged as truncated: " + text, //$NON-NLS-1$
            text.contains("truncated")); //$NON-NLS-1$
    }

    @Test
    public void testModuleSectionsBeyondTheLimitAreAnnouncedAsTruncated()
    {
        // flatten() raised the flag; until now nothing in this block read it, so the table was cut
        // and looked complete - while the child outline and the problem table beside it both
        // announce the very same cap.
        String text = render(moduleWithSections(3), ComparisonNodeStatus.FINISHED, access(null), 2);

        assertTrue("the module section table must announce its cap: " + text, //$NON-NLS-1$
            sectionOf(text, "## Module sections").contains(Pagination.limitReachedNotice(2))); //$NON-NLS-1$
    }

    @Test
    public void testModuleSectionsWithinTheLimitAreNotAnnouncedAsTruncated()
    {
        // Exactly `limit` sections drains the budget without declining anything, and a notice here
        // would point at a page that is already complete.
        String text = render(moduleWithSections(2), ComparisonNodeStatus.FINISHED, access(null), 2);

        assertFalse("a complete section table must not be flagged as truncated: " + text, //$NON-NLS-1$
            sectionOf(text, "## Module sections").contains("limit reached")); //$NON-NLS-1$
    }

    @Test
    public void testTheModuleSectionCountIsACountOfRenderedRows()
    {
        String text = render(moduleWithSections(3), ComparisonNodeStatus.FINISHED, access(null), 2);

        assertTrue("the header must count the rows the table actually holds: " + text, //$NON-NLS-1$
            text.contains("**Sections shown:** 2")); //$NON-NLS-1$
    }

    /**
     * The defect: the row budget was spent by the TRAVERSAL, not by the table. At depth > 1 a
     * descendant that is not a section - which this table never renders - still took a slot of
     * {@code limit}, so the section it pushed out was declined: the table came back with fewer
     * rows than the caller allowed, and a page that fitted was reported as cut.
     */
    @Test
    public void testADescendantThatIsNoRowDoesNotPushOutOneThatIs()
    {
        BslModuleSectionComparisonNode first = section("Section0"); //$NON-NLS-1$
        withChildren(first, childNode(77L));

        String text = sectionOf(render(moduleOf(first, section("Section1")), //$NON-NLS-1$
            ComparisonNodeStatus.FINISHED, access(null), 2, 2), "## Module sections"); //$NON-NLS-1$

        assertTrue("a node this table never renders must not cost it a row: " + text, //$NON-NLS-1$
            text.contains("Section1")); //$NON-NLS-1$
    }

    /** Its own literal: a cap announced over a page that is whole sends the caller after nothing. */
    @Test
    public void testADescendantThatIsNoRowDoesNotRaiseTheCap()
    {
        BslModuleSectionComparisonNode first = section("Section0"); //$NON-NLS-1$
        withChildren(first, childNode(77L));

        String text = sectionOf(render(moduleOf(first, section("Section1")), //$NON-NLS-1$
            ComparisonNodeStatus.FINISHED, access(null), 2, 2), "## Module sections"); //$NON-NLS-1$

        assertFalse("both sections fitted, so nothing was declined: " + text, //$NON-NLS-1$
            text.contains("limit reached")); //$NON-NLS-1$
    }

    /** And the count is the number of rows the table holds, which is now the same number. */
    @Test
    public void testTheSectionCountCountsRowsAndNotVisitedNodes()
    {
        BslModuleSectionComparisonNode first = section("Section0"); //$NON-NLS-1$
        withChildren(first, childNode(77L));

        String text = sectionOf(render(moduleOf(first, section("Section1")), //$NON-NLS-1$
            ComparisonNodeStatus.FINISHED, access(null), 2, 2), "## Module sections"); //$NON-NLS-1$

        assertTrue("the header must count the rows the table actually holds: " + text, //$NON-NLS-1$
            text.contains("**Sections shown:** 2")); //$NON-NLS-1$
    }

    /**
     * The defect: "walked PAST rather than collected" was the promise, and the code descended only
     * into children that were themselves sections - so a node that is not one did not cost a row,
     * it TRUNCATED its whole branch. Everything below it disappeared out of the table, and
     * {@code truncated} stayed false because those sections were never visited: rows vanished and
     * the document said the page was whole.
     *
     * <p>Reachable rather than theoretical. {@code BslModuleSectionComparisonNodeImpl
     * .getChildren()} is a bridge method delegating straight into
     * {@code TopComparisonNodeImpl.getChildren()}, which answers {@code topChildren} plus
     * {@code containmentChildren} with no filtering by type, so the narrow element type on the
     * interface is a generics declaration and the runtime list may carry any node.</p>
     */
    @Test
    public void testASectionBelowANonSectionIsStillCollected()
    {
        BslModuleSectionComparisonNode outer = section("Outer"); //$NON-NLS-1$
        ComparisonNode bridge = childNode(77L);
        withChildren(outer, bridge);
        withChildren(bridge, section("Inner")); //$NON-NLS-1$

        String text = sectionOf(render(moduleOf(outer), ComparisonNodeStatus.FINISHED,
            access(null), 10, 3), "## Module sections"); //$NON-NLS-1$

        assertTrue("a node this table does not render must be walked THROUGH, not stopped at: " //$NON-NLS-1$
            + text, text.contains("Inner")); //$NON-NLS-1$
    }

    /** Its own literal: the header must count the row that was nearly lost. */
    @Test
    public void testASectionBelowANonSectionIsCounted()
    {
        BslModuleSectionComparisonNode outer = section("Outer"); //$NON-NLS-1$
        ComparisonNode bridge = childNode(77L);
        withChildren(outer, bridge);
        withChildren(bridge, section("Inner")); //$NON-NLS-1$

        String text = sectionOf(render(moduleOf(outer), ComparisonNodeStatus.FINISHED,
            access(null), 10, 3), "## Module sections"); //$NON-NLS-1$

        assertTrue("both sections are rows: " + text, text.contains("**Sections shown:** 2")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * And its own literal for the Depth column: depth counts LEVELS, not sections. The node walked
     * past occupies the level it sits at, so the section under it is at 3 - reporting it as 2
     * would place it where nothing is.
     */
    @Test
    public void testTheDepthOfASectionBelowANonSectionCountsTheLevelWalkedPast()
    {
        BslModuleSectionComparisonNode outer = section("Outer"); //$NON-NLS-1$
        ComparisonNode bridge = childNode(77L);
        withChildren(outer, bridge);
        withChildren(bridge, section("Inner")); //$NON-NLS-1$

        String text = sectionOf(render(moduleOf(outer), ComparisonNodeStatus.FINISHED,
            access(null), 10, 3), "## Module sections"); //$NON-NLS-1$

        assertTrue("the walked-past level still counts: " + text, //$NON-NLS-1$
            text.contains("| 3 | " + BslModuleSectionType.PROCEDURE.getName() + " | Inner |")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The control that keeps the fix from paying for itself with a false alarm: a node walked past
     * costs no row, so a page that fitted is still reported as whole.
     */
    @Test
    public void testASectionBelowANonSectionRaisesNoCap()
    {
        BslModuleSectionComparisonNode outer = section("Outer"); //$NON-NLS-1$
        ComparisonNode bridge = childNode(77L);
        withChildren(outer, bridge);
        withChildren(bridge, section("Inner")); //$NON-NLS-1$

        String text = sectionOf(render(moduleOf(outer), ComparisonNodeStatus.FINISHED,
            access(null), 10, 3), "## Module sections"); //$NON-NLS-1$

        assertFalse("nothing was declined and nothing was skipped: " + text, //$NON-NLS-1$
            text.contains("limit reached")); //$NON-NLS-1$
        assertFalse("and the walk ran to the end: " + text, //$NON-NLS-1$
            text.contains("the section walk stopped")); //$NON-NLS-1$
    }

    /**
     * The bound the row limit stopped providing. Now that a walked-past node costs no row, the row
     * budget no longer caps how many nodes are VISITED, and depth alone bounds that at the
     * branching factor raised to the requested depth. The walk carries its own budget for exactly
     * that, and running into it is announced rather than swallowed - a walk that stopped LOOKING
     * knows nothing about what lay beyond, and reporting the page as whole would be the very
     * defect this test's neighbours are about.
     */
    @Test
    public void testAPathologicalSubtreeStopsTheWalkAndSaysSo()
    {
        BslModuleSectionComparisonNode outer = section("Outer"); //$NON-NLS-1$
        // Ten children, each of them the same node again: 10 + 100 + 1000 + 10000 nodes over the
        // four levels below the section, which passes the walk budget without passing the row one.
        ComparisonNode bridge = childNode(77L);
        withChildren(bridge, bridge, bridge, bridge, bridge, bridge, bridge, bridge, bridge, bridge,
            bridge);
        withChildren(outer, bridge, bridge, bridge, bridge, bridge, bridge, bridge, bridge, bridge,
            bridge);

        String text = sectionOf(render(moduleOf(outer), ComparisonNodeStatus.FINISHED,
            access(null), 500, 5), "## Module sections"); //$NON-NLS-1$

        assertTrue("the walk must stop and say where it stopped: " + text, //$NON-NLS-1$
            text.contains("the section walk stopped after " //$NON-NLS-1$
                + ComparisonNodeRenderer.MAX_SECTION_WALK_NODES + " nodes")); //$NON-NLS-1$
        assertFalse("no row was declined, so the row notice would send the caller after nothing: " //$NON-NLS-1$
            + text, text.contains("limit reached")); //$NON-NLS-1$
    }

    /**
     * The control: a NESTED SECTION is a row of this table, so it does spend the budget and the
     * section it pushes out is still declined and still announced. Without this the fix above
     * would also be passed by a table that had stopped counting anything at all.
     */
    @Test
    public void testANestedSectionIsARowAndStillReachesTheCap()
    {
        BslModuleSectionComparisonNode first = section("Section0"); //$NON-NLS-1$
        withChildren(first, section("Nested")); //$NON-NLS-1$

        String text = sectionOf(render(moduleOf(first, section("Section1")), //$NON-NLS-1$
            ComparisonNodeStatus.FINISHED, access(null), 2, 2), "## Module sections"); //$NON-NLS-1$

        assertTrue("a nested section is rendered, so the walk must still descend: " + text, //$NON-NLS-1$
            text.contains("Nested")); //$NON-NLS-1$
        assertTrue("and the section it pushed out must be announced: " + text, //$NON-NLS-1$
            text.contains(Pagination.limitReachedNotice(2)));
    }

    /**
     * The defect, and it was introduced by the fix above. Guarding the "no differences" phrase on
     * a LIST of the bounds - the node budget, and only it - left the DEPTH limit out, and the depth
     * limit is the one bound that bites while nothing at all has been collected. A module whose
     * sections sit under a child that is not a section has them at level 2, so at the default
     * {@code depth=1} the walk turns back with an empty list, no other bound has bitten, and the
     * document answers "no differences in the module sections" about a section it never looked at.
     * <p>
     * A false "nothing differs" out of a comparison tool is the worst answer it can give, and the
     * code before the fix did not give it: its list was non-empty, so it went to the table.
     */
    @Test
    public void testSectionsHiddenByTheDepthLimitAreNotReportedAsNoDifferences()
    {
        ComparisonNode bridge = childNode(77L);
        withChildren(bridge, section("Hidden")); //$NON-NLS-1$
        BslModuleComparisonNode module = moduleOf();
        withChildren(module, bridge);

        String text = sectionOf(render(module, ComparisonNodeStatus.FINISHED, access(null), 100, 1),
            "## Module sections"); //$NON-NLS-1$

        assertFalse("the walk never reached the section, so it may not be called absent: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.NO_DIFFERENCES + " in the module sections")); //$NON-NLS-1$
    }

    /** Its own literal: refusing to lie is not enough, the reader has to be told what to change. */
    @Test
    public void testSectionsHiddenByTheDepthLimitAreReportedWithTheReason()
    {
        ComparisonNode bridge = childNode(77L);
        withChildren(bridge, section("Hidden")); //$NON-NLS-1$
        BslModuleComparisonNode module = moduleOf();
        withChildren(module, bridge);

        String text = sectionOf(render(module, ComparisonNodeStatus.FINISHED, access(null), 100, 1),
            "## Module sections"); //$NON-NLS-1$

        assertTrue("an empty table must still say what stopped the walk: " + text, //$NON-NLS-1$
            text.contains("**Sections shown:** 0")); //$NON-NLS-1$
        assertTrue("and name the bound the caller can raise: " + text, //$NON-NLS-1$
            text.contains("turned back at depth 1")); //$NON-NLS-1$
        assertTrue("with the way to widen it: " + text, text.contains("raise depth")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The positive control for the pair above: a walk that ran to the END of the module still says
     * "no differences". Without it, a renderer that had simply dropped the phrase would pass both.
     */
    @Test
    public void testAModuleWalkedToItsEndStillSaysNoDifferences()
    {
        // A child that is not a section and has nothing under it: the walk turns back at depth 1
        // having seen everything there is, so nothing narrowed the answer.
        BslModuleComparisonNode module = moduleOf();
        withChildren(module, childNode(77L));

        String text = sectionOf(render(module, ComparisonNodeStatus.FINISHED, access(null), 100, 1),
            "## Module sections"); //$NON-NLS-1$

        assertTrue("a complete walk that found no section says so plainly: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.NO_DIFFERENCES + " in the module sections")); //$NON-NLS-1$
    }

    /**
     * The second half of the same defect: the bounds did not stop the WALK, only the collecting.
     * After a row was declined the outer loops went on offering siblings, so a module with more
     * direct sections than the limit declined a row and then spent the entire node budget on the
     * subtree beside it - printing the node-budget sentence next to the row-limit one. That second
     * warning tells the caller to lower a depth that had nothing to do with anything, and it is
     * produced entirely by work that could not have added a row.
     */
    @Test
    public void testADeclinedRowDoesNotAlsoRaiseTheNodeBudgetWarning()
    {
        // Ten children, each of them the same node again: 10 + 100 + 1000 + 10000 nodes over the
        // four levels below it, which passes the walk budget on its own.
        ComparisonNode pathological = childNode(77L);
        withChildren(pathological, pathological, pathological, pathological, pathological,
            pathological, pathological, pathological, pathological, pathological, pathological);
        BslModuleComparisonNode module = moduleOf();
        withChildren(module, section("Section0"), section("Section1"), pathological); //$NON-NLS-1$ //$NON-NLS-2$

        String text = sectionOf(render(module, ComparisonNodeStatus.FINISHED, access(null), 1, 5),
            "## Module sections"); //$NON-NLS-1$

        assertTrue("the row that was refused must be announced: " + text, //$NON-NLS-1$
            text.contains(Pagination.limitReachedNotice(1)));
        assertFalse("nothing was left to look for, so the walk must not go on and then report " //$NON-NLS-1$
            + "having run out of nodes: " + text, //$NON-NLS-1$
            text.contains("the section walk stopped")); //$NON-NLS-1$
    }

    /**
     * And its own test, counting WORK rather than words: the neighbouring test would also be
     * passed by a walk that still visited the whole graph and merely suppressed the second
     * sentence. What is counted is how many times a node BEYOND the refusal is asked for its
     * children - the walk's only way of going further.
     */
    @Test
    public void testTheWalkStopsAskingForChildrenOnceARowIsDeclined()
    {
        AtomicInteger asked = new AtomicInteger();
        ComparisonNode beyond = mock(ComparisonNode.class);
        when(beyond.eClass()).thenReturn(MODEL.nodeClass("ChildMdObjectComparisonNode")); //$NON-NLS-1$
        when(beyond.<ComparisonNode> getChildren()).thenAnswer(invocation -> {
            asked.incrementAndGet();
            return new BasicEList<ComparisonNode>();
        });
        BslModuleComparisonNode module = moduleOf();
        // Two sections for a limit of one - the second is refused - and three nodes after it that
        // the walk has no reason left to open.
        withChildren(module, section("Section0"), section("Section1"), beyond, beyond, beyond); //$NON-NLS-1$ //$NON-NLS-2$

        render(module, ComparisonNodeStatus.FINISHED, access(null), 1, 2);

        assertEquals("no row can be collected any more, so nothing past the refusal may be " //$NON-NLS-1$
            + "opened", 0, asked.get()); //$NON-NLS-1$
    }

    // ==================== Support state ====================

    @Test
    public void testSupportSettingsChildRendersAllThreeSides()
    {
        UserSupportModeComparisonNode user = mock(UserSupportModeComparisonNode.class);
        when(user.getParentConfigurationName()).thenReturn("VendorConfig"); //$NON-NLS-1$
        when(user.getMainValue()).thenReturn(UserSupportMode.CHANGES_ALLOWED);
        when(user.getOtherValue()).thenReturn(UserSupportMode.CHANGES_NOT_ALLOWED);
        when(user.getAncestorValue()).thenReturn(UserSupportMode.CANCELLED);

        ParentSupportModeComparisonNode parent = mock(ParentSupportModeComparisonNode.class);
        when(parent.getMainValue()).thenReturn(ParentSupportMode.WARNING_MODE);
        when(parent.getOtherValue()).thenReturn(ParentSupportMode.PROTECT_MODE);
        when(parent.getAncestorValue()).thenReturn(ParentSupportMode.FREE_MODE);

        SupportSettingsComparisonNode settings = mock(SupportSettingsComparisonNode.class);
        withChildren(settings, user, parent);

        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        withChildren(node, settings);

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null));

        assertTrue(text.contains("## Support settings")); //$NON-NLS-1$
        assertTrue("the parent configuration name comes from the support node", //$NON-NLS-1$
            text.contains("VendorConfig")); //$NON-NLS-1$
        for (String expected : Arrays.asList(UserSupportMode.CHANGES_ALLOWED.getName(),
            UserSupportMode.CHANGES_NOT_ALLOWED.getName(), UserSupportMode.CANCELLED.getName(),
            ParentSupportMode.WARNING_MODE.getName(), ParentSupportMode.PROTECT_MODE.getName(),
            ParentSupportMode.FREE_MODE.getName()))
        {
            assertTrue("support mode '" + expected + "' must be rendered: " + text, //$NON-NLS-1$ //$NON-NLS-2$
                text.contains(expected));
        }
    }

    @Test
    public void testNodeWithoutSupportSettingsRendersNoSupportSection()
    {
        String text = render(topNode("TopMdObjectComparisonNode"), //$NON-NLS-1$
            ComparisonNodeStatus.FINISHED, access(null));

        assertFalse("an object outside vendor support must not grow an empty support table", //$NON-NLS-1$
            text.contains("## Support settings")); //$NON-NLS-1$
    }

    // ==================== Potential problems ====================

    @Test
    public void testPotentialProblemsAreLabelledPotential()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(7L));
        StubAccess access = access(null);
        access.problems = Collections.singletonList(
            new PotentialMergeProblemDescription("Short text", "Full text")); //$NON-NLS-1$ //$NON-NLS-2$

        String text = render(node, ComparisonNodeStatus.FINISHED, access);

        assertTrue(text.contains("## Potential problems")); //$NON-NLS-1$
        assertTrue("the report must state these are possibilities, not results: " + text, //$NON-NLS-1$
            text.contains("POTENTIAL only")); //$NON-NLS-1$
        assertTrue(text.contains("Short text")); //$NON-NLS-1$
        assertTrue(text.contains("Full text")); //$NON-NLS-1$
    }

    /**
     * The ONE section of this document whose text this repository does not author: the platform
     * builds {@code PotentialMergeProblemDescription} from its own NLS bundles under
     * {@code Locale.getDefault()}, so on a Russian EDT these two columns read in Russian while the
     * rest of the document stays English. That is tolerable only while it is DISCLOSED - the class
     * otherwise promises locale-free labels, and an undisclosed exception to that promise is the
     * report claiming a determinism it does not have.
     */
    @Test
    public void testPotentialProblemTableSaysItsTextIsThePlatformsOwn()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(7L));
        StubAccess access = access(null);
        access.problems = Collections.singletonList(
            new PotentialMergeProblemDescription("Short text", "Full text")); //$NON-NLS-1$ //$NON-NLS-2$

        String text = render(node, ComparisonNodeStatus.FINISHED, access);

        assertTrue("platform-authored cells must be declared as such: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.PLATFORM_TEXT_NOTICE));
        // The escape hatch the notice points at has to actually be in the table. The full header is
        // matched, not just the id column: "| Node id |" alone also occurs in the child outline.
        String header = "| Node id | Problem | Details |"; //$NON-NLS-1$
        assertTrue("the locale-free identity column must be there to point at: " + text, //$NON-NLS-1$
            text.contains(header));
        // Above the table it disclaims, not somewhere further down where the rows have already been
        // read as the tool's own words.
        assertTrue("the notice must precede the table it describes: " + text, //$NON-NLS-1$
            text.indexOf(ComparisonNodeRenderer.PLATFORM_TEXT_NOTICE) < text.indexOf(header));
    }

    /**
     * The negative control for the test above: with nothing platform-authored on the page there is
     * nothing to disclaim, and a notice printed unconditionally would be boilerplate on every single
     * node render - which is how a disclaimer stops being read before it ever matters.
     */
    @Test
    public void testWithoutPotentialProblemsThereIsNoPlatformTextNotice()
    {
        String text = render(topNode("TopMdObjectComparisonNode"), //$NON-NLS-1$
            ComparisonNodeStatus.FINISHED, access(null));

        assertTrue("the section itself is still rendered: " + text, //$NON-NLS-1$
            text.contains("## Potential problems")); //$NON-NLS-1$
        assertFalse("nothing platform-authored was rendered, so nothing is disclaimed: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.PLATFORM_TEXT_NOTICE));
    }

    /**
     * The problem list is capped like every other table in this document, and a capped count that
     * does not SAY it was capped reads as the subtree total. Pinned on the NOTICE rather than on the
     * number, because the number was already correct before the cap was announced.
     */
    @Test
    public void testPotentialProblemsOverTheLimitAnnounceTheCap()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(7L));
        StubAccess access = access(null);
        access.problems = problems(5);

        String text = render(node, ComparisonNodeStatus.FINISHED, access, 3);

        assertTrue("only the capped rows are rendered: " + text, //$NON-NLS-1$
            text.contains("**Potential problems:** 3")); //$NON-NLS-1$
        assertTrue("a capped count must say that it is capped: " + text, //$NON-NLS-1$
            text.contains(Pagination.limitReachedNotice(3)));
        assertFalse("a problem past the cap must not be rendered: " + text, //$NON-NLS-1$
            text.contains("problem-3")); //$NON-NLS-1$
    }

    /**
     * The positive control for the test above: below the cap there is nothing to announce, so an
     * unconditional notice - which would make every count unreadable - fails here.
     */
    @Test
    public void testPotentialProblemsUnderTheLimitCarryNoCapNotice()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(7L));
        StubAccess access = access(null);
        access.problems = problems(2);

        String text = render(node, ComparisonNodeStatus.FINISHED, access, 3);

        assertTrue("all of them fit: " + text, text.contains("**Potential problems:** 2")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an uncapped count must not claim a cap: " + text, //$NON-NLS-1$
            text.contains("limit reached")); //$NON-NLS-1$
    }

    /**
     * The addressed node is not part of the flattening budget. Seeding the flatten output with it
     * spent one row of the cap before the first child was visited, so the LAST child of a subtree
     * that exactly fills the limit dropped out of scope and was never asked for its problems.
     */
    @Test
    public void testEveryChildWithinTheLimitIsAskedForItsProblems()
    {
        ComparisonNode first = childNode(11L);
        ComparisonNode second = childNode(12L);
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(10L));
        withChildren(node, first, second);

        StubAccess access = access(null);
        access.byNode.put(Long.valueOf(11L), Collections.singletonList(
            new PotentialMergeProblemDescription("first-child", "first-child details"))); //$NON-NLS-1$ //$NON-NLS-2$
        access.byNode.put(Long.valueOf(12L), Collections.singletonList(
            new PotentialMergeProblemDescription("second-child", "second-child details"))); //$NON-NLS-1$ //$NON-NLS-2$

        String text = render(node, ComparisonNodeStatus.FINISHED, access, 2);

        assertTrue("the first child's problem is reported: " + text, //$NON-NLS-1$
            text.contains("first-child")); //$NON-NLS-1$
        assertTrue("the last child inside the limit must not fall out of scope: " + text, //$NON-NLS-1$
            text.contains("second-child")); //$NON-NLS-1$
    }

    /**
     * "None reported" is a claim about what was LOOKED AT, and this section's row limit caps the
     * DESCENDANT LIST before a single problem is read off it. With three children and a limit of
     * two, the third child is never asked - so a problem recorded on it was reported as "(none
     * reported)", which is the same lie as "no differences" over an uncompared subtree.
     */
    @Test
    public void testAProblemBeyondTheTruncatedDescendantsIsNotReportedAsNone()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(10L));
        withChildren(node, childNode(11L), childNode(12L), childNode(13L));
        StubAccess access = access(null);
        access.byNode.put(Long.valueOf(13L), Collections.singletonList(
            new PotentialMergeProblemDescription("third-child", "third-child details"))); //$NON-NLS-1$ //$NON-NLS-2$

        // Scoped to the ONE section under test, so an assertion about this table cannot be
        // satisfied by a sentence printed under another heading.
        String text = sectionOf(render(node, ComparisonNodeStatus.FINISHED, access, 2),
            "## Potential problems"); //$NON-NLS-1$

        assertFalse("the third child was never visited, so its problem cannot be rendered: " + text, //$NON-NLS-1$
            text.contains("third-child")); //$NON-NLS-1$
        // The ABSENCE of the phrase, not the presence of a caveat beside it: a renderer that
        // printed "none reported" and then explained the cap would satisfy an assertion about the
        // explanation alone, which is exactly what this pair of tests used to do.
        assertFalse("an absence may not be ASSERTED over nodes nobody visited: " + text, //$NON-NLS-1$
            text.contains("none reported")); //$NON-NLS-1$
        assertTrue("and the section must name the bound that narrowed the scan: " + text, //$NON-NLS-1$
            text.contains("only the first 2 descendant nodes were examined")); //$NON-NLS-1$
    }

    /**
     * The control: with a limit that covers every descendant there is nothing to disclaim, so an
     * unconditional caveat - which would make the section unreadable - fails here.
     */
    @Test
    public void testAFullyScannedSubtreeReportsNoneWithoutACaveat()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(10L));
        withChildren(node, childNode(11L), childNode(12L));

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null), 2);

        assertTrue("nothing was found and nothing was skipped: " + text, //$NON-NLS-1$
            text.contains("_(none reported)_")); //$NON-NLS-1$
        assertFalse("a complete scan must not claim it was cut short: " + text, //$NON-NLS-1$
            text.contains("descendant nodes were examined")); //$NON-NLS-1$
    }

    /**
     * The THIRD place the same defect lived, and it is closed by the same construction rather than
     * by a third list of caveats: the scope of this scan is the same bounded walk the child
     * outline renders, so the DEPTH limit narrows it exactly as the row limit does. A problem on a
     * grandchild is outside a {@code depth=1} scan, and "(none reported)" over it asserts an
     * absence about a node nobody asked.
     */
    @Test
    public void testAProblemBelowTheRequestedDepthIsNotReportedAsNone()
    {
        ComparisonNode grandchild = childNode(12L);
        ComparisonNode child = childNode(11L);
        withChildren(child, grandchild);
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(10L));
        withChildren(node, child);
        StubAccess access = access(null);
        access.byNode.put(Long.valueOf(12L), Collections.singletonList(
            new PotentialMergeProblemDescription("grandchild", "grandchild details"))); //$NON-NLS-1$ //$NON-NLS-2$

        // Scoped to the ONE section under test: the child outline beside it names the same bound,
        // and an assertion over the whole document would be satisfied by that one instead.
        String text = sectionOf(render(node, ComparisonNodeStatus.FINISHED, access, 100, 1),
            "## Potential problems"); //$NON-NLS-1$

        assertFalse("the grandchild is below the requested depth, so it was never asked: " + text, //$NON-NLS-1$
            text.contains("grandchild details")); //$NON-NLS-1$
        // The pin that makes this test about the phrase rather than about the footnote under it.
        // It passed on a renderer that printed "none reported" and merely appended the reason,
        // because it only ever asked for the reason.
        assertFalse("an absence may not be ASSERTED over a level nobody descended to: " + text, //$NON-NLS-1$
            text.contains("none reported")); //$NON-NLS-1$
        assertTrue("and the bound that caused it must be named: " + text, //$NON-NLS-1$
            text.contains("turned back at depth 1")); //$NON-NLS-1$
    }

    // ============ A non-empty table is qualified by the same predicate as an empty one ============

    /**
     * The other half of the same claim. A count line qualified by the ROW limit alone reported a
     * walk the DEPTH limit had cut as a whole one: with one problem on a child and another on a
     * grandchild below the requested depth, the section read "Potential problems: 1" and nothing
     * beside it said the scan had turned back - a capped number read as a subtree total.
     */
    @Test
    public void testANonEmptyTableCutByTheDepthLimitSaysTheScanWasPartial()
    {
        String text = renderProblemsCutByDepth();

        assertTrue("the problem inside the scan is rendered: " + text, //$NON-NLS-1$
            text.contains("visible details")); //$NON-NLS-1$
        assertFalse("the one below the requested depth was never asked: " + text, //$NON-NLS-1$
            text.contains("hidden details")); //$NON-NLS-1$
        assertTrue("so the count covers what was visited, and must not read as a total: " + text, //$NON-NLS-1$
            text.contains("The scan was partial")); //$NON-NLS-1$
    }

    /**
     * Its own literal: saying the scan was partial is not enough, the bound the caller can raise
     * has to be named - and the row limit, which is what this branch used to ask about, is not it.
     */
    @Test
    public void testANonEmptyTableCutByTheDepthLimitNamesThatBound()
    {
        String text = renderProblemsCutByDepth();

        assertTrue("the caller can only widen a bound that is named: " + text, //$NON-NLS-1$
            text.contains("turned back at depth 1")); //$NON-NLS-1$
    }

    /**
     * Two bounds at once, which is where a site that unpacks ONE of them shows: the row limit caps
     * the descendant list at the third child while the depth limit turns the walk back inside the
     * first. Announcing only the row limit sent the caller after a number that was not the whole
     * story, and it is the shape the shared enumeration exists to make impossible.
     */
    @Test
    public void testACountCutByBothBoundsAnnouncesTheRowLimit()
    {
        String text = renderProblemsCutByBothBounds();

        assertTrue("the row limit narrowed the scope and must be named: " + text, //$NON-NLS-1$
            text.contains("only the first 2 descendant nodes were examined")); //$NON-NLS-1$
    }

    /** Its own literal, because the defect is precisely that the SECOND clause went missing. */
    @Test
    public void testACountCutByBothBoundsAlsoAnnouncesTheDepthLimit()
    {
        String text = renderProblemsCutByBothBounds();

        assertTrue("the depth limit cut the same scan and must be named beside it: " + text, //$NON-NLS-1$
            text.contains("turned back at depth 1")); //$NON-NLS-1$
    }

    // ==================== A null child is not something below the level ====================

    /**
     * {@code childrenOf} hands back the PLATFORM's list rather than a copy of it, and that list may
     * carry {@code null} elements. Every walk here tolerates them by returning on entry, so a node
     * whose only child is {@code null} has nothing below it - but the depth gate asked whether the
     * LIST was empty, called the level occupied and recorded the depth bound over a walk that had
     * in fact covered everything.
     */
    @Test
    public void testAChildListHoldingOnlyNullRaisesNoDepthBoundInTheOutline()
    {
        ComparisonNode child = childNode(11L);
        withNullOnlyChild(child);
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(10L));
        withChildren(node, child);

        String text = sectionOf(render(node, ComparisonNodeStatus.FINISHED, access(null), 100, 1),
            "## Children"); //$NON-NLS-1$

        assertTrue("the child itself is still rendered: " + text, //$NON-NLS-1$
            text.contains("**Children shown:** 1")); //$NON-NLS-1$
        assertFalse("nothing was hidden, so no bound may be announced: " + text, //$NON-NLS-1$
            text.contains("turned back at depth")); //$NON-NLS-1$
    }

    /**
     * The same list seen from the sentence a false bound SILENCES: one spurious
     * {@code DEPTH_LIMIT} is enough to withdraw the honest "No differences", which is the answer a
     * complete walk owes the caller.
     */
    @Test
    public void testAChildListHoldingOnlyNullDoesNotSuppressTheModuleSectionPhrase()
    {
        ComparisonNode bridge = childNode(77L);
        withNullOnlyChild(bridge);
        BslModuleComparisonNode module = moduleOf();
        withChildren(module, bridge);

        String text = sectionOf(render(module, ComparisonNodeStatus.FINISHED, access(null), 100, 1),
            "## Module sections"); //$NON-NLS-1$

        assertTrue("the walk saw everything there was, so the phrase must survive: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.NO_DIFFERENCES + " in the module sections")); //$NON-NLS-1$
    }

    /** The positive control: a REAL child below the level still raises the bound it always did. */
    @Test
    public void testARealChildBelowTheLevelStillRaisesTheDepthBound()
    {
        ComparisonNode child = childNode(11L);
        withChildren(child, childNode(12L));
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(10L));
        withChildren(node, child);

        String text = sectionOf(render(node, ComparisonNodeStatus.FINISHED, access(null), 100, 1),
            "## Children"); //$NON-NLS-1$

        assertTrue("a level that really has something below it is still announced: " + text, //$NON-NLS-1$
            text.contains("turned back at depth 1")); //$NON-NLS-1$
    }

    // ==================== Truncation is a declined row, not an exhausted budget ====================

    @Test
    public void testExactlyTheLimitOfChildrenIsNotReportedAsTruncated()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(10L));
        withChildren(node, childNode(11L), childNode(12L));

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null), 2);

        // Every child was rendered, so telling the caller to re-run with a higher limit sends them
        // after a page that is already complete. Same rule, and the same reason, as
        // FormStructureReader.renderItems.
        assertFalse("a complete page must not be flagged as truncated: " + text, //$NON-NLS-1$
            text.contains("limit")); //$NON-NLS-1$
    }

    @Test
    public void testMoreChildrenThanTheLimitIsReportedAsTruncated()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(10L));
        withChildren(node, childNode(11L), childNode(12L), childNode(13L));

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null), 2);

        assertTrue("a page that dropped a child must say so: " + text, //$NON-NLS-1$
            text.contains("limit")); //$NON-NLS-1$
    }

    // ==================== Fixtures ====================

    private static String render(ComparisonNode node, ComparisonNodeStatus status,
        ComparisonNodeRenderer.NodeAccess access)
    {
        return render(node, status, access, 100);
    }

    private static String render(ComparisonNode node, ComparisonNodeStatus status,
        ComparisonNodeRenderer.NodeAccess access, int limit)
    {
        return render(node, status, access, limit, 1);
    }

    private static String render(ComparisonNode node, ComparisonNodeStatus status,
        ComparisonNodeRenderer.NodeAccess access, int limit, int depth)
    {
        return render(node, status, access, limit, depth,
            ComparisonNodeRenderer.ContentCoverage.COMPARED);
    }

    private static String render(ComparisonNode node, ComparisonNodeStatus status,
        ComparisonNodeRenderer.NodeAccess access, int limit, int depth,
        ComparisonNodeRenderer.ContentCoverage coverage)
    {
        ComparisonNodeRenderer.Request request = new ComparisonNodeRenderer.Request("cmp-1", //$NON-NLS-1$
            "Catalog.Products", ComparisonSide.MAIN, status, depth, limit, null, coverage); //$NON-NLS-1$
        return ComparisonNodeRenderer.render(request, node, access);
    }

    // ============ a SCOPED run is reported as one, and it is reported as a RUN ============
    //
    // A scoped comparison turns mergeObjectsContent on, and the platform then drops the own
    // features of every object whose qualified name is not at or under a scope entry and builds no
    // child node for them. Such an object is matched, so it carries flags - and those flags read
    // exactly like the flags of an object that WAS compared and found equal. The tree report
    // already says this about the whole run; a single expanded node used to say nothing at all,
    // and then said it about the node, which no reading of the tree can support.

    /**
     * The classifier, exercised directly. It used to live in the tool's platform-facing adapter,
     * decided from a node, and no test could reach it: every test above and below supplied a ready
     * enum, so the decision itself was covered by nothing. It is now a function of ONE run-level
     * fact, and both of its values are pinned here.
     */
    @Test
    public void testAWholeConfigurationRunComparedContentEverywhere()
    {
        assertEquals("nothing is excluded when the exclusion is off", //$NON-NLS-1$
            ComparisonNodeRenderer.ContentCoverage.COMPARED,
            ComparisonNodeRenderer.ContentCoverage.ofRun(true));
    }

    @Test
    public void testAScopedRunIsClassifiedAsScopedAndNotAsCompared()
    {
        assertEquals("a scoped run excluded content somewhere, and the document must say so", //$NON-NLS-1$
            ComparisonNodeRenderer.ContentCoverage.SCOPED_RUN,
            ComparisonNodeRenderer.ContentCoverage.ofRun(false));
    }

    @Test
    public void testAScopedRunOpensWithTheScopedRunNotice()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null), 100, 1,
            ComparisonNodeRenderer.ContentCoverage.SCOPED_RUN);

        assertTrue("the document must open by saying the run excluded content: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.SCOPED_RUN_NOTICE));
    }

    /**
     * The notice states the RUN. It may not tell the caller that THIS object was excluded, because
     * the comparison tree does not answer that - see {@code ContentCoverage}. The pin is on the
     * word that makes the difference: the document says the placement is not stated, and a
     * rewording that quietly re-asserts the node would drop it.
     */
    @Test
    public void testTheScopedRunNoticeDoesNotClaimThisNodeWasExcluded()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null), 100, 1,
            ComparisonNodeRenderer.ContentCoverage.SCOPED_RUN);

        assertTrue("the document must say the node's own placement is NOT stated: " + text, //$NON-NLS-1$
            text.contains("is not stated here")); //$NON-NLS-1$
        assertFalse("and it must not assert that this node is outside the scope: " + text, //$NON-NLS-1$
            text.contains("this node is outside the")); //$NON-NLS-1$
    }

    @Test
    public void testAScopedRunIsNotReportedAsIdenticalWithoutQualification()
    {
        // The State cell comes from the platform's flags, which for an excluded object were filled
        // in without its content ever being looked at. The label stays - it is what EDT says - and
        // it is qualified, because on its own it reads as "compared, and equal".
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null), 100, 1,
            ComparisonNodeRenderer.ContentCoverage.SCOPED_RUN);

        assertTrue("the platform's own label is kept: " + text, text.contains("identical")); //$NON-NLS-1$
        assertTrue("and it may not stand unqualified: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.STATE_SCOPED_RUN));
    }

    @Test
    public void testAnEmptyChildOutlineInAScopedRunDoesNotSayNoDifferences()
    {
        // In a scoped run the engine may have excluded the features this table would have been
        // filled from, so its emptiness does not establish agreement any more than an unfinished
        // subtree's does - and the phrase of agreement may not be printed over it.
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null), 100, 1,
            ComparisonNodeRenderer.ContentCoverage.SCOPED_RUN);

        assertFalse("a scoped run may not claim the sides agree: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.NO_DIFFERENCES));
        assertTrue("and the qualification belongs in the sentence: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.CONTENT_MAY_BE_EXCLUDED));
    }

    @Test
    public void testAWholeConfigurationRunStillSaysNoDifferences()
    {
        // The positive control. Without it every assertion above would pass on a renderer that had
        // simply stopped printing the phrase.
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null));

        assertTrue("a compared, equal subtree says so: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.NO_DIFFERENCES));
        assertFalse("and carries no scoped-run notice: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.SCOPED_RUN_NOTICE));
    }

    // ==== the scoped-run text states the RUN, and nothing wider than the predicate ====
    //
    // MdCompareUtils.isExcludeObjectsContentFeature excludes a feature when the setting is on, the
    // feature is NOT a containment-many collection of MdObjects, and neither compared object's
    // qualified name is under a scope entry. Two things follow, and the document used to deny
    // both: the exclusion is per FEATURE, so an excluded object is not an object nothing was
    // looked at under, and the carve-out means child object nodes can still be built beneath it.
    //
    // Each removed claim gets its own @Test. JUnit abandons a method at its first failed
    // assertion, so a single method holding all of them would only ever hold the first one down.

    @Test
    public void testTheScopedRunNoticeDoesNotSayNothingBelowTheNodeWasLookedAt()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null), 100, 1,
            ComparisonNodeRenderer.ContentCoverage.SCOPED_RUN);

        assertFalse("the exclusion is per feature, so 'nothing about its content was looked at' " //$NON-NLS-1$
            + "is wider than the mechanism: " + text, //$NON-NLS-1$
            text.contains("nothing about its content")); //$NON-NLS-1$
    }

    @Test
    public void testTheScopedRunNoticeDoesNotClaimNoChildNodeWasBuilt()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null), 100, 1,
            ComparisonNodeRenderer.ContentCoverage.SCOPED_RUN);

        assertFalse("a containment-many collection of MdObjects is SPARED, so child object " //$NON-NLS-1$
            + "nodes can still be built under an excluded object: " + text, //$NON-NLS-1$
            text.contains("no child node")); //$NON-NLS-1$
    }

    // "never compared feature by feature" is NOT pinned here, and deliberately so: this document
    // never carried that phrase. It lived in ComparisonTreeReport's clause, in this class's own
    // javadoc and in the two guides, and it is pinned where it lived - measured, by reverting all
    // four texts at once and watching which tests reddened. A pin here would have stayed green
    // over the whole revert.

    @Test
    public void testTheScopedRunNoticeDoesNotReadAnEmptyTableAsNeverLookedAt()
    {
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null), 100, 1,
            ComparisonNodeRenderer.ContentCoverage.SCOPED_RUN);

        assertFalse("what follows from a scoped run is that an empty table is not AGREEMENT, " //$NON-NLS-1$
            + "not that it is a table nothing was looked at for: " + text, //$NON-NLS-1$
            text.contains("never looked at")); //$NON-NLS-1$
    }

    @Test
    public void testTheScopedStateQualifierDoesNotReduceTheFlagsToStructureAndPresence()
    {
        // The cell used to say the flags "speak for its structure and presence only". The spared
        // containment-many collections are compared, so a difference inside a member object still
        // reaches those flags - the old sentence told the caller to discount a real finding.
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null), 100, 1,
            ComparisonNodeRenderer.ContentCoverage.SCOPED_RUN);

        assertFalse("the flags are not reduced to structure and presence: " + text, //$NON-NLS-1$
            text.contains("structure and presence")); //$NON-NLS-1$
    }

    @Test
    public void testTheScopedRunNoticeStillStatesTheRunItself()
    {
        // Positive control on the FIRST of the two facts left standing. Without it every negative
        // pin above would pass on a renderer that had simply stopped printing the notice.
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null), 100, 1,
            ComparisonNodeRenderer.ContentCoverage.SCOPED_RUN);

        assertTrue("the run's own limit is the fact the document carries: " + text, //$NON-NLS-1$
            text.contains("this comparison ran with a `scope`")); //$NON-NLS-1$
    }

    @Test
    public void testTheScopedRunNoticeNamesTheCarveOutThatKeepsTheClaimNarrow()
    {
        // Positive control on the SECOND fact, and the one that makes the negatives non-vacuous:
        // the text says WHICH features can be excluded, naming the collections that are not.
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED, access(null), 100, 1,
            ComparisonNodeRenderer.ContentCoverage.SCOPED_RUN);

        assertTrue("the carve-out is what stops the claim from being an absolute: " + text, //$NON-NLS-1$
            text.contains("sparing its containment-many collections of metadata objects")); //$NON-NLS-1$
    }

    @Test
    public void testThePropertyTableIsNotCoveredByTheScopeCaveat()
    {
        // The exclusion is applied by the ENGINE to its own comparison; this table is built by
        // reading the matched objects here, so it still answers. Blanketing it would delete the
        // one section of the document that still carries a finding - and it is the section that
        // showed the difference an excluded node's flags had missed.
        EObject main = mdObject("Products", "a"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject other = mdObject("Products", "b"); //$NON-NLS-1$ //$NON-NLS-2$
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED,
            access(new ComparedObjects<EObject>(main, other, null)), 100, 1,
            ComparisonNodeRenderer.ContentCoverage.SCOPED_RUN);

        assertTrue("the properties were read and one of them differs: " + text, //$NON-NLS-1$
            text.contains("**Properties:** 2 (1 differing)")); //$NON-NLS-1$
    }

    // ============ no phrase of equality contradicts a number in the same document ============
    //
    // Measured live: three objects whose expanded document carried "**Properties:** N (1
    // differing)" AND a State of "identical", with the differing row printed in the table between
    // them. The State comes from the platform's flags and the count from this renderer's own
    // reading, so the two really can disagree - and the document has to say so instead of picking
    // one of its own halves.

    @Test
    public void testAnIdenticalStateIsQualifiedWhenThePropertyCountContradictsIt()
    {
        EObject main = mdObject("Products", "a"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject other = mdObject("Products", "b"); //$NON-NLS-1$ //$NON-NLS-2$
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED,
            access(new ComparedObjects<EObject>(main, other, null)));

        assertTrue("the count that contradicts it is printed here: " + text, //$NON-NLS-1$
            text.contains("**Properties:** 2 (1 differing)")); //$NON-NLS-1$
        assertTrue("so the State cell may not assert sameness on its own: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.STATE_DISPUTED_BY_PROPERTIES));
        assertTrue("and the platform's own verdict is still named: " + text, //$NON-NLS-1$
            text.contains("identical")); //$NON-NLS-1$
    }

    @Test
    public void testAnIdenticalStateStandsPlainWhenNothingContradictsIt()
    {
        // The control that keeps the pin above from passing on a renderer that qualifies every
        // State cell: with the two sides equal there is nothing to reconcile, and the cell must
        // read as the plain platform label.
        EObject main = mdObject("Products", "same"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject other = mdObject("Products", "same"); //$NON-NLS-1$ //$NON-NLS-2$
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.getComparisonFlags()).thenReturn(new ComparisonFlags());

        String text = render(node, ComparisonNodeStatus.FINISHED,
            access(new ComparedObjects<EObject>(main, other, null)));

        assertTrue("the fixture must really count no difference: " + text, //$NON-NLS-1$
            text.contains("**Properties:** 2 (0 differing)")); //$NON-NLS-1$
        assertFalse("nothing contradicts the verdict, so nothing is appended to it: " + text, //$NON-NLS-1$
            text.contains(ComparisonNodeRenderer.STATE_DISPUTED_BY_PROPERTIES));
    }

    /** One module section, named so a dropped or displaced row is identifiable. */
    private static BslModuleSectionComparisonNode section(String name)
    {
        BslModuleSectionComparisonNode section = mock(BslModuleSectionComparisonNode.class);
        when(section.getSectionType()).thenReturn(BslModuleSectionType.PROCEDURE);
        when(section.getName(ComparisonSide.MAIN)).thenReturn(name);
        return section;
    }

    /** A module carrying exactly these sections, in this order. */
    private static BslModuleComparisonNode moduleOf(BslModuleSectionComparisonNode... sections)
    {
        BslModuleComparisonNode module = mock(BslModuleComparisonNode.class);
        when(module.eClass()).thenReturn(MODEL.moduleNodeClass);
        EList<BslModuleSectionComparisonNode> list = new BasicEList<>();
        list.addAll(Arrays.asList(sections));
        when(module.getChildren()).thenReturn(list);
        return module;
    }

    /** A mocked top node with a real EClass, so the rendered "kind" is deterministic. */
    private static ComparisonNode topNode(String eClassName)
    {
        TopComparisonNode node = mock(TopComparisonNode.class);
        when(node.eClass()).thenReturn(MODEL.nodeClass(eClassName));
        return node;
    }

    /** A mocked child node carrying its own id, so per-node problems can be told apart. */
    private static ComparisonNode childNode(long id)
    {
        ComparisonNode node = mock(ComparisonNode.class);
        when(node.eClass()).thenReturn(MODEL.nodeClass("ChildMdObjectComparisonNode")); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(id));
        return node;
    }

    /** {@code count} distinct problems, named so a dropped row can be identified by index. */
    private static List<PotentialMergeProblemDescription> problems(int count)
    {
        List<PotentialMergeProblemDescription> list = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            list.add(new PotentialMergeProblemDescription("problem-" + i, //$NON-NLS-1$
                "problem-" + i + " details")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return list;
    }

    private static void withChildren(ComparisonNode parent, ComparisonNode... children)
    {
        EList<ComparisonNode> list = new BasicEList<>();
        list.addAll(Arrays.asList(children));
        when(parent.<ComparisonNode> getChildren()).thenReturn(list);
    }

    /**
     * A child list whose single element is {@code null} - the shape the platform's own list can
     * take, and the one the renderer sees now that it no longer copies that list.
     *
     * @param parent the node to give the list to
     */
    private static void withNullOnlyChild(ComparisonNode parent)
    {
        EList<ComparisonNode> list = new BasicEList<>();
        list.add(null);
        when(parent.<ComparisonNode> getChildren()).thenReturn(list);
    }

    /**
     * A problem the scan reaches and a problem one level below the requested depth, rendered at
     * {@code depth=1} with room to spare in the row budget - so the DEPTH limit, and only it, cut
     * the scan.
     *
     * @return the text of the potential-problem section
     */
    private static String renderProblemsCutByDepth()
    {
        ComparisonNode grandchild = childNode(12L);
        ComparisonNode child = childNode(11L);
        withChildren(child, grandchild);
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(10L));
        withChildren(node, child);
        StubAccess access = access(null);
        access.byNode.put(Long.valueOf(11L), Collections.singletonList(
            new PotentialMergeProblemDescription("visible", "visible details"))); //$NON-NLS-1$ //$NON-NLS-2$
        access.byNode.put(Long.valueOf(12L), Collections.singletonList(
            new PotentialMergeProblemDescription("hidden", "hidden details"))); //$NON-NLS-1$ //$NON-NLS-2$

        return sectionOf(render(node, ComparisonNodeStatus.FINISHED, access, 100, 1),
            "## Potential problems"); //$NON-NLS-1$
    }

    /**
     * A scan cut by BOTH bounds: three children for a limit of two decline the third, and the
     * first child holds a grandchild the requested depth of one turns the walk back at.
     *
     * @return the text of the potential-problem section
     */
    private static String renderProblemsCutByBothBounds()
    {
        ComparisonNode first = childNode(11L);
        withChildren(first, childNode(21L));
        ComparisonNode node = topNode("TopMdObjectComparisonNode"); //$NON-NLS-1$
        when(node.bmGetId()).thenReturn(Long.valueOf(10L));
        withChildren(node, first, childNode(12L), childNode(13L));
        StubAccess access = access(null);
        access.byNode.put(Long.valueOf(11L), Collections.singletonList(
            new PotentialMergeProblemDescription("visible", "visible details"))); //$NON-NLS-1$ //$NON-NLS-2$

        return sectionOf(render(node, ComparisonNodeStatus.FINISHED, access, 2, 1),
            "## Potential problems"); //$NON-NLS-1$
    }

    /** Renders a FORM node whose main side carries {@code form}, at {@code limit} rows per table. */
    private static String renderForm(EObject form, int limit)
    {
        FormComparisonNode node = mock(FormComparisonNode.class);
        when(node.eClass()).thenReturn(MODEL.formNodeClass);
        return render(node, ComparisonNodeStatus.FINISHED,
            access(new ComparedObjects<EObject>(form, null, null)), limit);
    }

    /** A form-like object carrying {@code count} attributes named {@code Attr0..Attr(n-1)}. */
    private static EObject formWithAttributes(int count)
    {
        EObject form = new DynamicEObjectImpl(MODEL.formClass);
        form.eSet(MODEL.formName, "ItemForm"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        List<EObject> attributes = (List<EObject>)form.eGet(MODEL.formAttributes);
        for (int i = 0; i < count; i++)
        {
            EObject attribute = new DynamicEObjectImpl(MODEL.mdClass);
            attribute.eSet(MODEL.mdName, "Attr" + i); //$NON-NLS-1$
            attributes.add(attribute);
        }
        return form;
    }

    /** A module node carrying {@code count} distinct sections, so a dropped one is identifiable. */
    private static BslModuleComparisonNode moduleWithSections(int count)
    {
        BslModuleComparisonNode module = mock(BslModuleComparisonNode.class);
        when(module.eClass()).thenReturn(MODEL.moduleNodeClass);
        EList<BslModuleSectionComparisonNode> sections = new BasicEList<>();
        for (int i = 0; i < count; i++)
        {
            BslModuleSectionComparisonNode section = mock(BslModuleSectionComparisonNode.class);
            when(section.getSectionType()).thenReturn(BslModuleSectionType.PROCEDURE);
            when(section.getName(ComparisonSide.MAIN)).thenReturn("Section" + i); //$NON-NLS-1$
            sections.add(section);
        }
        when(module.getChildren()).thenReturn(sections);
        return module;
    }

    /**
     * The text of ONE {@code ## } section of the document, so an assertion about one table cannot
     * be satisfied by a sentence printed under another heading.
     *
     * @param text the whole document
     * @param heading the section heading, including its {@code ##}
     * @return everything from that heading up to the next one
     */
    private static String sectionOf(String text, String heading)
    {
        int start = text.indexOf(heading);
        assertTrue("the document must carry " + heading + ":\n" + text, start >= 0); //$NON-NLS-1$ //$NON-NLS-2$
        int end = text.indexOf("\n## ", start + heading.length()); //$NON-NLS-1$
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    private static StubAccess access(IComparedObjects<EObject> objects)
    {
        StubAccess stub = new StubAccess();
        stub.objects = objects;
        return stub;
    }

    private static EObject mdObject(String name, String comment)
    {
        EObject object = new DynamicEObjectImpl(MODEL.mdClass);
        object.eSet(MODEL.mdName, name);
        object.eSet(MODEL.mdComment, comment);
        return object;
    }

    /**
     * An md-like object whose 'comment' cannot be read at all - the shape a dangling proxy takes
     * when the resolver behind it is not available.
     *
     * @param name the readable name
     * @return the object
     */
    private static EObject unreadableComment(String name)
    {
        EObject object = new DynamicEObjectImpl(MODEL.mdClass)
        {
            @Override
            public Object eGet(org.eclipse.emf.ecore.EStructuralFeature feature)
            {
                if (MODEL.mdComment.getName().equals(feature.getName()))
                {
                    throw new IllegalStateException("the value behind this feature cannot be resolved"); //$NON-NLS-1$
                }
                return super.eGet(feature);
            }
        };
        object.eSet(MODEL.mdName, name);
        return object;
    }

    /** The whole table row containing {@code needle}, or {@code null}. */
    private static String rowContaining(String text, String needle)
    {
        for (String line : text.split("\n")) //$NON-NLS-1$
        {
            if (line.startsWith("|") && line.contains(needle)) //$NON-NLS-1$
            {
                return line;
            }
        }
        return null;
    }

    /** Records what the renderer asked for and answers with the fixture. */
    private static final class StubAccess
        implements ComparisonNodeRenderer.NodeAccess
    {
        private IComparedObjects<EObject> objects;
        private List<PotentialMergeProblemDescription> problems = new ArrayList<>();
        private final Map<Long, List<PotentialMergeProblemDescription>> byNode = new HashMap<>();

        @Override
        public IComparedObjects<?> comparedObjects(ComparisonNode node)
        {
            return objects;
        }

        @Override
        public List<PotentialMergeProblemDescription> potentialProblems(long nodeId)
        {
            List<PotentialMergeProblemDescription> mapped = byNode.get(Long.valueOf(nodeId));
            return mapped == null ? problems : mapped;
        }
    }

    /** A tiny dynamic EMF model: an md-like object, a form-like object and named node EClasses. */
    private static final class ModelFixture
    {
        final EClass mdClass;
        final EAttribute mdName;
        final EAttribute mdComment;
        final EClass formClass;
        final EAttribute formName;
        final EReference formAttributes;
        final EClass formNodeClass;
        final EClass moduleNodeClass;
        private final EPackage pkg;

        ModelFixture()
        {
            EcoreFactory factory = EcoreFactory.eINSTANCE;
            pkg = factory.createEPackage();
            pkg.setName("comparelike"); //$NON-NLS-1$
            pkg.setNsPrefix("comparelike"); //$NON-NLS-1$
            pkg.setNsURI("http://ditrix.com/test/comparelike"); //$NON-NLS-1$

            mdClass = factory.createEClass();
            mdClass.setName("CatalogLike"); //$NON-NLS-1$
            mdName = stringAttribute(factory, "name"); //$NON-NLS-1$
            mdComment = stringAttribute(factory, "comment"); //$NON-NLS-1$
            mdClass.getEStructuralFeatures().add(mdName);
            mdClass.getEStructuralFeatures().add(mdComment);
            pkg.getEClassifiers().add(mdClass);

            formClass = factory.createEClass();
            formClass.setName("FormLike"); //$NON-NLS-1$
            formName = stringAttribute(factory, "name"); //$NON-NLS-1$
            formClass.getEStructuralFeatures().add(formName);
            // The feature the form reader looks for by NAME; the element type only has to carry a
            // 'name', which the md-like class already does.
            formAttributes = factory.createEReference();
            formAttributes.setName("attributes"); //$NON-NLS-1$
            formAttributes.setEType(mdClass);
            formAttributes.setContainment(true);
            formAttributes.setUpperBound(-1);
            formClass.getEStructuralFeatures().add(formAttributes);
            pkg.getEClassifiers().add(formClass);

            formNodeClass = nodeClass("FormComparisonNode"); //$NON-NLS-1$
            moduleNodeClass = nodeClass("BslModuleComparisonNode"); //$NON-NLS-1$
        }

        EClass nodeClass(String name)
        {
            for (Object classifier : pkg.getEClassifiers())
            {
                if (classifier instanceof EClass && name.equals(((EClass)classifier).getName()))
                {
                    return (EClass)classifier;
                }
            }
            EClass created = EcoreFactory.eINSTANCE.createEClass();
            created.setName(name);
            pkg.getEClassifiers().add(created);
            return created;
        }

        private static EAttribute stringAttribute(EcoreFactory factory, String name)
        {
            EAttribute attribute = factory.createEAttribute();
            attribute.setName(name);
            attribute.setEType(EcorePackage.Literals.ESTRING);
            return attribute;
        }
    }
}
