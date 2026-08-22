/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

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

        String text = render(node, ComparisonNodeStatus.FINISHED, access, 2);

        assertFalse("the third child was never visited, so its problem cannot be rendered: " + text, //$NON-NLS-1$
            text.contains("third-child")); //$NON-NLS-1$
        assertTrue("and the section must say the scan was partial, not assert an absence: " + text, //$NON-NLS-1$
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
        ComparisonNodeRenderer.Request request = new ComparisonNodeRenderer.Request("cmp-1", //$NON-NLS-1$
            "Catalog.Products", ComparisonSide.MAIN, status, 1, limit, null); //$NON-NLS-1$
        return ComparisonNodeRenderer.render(request, node, access);
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
