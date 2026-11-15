/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.Subsystem;
import com.google.gson.JsonElement;

/** Issue #933 PR-3: what a clone into another project needs there, and what binds the preview to it. */
public class MetadataCloneDestinationTest
{
    private static final String SRC = "Src";
    private static final String DST = "Dst";

    @Test
    public void missingReferencesComeFromEveryReferenceAndOnlyFromWhatTheDestinationLacks()
    {
        Catalog source = MdClassFactory.eINSTANCE.createCatalog();
        source.setName("Source");
        // An owner the destination lacks, one it has, the source itself, a non-model value and one the source
        // cannot resolve either.
        source.getOwners().add(proxy(MdClassPackage.Literals.CATALOG, top(SRC, "Catalog.Gone")));
        source.getOwners().add(proxy(MdClassPackage.Literals.CATALOG, top(SRC, "Catalog.Here")));
        source.getOwners().add(proxy(MdClassPackage.Literals.CATALOG, top(SRC, "Catalog.Source")));
        source.getOwners().add(proxy(MdClassPackage.Literals.CATALOG, URI.createURI("unresolved:/Catalog.Any")));
        source.getOwners().add(proxy(MdClassPackage.Literals.CATALOG, top(SRC, "Catalog.Broken")));
        // A type of an owned attribute: types in type descriptions are found by the same generic walk.
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Link");
        TypeDescription type = McoreFactory.eINSTANCE.createTypeDescription();
        type.getTypes().add(proxy(com._1c.g5.v8.dt.mcore.McorePackage.Literals.TYPE,
            member(SRC, "Document.Absent", "/producedTypes/refType/type")));
        type.getTypes().add(proxy(com._1c.g5.v8.dt.mcore.McorePackage.Literals.TYPE,
            member(SRC, "Document.Order", "/producedTypes/refType/type")));
        type.getTypes().add(proxy(com._1c.g5.v8.dt.mcore.McorePackage.Literals.TYPE,
            member(SRC, "Catalog.Source", "/producedTypes/refType/type")));
        attribute.setType(type);
        source.getAttributes().add(attribute);

        Set<String> resolvable = new HashSet<>(List.of(
            top(SRC, "Catalog.Gone").toString(), top(SRC, "Catalog.Here").toString(), top(DST, "Catalog.Here").toString(),
            member(SRC, "Document.Absent", "/producedTypes/refType/type").toString(),
            member(SRC, "Document.Order", "/producedTypes/refType/type").toString(),
            // The destination has the document, but not the referenced member.
            top(DST, "Document.Order").toString(),
            // The source's own addresses resolve in the source and are still not missing: they are copied.
            top(SRC, "Catalog.Source").toString(),
            member(SRC, "Catalog.Source", "/producedTypes/refType/type").toString()));
        List<String> missing = MetadataCloneDestination.missingReferences(source, SRC, DST, resolver(resolvable));
        String orderPath = BmUriUtil.extractContainmentPath(member(SRC, "Document.Order", "/producedTypes/refType/type"));
        assertEquals(List.of("Catalog.Gone", "Document.Absent", "Document.Order#" + orderPath), missing);

        resolvable.add(top(DST, "Catalog.Gone").toString());
        resolvable.add(member(DST, "Document.Absent", "/producedTypes/refType/type").toString());
        resolvable.add(member(DST, "Document.Order", "/producedTypes/refType/type").toString());
        assertEquals("Everything resolves in the destination", List.of(),
            MetadataCloneDestination.missingReferences(source, SRC, DST, resolver(resolvable)));
    }

    @Test
    public void aDestinationNameAndAnUnadoptedBaseNameAreRefused()
    {
        Configuration target = MdClassFactory.eINSTANCE.createConfiguration();
        target.getCatalogs().add(catalog("Taken"));
        Configuration base = MdClassFactory.eINSTANCE.createConfiguration();
        base.getCatalogs().add(catalog("BaseOnly"));
        Catalog source = catalog("Source");
        MetadataCloneDestination extension = destination(target, true, base, uri -> null);
        MetadataCloneDestination configuration = destination(target, false, null, uri -> null);

        String taken = refusal(() -> extension.checkName(source, "taken"));
        assertTrue(taken, taken.contains("Catalog.taken already exists in project 'Dst'"));
        assertTrue(taken, taken.contains("Choose another newName"));
        String inBase = refusal(() -> extension.checkName(source, "BaseOnly"));
        assertTrue(inBase, inBase.contains("Catalog.BaseOnly exists in 'Base'"));
        assertTrue(inBase, inBase.contains("has not adopted it"));
        assertTrue(inBase, inBase.contains("adopt_metadata_object"));
        extension.checkName(source, "Free");
        configuration.checkName(source, "BaseOnly");

        // Without newName the clone keeps the source name, which must then be free.
        target.getCatalogs().add(catalog("Source"));
        String kept = refusal(() -> configuration.checkName(source, null));
        assertTrue(kept, kept.contains("Catalog.Source already exists"));
        assertTrue(kept, kept.contains("Pass a newName"));
        assertEquals("Source", MetadataCloneDestination.effectiveName(source, null));
        assertEquals("Copy", MetadataCloneDestination.effectiveName(source, "Copy"));
    }

