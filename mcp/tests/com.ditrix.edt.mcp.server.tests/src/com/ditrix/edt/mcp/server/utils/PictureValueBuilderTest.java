/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com.ditrix.edt.mcp.server.utils.MetadataPropertyIntrospector.PropertyInfo;
import com.google.gson.JsonPrimitive;

/** Headless tests for {@link PictureValueBuilder}. */
public class PictureValueBuilderTest
{
    @Test
    public void testStandardPictureUsesProviderFullSymbolicNameAndReturnsProxy()
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        EObject platformPicture = newStandardProxyPicture("Change"); //$NON-NLS-1$
        Mockito.doReturn(platformPicture).when(provider).createProxy("StdPicture.Change"); //$NON-NLS-1$

        PictureValueBuilder.Result result = PictureValueBuilder.buildWithProvider(
            new JsonPrimitive("StdPicture.Change"), MetadataScope.ofConfiguration(null), provider); //$NON-NLS-1$

        assertNull(result.error);
        assertSame(platformPicture, result.picture);
        Mockito.verify(provider).createProxy("StdPicture.Change"); //$NON-NLS-1$
    }

    @Test
    public void testExtendedPictureUsesFullExtendedKeyAndRendersThatPrefix()
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        EObject platformPicture = newExtendedProxyPicture("ExtendedChange"); //$NON-NLS-1$
        Mockito.doReturn(platformPicture).when(provider)
            .createProxy("StdExtPicture.ExtendedChange"); //$NON-NLS-1$

        PictureValueBuilder.Result result = PictureValueBuilder.buildWithProvider(
            new JsonPrimitive("StdExtPicture.ExtendedChange"), //$NON-NLS-1$
            MetadataScope.ofConfiguration(null), provider);

        assertNull(result.error);
        assertSame(platformPicture, result.picture);
        Mockito.verify(provider).createProxy("StdExtPicture.ExtendedChange"); //$NON-NLS-1$
        EObject holder = newPictureHolder();
        holder.eSet(holder.eClass().getEStructuralFeature("picture"), pictureRef(result.picture)); //$NON-NLS-1$
        assertEquals("StdExtPicture.ExtendedChange", //$NON-NLS-1$
            MetadataPropertyIntrospector.find(holder, "picture").currentValue); //$NON-NLS-1$
    }

    @Test
    public void testRussianStandardPictureNameUsesFullBilingualProviderKeyAndRendersCanonicalName()
    {
        String russianName = "\u0418\u0437\u043C\u0435\u043D\u0438\u0442\u044C"; //$NON-NLS-1$
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        EObject platformPicture = newStandardProxyPicture("Change"); //$NON-NLS-1$
        Mockito.doReturn(platformPicture).when(provider)
            .createProxy("StdPicture." + russianName); //$NON-NLS-1$

        PictureValueBuilder.Result result = PictureValueBuilder.buildWithProvider(
            new JsonPrimitive("StdPicture." + russianName), //$NON-NLS-1$
            MetadataScope.ofConfiguration(null), provider);

        assertNull(result.error);
        assertSame(platformPicture, result.picture);
        Mockito.verify(provider).createProxy("StdPicture." + russianName); //$NON-NLS-1$
        EObject holder = newPictureHolder();
        holder.eSet(holder.eClass().getEStructuralFeature("picture"), pictureRef(result.picture)); //$NON-NLS-1$
        assertEquals("StdPicture.Change", //$NON-NLS-1$
            MetadataPropertyIntrospector.find(holder, "picture").currentValue); //$NON-NLS-1$
    }

    @Test
    public void testUnknownStandardPictureReturnsActionableErrorWithoutThrowing()
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        Mockito.doThrow(new IllegalArgumentException("unknown picture")) //$NON-NLS-1$
            .when(provider).createProxy("StdPicture.Nope"); //$NON-NLS-1$

        PictureValueBuilder.Result result = PictureValueBuilder.buildWithProvider(
            new JsonPrimitive("StdPicture.Nope"), MetadataScope.ofConfiguration(null), provider); //$NON-NLS-1$

        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("StdPicture.Nope")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("list_common_pictures")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("StdPicture.<Name>")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("StdExtPicture.<Name>")); //$NON-NLS-1$
    }

    @Test
    public void testEnglishCommonPictureResolvesThroughSharedMetadataResolver()
    {
        Configuration configuration = configurationWithPicture("Logo"); //$NON-NLS-1$

        PictureValueBuilder.Result result = PictureValueBuilder.buildWithProvider(
            new JsonPrimitive("CommonPicture.Logo"), MetadataScope.ofConfiguration(configuration), //$NON-NLS-1$
            (IEObjectProvider)null);

        assertNull(result.error);
        assertSame(configuration.getCommonPictures().get(0), result.picture);
        assertEquals("Logo", ((CommonPicture)result.picture).getName()); //$NON-NLS-1$
    }

    @Test
    public void testRussianCommonPictureTypeTokenUsesSameResolver()
    {
        Configuration configuration = configurationWithPicture("Logo"); //$NON-NLS-1$
        String russianToken = "\u041E\u0431\u0449\u0430\u044F\u041A\u0430\u0440" //$NON-NLS-1$
            + "\u0442\u0438\u043D\u043A\u0430"; //$NON-NLS-1$

        PictureValueBuilder.Result result = PictureValueBuilder.buildWithProvider(
            new JsonPrimitive(russianToken + ".Logo"), MetadataScope.ofConfiguration(configuration), //$NON-NLS-1$
            (IEObjectProvider)null);

        assertNull(result.error);
        assertSame(configuration.getCommonPictures().get(0), result.picture);
        assertEquals("Logo", ((CommonPicture)result.picture).getName()); //$NON-NLS-1$
    }

    @Test
    public void testMalformedValueShowsBothAcceptedForms()
    {
        PictureValueBuilder.Result result = PictureValueBuilder.buildWithProvider(
            new JsonPrimitive("Change"), MetadataScope.ofConfiguration(null), (IEObjectProvider)null); //$NON-NLS-1$

        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("Change")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("StdPicture.<Name>")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("StdExtPicture.<Name>")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("CommonPicture.<Name>")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("list_common_pictures")); //$NON-NLS-1$
    }

    @Test
    public void testStandardPictureRendersBackToSymbolicName()
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        // The provider proxy deliberately has no feature state; its URI fragment is the fallback.
        Mockito.doReturn(newStandardProxyPicture("Change")).when(provider) //$NON-NLS-1$
            .createProxy("StdPicture.Change"); //$NON-NLS-1$
        PictureValueBuilder.Result built = PictureValueBuilder.buildWithProvider(
            new JsonPrimitive("StdPicture.Change"), MetadataScope.ofConfiguration(null), provider); //$NON-NLS-1$
        EObject holder = newPictureHolder();
        holder.eSet(holder.eClass().getEStructuralFeature("picture"), pictureRef(built.picture)); //$NON-NLS-1$

        PropertyInfo info = MetadataPropertyIntrospector.find(holder, "picture"); //$NON-NLS-1$
        assertNotNull(info);
        assertEquals("StdPicture.Change", info.currentValue); //$NON-NLS-1$
    }

    @Test
    public void testResolvedPictureUsesRealNameAndResourceUri()
    {
        Picture platformPicture = newResolvedPlatformPicture("ResolvedChange", //$NON-NLS-1$
            URI.createURI("v8:/Pictures/StdExt/8.3.27")); //$NON-NLS-1$
        EObject holder = newPictureHolder();
        holder.eSet(holder.eClass().getEStructuralFeature("picture"), //$NON-NLS-1$
            pictureRef(platformPicture));

        assertEquals("StdExtPicture.ResolvedChange", //$NON-NLS-1$
            MetadataPropertyIntrospector.find(holder, "picture").currentValue); //$NON-NLS-1$
    }

    @Test
    public void testCommonPictureRendersBackToSymbolicName()
    {
        Configuration configuration = configurationWithPicture("Logo"); //$NON-NLS-1$
        PictureValueBuilder.Result built = PictureValueBuilder.buildWithProvider(
            new JsonPrimitive("CommonPicture.Logo"), MetadataScope.ofConfiguration(configuration), //$NON-NLS-1$
            (IEObjectProvider)null);
        EObject holder = newPictureHolder();
        holder.eSet(holder.eClass().getEStructuralFeature("picture"), pictureRef(built.picture)); //$NON-NLS-1$

        assertEquals("CommonPicture.Logo", //$NON-NLS-1$
            MetadataPropertyIntrospector.find(holder, "picture").currentValue); //$NON-NLS-1$
    }

    private static EObject pictureRef(EObject picture)
    {
        EObject pictureRef = EcoreUtil.create(McorePackage.Literals.PICTURE_REF);
        pictureRef.eSet(McorePackage.Literals.PICTURE_REF__PICTURE, picture);
        return pictureRef;
    }

    private static Configuration configurationWithPicture(String name)
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        CommonPicture picture = (CommonPicture)EcoreUtil.create(MdClassPackage.Literals.COMMON_PICTURE);
        picture.setName(name);
        configuration.getCommonPictures().add(picture);
        return configuration;
    }

    /** A mocked generated Picture described by a synthetic platform-picture EClass. */
    private static Picture newStandardProxyPicture(String name)
    {
        return newPlatformPicture(URI.createURI("v8:/Pictures/Std/8.3.27#/" + name)); //$NON-NLS-1$
    }

    private static Picture newExtendedProxyPicture(String name)
    {
        return newPlatformPicture(URI.createURI("v8:/Pictures/StdExt/8.3.27#/" + name)); //$NON-NLS-1$
    }

    /** An unresolved generated-Picture mock whose only value state is its real provider proxy URI. */
    private static Picture newPlatformPicture(URI proxyUri)
    {
        Picture picture = Mockito.mock(Picture.class,
            Mockito.withSettings().extraInterfaces(InternalEObject.class));
        InternalEObject internal = (InternalEObject)picture;
        Mockito.when(picture.eClass()).thenReturn(newPlatformPictureType());
        Mockito.when(picture.eIsProxy()).thenReturn(true);
        Mockito.when(internal.eProxyURI()).thenReturn(proxyUri);
        return picture;
    }

    /** A resolved generated-Picture mock with a real name feature and defining resource URI. */
    private static Picture newResolvedPlatformPicture(String name, URI resourceUri)
    {
        EClass type = newPlatformPictureType();
        EStructuralFeature nameFeature = type.getEStructuralFeature("name"); //$NON-NLS-1$
        Resource resource = Mockito.mock(Resource.class);
        Mockito.when(resource.getURI()).thenReturn(resourceUri);
        Picture picture = Mockito.mock(Picture.class);
        Mockito.when(picture.eClass()).thenReturn(type);
        Mockito.when(picture.eIsProxy()).thenReturn(false);
        Mockito.when(picture.eResource()).thenReturn(resource);
        Mockito.when(picture.eGet(nameFeature, true)).thenReturn(name);
        return picture;
    }

    private static EClass newPlatformPictureType()
    {
        EcoreFactory factory = EcoreFactory.eINSTANCE;
        EClass type = factory.createEClass();
        type.setName("PlatformPictureProbe"); //$NON-NLS-1$
        type.getESuperTypes().add(McorePackage.Literals.PICTURE);
        EAttribute nameFeature = factory.createEAttribute();
        nameFeature.setName("name"); //$NON-NLS-1$
        nameFeature.setEType(EcorePackage.Literals.ESTRING);
        type.getEStructuralFeatures().add(nameFeature);
        return type;
    }

    private static EObject newPictureHolder()
    {
        EcoreFactory factory = EcoreFactory.eINSTANCE;
        EPackage pkg = newPackage(factory, "pictureholder"); //$NON-NLS-1$
        EClass holder = factory.createEClass();
        holder.setName("PictureHolder"); //$NON-NLS-1$
        EReference picture = factory.createEReference();
        picture.setName("picture"); //$NON-NLS-1$
        picture.setEType(McorePackage.Literals.PICTURE);
        picture.setContainment(true);
        picture.setUpperBound(1);
        holder.getEStructuralFeatures().add(picture);
        pkg.getEClassifiers().add(holder);
        return pkg.getEFactoryInstance().create(holder);
    }

    private static EPackage newPackage(EcoreFactory factory, String name)
    {
        EPackage pkg = factory.createEPackage();
        pkg.setName(name);
        pkg.setNsPrefix(name);
        pkg.setNsURI("http://ditrix.com/test/" + name); //$NON-NLS-1$
        return pkg;
    }
}
