/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormElementWriter.FormMemberRef;

/**
 * Tests the REFUSALS {@link MetadataRenameService} makes before it opens a transaction: a new name
 * that is not a legal identifier (refused for EVERY target, form or mdclass alike) and, for the
 * managed-form branch (issue #381), an event-handler address, a bare column address, a
 * designer-owned auto child addressed directly, and a target name a sibling already bears.
 * <p>
 * Reached by REFLECTION on four private statics - {@code invalidNewNameError},
 * {@code formRenameIneligibility}, {@code designerChildRefusal} and
 * {@code duplicateNameRefusal}. Their only public entry
 * ({@code rename}) needs a live EDT project, an {@code IFormRefactoringService} and a BM
 * transaction, none of which exist headlessly, and production shape is not bent for testability -
 * so no test seam was added. The cost is stated rather than hidden: RENAMING one of those methods
 * breaks these tests with a {@code NoSuchMethodException} instead of a compile error, and the
 * refusal wording is pinned only because it is asserted here.
 * <p>
 * The duplicate-name check reads a form model, so it runs against a dynamic EMF metamodel shaped
 * like the pieces it touches - the {@code attributes} list beside the {@code items} tree - which is
 * the same headless technique {@code FormElementWriterTest} uses (the real
 * {@code com._1c.g5.v8.dt.form.model} package is never imported). Russian tokens are built from
 * code points, so the assertion exercises the real Cyrillic mapping instead of round-tripping one
 * literal.
 */
public class MetadataRenameServiceTest
{
    /** Cyrillic 'Spravochnik' - the Russian TYPE token for a Catalog. */
    private static final String RU_CATALOG =
        fromCp(0x0421, 0x043f, 0x0440, 0x0430, 0x0432, 0x043e, 0x0447, 0x043d, 0x0438, 0x043a);

    /** Cyrillic 'Forma' - the Russian FORM segment of a form address. */
    private static final String RU_FORM = fromCp(0x0424, 0x043e, 0x0440, 0x043c, 0x0430);

    /** Cyrillic 'Obrabotchik' - the Russian Handler kind token. */
    private static final String RU_HANDLER =
        fromCp(0x041e, 0x0431, 0x0440, 0x0430, 0x0431, 0x043e, 0x0442, 0x0447, 0x0438, 0x043a);

    /** Cyrillic 'PriOtkrytii' - the Russian spelling of the OnOpen form event. */
    private static final String RU_ON_OPEN = fromCp(0x041f, 0x0440, 0x0438, 0x041e, 0x0442, 0x043a,
        0x0440, 0x044b, 0x0442, 0x0438, 0x0438);

    /** Cyrillic 'Kolonka' - the Russian Column kind token. */
    private static final String RU_COLUMN =
        fromCp(0x041a, 0x043e, 0x043b, 0x043e, 0x043d, 0x043a, 0x0430);

    /** Cyrillic 'Rekvizit' - the Russian Attribute kind token. */
    private static final String RU_ATTRIBUTE =
        fromCp(0x0420, 0x0435, 0x043a, 0x0432, 0x0438, 0x0437, 0x0438, 0x0442);

    /** Cyrillic 'Pole' - the Russian Field kind token. */
    private static final String RU_FIELD = fromCp(0x041f, 0x043e, 0x043b, 0x0435);

    /** Cyrillic 'Gruppa' - the Russian Group kind token (an AutoCommandBar IS a Group). */
    private static final String RU_GROUP =
        fromCp(0x0413, 0x0440, 0x0443, 0x043f, 0x043f, 0x0430);

    // ==================== Handler addresses are refused, with the rebind path ====================