    @Test
    public void theMissingRefusalNamesEveryReferenceAndTheWayOut()
    {
        Configuration target = MdClassFactory.eINSTANCE.createConfiguration();
        MetadataCloneDestination extension = destination(target, true, MdClassFactory.eINSTANCE.createConfiguration(),
            uri -> null);
        MetadataCloneDestination.MissingReferences adopt =
            extension.missing("Document.Order", List.of("AccumulationRegister.Stock"));
        assertTrue(adopt.getMessage(), adopt.getMessage().contains("AccumulationRegister.Stock"));
        assertTrue(adopt.getMessage(), adopt.getMessage().contains("adopt_metadata_object (projectName 'Base', "
            + "extensionProjectName 'Dst')"));
        assertTrue(adopt.getMessage(), adopt.getMessage().contains("Nothing was written"));
        assertEquals(List.of("AccumulationRegister.Stock"), adopt.references);
        assertNotNull("A missing reference is a final refusal, not a stale source", MetadataClonePlan.Refusal.in(
            new IllegalStateException(adopt)));

        List<String> many = new ArrayList<>();
        for (int i = 0; i < MetadataCloneDestination.MESSAGE_LIMIT + 3; i++)
        {
            many.add("Catalog.C" + i);
        }
        MetadataCloneDestination.MissingReferences create =
            destination(target, false, null, uri -> null).missing("Catalog.Source", many);
        assertTrue(create.getMessage(), create.getMessage().contains("create_metadata"));
        assertFalse(create.getMessage(), create.getMessage().contains("adopt_metadata_object"));
        assertTrue(create.getMessage(), create.getMessage().contains("and 3 more (see missingReferences)"));
        assertFalse(create.getMessage(), create.getMessage().contains("Catalog.C" + MetadataCloneDestination.MESSAGE_LIMIT
            + ","));
        assertEquals(many, create.references);
    }

    @Test
    public void aCrossCaptureRefusesMissingReferencesBeforeAnythingElse() throws Exception
    {
        MetadataCloneFilesFixture fixture = new MetadataCloneFilesFixture();
        fixture.source.getOwners().add(proxy(MdClassPackage.Literals.CATALOG, top(SRC, "Catalog.Gone")));
        MetadataCloneDestination destination = destination(MdClassFactory.eINSTANCE.createConfiguration(), false, null,
            resolver(Set.of(top(SRC, "Catalog.Gone").toString())));
        try
        {
            MetadataClonePlan.captureInto(destination, fixture.project, fixture.model, fixture.config, fixture.source,
                "Copy", fixture.fs, false);
            fail("A reference the destination lacks must refuse the preview");
        }
        catch (MetadataCloneDestination.MissingReferences expected)
        {
            assertEquals(List.of("Catalog.Gone"), expected.references);
        }
    }

    @Test
    public void aCrossCaptureBindsTheDestinationAndLeavesTheSameProjectHashAlone() throws Exception
    {
        MetadataCloneFilesFixture fixture = new MetadataCloneFilesFixture();
        String sameProject = fixture.preview().fingerprint;
        Configuration target = MdClassFactory.eINSTANCE.createConfiguration();
        target.getCatalogs().add(catalog("Sibling"));
        String into = capture(fixture, destination(target, false, null, uri -> null));
        assertEquals("A cross capture is deterministic", into, capture(fixture, destination(target, false, null,
            uri -> null)));
        assertNotEquals("The destination joins the fingerprint", sameProject, into);
        assertEquals("The same-project capture is unchanged", sameProject, fixture.preview().fingerprint);

        assertNotEquals("Another destination project", into, capture(fixture, new MetadataCloneDestination("Other",
            target, false, null, null, SRC, DST, uri -> null, folder -> false)));
        Configuration base = MdClassFactory.eINSTANCE.createConfiguration();
        String asExtension = capture(fixture, destination(target, true, base, uri -> null));
        assertNotEquals("The destination kind", into, asExtension);
        base.getCatalogs().add(catalog("BaseSibling"));
        assertNotEquals("A base-configuration sibling", asExtension, capture(fixture, destination(target, true, base,
            uri -> null)));
        target.getCatalogs().get(0).setUuid(UUID.randomUUID());
        assertNotEquals("A destination sibling's identity", into, capture(fixture, destination(target, false, null,
            uri -> null)));
        target.setNamePrefix("p_");
        assertNotEquals("The destination configuration root", into, capture(fixture, destination(target, false, null,
            uri -> null)));
    }

