/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.BeforeClass;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectBelonging;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionPackage;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdClassExtensionTypePackage;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;

/**
 * Issues #709 / #710: {@link AdoptedPropertyRouter#wouldDrop} against EDT's OWN {@code .mdo} writer of an
 * extension, {@code com._1c.g5.v8.dt.internal.md.extension.resource.MdExtXmlFormatter} - an internal class of
 * the md.extension bundle, loaded from that bundle and asked through its protected {@code shouldSaveFeature}.
 * Every metadata class is paired with the extension class EDT's property mapper gives it (the class named
 * {@code <Class>Extension}, else its nearest supertype's), and every feature of the class that has a
 * same-named feature in the extension class is asked for every state (unset, None, Checked, Extended,
 * Notify) and every belonging (adopted with a block, adopted without one, native), and once with a block that
 * answers {@code null} for the state: the mirror must answer "dropped" exactly when EDT's writer does not save
 * the value. A new exemption or rule in EDT fails here.
 *
 * <p>The object is a proxy of the metadata interface whose {@code eIsSet} answers {@code true}, so the
 * writer's generic rule saves whatever its extension rule does not drop. The extension block is real - and a
 * real block answers its generated default, None, for an unset state - except the one that answers
 * {@code null}, a proxy: the writer drops on "null or None", and only a proxy reaches the null half. The
 * test FAILS - it never skips - when the writer or its method cannot be loaded: the mirror is only as good
 * as this comparison.</p>
 */
public class AdoptedPropertyFormatterConformanceTest
{
    private static final String WRITER = "com._1c.g5.v8.dt.internal.md.extension.resource.MdExtXmlFormatter"; //$NON-NLS-1$
    private static final String WRITER_BUNDLE = "com._1c.g5.v8.dt.md.extension"; //$NON-NLS-1$

    private static final List<MdPropertyState> STATES = Arrays.asList(null, MdPropertyState.NONE,
        MdPropertyState.CHECKED, MdPropertyState.EXTENDED, MdPropertyState.NOTIFY);

    private static Object writer;
    private static Method shouldSaveFeature;

    @BeforeClass
    public static void loadEdtsWriter() throws ReflectiveOperationException
    {
        Class<?> type = loadFromBundle(WRITER_BUNDLE, WRITER);
        assertNotNull("EDT's .mdo writer of an extension is not loadable from bundle " + WRITER_BUNDLE + ": " //$NON-NLS-1$ //$NON-NLS-2$
            + WRITER + " - renamed or moved; AdoptedPropertyRouter.wouldDrop mirrors it and must be checked " //$NON-NLS-1$
            + "against its successor", type); //$NON-NLS-1$
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        writer = constructor.newInstance();
        shouldSaveFeature = type.getDeclaredMethod("shouldSaveFeature", EObject.class, EStructuralFeature.class); //$NON-NLS-1$
        shouldSaveFeature.setAccessible(true);
    }