    /**
     * A form-level handler FQN names an EVENT BINDING, not a renameable element. The refusal has to
     * say so AND name the operation the caller actually wants, because the alternative reading -
     * "the element is missing" - sends an agent hunting for an element that is right there.
     */
    @Test
    public void testFormLevelHandlerIsRefusedWithTheRebindPath() throws Exception
    {
        String refusal =
            formRenameIneligibility(ref("Catalog.Catalog.Form.ItemForm.Handler.OnOpen")); //$NON-NLS-1$
        assertHandlerRefusal(refusal);
    }

    /** The item-level shape ({@code ...Field.X.Handler.OnChange}) is the same refusal. */
    @Test
    public void testItemLevelHandlerIsRefusedWithTheRebindPath() throws Exception
    {
        String refusal = formRenameIneligibility(
            ref("Catalog.Catalog.Form.ItemForm.Field.Price.Handler.OnChange")); //$NON-NLS-1$
        assertHandlerRefusal(refusal);
    }

    /** A command's Action handler ({@code ...Command.X.Handler.Action}) is the same refusal. */
    @Test
    public void testCommandActionHandlerIsRefusedWithTheRebindPath() throws Exception
    {
        String refusal = formRenameIneligibility(
            ref("Catalog.Catalog.Form.ItemForm.Command.Print.Handler.Action")); //$NON-NLS-1$
        assertHandlerRefusal(refusal);
    }

    /** A CommonForm IS a form, so its handler address is refused by the same rule. */
    @Test
    public void testCommonFormHandlerIsRefusedWithTheRebindPath() throws Exception
    {
        String refusal = formRenameIneligibility(ref("CommonForm.Form.Handler.OnOpen")); //$NON-NLS-1$
        assertHandlerRefusal(refusal);
    }

    /**
     * The Russian half of the bilingual minimum: the very same address written in Cyrillic tokens
     * must reach the very same refusal. A check that only knew the English spellings would let a
     * Russian-script handler address fall through into the rename branch.
     */
    @Test
    public void testRussianHandlerTokenIsRefusedToo() throws Exception
    {
        String refusal = formRenameIneligibility(ref(RU_CATALOG + ".Catalog." + RU_FORM //$NON-NLS-1$
            + ".ItemForm." + RU_HANDLER + "." + RU_ON_OPEN)); //$NON-NLS-1$ //$NON-NLS-2$
        assertHandlerRefusal(refusal);
    }

    // ==================== A bare column address is refused, with the right shape ====================

    /**
     * A column belongs to a collection form ATTRIBUTE, so a bare {@code Column.Name} names no
     * owner and can address nothing. The refusal must hand back the corrected shape - the caller
     * cannot guess that the owner goes in the middle of the FQN.
     */
    @Test
    public void testBareColumnAddressIsRefusedWithTheOwnerShape() throws Exception
    {
        String refusal =
            formRenameIneligibility(ref("Catalog.Catalog.Form.ItemForm.Column.Price")); //$NON-NLS-1$
        assertColumnRefusal(refusal, "Price"); //$NON-NLS-1$
    }

    /** The same verdict through the Russian Column token. */
    @Test
    public void testRussianBareColumnAddressIsRefusedToo() throws Exception
    {
        String refusal = formRenameIneligibility(ref(RU_CATALOG + ".Catalog." + RU_FORM //$NON-NLS-1$
            + ".ItemForm." + RU_COLUMN + ".Price")); //$NON-NLS-1$ //$NON-NLS-2$
        assertColumnRefusal(refusal, "Price"); //$NON-NLS-1$
    }

