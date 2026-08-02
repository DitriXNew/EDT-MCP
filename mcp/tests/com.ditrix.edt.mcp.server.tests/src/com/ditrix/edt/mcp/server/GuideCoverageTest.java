/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.GuideLoader;

/**
 * Registry-driven ratchet for tool guides: every registered {@link IMcpTool} must
 * have a non-empty extended guide, served from its bundled Markdown resource
 * {@code guides/<name>.md} via {@link GuideLoader}.
 *
 * <p>Tool guides are the single source of truth in {@code guides/*.md} (one file
 * per tool, packaged into the plugin via {@code build.properties} {@code bin.includes}).
 * This test fails the build when a tool ships without a guide file — so adding a
 * tool now requires adding its guide — and doubles as the proof that the resource
 * loader actually resolves the bundled Markdown at runtime (the default
 * {@code getGuide()} path), not just that the files exist on disk.</p>
 */
public class GuideCoverageTest
{
    private McpToolRegistry registry;

    @Before
    public void setUp()
    {
        // Same production registration the prefs UI / Activator uses.
        registry = McpToolRegistry.getInstance();
        new McpServer().registerTools();
        GuideLoader.clearCache();
    }

    @After
    public void tearDown()
    {
        registry.clear();
        GuideLoader.clearCache();
    }

    /** Sanity: the production registration actually populated tools (guards a vacuous pass). */
    @Test
    public void testRegistryIsPopulated()
    {
        assertTrue("registerTools() should register a non-trivial set of tools", //$NON-NLS-1$
            registry.getToolCount() >= 50);
    }

    @Test
    public void testEveryToolHasANonEmptyGuide()
    {
        List<String> problems = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            String guide = tool.getGuide();
            if (guide == null || guide.trim().isEmpty())
            {
                problems.add(tool.getClass().getSimpleName() + " (" + tool.getName() //$NON-NLS-1$
                    + "): no guide — add guides/" + tool.getName() + ".md"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        assertTrue("tool guide coverage violations:\n  " + String.join("\n  ", problems), //$NON-NLS-1$ //$NON-NLS-2$
            problems.isEmpty());
    }

    /**
     * The form-structure sections the READER renders must all be named by the READ guide. A section
     * added to the output without a matching guide entry leaves an agent unable to discover it -
     * exactly the read/write documentation drift issue #295's review flagged.
     *
     * <p>The expected sections are NOT a hard-coded list: they are the {@code ## } headings
     * {@link FormStructureReader#render} actually emits for the fixture below, so a SIXTH section
     * cannot be added to the renderer and quietly stay undocumented - the drift a hand-written pin
     * would keep missing (the same defect the review found in this test's four-name pin). The one
     * thing the fixture cannot cover is a future section that renders only for a shape it does not
     * build, so it exercises every conditional section the reader has today - including
     * {@code Attribute columns}, which needs a collection attribute WITH columns.</p>
     */
    @Test
    public void testFormStructureSectionsAreDocumentedInTheGuide()
    {
        String guide = null;
        for (IMcpTool tool : registry.getAllTools())
        {
            if ("get_metadata_details".equals(tool.getName())) //$NON-NLS-1$
            {
                guide = tool.getGuide();
            }
        }
        assertNotNull("get_metadata_details must be registered with a guide", guide); //$NON-NLS-1$

        List<String> sections = renderedFormSections();
        assertTrue("the fixture must exercise the CONDITIONAL 'Attribute columns' section, " //$NON-NLS-1$
            + "otherwise this test silently stops covering it: " + sections, //$NON-NLS-1$
            sections.contains("Attribute columns")); //$NON-NLS-1$
        for (String section : sections)
        {
            assertTrue("the get_metadata_details guide must name the form section '" + section //$NON-NLS-1$
                + "' the reader renders", guide.contains(section)); //$NON-NLS-1$
        }
    }

    /**
     * Renders a form through {@link FormStructureReader} and returns its {@code ## } section headings.
     * The fixture is a dynamic-EMF form shaped like a managed form, carrying one attribute with one
     * column so every section the reader can emit is emitted.
     */
    @SuppressWarnings("unchecked")
    private static List<String> renderedFormSections()
    {
        EcoreFactory factory = EcoreFactory.eINSTANCE;
        EPackage pkg = factory.createEPackage();
        pkg.setName("formlike"); //$NON-NLS-1$
        pkg.setNsPrefix("formlike"); //$NON-NLS-1$
        pkg.setNsURI("http://ditrix.com/test/guidecoverage"); //$NON-NLS-1$

        EClass columnClass = factory.createEClass();
        columnClass.setName("FormAttributeColumn"); //$NON-NLS-1$
        EClass attributeClass = factory.createEClass();
        attributeClass.setName("FormAttribute"); //$NON-NLS-1$
        attributeClass.getEStructuralFeatures().add(containment(factory, "columns", columnClass)); //$NON-NLS-1$
        EClass formClass = factory.createEClass();
        formClass.setName("Form"); //$NON-NLS-1$
        formClass.getEStructuralFeatures().add(containment(factory, "attributes", attributeClass)); //$NON-NLS-1$
        pkg.getEClassifiers().add(columnClass);
        pkg.getEClassifiers().add(attributeClass);
        pkg.getEClassifiers().add(formClass);

        EObject form = new DynamicEObjectImpl(formClass);
        EObject attribute = new DynamicEObjectImpl(attributeClass);
        ((List<EObject>)form.eGet(formClass.getEStructuralFeature("attributes"))).add(attribute); //$NON-NLS-1$
        ((List<EObject>)attribute.eGet(attributeClass.getEStructuralFeature("columns"))) //$NON-NLS-1$
            .add(new DynamicEObjectImpl(columnClass));

        List<String> sections = new ArrayList<>();
        for (String line : FormStructureReader.render("Catalog.X.Form.F", form, "en").split("\n")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            if (line.startsWith("## ")) //$NON-NLS-1$
            {
                sections.add(line.substring(3).trim());
            }
        }
        return sections;
    }

    /** A many-valued containment reference named {@code name} holding {@code type}. */
    private static EReference containment(EcoreFactory factory, String name, EClass type)
    {
        EReference reference = factory.createEReference();
        reference.setName(name);
        reference.setEType(type);
        reference.setContainment(true);
        reference.setUpperBound(-1);
        return reference;
    }

    /** The loader resolves a bundled guide by tool name (validates the resource path itself). */
    @Test
    public void testLoaderResolvesBundledGuide()
    {
        String guide = GuideLoader.load("list_projects"); //$NON-NLS-1$
        assertTrue("GuideLoader should resolve guides/list_projects.md from the bundle", //$NON-NLS-1$
            guide != null && !guide.trim().isEmpty());
    }

    /** A missing guide degrades to an empty string rather than throwing. */
    @Test
    public void testMissingGuideIsEmptyNotError()
    {
        assertTrue("an unknown tool name must yield an empty guide, not an error", //$NON-NLS-1$
            GuideLoader.load("no_such_tool_zzz").isEmpty()); //$NON-NLS-1$
    }
}
