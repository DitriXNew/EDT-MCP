/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.bm.core.BmUriUtil;
import com._1c.g5.v8.bm.core.IBmObject;

/** A headless command source with model-supplied bilingual names and an attached BM address. */
public final class FormExcludedCommandsFixture
{
    public final EPackage formPackage = EcoreFactory.eINSTANCE.createEPackage();
    public final EClass source = type("FormStandardCommandSource");
    public final EClass command = type("FormStandardCommand");
    public final EClass table = type("Table");
    public final EReference commands;
    public final EReference excluded;
    public final EObject model;
    public final IBmObject target = mock(IBmObject.class);

    public FormExcludedCommandsFixture()
    {
        formPackage.setName("form");
        formPackage.setNsPrefix("form");
        formPackage.setNsURI("http://g5.1c.ru/v8/dt/form");
        table.getESuperTypes().add(source);
        attribute(command, "name", EcorePackage.Literals.ESTRING);
        attribute(command, "nameRu", EcorePackage.Literals.ESTRING);
        attribute(table, "name", EcorePackage.Literals.ESTRING);
        attribute(table, "visible", EcorePackage.Literals.EBOOLEAN);
        commands = reference(source, "commands", true);
        commands.setTransient(true);
        excluded = reference(source, "excludedCommands", false);
        model = formPackage.getEFactoryInstance().create(table);
        model.eSet(table.getEStructuralFeature("name"), "Rows");
        when(target.eClass()).thenReturn(table);
        when(target.eGet(any(EStructuralFeature.class))).thenAnswer(i -> model.eGet(i.getArgument(0)));
        when(target.eGet(any(EStructuralFeature.class), anyBoolean()))
            .thenAnswer(i -> model.eGet(i.getArgument(0), i.getArgument(1)));
        when(target.bmGetUri()).thenReturn(BmUriUtil.createContainedBmObjectUri(
            "test", "Catalog.Goods.Form.ItemForm", "/items:Rows"));
        offer("Add", "\u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c");
        offer("Copy", "\u0421\u043a\u043e\u043f\u0438\u0440\u043e\u0432\u0430\u0442\u044c");
        offer("Delete", "\u0423\u0434\u0430\u043b\u0438\u0442\u044c");
    }

    public void offer(String english, String russian)
    {
        EObject entry = formPackage.getEFactoryInstance().create(command);
        entry.eSet(command.getEStructuralFeature("name"), english);
        entry.eSet(command.getEStructuralFeature("nameRu"), russian);
        offered().add(entry);
    }

    @SuppressWarnings("unchecked")
    public EList<EObject> offered()
    {
        return (EList<EObject>)model.eGet(commands);
    }

    @SuppressWarnings("unchecked")
    public EList<EObject> stored()
    {
        return (EList<EObject>)model.eGet(excluded, false);
    }

    public void seed(String name)
    {
        EObject proxy = formPackage.getEFactoryInstance().create(command);
        ((InternalEObject)proxy).eSetProxyURI(BmUriUtil.createContainedBmObjectUri(
            "test", "Catalog.Goods.Form.ItemForm", "/items:Rows/commands:" + name));
        stored().add(proxy);
    }

    public List<String> storedNames()
    {
        return stored().stream().map(entry -> {
            URI uri = ((InternalEObject)entry).eProxyURI();
            String path = BmUriUtil.extractContainmentPath(uri);
            return BmUriUtil.decodeCollectionItemId(path.substring(path.lastIndexOf(':') + 1));
        }).toList();
    }

    private EClass type(String name)
    {
        EClass value = EcoreFactory.eINSTANCE.createEClass();
        value.setName(name);
        formPackage.getEClassifiers().add(value);
        return value;
    }

    private EReference reference(EClass owner, String name, boolean containment)
    {
        EReference value = EcoreFactory.eINSTANCE.createEReference();
        value.setName(name);
        value.setEType(command);
        value.setUpperBound(-1);
        value.setContainment(containment);
        owner.getEStructuralFeatures().add(value);
        return value;
    }

    private static void attribute(EClass owner, String name, org.eclipse.emf.ecore.EDataType type)
    {
        var value = EcoreFactory.eINSTANCE.createEAttribute();
        value.setName(name);
        value.setEType(type);
        owner.getEStructuralFeatures().add(value);
    }
}