    @Test
    public void testWouldDropAnswersWhatEdtsWriterDoes() throws ReflectiveOperationException
    {
        int compared = 0;
        int dropped = 0;
        int nullDropped = 0;
        List<String> mismatches = new ArrayList<>();
        for (EClassifier classifier : MdClassPackage.eINSTANCE.getEClassifiers())
        {
            if (!(classifier instanceof EClass))
            {
                continue;
            }
            EClass mdClass = (EClass)classifier;
            if (mdClass.isAbstract() || mdClass.isInterface()
                || !MdClassPackage.Literals.MD_OBJECT.isSuperTypeOf(mdClass))
            {
                continue;
            }
            EClass extensionClass = extensionClassOf(mdClass);
            if (extensionClass == null)
            {
                continue;
            }
            for (EStructuralFeature feature : mdClass.getEAllStructuralFeatures())
            {
                EStructuralFeature state = extensionClass.getEStructuralFeature(feature.getName());
                if (state == null)
                {
                    continue;
                }
                assertEquals("a same-named extension feature that is not a state would break EDT's writer (it " //$NON-NLS-1$
                    + "casts the value to MdPropertyState): " + mdClass.getName() + "." + feature.getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + " / " + extensionClass.getName() + "." + state.getName(), //$NON-NLS-1$ //$NON-NLS-2$
                    MdClassExtensionTypePackage.Literals.MD_PROPERTY_STATE, state.getEType());
                MdObject object = newObject(mdClass);
                ObjectExtension extension = (ObjectExtension)EcoreUtil.create(extensionClass);
                for (Belonging belonging : Belonging.values())
                {
                    for (MdPropertyState value : STATES)
                    {
                        if (belonging == Belonging.ADOPTED_WITH_A_NULL_STATE && value != null)
                        {
                            continue; // that block answers null whatever the state: asked once
                        }
                        configure(object, extension, state, belonging, value);
                        boolean saved = saves(object, feature);
                        boolean drops = AdoptedPropertyRouter.wouldDrop(object, feature);
                        compared++;
                        if (drops)
                        {
                            dropped++;
                        }
                        if (belonging == Belonging.ADOPTED_WITH_A_NULL_STATE && !saved)
                        {
                            nullDropped++;
                        }
                        boolean genericRuleRefuses = feature.isDerived() || feature.isTransient();
                        boolean agrees = genericRuleRefuses ? !drops || !saved : drops == !saved;
                        if (!agrees)
                        {
                            mismatches.add(mdClass.getName() + "." + feature.getName() + " " + belonging + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                + value + ": EDT saves=" + saved + ", wouldDrop=" + drops); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    }
                }
            }
        }
        assertTrue("the mirror disagrees with EDT's writer: " + mismatches, mismatches.isEmpty()); //$NON-NLS-1$
        assertTrue("too few cases compared - the enumeration found almost nothing: " + compared, compared > 1000); //$NON-NLS-1$
        assertTrue("no case was dropped - the comparison does not reach the writer's drop rule: " + dropped, //$NON-NLS-1$
            dropped > 100);
        assertTrue("the block that answers null never reached the writer's drop rule: " + nullDropped, //$NON-NLS-1$
            nullDropped > 100);
    }

    @Test
    public void testTheExtensionRootsSynonymIsWrittenByBoth() throws ReflectiveOperationException
    {
        // The one exemption of EDT's writer: an extension root's synonym. The root's other states still drop.
        EClass root = MdClassPackage.Literals.CONFIGURATION;
        EClass rootExtension = MdClassExtensionPackage.Literals.CONFIGURATION_EXTENSION;
        MdObject object = object(root, rootExtension, MdClassExtensionPackage.Literals.MD_OBJECT_EXTENSION__SYNONYM,
            Belonging.ADOPTED_WITH_BLOCK, MdPropertyState.NONE);
        assertTrue("EDT writes an extension root's synonym in state None", //$NON-NLS-1$
            saves(object, MdClassPackage.Literals.MD_OBJECT__SYNONYM));
        assertFalse(AdoptedPropertyRouter.wouldDrop(object, MdClassPackage.Literals.MD_OBJECT__SYNONYM));

        EStructuralFeature caption = rootExtension.getEStructuralFeature("caption"); //$NON-NLS-1$
        object = object(root, rootExtension, caption, Belonging.ADOPTED_WITH_BLOCK, MdPropertyState.NONE);
        assertFalse("EDT drops the root's caption in state None", //$NON-NLS-1$
            saves(object, MdClassPackage.Literals.CONFIGURATION__CAPTION));
        assertTrue(AdoptedPropertyRouter.wouldDrop(object, MdClassPackage.Literals.CONFIGURATION__CAPTION));
    }

    @Test
    public void testTheStateIsFoundByTheExactNameByBoth() throws ReflectiveOperationException
    {
        // BasicFeature.toolTip against BasicFeatureExtension.tooltip: the writer misses the state (exact name)
        // and saves the value; EDT's own property mapper, which ignores case, would have found it.
        EClass attribute = MdClassPackage.Literals.CATALOG_ATTRIBUTE;
        EClass extension = extensionClassOf(attribute);
        assertEquals(MdClassExtensionPackage.Literals.BASIC_FEATURE_EXTENSION, extension);
        EStructuralFeature tooltip = MdClassExtensionPackage.Literals.BASIC_FEATURE_EXTENSION__TOOLTIP;
        assertTrue("the names differ only by case", //$NON-NLS-1$
            tooltip.getName().equalsIgnoreCase(MdClassPackage.Literals.BASIC_FEATURE__TOOL_TIP.getName()));
        MdObject object = object(attribute, extension, tooltip, Belonging.ADOPTED_WITH_BLOCK, MdPropertyState.NONE);
        assertTrue("EDT writes the attribute's tool tip", saves(object, MdClassPackage.Literals.BASIC_FEATURE__TOOL_TIP)); //$NON-NLS-1$
        assertFalse(AdoptedPropertyRouter.wouldDrop(object, MdClassPackage.Literals.BASIC_FEATURE__TOOL_TIP));
    }

    @Test
    public void testAnAdoptedSubsystemsContentIsDroppedByBothExactlyWhileItsStateIsNone()
        throws ReflectiveOperationException
    {
        // Subsystem.content against SubsystemExtension.content by name, so the row cannot leave the enumeration above
        // unnoticed. EDT's writer takes no platform version, so this holds on every one: the state is part of
        // SubsystemExtension on every version (MdClassExtension.xcore: "MdPropertyState content // since 8.3.25" -
        // a comment), EDT's adopter marks it Extended (SubsystemAdopterParticipant), and only an unset or None state
        // drops the list.
        EClass subsystem = MdClassPackage.Literals.SUBSYSTEM;
        EClass extension = extensionClassOf(subsystem);
        assertEquals(MdClassExtensionPackage.Literals.SUBSYSTEM_EXTENSION, extension);
        for (MdPropertyState value : STATES)
        {
            MdObject object = object(subsystem, extension, MdClassExtensionPackage.Literals.SUBSYSTEM_EXTENSION__CONTENT,
                Belonging.ADOPTED_WITH_BLOCK, value);
            boolean dropped = value == null || value == MdPropertyState.NONE;
            assertEquals("EDT's writer, a content state " + value, Boolean.valueOf(!dropped), //$NON-NLS-1$
                Boolean.valueOf(saves(object, MdClassPackage.Literals.SUBSYSTEM__CONTENT)));
            assertEquals("the mirror, a content state " + value, Boolean.valueOf(dropped), //$NON-NLS-1$
                Boolean.valueOf(AdoptedPropertyRouter.wouldDrop(object, MdClassPackage.Literals.SUBSYSTEM__CONTENT)));
        }
    }

    // ==================== helpers ====================

    /**
     * How the object belongs, and whether it carries an extension block: a real one, one that answers
     * {@code null} for every state, or none.
     */
    private enum Belonging
    {
        ADOPTED_WITH_BLOCK, ADOPTED_WITH_A_NULL_STATE, ADOPTED_WITHOUT_BLOCK, NATIVE
    }

    private static boolean saves(EObject object, EStructuralFeature feature) throws ReflectiveOperationException
    {
        try
        {
            return ((Boolean)shouldSaveFeature.invoke(writer, object, feature)).booleanValue();
        }
        catch (InvocationTargetException e)
        {
            fail("EDT's writer threw for " + feature.getName() + ": " + e.getCause()); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    /**
     * A metadata object of {@code mdClass} whose every feature reads as set, with the given belonging and -
     * for {@link Belonging#ADOPTED_WITH_BLOCK} and {@link Belonging#NATIVE} - a real extension block of
     * {@code extensionClass} whose {@code state} feature holds {@code value} ({@code null}: left unset).
     */
    private static MdObject object(EClass mdClass, EClass extensionClass, EStructuralFeature state,
        Belonging belonging, MdPropertyState value)
    {
        MdObject object = newObject(mdClass);
        configure(object, (ObjectExtension)EcoreUtil.create(extensionClass), state, belonging, value);
        return object;
    }

    /**
     * A proxy of the interface of {@code mdClass} whose every feature reads as set and whose belonging and
     * extension block {@link #configure} sets; every other call answers the type's default. A JDK proxy, not
     * a mock: the writer asks the object only these three questions, and a mock of each of the metadata
     * interfaces costs minutes.
     */
    private static MdObject newObject(EClass mdClass)
    {
        Class<?> type = mdClass.getInstanceClass();
        return (MdObject)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
            new ObjectAnswers(mdClass.getName()));
    }

    /** What a proxied metadata object answers. */
    private static final class ObjectAnswers
        implements InvocationHandler
    {
        private final String name;
        private ObjectBelonging belonging;
        private ObjectExtension extension;

        ObjectAnswers(String name)
        {
            this.name = name;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
        {
            switch (method.getName())
            {
                case "getObjectBelonging": //$NON-NLS-1$
                    return belonging;
                case "getExtension": //$NON-NLS-1$
                    return extension;
                case "eIsSet": //$NON-NLS-1$
                    return Boolean.TRUE;
                case "hashCode": //$NON-NLS-1$
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "equals": //$NON-NLS-1$
                    return Boolean.valueOf(proxy == args[0]);
                case "toString": //$NON-NLS-1$
                    return name;
                default:
                    return defaultOf(method.getReturnType());
            }
        }

        private static Object defaultOf(Class<?> type)
        {
            if (type == boolean.class)
            {
                return Boolean.FALSE;
            }
            if (type == int.class)
            {
                return Integer.valueOf(0);
            }
            if (type == long.class)
            {
                return Long.valueOf(0L);
            }
            return null;
        }
    }

    /**
     * Sets the belonging of {@code object} and - unless it is adopted without a block - hands it
     * {@code extension}, whose {@code state} holds {@code value} ({@code null}: unset); for
     * {@link Belonging#ADOPTED_WITH_A_NULL_STATE} a block of the same class that answers {@code null} instead.
     */
    private static void configure(MdObject object, ObjectExtension extension, EStructuralFeature state,
        Belonging belonging, MdPropertyState value)
    {
        ObjectAnswers answers = (ObjectAnswers)Proxy.getInvocationHandler(object);
        answers.belonging = belonging == Belonging.NATIVE ? ObjectBelonging.NATIVE : ObjectBelonging.ADOPTED;
        if (value == null)
        {
            extension.eUnset(state);
        }
        else
        {
            extension.eSet(state, value);
        }
        if (belonging == Belonging.ADOPTED_WITHOUT_BLOCK)
        {
            answers.extension = null;
        }
        else
        {
            answers.extension = belonging == Belonging.ADOPTED_WITH_A_NULL_STATE ? nullStateBlock(extension.eClass())
                : extension;
        }
    }

    /**
     * A block of {@code extensionClass} that answers {@code null} for every state - a JDK proxy of its
     * interface. EDT's writer asks a block only its class and the state (and whether it is an extension root's),
     * so this is all a block needs to answer.
     */
    private static ObjectExtension nullStateBlock(EClass extensionClass)
    {
        Class<?> type = extensionClass.getInstanceClass();
        return (ObjectExtension)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
            (proxy, method, args) -> {
                switch (method.getName())
                {
                    case "eClass": //$NON-NLS-1$
                        return extensionClass;
                    case "hashCode": //$NON-NLS-1$
                        return Integer.valueOf(System.identityHashCode(proxy));
                    case "equals": //$NON-NLS-1$
                        return Boolean.valueOf(proxy == args[0]);
                    case "toString": //$NON-NLS-1$
                        return extensionClass.getName() + " answering null"; //$NON-NLS-1$
                    default:
                        return ObjectAnswers.defaultOf(method.getReturnType());
                }
            });
    }

    /**
     * The extension class EDT's property mapper ({@code NameBasedPropertyMapper.lookupExtensionClass}) gives a
     * metadata class: the class of the extension package named {@code <Class>Extension} (ignoring case), else
     * the one of its supertypes, nearest first.
     */
    private static EClass extensionClassOf(EClass mdClass)
    {
        for (EClassifier candidate : MdClassExtensionPackage.eINSTANCE.getEClassifiers())
        {
            if (candidate instanceof EClass && candidate.getName().equalsIgnoreCase(mdClass.getName() + "Extension")) //$NON-NLS-1$
            {
                return (EClass)candidate;
            }
        }
        List<EClass> supertypes = new ArrayList<>(mdClass.getEAllSuperTypes());
        for (int i = supertypes.size() - 1; i >= 0; i--)
        {
            EClass found = extensionClassOf(supertypes.get(i));
            if (found != null)
            {
                return found;
            }
        }
        return null;
    }

    /** Loads {@code className} from the bundle {@code symbolicName}, or {@code null} when it is not there. */
    private static Class<?> loadFromBundle(String symbolicName, String className)
    {
        Bundle self = FrameworkUtil.getBundle(AdoptedPropertyFormatterConformanceTest.class);
        if (self == null)
        {
            return null;
        }
        for (Bundle bundle : self.getBundleContext().getBundles())
        {
            if (symbolicName.equals(bundle.getSymbolicName()))
            {
                try
                {
                    return bundle.loadClass(className);
                }
                catch (ClassNotFoundException | IllegalStateException e)
                {
                    return null;
                }
            }
        }
        return null;
    }
}
