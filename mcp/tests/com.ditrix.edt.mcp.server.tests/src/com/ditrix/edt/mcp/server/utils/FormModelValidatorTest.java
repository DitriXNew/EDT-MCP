/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EEnum;
import org.eclipse.emf.ecore.EEnumLiteral;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;

/**
 * The structural checks themselves, driven against a dynamic-EMF form.
 *
 * <p>A synthetic model rather than a live one on purpose: the checks are about SHAPE, and a
 * hand-built form is the only way to pin what each one does NOT fire on. Every test therefore
 * asserts both edges - the defect is reported, and the clean form next to it is silent.</p>
 */
public class FormModelValidatorTest
{
    @Test
    public void testACleanFormHasNoFindings()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        form.field("Description", "Object", "Description"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("a clean form must report nothing: " + codes(form), //$NON-NLS-1$
            List.of(), codes(form));
    }

    @Test
    public void testTwoMainAttributesAreReportedAndOneIsNot()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        assertFalse(codes(form).contains("multiple-main-attributes")); //$NON-NLS-1$

        form.attribute("Record", 2, true); //$NON-NLS-1$
        assertTrue("a form carries one main attribute or none: " + codes(form), //$NON-NLS-1$
            codes(form).contains("multiple-main-attributes")); //$NON-NLS-1$
    }

    @Test
    public void testARootExtInfoWithoutAMainAttributeIsAWarningNotAnError()
    {
        Form form = new Form();
        form.attribute("Object", 1, false); //$NON-NLS-1$
        form.giveRootExtInfo();

        List<FormModelValidator.Finding> findings = FormModelValidator.validate(form.root);
        assertEquals(1, findings.size());
        assertEquals("orphan-form-ext-info", findings.get(0).code); //$NON-NLS-1$
        assertEquals("a node nobody backs does not make the form invalid", //$NON-NLS-1$
            FormModelValidator.SEVERITY_WARNING, findings.get(0).severity);
    }

    @Test
    public void testAFormRootExtInfoFollowsItsMainAttribute()
    {
        Form form = new Form();
        EObject main = form.attribute("Object", 1, true); //$NON-NLS-1$
        form.giveAttributeTypes(main, "CatalogObject.Goods"); //$NON-NLS-1$

        List<FormModelValidator.Finding> missing = findings(form,
            FormModelValidator.CODE_MISSING_EXT_INFO);
        assertEquals("the main attribute requires one root node: " + codes(form), //$NON-NLS-1$
            1, missing.size());
        assertEquals("the root finding uses the form address", "(form)", missing.get(0).path); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(missing.get(0).message.contains("CatalogFormExtInfo")); //$NON-NLS-1$
        assertTrue(missing.get(0).message.contains("CatalogObject")); //$NON-NLS-1$

        form.giveRootExtInfo(form.documentFormExtInfoType);
        List<FormModelValidator.Finding> stale = findings(form,
            FormModelValidator.CODE_STALE_EXT_INFO);
        assertEquals("a root node left from another main type is stale: " + codes(form), //$NON-NLS-1$
            1, stale.size());
        assertTrue(stale.get(0).message.contains("DocumentFormExtInfo")); //$NON-NLS-1$
        assertTrue(stale.get(0).message.contains("CatalogFormExtInfo")); //$NON-NLS-1$

        form.giveRootExtInfo(form.extInfoType);
        assertEquals("the correctly paired root is clean: " + codes(form), List.of(), codes(form)); //$NON-NLS-1$
    }

    @Test
    public void testAMainAttributeWithNoRootPairingIsSilent()
    {
        Form form = new Form();
        EObject main = form.attribute("Caption", 1, true); //$NON-NLS-1$
        form.giveAttributeTypes(main, "String"); //$NON-NLS-1$
        form.giveRootExtInfo();

        assertEquals("an unmapped category cannot prove its existing root node stale: " //$NON-NLS-1$
            + codes(form),
            List.of(), codes(form));
    }

    @Test
    public void testMissingAttributePresentationHoldersAreReportedSeparately()
    {
        Form form = new Form();
        EObject attribute = form.attribute("Rows", 1, false); //$NON-NLS-1$
        form.removePresentationHolder(attribute, "view"); //$NON-NLS-1$

        List<FormModelValidator.Finding> one = findings(form,
            FormModelValidator.CODE_MISSING_PRESENTATION_FLAG);
        assertEquals("one absent holder produces one finding: " + codes(form), 1, one.size()); //$NON-NLS-1$
        assertTrue("the message must name the holder to add: " + one.get(0).message, //$NON-NLS-1$
            one.get(0).message.contains("view")); //$NON-NLS-1$
        assertTrue(one.get(0).message.contains("attribute")); //$NON-NLS-1$

        form.removePresentationHolder(attribute, "edit"); //$NON-NLS-1$
        List<FormModelValidator.Finding> both = findings(form,
            FormModelValidator.CODE_MISSING_PRESENTATION_FLAG);
        assertEquals("each absent holder needs its own repair: " + codes(form), 2, both.size()); //$NON-NLS-1$
    }

    @Test
    public void testAnEmptyPresentationHolderIsPresent()
    {
        Form form = new Form();
        form.attribute("Rows", 1, false); //$NON-NLS-1$

        assertTrue("an empty AdjustableBoolean holder is still present", //$NON-NLS-1$
            findings(form, FormModelValidator.CODE_MISSING_PRESENTATION_FLAG).isEmpty());
    }

    @Test
    public void testAColumnMissingAPresentationHolderUsesTheColumnAddress()
    {
        Form form = new Form();
        EObject attribute = form.attribute("Rows", 1, false); //$NON-NLS-1$
        EObject column = form.column(attribute, "Price", 2); //$NON-NLS-1$
        form.removePresentationHolder(column, "edit"); //$NON-NLS-1$

        List<FormModelValidator.Finding> findings = findings(form,
            FormModelValidator.CODE_MISSING_PRESENTATION_FLAG);
        assertEquals(1, findings.size());
        assertEquals("the direct-column label must match the namespace walk", //$NON-NLS-1$
            "Attribute.Rows.Column.Price", findings.get(0).path); //$NON-NLS-1$
        assertTrue(findings.get(0).message.contains("edit")); //$NON-NLS-1$
        assertTrue(findings.get(0).message.contains("column")); //$NON-NLS-1$
    }

    @Test
    public void testDuplicateNamesAreJudgedPerNamespace()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        form.command("Print", 1); //$NON-NLS-1$
        form.group("Print"); //$NON-NLS-1$

        assertFalse("a command and an item may share a name - different namespaces: " + codes(form), //$NON-NLS-1$
            codes(form).contains("duplicate-name")); //$NON-NLS-1$

        form.group("Print"); //$NON-NLS-1$
        assertTrue("two items of one name is a duplicate: " + codes(form), //$NON-NLS-1$
            codes(form).contains("duplicate-name")); //$NON-NLS-1$
    }

    @Test
    public void testDuplicateIdsAreJudgedPerIdSpace()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        form.command("Print", 1); //$NON-NLS-1$

        assertFalse("an attribute and a command may share an id - different spaces: " + codes(form), //$NON-NLS-1$
            codes(form).contains("duplicate-id")); //$NON-NLS-1$

        form.attribute("Second", 1, false); //$NON-NLS-1$
        assertTrue("two attributes of one id collide: " + codes(form), //$NON-NLS-1$
            codes(form).contains("duplicate-id")); //$NON-NLS-1$
    }

    /**
     * A data path is rooted at an ATTRIBUTE and nothing else. Nothing binds to a parameter that way
     * (issue #396), and across a real configuration not one of the 3918 forms carrying parameters has
     * a path rooted at one.
     */
    @Test
    public void testADataPathMustStartAtAnAttribute()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        form.parameter("Key"); //$NON-NLS-1$
        form.field("ByAttribute", "Object", "Description"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse("an attribute root resolves: " + codes(form), //$NON-NLS-1$
            codes(form).contains("unresolved-data-path")); //$NON-NLS-1$

        form.field("ByParameter", "Key"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a parameter is not a data source: " + codes(form), //$NON-NLS-1$
            codes(form).contains("unresolved-data-path")); //$NON-NLS-1$
    }

    @Test
    public void testAFieldWithNoDataPathIsReported()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        form.field("Empty"); //$NON-NLS-1$

        assertTrue("a field that displays nothing is a defect: " + codes(form), //$NON-NLS-1$
            codes(form).contains("missing-data-path")); //$NON-NLS-1$
    }

    @Test
    public void testAButtonWithoutACommandIsReportedAndOneWithIsNot()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject command = form.command("Print", 1); //$NON-NLS-1$
        form.button("RunPrint", command); //$NON-NLS-1$
        assertFalse("a bound button is fine: " + codes(form), //$NON-NLS-1$
            codes(form).contains("missing-command-reference")); //$NON-NLS-1$

        form.button("Orphan", null); //$NON-NLS-1$
        assertTrue("a button that runs nothing is a defect: " + codes(form), //$NON-NLS-1$
            codes(form).contains("missing-command-reference")); //$NON-NLS-1$
    }

    @Test
    public void testAHandlerNeedsBothAProcedureAndAnEvent()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject event = form.event("OnCreateAtServer"); //$NON-NLS-1$
        form.handler(form.root, "OnCreateAtServer", event); //$NON-NLS-1$
        assertEquals("a complete binding reports nothing: " + codes(form), List.of(), codes(form)); //$NON-NLS-1$

        form.handler(form.root, "", event); //$NON-NLS-1$
        assertTrue("a binding with no procedure cannot be called: " + codes(form), //$NON-NLS-1$
            codes(form).contains("empty-handler-name")); //$NON-NLS-1$

        form.handler(form.root, "OnOpen", null); //$NON-NLS-1$
        assertTrue("a binding with no event is bound to nothing: " + codes(form), //$NON-NLS-1$
            codes(form).contains("unresolved-event-reference")); //$NON-NLS-1$
    }

    /**
     * The sentinel the platform WANTS. A naive "an id must be positive" check inverts the truth
     * here: a {@code 0} id serializes without an {@code <id>} element and EDT then flags the form.
     */
    @Test
    public void testTheAutoCommandBarSentinelIsNotAnInvalidId()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        form.giveAutoCommandBar(-1);
        assertFalse("-1 is the id the platform writes: " + codes(form), //$NON-NLS-1$
            codes(form).contains("invalid-auto-command-bar-id")); //$NON-NLS-1$

        form.giveAutoCommandBar(0);
        assertTrue("0 is the one that breaks the form: " + codes(form), //$NON-NLS-1$
            codes(form).contains("invalid-auto-command-bar-id")); //$NON-NLS-1$
    }

    /**
     * The one item kind that carries neither a name nor an id, and legitimately so: across a full
     * real configuration those 176 unnamed and 209 id-less items are ALL additions. Judged like any
     * other item, every table with a search string would report two defects.
     */
    @Test
    public void testAnAdditionIsExemptFromTheNameAndIdRules()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        form.addition();

        assertEquals("an addition carries neither and is still fine: " + codes(form), //$NON-NLS-1$
            List.of(), codes(form));
    }

    @Test
    public void testAnItemWithoutANameOrAnIdIsReported()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject nameless = form.group("Placeholder"); //$NON-NLS-1$
        nameless.eSet(nameless.eClass().getEStructuralFeature("name"), ""); //$NON-NLS-1$ //$NON-NLS-2$
        nameless.eSet(nameless.eClass().getEStructuralFeature("id"), Integer.valueOf(0)); //$NON-NLS-1$

        List<String> codes = codes(form);
        assertTrue("an unaddressable item is a defect: " + codes, //$NON-NLS-1$
            codes.contains("unnamed-member")); //$NON-NLS-1$
        assertTrue("and so is an id that serializes as nothing: " + codes, //$NON-NLS-1$
            codes.contains("missing-id")); //$NON-NLS-1$
    }

    @Test
    public void testCommandIdsAreJudgedToo()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        form.command("Print", 7); //$NON-NLS-1$
        assertFalse(codes(form).contains("duplicate-id")); //$NON-NLS-1$

        form.command("Send", 7); //$NON-NLS-1$
        assertTrue("commands are their own id space and it is judged: " + codes(form), //$NON-NLS-1$
            codes(form).contains("duplicate-id")); //$NON-NLS-1$
    }

    @Test
    public void testABlankFirstSegmentIsAnEmptyDataPath()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        form.field("Blank", "", "Description"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("a path that starts nowhere displays nothing: " + codes(form), //$NON-NLS-1$
            codes(form).contains("empty-data-path")); //$NON-NLS-1$
    }

    /**
     * A command removed from the form does NOT turn the button's reference into a proxy, so the
     * proxy test alone would call this form valid - and this is exactly the state the tools produce
     * today, verified live: delete_metadata removes the command and rewrites no button.
     */
    @Test
    public void testAButtonPointingAtARemovedCommandIsReported()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject command = form.command("Print", 1); //$NON-NLS-1$
        form.button("RunPrint", command); //$NON-NLS-1$
        assertFalse(codes(form).contains("unresolved-command-reference")); //$NON-NLS-1$

        form.removeCommand(command);
        assertTrue("the button now runs a command that is not in the form: " + codes(form), //$NON-NLS-1$
            codes(form).contains("unresolved-command-reference")); //$NON-NLS-1$
    }

    @Test
    public void testTwoBindingsOfOneEventAreReportedButABaseAndAnExtensionAreNot()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject event = form.event("OnCreateAtServer"); //$NON-NLS-1$
        form.handler(form.root, "OnCreateAtServer", event); //$NON-NLS-1$
        form.extensionHandler(form.root, "ext", event, "After"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a base handler and an extension coexist: " + codes(form), //$NON-NLS-1$
            codes(form).contains("duplicate-handler-binding")); //$NON-NLS-1$

        form.handler(form.root, "second", event); //$NON-NLS-1$
        assertTrue("two base handlers on one event leave the platform guessing: " + codes(form), //$NON-NLS-1$
            codes(form).contains("duplicate-handler-binding")); //$NON-NLS-1$
    }

    @Test
    public void testAnExtensionHandlerWithoutACallTypeIsReported()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject event = form.event("OnCreateAtServer"); //$NON-NLS-1$
        form.extensionHandler(form.root, "ext", event, null); //$NON-NLS-1$

        assertTrue("an extension that does not say HOW it intercepts is half-built: " + codes(form), //$NON-NLS-1$
            codes(form).contains("extension-handler-without-call-type")); //$NON-NLS-1$
    }

    @Test
    public void testACommandActionWithoutAProcedureIsReported()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject command = form.command("Print", 1); //$NON-NLS-1$
        form.giveCommandAction(command, "PrintCommand"); //$NON-NLS-1$
        assertFalse("a command that names its procedure is fine: " + codes(form), //$NON-NLS-1$
            codes(form).contains("empty-command-action")); //$NON-NLS-1$

        form.giveCommandAction(command, null);
        assertTrue("an action with no handler runs nothing: " + codes(form), //$NON-NLS-1$
            codes(form).contains("empty-command-action")); //$NON-NLS-1$
    }

    @Test
    public void testAButtonPointingAtAnotherFormsCommandIsReported()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        form.button("Foreign", form.foreignCommand("Print")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a form command of another form is not resolvable here: " + codes(form), //$NON-NLS-1$
            codes(form).contains("unresolved-command-reference")); //$NON-NLS-1$
    }

    /**
     * A finding's path is a promise: it is pasted back into another call. The item NAMESPACE is
     * called "item", but there is no such kind token - the address has to name the element's own
     * kind.
     */
    @Test
    public void testAnItemFindingIsAddressedByItsKind()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        form.group("Twice"); //$NON-NLS-1$
        form.group("Twice"); //$NON-NLS-1$

        List<FormModelValidator.Finding> findings = FormModelValidator.validate(form.root);
        List<String> paths = new ArrayList<>();
        for (FormModelValidator.Finding finding : findings)
        {
            paths.add(finding.path);
        }
        assertTrue("the path must name a kind a tool accepts: " + paths, //$NON-NLS-1$
            paths.contains("Group.Twice")); //$NON-NLS-1$
    }

    /**
     * A retype can leave an element whose new type pairs with NO ext-info while the old node stays.
     * Asking the resolver alone misses it: with no type-derived classifier it answers the existing
     * holder's own class, so expected equals actual and nothing is reported.
     */
    @Test
    public void testANodeLeftOnATypeThatPairsWithNoneIsReported()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject field = form.field("Stale", "Object", "Description"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(codes(form).contains("stale-ext-info")); //$NON-NLS-1$

        // This field names no type, which is the same answer the dispatch gives for a type that
        // pairs with nothing - and it carries a node all the same.
        form.giveItemExtInfo(field);
        assertTrue("a node on a type that pairs with none is stale: " + codes(form), //$NON-NLS-1$
            codes(form).contains("stale-ext-info")); //$NON-NLS-1$
    }

    /**
     * An {@code ExtendedTooltip} is a {@code Decoration}, and the platform pins its node to a
     * {@code LabelDecorationExtInfo}. One hangs off nearly every visual item, so a tooltip that lost
     * its node - or kept the wrong one - is a defect the walk has to see.
     */
    @Test
    public void testAnExtendedTooltipIsJudgedThroughTheDecorationItInherits()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject field = form.field("Price", "Object"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject tooltip = form.tooltipOn(field, "Label"); //$NON-NLS-1$

        assertTrue("a Label tooltip with no node at all is a defect: " + codes(form), //$NON-NLS-1$
            codes(form).contains("missing-ext-info")); //$NON-NLS-1$

        // Given a node of the wrong class it is stale, exactly as for a plain decoration.
        form.giveItemExtInfo(tooltip);
        assertTrue("a tooltip carrying the wrong node is stale: " + codes(form), //$NON-NLS-1$
            codes(form).contains("stale-ext-info")); //$NON-NLS-1$
    }

    /**
     * The BOUNDARY of the command check, pinned deliberately: a standard command is never reported,
     * wherever it is rooted. Containment cannot tell this form's inferred set from a peer's - an
     * adopted form keeps one rooted in its linked base form, an inferred one may be rooted nowhere -
     * so a rule built on it produced false findings on healthy forms twice. Silence here is the
     * claim, not an oversight; a future rule needs the form's own command SOURCES, not the tree.
     */
    @Test
    public void testAStandardCommandIsNeverReportedWhereverItIsRooted()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        form.button("RunOwn", form.ownStandardCommand("Post")); //$NON-NLS-1$ //$NON-NLS-2$
        form.button("RunForeign", form.standardCommandOfAnotherForm("Post")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("no standard command may be called unresolvable: " + codes(form), //$NON-NLS-1$
            codes(form).contains("unresolved-command-reference")); //$NON-NLS-1$
    }

    /**
     * The binding is stored as a NAME path and re-resolved on load, so a surviving command of the
     * same name keeps it valid even when the reference went stale. Judged by object identity this
     * form would be called broken although it loads and resolves perfectly.
     */
    @Test
    public void testASurvivingCommandOfTheSameNameKeepsTheBindingValid()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject first = form.command("Print", 1); //$NON-NLS-1$
        form.command("Print", 2); //$NON-NLS-1$
        form.button("RunPrint", first); //$NON-NLS-1$

        form.removeCommand(first);

        assertFalse("a command of that NAME is still in the form: " + codes(form), //$NON-NLS-1$
            codes(form).contains("unresolved-command-reference")); //$NON-NLS-1$
    }

    /**
     * The platform rejects an extended tooltip whose TYPE is not Label, and it rejects it for the
     * type alone - so carrying the correct label node does not make such a form valid.
     */
    @Test
    public void testAnExtendedTooltipTypedOtherThanLabelIsReported()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject field = form.field("Price", "Object"); //$NON-NLS-1$ //$NON-NLS-2$
        form.tooltipOn(field, "Label"); //$NON-NLS-1$
        assertFalse("a Label tooltip is what every real one is: " + codes(form), //$NON-NLS-1$
            codes(form).contains("invalid-extended-tooltip-type")); //$NON-NLS-1$

        EObject other = form.field("Cost", "Object"); //$NON-NLS-1$ //$NON-NLS-2$
        form.tooltipOn(other, "Picture"); //$NON-NLS-1$
        assertTrue("the platform rejects a tooltip typed anything but Label: " + codes(form), //$NON-NLS-1$
            codes(form).contains("invalid-extended-tooltip-type")); //$NON-NLS-1$
    }

    /**
     * An extension command's action names its procedures in the plural {@code handlers} list, and
     * the singular slot stays empty. Reading only the singular one calls a fully populated
     * extension action empty - a false finding on a healthy form.
     */
    @Test
    public void testAnExtensionActionNamesItsProcedureInTheHandlersList()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject command = form.command("Print", 1); //$NON-NLS-1$
        form.giveCommandActionHandlers(command, "PrintCommand"); //$NON-NLS-1$
        assertFalse("the action names a procedure, in the shape an extension uses: " + codes(form), //$NON-NLS-1$
            codes(form).contains("empty-command-action")); //$NON-NLS-1$

        // An action with an EMPTY list still runs nothing.
        form.giveCommandActionHandlers(command);
        assertTrue("an action naming no procedure at all runs nothing: " + codes(form), //$NON-NLS-1$
            codes(form).contains("empty-command-action")); //$NON-NLS-1$
    }

    /**
     * A form EVENT accepts any call type except the method-only one, so a non-empty value is not the
     * same as a valid one - the writer refuses exactly this value with the same reasoning.
     */
    @Test
    public void testAnExtensionHandlerWithTheMethodOnlyCallTypeIsReported()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject event = form.event("OnCreateAtServer"); //$NON-NLS-1$
        form.extensionHandler(form.root, "before", event, "Before"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Before is an ordinary event call type: " + codes(form), //$NON-NLS-1$
            codes(form).contains("invalid-extension-call-type")); //$NON-NLS-1$

        EObject other = form.event("OnOpen"); //$NON-NLS-1$
        form.extensionHandler(form.root, "method", other, "ChangeAndValidate"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("ChangeAndValidate intercepts a METHOD, not an event: " + codes(form), //$NON-NLS-1$
            codes(form).contains("invalid-extension-call-type")); //$NON-NLS-1$
    }

    /**
     * Each entry of an extension action names its own procedure - {@code CommandHandlerExtension}
     * declares {@code name} as {@code String[1]} - so a blank entry runs nothing however well its
     * neighbour is filled in. Stopping at the first named entry hid exactly that.
     */
    @Test
    public void testABlankHandlerEntryIsReportedBesideANamedOne()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject command = form.command("Print", 1); //$NON-NLS-1$
        form.giveCommandActionHandlers(command, "PrintCommand", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a blank entry beside a named one still runs nothing: " + codes(form), //$NON-NLS-1$
            codes(form).contains("empty-handler-name")); //$NON-NLS-1$
        assertFalse("the action is NOT empty - it does name a procedure: " + codes(form), //$NON-NLS-1$
            codes(form).contains("empty-command-action")); //$NON-NLS-1$

        // The other edge: an action whose entries all name a procedure says nothing at all.
        form.giveCommandActionHandlers(command, "PrintCommand", "PrintCommandEnd"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("every entry names a procedure: " + codes(form), //$NON-NLS-1$
            codes(form).contains("empty-handler-name")); //$NON-NLS-1$

        // ...and an action whose entries are ALL blank is reported ONCE, as a whole.
        form.giveCommandActionHandlers(command, ""); //$NON-NLS-1$
        assertTrue("an action naming nothing at all is the whole-action verdict: " + codes(form), //$NON-NLS-1$
            codes(form).contains("empty-command-action")); //$NON-NLS-1$
        assertFalse("and it is not ALSO reported per entry: " + codes(form), //$NON-NLS-1$
            codes(form).contains("empty-handler-name")); //$NON-NLS-1$
    }

    /**
     * A data path roots at an ATTRIBUTE. A form PARAMETER is not a data-path root - nothing binds
     * to one that way - and the refusal has to say so, because the guide used to promise otherwise
     * and a caller following it would build a binding this very tool rejects.
     */
    @Test
    public void testTheDataPathRefusalNamesTheAttributeRule()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        form.field("Price", "Parameters"); //$NON-NLS-1$ //$NON-NLS-2$
        String message = messageFor(form, "unresolved-data-path"); //$NON-NLS-1$
        assertTrue("the refusal must state the rule it applied: " + message, //$NON-NLS-1$
            message.contains("not a form attribute")); //$NON-NLS-1$
        assertFalse("and must not promise a parameter root the validator rejects: " + message, //$NON-NLS-1$
            message.contains("nor a form parameter")); //$NON-NLS-1$
    }

    @Test
    public void testAnAttributesValueTypeDecidesWhetherExtInfoIsRequired()
    {
        Form form = new Form();
        EObject dynamicList = form.attribute("Rows", 1, false); //$NON-NLS-1$
        form.giveAttributeTypes(dynamicList, "DynamicList"); //$NON-NLS-1$
        List<String> missingCodes = codes(form);
        assertEquals("a DynamicList attribute without its paired node must be reported: " //$NON-NLS-1$
            + missingCodes, List.of(FormModelValidator.CODE_MISSING_EXT_INFO), missingCodes);

        form.giveDynamicListExtInfo(dynamicList);
        List<String> completeCodes = codes(form);
        assertEquals("a DynamicListExtInfo is the expected node: " + completeCodes, //$NON-NLS-1$
            List.of(), completeCodes);

        EObject primitive = form.attribute("Caption", 2, false); //$NON-NLS-1$
        form.giveAttributeTypes(primitive, "String"); //$NON-NLS-1$
        List<String> primitiveCodes = codes(form);
        assertEquals("a primitive attribute takes no ext-info and must stay silent: " //$NON-NLS-1$
            + primitiveCodes, List.of(), primitiveCodes);

        EObject multi = form.attribute("Choice", 3, false); //$NON-NLS-1$
        form.giveAttributeTypes(multi, "DynamicList", "String"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> multiCodes = codes(form);
        assertEquals("a MULTI-typed attribute is unknowable and must stay silent: " //$NON-NLS-1$
            + multiCodes, List.of(), multiCodes);
    }

    @Test
    public void testAHandlerEventMustBePublishedByItsOwnerWhenPublicationIsKnown()
    {
        Form form = new Form();
        form.attribute("Object", 1, true); //$NON-NLS-1$
        EObject event = form.event("OnOpen"); //$NON-NLS-1$
        form.handler(form.root, "Open", event); //$NON-NLS-1$

        FormModelValidator.EventPublication published =
            container -> container == form.root ? List.of("onopen") : List.of(); //$NON-NLS-1$
        List<String> publishedCodes = codes(form, published);
        assertEquals("event-name publication is matched case-insensitively: " //$NON-NLS-1$
            + publishedCodes, List.of(), publishedCodes);

        FormModelValidator.EventPublication foreign =
            container -> container == form.root ? List.of("OnCreateAtServer") : List.of(); //$NON-NLS-1$
        List<String> foreignCodes = codes(form, foreign);
        assertEquals("an event published by another type must be reported: " //$NON-NLS-1$
            + foreignCodes, List.of(FormModelValidator.CODE_FOREIGN_EVENT_REFERENCE), foreignCodes);

        List<String> unknownCodes = codes(form, container -> List.of());
        assertEquals("an empty publication means cannot tell and must produce no finding: " //$NON-NLS-1$
            + unknownCodes, List.of(), unknownCodes);
    }

    /**
     * Retyping an attribute away from a paired type leaves its old node behind, and the writer
     * CLEARS one there - so a node the value type forbids is a defect, not an absence of opinion.
     * The unreadable case stays silent: a type that is not single says nothing either way.
     */
    @Test
    public void testAnExtInfoTheValueTypeForbidsIsReported()
    {
        Form form = new Form();
        EObject retyped = form.attribute("Rows", 1, false); //$NON-NLS-1$
        form.giveAttributeTypes(retyped, "String"); //$NON-NLS-1$
        form.giveDynamicListExtInfo(retyped);
        assertTrue("a String attribute may carry no ext-info at all: " + codes(form), //$NON-NLS-1$
            codes(form).contains(FormModelValidator.CODE_STALE_EXT_INFO));

        // A type that is not SINGLE cannot be read, and there the same node proves nothing.
        form.giveAttributeTypes(retyped, "DynamicList", "String"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a MULTI-typed attribute is unknowable and must stay silent: " + codes(form), //$NON-NLS-1$
            codes(form).contains(FormModelValidator.CODE_STALE_EXT_INFO));
    }

    // --- the synthetic form --------------------------------------------------------------------

    /** The message of the FIRST finding carrying {@code code}, or empty when there is none. */
    private static String messageFor(Form form, String code)
    {
        for (FormModelValidator.Finding finding : FormModelValidator.validate(form.root))
        {
            if (code.equals(finding.code))
            {
                return finding.message;
            }
        }
        return ""; //$NON-NLS-1$
    }

    private static List<FormModelValidator.Finding> findings(Form form, String code)
    {
        List<FormModelValidator.Finding> matches = new ArrayList<>();
        for (FormModelValidator.Finding finding : FormModelValidator.validate(form.root))
        {
            if (code.equals(finding.code))
            {
                matches.add(finding);
            }
        }
        return matches;
    }

    private static List<String> codes(Form form)
    {
        List<String> codes = new ArrayList<>();
        for (FormModelValidator.Finding finding : FormModelValidator.validate(form.root))
        {
            codes.add(finding.code);
        }
        return codes;
    }

    private static List<String> codes(Form form, FormModelValidator.EventPublication publication)
    {
        List<String> codes = new ArrayList<>();
        for (FormModelValidator.Finding finding :
            FormModelValidator.validate(form.root, publication))
        {
            codes.add(finding.code);
        }
        return codes;
    }

    /**
     * A dynamic-EMF form shaped like the platform's: the EClass NAMES are what
     * {@code FormElementWriter.addressableKind} keys on, so they are the platform's own.
     */
    private static final class Form
    {
        final EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
        final EClass formType;
        final EClass attributeType;
        final EClass columnType;
        final EClass presentationFlagType;
        final EClass commandType;
        final EClass parameterType;
        final EClass groupType;
        final EClass fieldType;
        final EClass buttonType;
        final EClass barType;
        final EClass additionType;
        final EClass extensionHandlerType;
        final EClass commandActionType;
        final EClass decorationType;
        final EClass standardCommandType;
        final EClass tooltipType;
        final EClass dataPathType;
        final EClass eventType;
        final EClass handlerType;
        final EClass extInfoType;
        final EClass documentFormExtInfoType;
        final EClass dynamicListExtInfoType;
        final EObject root;
        /** Items are addressed by id, so the fixture allocates one per item as the platform does. */
        private int itemId = 100;

        Form()
        {
            pkg.setName("form"); //$NON-NLS-1$
            pkg.setNsURI("http://g5.1c.ru/v8/dt/form/validatortest"); //$NON-NLS-1$
            pkg.setNsPrefix("form"); //$NON-NLS-1$

            dataPathType = eClass("DataPath"); //$NON-NLS-1$
            dataPathType.getEStructuralFeatures()
                .add(attribute("segments", EcorePackage.Literals.ESTRING, true)); //$NON-NLS-1$

            eventType = eClass("Event"); //$NON-NLS-1$
            eventType.getEStructuralFeatures().add(attribute("name", EcorePackage.Literals.ESTRING, false)); //$NON-NLS-1$

            handlerType = eClass("EventHandler"); //$NON-NLS-1$
            handlerType.getEStructuralFeatures().add(attribute("name", EcorePackage.Literals.ESTRING, false)); //$NON-NLS-1$
            handlerType.getEStructuralFeatures().add(reference("event", eventType, false, false)); //$NON-NLS-1$

            extInfoType = eClass("CatalogFormExtInfo"); //$NON-NLS-1$
            documentFormExtInfoType = eClass("DocumentFormExtInfo"); //$NON-NLS-1$
            dynamicListExtInfoType = eClass("DynamicListExtInfo"); //$NON-NLS-1$
            presentationFlagType = eClass("AdjustableBoolean"); //$NON-NLS-1$

            EClass commandBase = eClass("Command"); //$NON-NLS-1$
            commandBase.setAbstract(true);
            commandType = eClass("FormCommand"); //$NON-NLS-1$
            commandType.getESuperTypes().add(commandBase);
            named(commandType);
            // FormStandardCommand extends Command, NOT FormCommand (Form.xcore).
            standardCommandType = eClass("FormStandardCommand"); //$NON-NLS-1$
            standardCommandType.getESuperTypes().add(commandBase);
            named(standardCommandType);

            parameterType = eClass("FormParameter"); //$NON-NLS-1$
            parameterType.getEStructuralFeatures()
                .add(attribute("name", EcorePackage.Literals.ESTRING, false)); //$NON-NLS-1$

            attributeType = eClass("FormAttribute"); //$NON-NLS-1$
            named(attributeType);
            attributeType.getEStructuralFeatures()
                .add(attribute("main", EcorePackage.Literals.EBOOLEAN, false)); //$NON-NLS-1$
            attributeType.getEStructuralFeatures()
                .add(reference("valueType", EcorePackage.Literals.EOBJECT, false, true)); //$NON-NLS-1$
            attributeType.getEStructuralFeatures()
                .add(reference("extInfo", dynamicListExtInfoType, false, true)); //$NON-NLS-1$
            attributeType.getEStructuralFeatures()
                .add(reference("view", presentationFlagType, false, true)); //$NON-NLS-1$
            attributeType.getEStructuralFeatures()
                .add(reference("edit", presentationFlagType, false, true)); //$NON-NLS-1$

            columnType = eClass("FormAttributeColumn"); //$NON-NLS-1$
            named(columnType);
            columnType.getEStructuralFeatures()
                .add(reference("view", presentationFlagType, false, true)); //$NON-NLS-1$
            columnType.getEStructuralFeatures()
                .add(reference("edit", presentationFlagType, false, true)); //$NON-NLS-1$
            attributeType.getEStructuralFeatures()
                .add(reference("columns", columnType, true, true)); //$NON-NLS-1$
            EClass additionalColumnsType = eClass("FormAttributeAdditionalColumns"); //$NON-NLS-1$
            additionalColumnsType.getEStructuralFeatures()
                .add(reference("columns", columnType, true, true)); //$NON-NLS-1$
            attributeType.getEStructuralFeatures()
                .add(reference("additionalColumns", additionalColumnsType, true, true)); //$NON-NLS-1$

            EClass itemBase = eClass("FormItem"); //$NON-NLS-1$
            itemBase.setAbstract(true);
            named(itemBase);
            itemBase.getEStructuralFeatures().add(reference("handlers", handlerType, true, true)); //$NON-NLS-1$
            itemBase.getEStructuralFeatures().add(reference("extInfo", extInfoType, false, true)); //$NON-NLS-1$

            groupType = eClass("FormGroup"); //$NON-NLS-1$
            groupType.getESuperTypes().add(itemBase);
            groupType.getEStructuralFeatures().add(reference("items", itemBase, true, true)); //$NON-NLS-1$

            fieldType = eClass("FormField"); //$NON-NLS-1$
            fieldType.getESuperTypes().add(itemBase);
            fieldType.getEStructuralFeatures().add(reference("dataPath", dataPathType, false, true)); //$NON-NLS-1$

            buttonType = eClass("Button"); //$NON-NLS-1$
            buttonType.getESuperTypes().add(itemBase);
            buttonType.getEStructuralFeatures()
                .add(reference("commandName", (EClass)pkg.getEClassifier("Command"), false, false)); //$NON-NLS-1$ //$NON-NLS-2$

            additionType = eClass("Addition"); //$NON-NLS-1$
            additionType.getESuperTypes().add(itemBase);

            extensionHandlerType = eClass("EventHandlerExtension"); //$NON-NLS-1$
            extensionHandlerType.getESuperTypes().add(handlerType);
            extensionHandlerType.getEStructuralFeatures()
                .add(attribute("callType", EcorePackage.Literals.ESTRING, false)); //$NON-NLS-1$

            commandActionType = eClass("FormCommandHandlerContainer"); //$NON-NLS-1$
            commandActionType.getEStructuralFeatures()
                .add(reference("handler", handlerType, false, true)); //$NON-NLS-1$
            // An EXTENSION command names its procedures in a list instead of the singular slot.
            commandActionType.getEStructuralFeatures()
                .add(reference("handlers", handlerType, true, true)); //$NON-NLS-1$
            commandType.getEStructuralFeatures()
                .add(reference("action", commandActionType, false, true)); //$NON-NLS-1$

            barType = eClass("AutoCommandBar"); //$NON-NLS-1$
            barType.getESuperTypes().add(itemBase);

            // Picture is the FIRST literal of ManagedFormDecorationType, so an unset type means
            // Picture - the order matters, and the shipped metamodel is the one reproduced here.
            EEnum decorationKind = eEnum("ManagedFormDecorationType", "Picture", "Label"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            decorationType = eClass("Decoration"); //$NON-NLS-1$
            decorationType.getESuperTypes().add(itemBase);
            decorationType.getEStructuralFeatures().add(attribute("type", decorationKind, false)); //$NON-NLS-1$
            // An ExtendedTooltip adds nothing of its own - it IS a Decoration.
            tooltipType = eClass("ExtendedTooltip"); //$NON-NLS-1$
            tooltipType.getESuperTypes().add(decorationType);
            itemBase.getEStructuralFeatures()
                .add(reference("extendedTooltip", tooltipType, false, true)); //$NON-NLS-1$

            formType = eClass("Form"); //$NON-NLS-1$
            formType.getEStructuralFeatures().add(reference("items", itemBase, true, true)); //$NON-NLS-1$
            formType.getEStructuralFeatures().add(reference("attributes", attributeType, true, true)); //$NON-NLS-1$
            formType.getEStructuralFeatures().add(reference("formCommands", commandType, true, true)); //$NON-NLS-1$
            formType.getEStructuralFeatures()
                .add(reference("standardCommands", standardCommandType, true, true)); //$NON-NLS-1$
            formType.getEStructuralFeatures().add(reference("parameters", parameterType, true, true)); //$NON-NLS-1$
            formType.getEStructuralFeatures().add(reference("handlers", handlerType, true, true)); //$NON-NLS-1$
            formType.getEStructuralFeatures().add(reference("autoCommandBar", barType, false, true)); //$NON-NLS-1$
            formType.getEStructuralFeatures()
                .add(reference("extInfo", EcorePackage.Literals.EOBJECT, false, true)); //$NON-NLS-1$

            root = create(formType);
            // Every form EDT writes has one - all 9851 forms of a real configuration included - so a
            // synthetic form without it would make every test read as "missing auto command bar".
            giveAutoCommandBar(-1);
        }

        EObject attribute(String name, int id, boolean main)
        {
            EObject attribute = create(attributeType);
            set(attribute, "name", name); //$NON-NLS-1$
            set(attribute, "id", Integer.valueOf(id)); //$NON-NLS-1$
            set(attribute, "main", Boolean.valueOf(main)); //$NON-NLS-1$
            set(attribute, "view", create(presentationFlagType)); //$NON-NLS-1$
            set(attribute, "edit", create(presentationFlagType)); //$NON-NLS-1$
            add(root, "attributes", attribute); //$NON-NLS-1$
            return attribute;
        }

        EObject column(EObject attribute, String name, int id)
        {
            EObject column = create(columnType);
            set(column, "name", name); //$NON-NLS-1$
            set(column, "id", Integer.valueOf(id)); //$NON-NLS-1$
            set(column, "view", create(presentationFlagType)); //$NON-NLS-1$
            set(column, "edit", create(presentationFlagType)); //$NON-NLS-1$
            add(attribute, "columns", column); //$NON-NLS-1$
            return column;
        }

        void removePresentationHolder(EObject member, String featureName)
        {
            set(member, featureName, null);
        }

        void giveAttributeTypes(EObject attribute, String... typeNames)
        {
            TypeDescription valueType = McoreFactory.eINSTANCE.createTypeDescription();
            for (String typeName : typeNames)
            {
                Type type = McoreFactory.eINSTANCE.createType();
                type.setName(typeName);
                valueType.getTypes().add(type);
            }
            set(attribute, "valueType", valueType); //$NON-NLS-1$
        }

        void giveDynamicListExtInfo(EObject attribute)
        {
            set(attribute, "extInfo", create(dynamicListExtInfoType)); //$NON-NLS-1$
        }

        /** The extended tooltip nearly every visual item carries, in its own inherited slot. */
        EObject tooltipOn(EObject item, String type)
        {
            EObject tooltip = create(tooltipType);
            set(tooltip, "name", "Tip"); //$NON-NLS-1$ //$NON-NLS-2$
            set(tooltip, "id", Integer.valueOf(++itemId)); //$NON-NLS-1$
            EAttribute kind = (EAttribute)tooltipType.getEStructuralFeature("type"); //$NON-NLS-1$
            set(tooltip, "type", ((EEnum)kind.getEAttributeType()).getEEnumLiteralByLiteral(type)); //$NON-NLS-1$
            set(item, "extendedTooltip", tooltip); //$NON-NLS-1$
            return tooltip;
        }

        /** A standard command this form publishes - the resolvable kind. */
        EObject ownStandardCommand(String name)
        {
            EObject command = create(standardCommandType);
            set(command, "name", name); //$NON-NLS-1$
            add(root, "standardCommands", command); //$NON-NLS-1$
            return command;
        }

        /** The same kind of command, but published by a DIFFERENT form root. */
        EObject standardCommandOfAnotherForm(String name)
        {
            EObject otherForm = create(formType);
            EObject command = create(standardCommandType);
            set(command, "name", name); //$NON-NLS-1$
            add(otherForm, "standardCommands", command); //$NON-NLS-1$
            return command;
        }

        EObject command(String name, int id)
        {
            EObject command = create(commandType);
            set(command, "name", name); //$NON-NLS-1$
            set(command, "id", Integer.valueOf(id)); //$NON-NLS-1$
            add(root, "formCommands", command); //$NON-NLS-1$
            return command;
        }

        EObject parameter(String name)
        {
            EObject parameter = create(parameterType);
            set(parameter, "name", name); //$NON-NLS-1$
            add(root, "parameters", parameter); //$NON-NLS-1$
            return parameter;
        }

        EObject group(String name)
        {
            EObject group = create(groupType);
            set(group, "name", name); //$NON-NLS-1$
            set(group, "id", Integer.valueOf(++itemId)); //$NON-NLS-1$
            add(root, "items", group); //$NON-NLS-1$
            return group;
        }

        EObject field(String name, String... segments)
        {
            EObject field = create(fieldType);
            set(field, "name", name); //$NON-NLS-1$
            set(field, "id", Integer.valueOf(++itemId)); //$NON-NLS-1$
            if (segments.length > 0)
            {
                EObject dataPath = create(dataPathType);
                for (String segment : segments)
                {
                    add(dataPath, "segments", segment); //$NON-NLS-1$
                }
                set(field, "dataPath", dataPath); //$NON-NLS-1$
            }
            add(root, "items", field); //$NON-NLS-1$
            return field;
        }

        EObject button(String name, EObject command)
        {
            EObject button = create(buttonType);
            set(button, "name", name); //$NON-NLS-1$
            set(button, "id", Integer.valueOf(++itemId)); //$NON-NLS-1$
            if (command != null)
            {
                set(button, "commandName", command); //$NON-NLS-1$
            }
            add(root, "items", button); //$NON-NLS-1$
            return button;
        }

        /** A table addition - the one item kind that legitimately carries neither name nor id. */
        EObject addition()
        {
            EObject addition = create(additionType);
            add(root, "items", addition); //$NON-NLS-1$
            return addition;
        }

        void extensionHandler(EObject container, String procedure, EObject event, String callType)
        {
            EObject handler = create(extensionHandlerType);
            set(handler, "name", procedure); //$NON-NLS-1$
            set(handler, "event", event); //$NON-NLS-1$
            if (callType != null)
            {
                set(handler, "callType", callType); //$NON-NLS-1$
            }
            add(container, "handlers", handler); //$NON-NLS-1$
        }

        void giveCommandAction(EObject command, String procedure)
        {
            EObject action = create(commandActionType);
            if (procedure != null)
            {
                EObject handler = create(handlerType);
                set(handler, "name", procedure); //$NON-NLS-1$
                set(action, "handler", handler); //$NON-NLS-1$
            }
            set(command, "action", action); //$NON-NLS-1$
        }

        /** The extension shape of an action: procedures in the plural list, singular slot empty. */
        void giveCommandActionHandlers(EObject command, String... procedures)
        {
            EObject action = create(commandActionType);
            for (String procedure : procedures)
            {
                EObject handler = create(handlerType);
                set(handler, "name", procedure); //$NON-NLS-1$
                add(action, "handlers", handler); //$NON-NLS-1$
            }
            set(command, "action", action); //$NON-NLS-1$
        }

        @SuppressWarnings("unchecked")
        void removeCommand(EObject command)
        {
            ((List<EObject>)root.eGet(root.eClass().getEStructuralFeature("formCommands"))) //$NON-NLS-1$
                .remove(command);
        }

        /** A command contained in a DIFFERENT form - the button resolver never accepts one. */
        EObject foreignCommand(String name)
        {
            EObject otherForm = create(formType);
            EObject command = create(commandType);
            set(command, "name", name); //$NON-NLS-1$
            set(command, "id", Integer.valueOf(1)); //$NON-NLS-1$
            add(otherForm, "formCommands", command); //$NON-NLS-1$
            return command;
        }

        EObject event(String name)
        {
            EObject event = create(eventType);
            set(event, "name", name); //$NON-NLS-1$
            return event;
        }

        void handler(EObject container, String procedure, EObject event)
        {
            EObject handler = create(handlerType);
            set(handler, "name", procedure); //$NON-NLS-1$
            if (event != null)
            {
                set(handler, "event", event); //$NON-NLS-1$
            }
            add(container, "handlers", handler); //$NON-NLS-1$
        }

        void giveItemExtInfo(EObject item)
        {
            set(item, "extInfo", create(extInfoType)); //$NON-NLS-1$
        }

        void giveRootExtInfo()
        {
            giveRootExtInfo(extInfoType);
        }

        void giveRootExtInfo(EClass classifier)
        {
            set(root, "extInfo", create(classifier)); //$NON-NLS-1$
        }

        void giveAutoCommandBar(int id)
        {
            EObject bar = create(barType);
            set(bar, "name", "FormCommandBar"); //$NON-NLS-1$ //$NON-NLS-2$
            set(bar, "id", Integer.valueOf(id)); //$NON-NLS-1$
            set(root, "autoCommandBar", bar); //$NON-NLS-1$
        }

        // --- EMF plumbing ---

        private EEnum eEnum(String name, String... literals)
        {
            EEnum eEnum = EcoreFactory.eINSTANCE.createEEnum();
            eEnum.setName(name);
            int value = 0;
            for (String literal : literals)
            {
                EEnumLiteral eLiteral = EcoreFactory.eINSTANCE.createEEnumLiteral();
                eLiteral.setName(literal);
                eLiteral.setLiteral(literal);
                eLiteral.setValue(value++);
                eEnum.getELiterals().add(eLiteral);
            }
            pkg.getEClassifiers().add(eEnum);
            return eEnum;
        }

        private EClass eClass(String name)
        {
            EClass eClass = EcoreFactory.eINSTANCE.createEClass();
            eClass.setName(name);
            pkg.getEClassifiers().add(eClass);
            return eClass;
        }

        private static void named(EClass eClass)
        {
            eClass.getEStructuralFeatures().add(attribute("name", EcorePackage.Literals.ESTRING, false)); //$NON-NLS-1$
            eClass.getEStructuralFeatures().add(attribute("id", EcorePackage.Literals.EINT, false)); //$NON-NLS-1$
        }

        private static EAttribute attribute(String name, org.eclipse.emf.ecore.EClassifier type,
            boolean many)
        {
            EAttribute attribute = EcoreFactory.eINSTANCE.createEAttribute();
            attribute.setName(name);
            attribute.setEType(type);
            attribute.setUpperBound(many ? -1 : 1);
            return attribute;
        }

        private static EReference reference(String name, EClass type, boolean many,
            boolean containment)
        {
            EReference reference = EcoreFactory.eINSTANCE.createEReference();
            reference.setName(name);
            reference.setEType(type);
            reference.setUpperBound(many ? -1 : 1);
            reference.setContainment(containment);
            return reference;
        }

        private EObject create(EClass type)
        {
            return pkg.getEFactoryInstance().create(type);
        }

        private static void set(EObject object, String featureName, Object value)
        {
            object.eSet(object.eClass().getEStructuralFeature(featureName), value);
        }

        @SuppressWarnings("unchecked")
        private static void add(EObject object, String featureName, Object value)
        {
            EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
            ((List<Object>)object.eGet(feature)).add(value);
        }
    }
}
