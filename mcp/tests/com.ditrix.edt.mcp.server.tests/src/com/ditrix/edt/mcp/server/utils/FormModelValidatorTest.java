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
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.junit.Test;

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

    // --- the synthetic form --------------------------------------------------------------------

    private static List<String> codes(Form form)
    {
        List<String> codes = new ArrayList<>();
        for (FormModelValidator.Finding finding : FormModelValidator.validate(form.root))
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
        final EClass commandType;
        final EClass parameterType;
        final EClass groupType;
        final EClass fieldType;
        final EClass buttonType;
        final EClass barType;
        final EClass additionType;
        final EClass extensionHandlerType;
        final EClass commandActionType;
        final EClass dataPathType;
        final EClass eventType;
        final EClass handlerType;
        final EClass extInfoType;
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

            commandType = eClass("FormCommand"); //$NON-NLS-1$
            named(commandType);

            parameterType = eClass("FormParameter"); //$NON-NLS-1$
            parameterType.getEStructuralFeatures()
                .add(attribute("name", EcorePackage.Literals.ESTRING, false)); //$NON-NLS-1$

            attributeType = eClass("FormAttribute"); //$NON-NLS-1$
            named(attributeType);
            attributeType.getEStructuralFeatures()
                .add(attribute("main", EcorePackage.Literals.EBOOLEAN, false)); //$NON-NLS-1$

            EClass itemBase = eClass("FormItem"); //$NON-NLS-1$
            itemBase.setAbstract(true);
            named(itemBase);
            itemBase.getEStructuralFeatures().add(reference("handlers", handlerType, true, true)); //$NON-NLS-1$

            groupType = eClass("FormGroup"); //$NON-NLS-1$
            groupType.getESuperTypes().add(itemBase);
            groupType.getEStructuralFeatures().add(reference("items", itemBase, true, true)); //$NON-NLS-1$

            fieldType = eClass("FormField"); //$NON-NLS-1$
            fieldType.getESuperTypes().add(itemBase);
            fieldType.getEStructuralFeatures().add(reference("dataPath", dataPathType, false, true)); //$NON-NLS-1$

            buttonType = eClass("Button"); //$NON-NLS-1$
            buttonType.getESuperTypes().add(itemBase);
            buttonType.getEStructuralFeatures().add(reference("commandName", commandType, false, false)); //$NON-NLS-1$

            additionType = eClass("Addition"); //$NON-NLS-1$
            additionType.getESuperTypes().add(itemBase);

            extensionHandlerType = eClass("EventHandlerExtension"); //$NON-NLS-1$
            extensionHandlerType.getESuperTypes().add(handlerType);
            extensionHandlerType.getEStructuralFeatures()
                .add(attribute("callType", EcorePackage.Literals.ESTRING, false)); //$NON-NLS-1$

            commandActionType = eClass("FormCommandHandlerContainer"); //$NON-NLS-1$
            commandActionType.getEStructuralFeatures()
                .add(reference("handler", handlerType, false, true)); //$NON-NLS-1$
            commandType.getEStructuralFeatures()
                .add(reference("action", commandActionType, false, true)); //$NON-NLS-1$

            barType = eClass("AutoCommandBar"); //$NON-NLS-1$
            barType.getESuperTypes().add(itemBase);

            formType = eClass("Form"); //$NON-NLS-1$
            formType.getEStructuralFeatures().add(reference("items", itemBase, true, true)); //$NON-NLS-1$
            formType.getEStructuralFeatures().add(reference("attributes", attributeType, true, true)); //$NON-NLS-1$
            formType.getEStructuralFeatures().add(reference("formCommands", commandType, true, true)); //$NON-NLS-1$
            formType.getEStructuralFeatures().add(reference("parameters", parameterType, true, true)); //$NON-NLS-1$
            formType.getEStructuralFeatures().add(reference("handlers", handlerType, true, true)); //$NON-NLS-1$
            formType.getEStructuralFeatures().add(reference("autoCommandBar", barType, false, true)); //$NON-NLS-1$
            formType.getEStructuralFeatures().add(reference("extInfo", extInfoType, false, true)); //$NON-NLS-1$

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
            add(root, "attributes", attribute); //$NON-NLS-1$
            return attribute;
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

        void giveRootExtInfo()
        {
            set(root, "extInfo", create(extInfoType)); //$NON-NLS-1$
        }

        void giveAutoCommandBar(int id)
        {
            EObject bar = create(barType);
            set(bar, "name", "FormCommandBar"); //$NON-NLS-1$ //$NON-NLS-2$
            set(bar, "id", Integer.valueOf(id)); //$NON-NLS-1$
            set(root, "autoCommandBar", bar); //$NON-NLS-1$
        }

        // --- EMF plumbing ---

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