    /**
     * The counterpart that keeps the two refusals above from being vacuous: an eligible address
     * must NOT be refused. A check that returned a message for everything would pass every
     * assertion here except this one.
     */
    @Test
    public void testAnEligibleFormAddressIsNotRefused() throws Exception
    {
        assertNull("an ordinary field address is renameable", //$NON-NLS-1$
            formRenameIneligibility(ref("Catalog.Catalog.Form.ItemForm.Field.Price"))); //$NON-NLS-1$
        assertNull("a column addressed on its OWNING attribute is renameable", //$NON-NLS-1$
            formRenameIneligibility(
                ref("Catalog.Catalog.Form.ItemForm.Attribute.Goods.Column.Price"))); //$NON-NLS-1$
        assertNull("and the same address in Cyrillic tokens", //$NON-NLS-1$
            formRenameIneligibility(ref(RU_CATALOG + ".Catalog." + RU_FORM + ".ItemForm." //$NON-NLS-1$ //$NON-NLS-2$
                + RU_ATTRIBUTE + ".Goods." + RU_COLUMN + ".Price"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Designer-owned children are refused BY ECLASS ====================

    /**
     * An AutoCommandBar / ContextMenu / ExtendedTooltip has a name the platform derives from its
     * owner, and EDT's naming service refuses to rename it. Each one IS addressable (an auto command
     * bar is a Group, an extended tooltip is a Decoration), so the refusal cannot be made by address
     * - it is made by the resolved element's ECLASS. The message must point at the owner, because
     * renaming the owner is the operation that actually moves the auto child's name.
     */
    @Test
    public void testDesignerOwnedChildrenAreRefusedByEClass() throws Exception
    {
        assertDesignerRefusal("AutoCommandBar", "Group", "FormCommandBar"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertDesignerRefusal("ContextMenu", "Group", "PriceContextMenu"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertDesignerRefusal("ExtendedTooltip", "Decoration", "PriceExtendedTooltip"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The same verdict when the designer child is addressed with a Russian kind token. */
    @Test
    public void testDesignerOwnedChildIsRefusedThroughARussianAddressToo() throws Exception
    {
        EObject bar = new DynamicEObjectImpl(MODEL.autoCommandBar);
        String refusal = designerChildRefusal(bar, ref(RU_CATALOG + ".Catalog." + RU_FORM //$NON-NLS-1$
            + ".ItemForm." + RU_GROUP + ".FormCommandBar")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("the refusal is made by ECLASS, so the address language cannot change it", //$NON-NLS-1$
            refusal);
        assertTrue("it must still name the class: " + refusal, //$NON-NLS-1$
            refusal.contains("AutoCommandBar")); //$NON-NLS-1$
    }

    /**
     * The negative half: an ORDINARY element must pass. Without it a check that refused everything
     * would satisfy the assertions above and quietly make the whole branch unreachable.
     */
    @Test
    public void testAnOrdinaryFormElementIsNotADesignerChild() throws Exception
    {
        EObject field = new DynamicEObjectImpl(MODEL.formField);
        assertNull("an ordinary FormField is renameable", designerChildRefusal(field, //$NON-NLS-1$
            ref("Catalog.Catalog.Form.ItemForm.Field.Price"))); //$NON-NLS-1$
    }

    // ==================== A duplicate target name is refused ====================

    /**
     * The new name is already borne by a sibling ATTRIBUTE. The refusal must name BOTH the taken
     * name and the form it is taken on - a rename request carries neither, so an error naming only
     * "a duplicate" leaves the caller nothing to act on.
     */
    @Test
    public void testADuplicateAttributeNameIsRefused() throws Exception
    {
        String refusal = duplicateNameRefusal(newForm(),
            ref("Catalog.Catalog.Form.ItemForm.Attribute.Cost"), "Price"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("renaming onto a taken attribute name must be refused", refusal); //$NON-NLS-1$
        assertTrue("the refusal must name the taken name: " + refusal, //$NON-NLS-1$
            refusal.contains("Price")); //$NON-NLS-1$
        assertTrue("and the form it is taken on: " + refusal, //$NON-NLS-1$
            refusal.contains("Catalog.Catalog.forms.ItemForm")); //$NON-NLS-1$
        assertTrue("and say the name already exists: " + refusal, //$NON-NLS-1$
            refusal.contains("already exists")); //$NON-NLS-1$
    }

    /**
     * 1C names are compared case-insensitively, so a target differing only in case is the SAME
     * name. An exact-match check would let this through and hand the clash to the platform.
     */
    @Test
    public void testADuplicateDifferingOnlyInCaseIsRefused() throws Exception
    {
        assertNotNull("'PRICE' is the same name as 'Price'", duplicateNameRefusal(newForm(), //$NON-NLS-1$
            ref("Catalog.Catalog.Form.ItemForm.Attribute.Cost"), "PRICE")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A CASE-ONLY rename of an element is NOT a duplicate: every lookup here is case-insensitive, so
     * the target always finds ITSELF. Excluding the member by identity is what keeps 'Price' -> 'price'
     * from being refused as a clash with itself - a false refusal on a perfectly healthy rename.
     */
    @Test
    public void testACaseOnlyRenameOfTheElementItselfIsNotADuplicate() throws Exception
    {
        EObject form = newForm();
        EObject price = FormElementWriter.findFormAttribute(form, "Price"); //$NON-NLS-1$
        assertNotNull("fixture must carry the Price attribute", price); //$NON-NLS-1$

        assertNull("renaming Price to price must not clash with Price itself", //$NON-NLS-1$
            duplicateNameRefusal(form, price,
                ref("Catalog.Catalog.Form.ItemForm.Attribute.Price"), "price")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A form COMMAND is checked against the COMMAND namespace, not the item tree. Checking it against
     * the items refused a healthy rename whose new name merely matched some field, and simultaneously
     * let a genuine duplicate command through.
     */
    @Test
    public void testACommandIsCheckedAgainstCommandsNotItems() throws Exception
    {
        EObject form = newForm();

        // PriceField, not Price: Price is an ATTRIBUTE in this fixture, and the item-tree lookup the
        // old code used would not have found it either - the assertion would have passed against the
        // very bug it is named for. It has to name something that really is in the item tree.
        assertNull("a command may take a name a FIELD bears - separate namespaces", //$NON-NLS-1$
            duplicateNameRefusal(form, ref("Catalog.Catalog.Form.ItemForm.Command.Run"), //$NON-NLS-1$
                "PriceField")); //$NON-NLS-1$
        assertNotNull("but a name another COMMAND already bears must be refused", //$NON-NLS-1$
            duplicateNameRefusal(form, ref("Catalog.Catalog.Form.ItemForm.Command.Run"), //$NON-NLS-1$
                "Print")); //$NON-NLS-1$
    }

    /** The same refusal reached through the Russian Attribute token. */
    @Test
    public void testADuplicateAttributeNameIsRefusedThroughARussianTokenToo() throws Exception
    {
        String refusal = duplicateNameRefusal(newForm(),
            ref("Catalog.Catalog.Form.ItemForm." + RU_ATTRIBUTE + ".Cost"), "Price"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull("the Russian Attribute token must select the same namespace", refusal); //$NON-NLS-1$
        assertTrue("and produce the same message: " + refusal, //$NON-NLS-1$
            refusal.contains("already exists")); //$NON-NLS-1$
    }

    /**
     * The item namespace is searched through the WHOLE item tree, not just the form's top level:
     * form-item names are form-wide unique, so a name taken by an item nested inside a group is
     * taken. A check that only scanned the root would report this one free and then fail deep
     * inside EDT's refactoring.
     */
    @Test
    public void testADuplicateItemNameIsRefusedEvenWhenNestedInAGroup() throws Exception
    {
        String refusal = duplicateNameRefusal(newForm(),
            ref("Catalog.Catalog.Form.ItemForm.Field.Price"), "NestedField"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("a name borne by a nested item is taken", refusal); //$NON-NLS-1$
        assertTrue("the refusal must name it: " + refusal, refusal.contains("NestedField")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The same refusal reached through the Russian Field token. */
    @Test
    public void testADuplicateItemNameIsRefusedThroughARussianTokenToo() throws Exception
    {
        assertNotNull("the Russian Field token must select the item namespace", //$NON-NLS-1$
            duplicateNameRefusal(newForm(),
                ref("Catalog.Catalog.Form.ItemForm." + RU_FIELD + ".Price"), "PriceField")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A form ATTRIBUTE and a form ITEM live in different namespaces, which is why the check follows
     * the ADDRESSED kind instead of scanning the whole form. Both directions are pinned, because a
     * check that scanned everything would pass every other test here and refuse two renames the
     * platform accepts.
     */
    @Test
    public void testTheTwoNamespacesDoNotBlockEachOther() throws Exception
    {
        assertNull("an attribute may take a name an ITEM bears", duplicateNameRefusal(newForm(), //$NON-NLS-1$
            ref("Catalog.Catalog.Form.ItemForm.Attribute.Cost"), "PriceField")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("an item may take a name an ATTRIBUTE bears", duplicateNameRefusal(newForm(), //$NON-NLS-1$
            ref("Catalog.Catalog.Form.ItemForm.Field.PriceField"), "Price")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A name nothing bears is not refused - the other half of every assertion above. */
    @Test
    public void testAFreeNameIsNotRefused() throws Exception
    {
        assertNull("a free attribute name must be accepted", duplicateNameRefusal(newForm(), //$NON-NLS-1$
            ref("Catalog.Catalog.Form.ItemForm.Attribute.Cost"), "Discount")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("a free item name must be accepted", duplicateNameRefusal(newForm(), //$NON-NLS-1$
            ref("Catalog.Catalog.Form.ItemForm.Field.Price"), "DiscountField")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== the new name must be a legal identifier ====================
    //
    // The verdict is the PLATFORM'S own predicate (StringUtils.isValidName), reached through the
    // service's one shared entry - so these tests MEASURE what the platform accepts instead of
    // restating a rule we invented. The acceptance half matters most: a false refusal on a legal
    // name is worse than the miss it was meant to prevent.

    /** Cyrillic 'Tovar' - a perfectly legal 1C name, and the reason the rule cannot be ASCII-only. */
    private static final String RU_TOVAR = fromCp(0x0422, 0x043e, 0x0432, 0x0430, 0x0440);

    /**
     * A dot in the new name is the damaging case: the write succeeds, but the result is addressable
     * by no FQN (the dot IS the FQN separator) and the cascade rewrites the form module into
     * something that no longer parses. The old name is gone by then, so this cannot be undone
     * through the tool - which is why it has to be refused BEFORE the cascade, not reported after.
     */
    @Test
    public void testANewNameWithADotIsRefused() throws Exception
    {
        String refusal = invalidNewNameError("Bad.Name"); //$NON-NLS-1$
        assertNotNull("a dotted new name must be refused", refusal); //$NON-NLS-1$
        assertTrue("the refusal must quote the bad value: " + refusal, //$NON-NLS-1$
            refusal.contains("Bad.Name")); //$NON-NLS-1$
        assertTrue("and say what a name may contain: " + refusal, //$NON-NLS-1$
            refusal.contains("letters, digits and underscores")); //$NON-NLS-1$
    }

    /** The other malformed shapes the platform predicate rejects. */
    @Test
    public void testTheOtherIllegalNewNamesAreRefused() throws Exception
    {
        for (String bad : new String[] {"1Price", "Bad Name", "", "Price-2", "Price!", " Price"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            assertNotNull("'" + bad + "' is not a legal 1C name and must be refused", //$NON-NLS-1$ //$NON-NLS-2$
                invalidNewNameError(bad));
        }
        assertNotNull("a missing new name must be refused, not thrown on", //$NON-NLS-1$
            invalidNewNameError(null));
    }

    /**
     * The half that keeps the check from being a blanket refusal - and the half that measures the
     * platform predicate rather than assuming it. A CYRILLIC name is legal 1C (the predicate asks
     * Character.isLetter, not an ASCII range): a rule written by hand against [A-Za-z] would pass
     * every assertion above and reject half the configurations in the country.
     */
    @Test
    public void testLegalNewNamesAreNotRefused() throws Exception
    {
        for (String good : new String[] {"Price", "_Price", "Price2", "P", "_", RU_TOVAR}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertNull("'" + good + "' is a legal 1C name and must be accepted", //$NON-NLS-1$ //$NON-NLS-2$
                invalidNewNameError(good));
        }
    }

    // ==================== shared assertions ====================

    /** Every handler address must reach the SAME refusal, whatever shape carried it. */
    private static void assertHandlerRefusal(String refusal)
    {
        assertNotNull("a handler address must be refused before any transaction opens", refusal); //$NON-NLS-1$
        assertTrue("the refusal must say it addresses a handler: " + refusal, //$NON-NLS-1$
            refusal.contains("form event handler")); //$NON-NLS-1$
        assertTrue("it must name the tool that rebinds one: " + refusal, //$NON-NLS-1$
            refusal.contains("modify_metadata")); //$NON-NLS-1$
        assertTrue("and the property that carries the new target: " + refusal, //$NON-NLS-1$
            refusal.contains("procedure")); //$NON-NLS-1$
        assertFalse("it must NOT pretend the element is missing - it is right there: " + refusal, //$NON-NLS-1$
            refusal.contains("not found")); //$NON-NLS-1$
    }

    /** Every bare-column address must be refused with the owner-bearing shape. */
    private static void assertColumnRefusal(String refusal, String leaf)
    {
        assertNotNull("a bare column address must be refused", refusal); //$NON-NLS-1$
        assertTrue("the refusal must hand back the corrected shape: " + refusal, //$NON-NLS-1$
            refusal.contains("Attribute.<AttributeName>.Column.")); //$NON-NLS-1$
        assertTrue("and carry the addressed leaf into it: " + refusal, refusal.contains(leaf)); //$NON-NLS-1$
        assertFalse("a column address is not a handler address: " + refusal, //$NON-NLS-1$
            refusal.contains("form event handler")); //$NON-NLS-1$
    }

    /**
     * A designer-owned child of the given ECLASS, addressed by {@code kindToken}, is refused with
     * the "rename the owner instead" advice.
     *
     * @param eClassName the designer-owned EClass to instantiate
     * @param kindToken the kind token the element is really addressable by
     * @param name the element's derived name
     */
    private static void assertDesignerRefusal(String eClassName, String kindToken, String name)
        throws Exception
    {
        EObject member = new DynamicEObjectImpl(MODEL.designerOwned(eClassName));
        String refusal = designerChildRefusal(member,
            ref("Catalog.Catalog.Form.ItemForm." + kindToken + "." + name)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(eClassName + " must be refused a direct rename", refusal); //$NON-NLS-1$
        assertTrue("the refusal must name the element: " + refusal, refusal.contains(name)); //$NON-NLS-1$
        assertTrue("and its designer-owned class: " + refusal, refusal.contains(eClassName)); //$NON-NLS-1$
        assertTrue("and point at the operation that DOES work: " + refusal, //$NON-NLS-1$
            refusal.contains("Rename the OWNING element instead")); //$NON-NLS-1$
    }

    // ==================== reflective access to the private refusals ====================

    private static String formRenameIneligibility(FormMemberRef ref) throws Exception
    {
        Method method = MetadataRenameService.class.getDeclaredMethod(
            "formRenameIneligibility", FormMemberRef.class); //$NON-NLS-1$
        method.setAccessible(true);
        return (String)method.invoke(null, ref);
    }

    private static String invalidNewNameError(String newName) throws Exception
    {
        Method method =
            MetadataRenameService.class.getDeclaredMethod("invalidNewNameError", String.class); //$NON-NLS-1$
        method.setAccessible(true);
        return (String)method.invoke(null, newName);
    }

    private static String designerChildRefusal(EObject member, FormMemberRef ref) throws Exception
    {
        Method method = MetadataRenameService.class.getDeclaredMethod(
            "designerChildRefusal", EObject.class, FormMemberRef.class); //$NON-NLS-1$
        method.setAccessible(true);
        return (String)method.invoke(null, member, ref);
    }

    /**
     * The convenience overload used where the rename target is NOT the element that would clash:
     * passes {@code null} as the member, which can never be identity-equal to a found clash, so the
     * refusal is decided purely by the namespace lookup.
     */
    private static String duplicateNameRefusal(EObject formModel, FormMemberRef ref, String newName)
        throws Exception
    {
        return duplicateNameRefusal(formModel, null, ref, newName);
    }

    private static String duplicateNameRefusal(EObject formModel, EObject member, FormMemberRef ref,
        String newName) throws Exception
    {
        Method method = MetadataRenameService.class.getDeclaredMethod(
            "duplicateNameRefusal", EObject.class, EObject.class, FormMemberRef.class, //$NON-NLS-1$
            String.class);
        method.setAccessible(true);
        return (String)method.invoke(null, formModel, member, ref, newName);
    }

    /** Parses a probe FQN through the writer's OWN parser - the same one the branch calls. */
    private static FormMemberRef ref(String fqn)
    {
        FormMemberRef parsed = FormElementWriter.parse(fqn);
        assertNotNull("the probe FQN must parse as a form member: " + fqn, parsed); //$NON-NLS-1$
        return parsed;
    }

    /** Builds a Cyrillic token from code points, keeping this source pure ASCII. */
    private static String fromCp(int... codePoints)
    {
        return new String(codePoints, 0, codePoints.length);
    }

    // ==================== dynamic form-like EMF metamodel ====================

    private static final FormLikeModel MODEL = new FormLikeModel();

    /**
     * A form carrying one ATTRIBUTE ({@code Price}), one top-level ITEM ({@code PriceField}) and a
     * group holding a nested item ({@code NestedField}) - the three positions the duplicate-name
     * check has to look in, and the one it must not confuse with the others.
     */
    private static EObject newForm()
    {
        EObject form = new DynamicEObjectImpl(MODEL.form);
        addTo(form, "attributes", named(MODEL.formAttribute, "Price")); //$NON-NLS-1$ //$NON-NLS-2$
        addTo(form, "formCommands", named(MODEL.formCommand, "Print")); //$NON-NLS-1$ //$NON-NLS-2$
        addTo(form, "items", named(MODEL.formField, "PriceField")); //$NON-NLS-1$ //$NON-NLS-2$
        EObject group = named(MODEL.formGroup, "MainGroup"); //$NON-NLS-1$
        addTo(form, "items", group); //$NON-NLS-1$
        addTo(group, "items", named(MODEL.formField, "NestedField")); //$NON-NLS-1$ //$NON-NLS-2$
        return form;
    }

    private static EObject named(EClass eClass, String name)
    {
        EObject object = new DynamicEObjectImpl(eClass);
        object.eSet(eClass.getEStructuralFeature("name"), name); //$NON-NLS-1$
        return object;
    }

    @SuppressWarnings("unchecked")
    private static void addTo(EObject owner, String featureName, EObject child)
    {
        ((List<EObject>)owner.eGet(owner.eClass().getEStructuralFeature(featureName))).add(child);
    }

    /**
     * The slice of the form metamodel the duplicate-name check walks: a {@code Form} with its
     * {@code attributes} list and its {@code items} tree, plus the three designer-owned EClasses
     * the rename branch recognizes by name. The abstract base is called {@code FormItem} and the
     * package publishes it under that name because the writer's item search looks the classifier up
     * BY NAME - a differently named base would make every item lookup answer nothing.
     */
    private static final class FormLikeModel
    {
        final EClass form;
        final EClass formAttribute;
        final EClass formCommand;
        final EClass formField;
        final EClass formGroup;
        final EClass autoCommandBar;

        private final EPackage pkg;

        FormLikeModel()
        {
            EcoreFactory f = EcoreFactory.eINSTANCE;
            pkg = f.createEPackage();
            pkg.setName("formlike"); //$NON-NLS-1$
            pkg.setNsPrefix("formlike"); //$NON-NLS-1$
            pkg.setNsURI("http://ditrix.com/test/formlike-rename"); //$NON-NLS-1$

            EClass formItem = f.createEClass();
            formItem.setName("FormItem"); //$NON-NLS-1$
            formItem.setAbstract(true);
            addName(f, formItem);

            formField = f.createEClass();
            formField.setName("FormField"); //$NON-NLS-1$
            formField.getESuperTypes().add(formItem);

            formGroup = f.createEClass();
            formGroup.setName("FormGroup"); //$NON-NLS-1$
            formGroup.getESuperTypes().add(formItem);
            formGroup.getEStructuralFeatures().add(containment(f, "items", formItem, true)); //$NON-NLS-1$

            // The three the branch refuses BY ECLASS. Modelled as real FormItem subclasses because
            // that is what they are: each one is reachable through an ordinary kind token, which is
            // exactly why the address cannot be what identifies them.
            autoCommandBar = designerChild(f, formItem, "AutoCommandBar"); //$NON-NLS-1$
            designerChild(f, formItem, "ContextMenu"); //$NON-NLS-1$
            designerChild(f, formItem, "ExtendedTooltip"); //$NON-NLS-1$

            formAttribute = f.createEClass();
            formAttribute.setName("FormAttribute"); //$NON-NLS-1$
            addName(f, formAttribute);

            // A form COMMAND is NOT a FormItem: it lives in its own 'formCommands' namespace. The
            // fixture has to model that separateness, otherwise a duplicate check that wrongly
            // searched the item tree would look correct here.
            formCommand = f.createEClass();
            formCommand.setName("FormCommand"); //$NON-NLS-1$
            addName(f, formCommand);

            form = f.createEClass();
            form.setName("Form"); //$NON-NLS-1$
            form.getEStructuralFeatures().add(containment(f, "items", formItem, true)); //$NON-NLS-1$
            form.getEStructuralFeatures().add(
                containment(f, "attributes", formAttribute, true)); //$NON-NLS-1$
            form.getEStructuralFeatures().add(
                containment(f, "formCommands", formCommand, true)); //$NON-NLS-1$

            pkg.getEClassifiers().add(formItem);
            pkg.getEClassifiers().add(formField);
            pkg.getEClassifiers().add(formGroup);
            pkg.getEClassifiers().add(formAttribute);
            pkg.getEClassifiers().add(formCommand);
            pkg.getEClassifiers().add(form);
        }

        /** The designer-owned EClass of that name (registered by the constructor). */
        EClass designerOwned(String name)
        {
            return (EClass)pkg.getEClassifier(name);
        }

        private EClass designerChild(EcoreFactory f, EClass formItem, String name)
        {
            EClass child = f.createEClass();
            child.setName(name);
            child.getESuperTypes().add(formItem);
            pkg.getEClassifiers().add(child);
            return child;
        }

        private static void addName(EcoreFactory f, EClass owner)
        {
            EAttribute name = f.createEAttribute();
            name.setName("name"); //$NON-NLS-1$
            name.setEType(EcorePackage.Literals.ESTRING);
            owner.getEStructuralFeatures().add(name);
        }

        private static EReference containment(EcoreFactory f, String featureName, EClass type,
            boolean many)
        {
            EReference reference = f.createEReference();
            reference.setName(featureName);
            reference.setEType(type);
            reference.setContainment(true);
            reference.setUpperBound(many ? -1 : 1);
            return reference;
        }
    }
}