    @Test
    public void aCrossCaptureReportsSourceRelationsOnlyAndUsesTheDestinationsPrefixAndFolder() throws Exception
    {
        MetadataCloneFilesFixture fixture = new MetadataCloneFilesFixture();
        Subsystem subsystem = MdClassFactory.eINSTANCE.createSubsystem();
        subsystem.setName("Sales");
        subsystem.getContent().add(fixture.source);
        fixture.config.getSubsystems().add(subsystem);
        fixture.config.setNamePrefix("src_");
        assertEquals("supported", support(fixture.preview()));

        Configuration target = MdClassFactory.eINSTANCE.createConfiguration();
        target.setNamePrefix("ext_");
        MetadataClonePlan into = MetadataClonePlan.captureInto(destination(target, true,
            MdClassFactory.eINSTANCE.createConfiguration(), uri -> null), fixture.project, fixture.model, fixture.config,
            fixture.source, "Copy", fixture.fs, false);
        assertEquals("The owner lives in the source project", "reportedOnly", support(into));
        assertEquals(1, into.kindWarnings.size());
        assertTrue(into.kindWarnings.get(0), into.kindWarnings.get(0).contains("namePrefix 'ext_'"));

        List<String> asked = new ArrayList<>();
        MetadataCloneDestination occupied = new MetadataCloneDestination(DST, target, false, null, null, SRC, DST,
            uri -> null, folder -> asked.add(folder));
        try
        {
            MetadataClonePlan.captureInto(occupied, fixture.project, fixture.model, fixture.config, fixture.source,
                "Copy", fixture.fs, false);
            fail("An existing destination folder must refuse");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("'src/Catalogs/Copy' already exists in "
                + "project 'Dst'"));
        }
        assertEquals(List.of("src/Catalogs/Copy"), asked);
    }

    private static String support(MetadataClonePlan plan)
    {
        for (JsonElement relation : plan.incoming)
        {
            if ("subsystem".equals(relation.getAsJsonObject().get("kind").getAsString()))
            {
                return relation.getAsJsonObject().get("transferSupport").getAsString();
            }
        }
        throw new AssertionError("no subsystem relation in " + plan.incoming);
    }

    private static String capture(MetadataCloneFilesFixture fixture, MetadataCloneDestination destination)
    {
        return MetadataClonePlan.captureInto(destination, fixture.project, fixture.model, fixture.config,
            fixture.source, "Copy", fixture.fs, false).fingerprint;
    }

    private static MetadataCloneDestination destination(Configuration target, boolean extension, Configuration base,
        Function<URI, EObject> resolve)
    {
        return new MetadataCloneDestination(DST, target, extension, base, base == null ? null : "Base", SRC, DST,
            resolve, folder -> false);
    }

    private static String refusal(Runnable call)
    {
        try
        {
            call.run();
        }
        catch (MetadataClonePlan.Refusal expected)
        {
            return expected.getMessage();
        }
        throw new AssertionError("expected a refusal");
    }

    private static Function<URI, EObject> resolver(Set<String> resolvable)
    {
        return uri -> resolvable.contains(uri.toString()) ? MdClassFactory.eINSTANCE.createCatalog() : null;
    }

    private static Catalog catalog(String name)
    {
        Catalog result = MdClassFactory.eINSTANCE.createCatalog();
        result.setName(name);
        result.setUuid(UUID.randomUUID());
        return result;
    }

    private static URI top(String namespace, String fqn)
    {
        return BmUriUtil.createTopBmObjectUri(namespace, fqn);
    }

    private static URI member(String namespace, String fqn, String path)
    {
        return BmUriUtil.createContainedBmObjectUri(namespace, fqn, path);
    }

    @SuppressWarnings("unchecked")
    private static <T extends EObject> T proxy(EClass type, URI uri)
    {
        EObject result = EcoreUtil.create(type);
        ((InternalEObject)result).eSetProxyURI(uri);
        return (T)result;
    }
}
