/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.platform.IEObjectProvider;

/**
 * Headless tests for {@link StandardCommandGroupResolver}. The platform catalogue is injected as a
 * stub provider shaped like the real one ({@code AbstractEObjectProvider}): an EXACT, case-sensitive
 * name map, plus a description LIST that carries the English name and the Russian {@code nameRu}
 * identifier of the same group under one shared {@code EObjectURI}.
 */
public class StandardCommandGroupResolverTest
{
    /**
     * The Russian identifier of ActionsPanelTools - the platform's own {@code nameRu} token, not the
     * localized UI string. Spelled in code points: raw Cyrillic literals are banned in this code base.
     */
    private static final String ACTIONS_PANEL_TOOLS_RU = // PanelDeystviyServis
        "\u041F\u0430\u043D\u0435\u043B\u044C\u0414\u0435\u0439\u0441\u0442\u0432\u0438\u0439" //$NON-NLS-1$
            + "\u0421\u0435\u0440\u0432\u0438\u0441"; //$NON-NLS-1$

    /**
     * The affirmative half of the merged refusal - the clause the RETIRED wording could not contain.
     * Pinning "STANDARD command group" alone proves nothing: the old "...STANDARD command groups are
     * a different, enum-addressed value space and are not supported here" carried that substring too,
     * and named the FQN form as well, so such a pin stays green on the code this change replaced.
     */
    private static final String ACCEPTS_A_BARE_STANDARD_GROUP =
        "the bare name of a platform built-in STANDARD command group"; //$NON-NLS-1$

    /** The retired claim; its absence is half of what tells the new refusal from the old one. */
    private static final String RETIRED_CLAIM = "not supported here"; //$NON-NLS-1$

    /** The Russian {@code nameRu} identifier of NavigationPanelSeeAlso, in code points. */
    private static final String NAVIGATION_PANEL_SEE_ALSO_RU = // PanelNavigatsiiSmTakzhe
        "\u041F\u0430\u043D\u0435\u043B\u044C\u041D\u0430\u0432\u0438\u0433\u0430\u0446\u0438" //$NON-NLS-1$
            + "\u0438\u0421\u043C\u0422\u0430\u043A\u0436\u0435"; //$NON-NLS-1$

    // ===== R1: the English name resolves to the platform's own proxy ==============================

    @Test
    public void testEnglishNameResolvesToTheProviderProxy()
    {
        StubProvider provider = catalogue();

        StandardCommandGroupResolver.Result result =
            StandardCommandGroupResolver.resolve(provider, "ActionsPanelTools"); //$NON-NLS-1$

        assertNull(result.error, result.error);
        assertNotNull("the English name must resolve", result.group); //$NON-NLS-1$
        assertTrue("the resolved value must be the platform's UNRESOLVED proxy", //$NON-NLS-1$
            result.group.eIsProxy());
        assertEquals("the proxy must point at the catalogue entry for that name", //$NON-NLS-1$
            uriOf("ActionsPanelTools"), //$NON-NLS-1$
            ((InternalEObject)result.group).eProxyURI());
    }

    // ===== R2: the Russian nameRu identifier resolves to the SAME group ===========================

    @Test
    public void testRussianIdentifierResolvesToTheSameGroupAsTheEnglishName()
    {
        StubProvider provider = catalogue();

        StandardCommandGroupResolver.Result russian =
            StandardCommandGroupResolver.resolve(provider, ACTIONS_PANEL_TOOLS_RU);
        StandardCommandGroupResolver.Result english =
            StandardCommandGroupResolver.resolve(provider, "ActionsPanelTools"); //$NON-NLS-1$

        assertNull(russian.error, russian.error);
        assertNotNull("the Russian identifier must resolve", russian.group); //$NON-NLS-1$
        assertEquals("both identifiers address ONE group, so both proxies carry its URI", //$NON-NLS-1$
            ((InternalEObject)english.group).eProxyURI(),
            ((InternalEObject)russian.group).eProxyURI());
    }

    // ===== R3: case-insensitive, and NOT through the exact-name fast path =========================

    @Test
    public void testLowercaseNameResolvesThroughTheCaseInsensitivePass()
    {
        StubProvider provider = catalogue();

        StandardCommandGroupResolver.Result result =
            StandardCommandGroupResolver.resolve(provider, "actionspaneltools"); //$NON-NLS-1$

        assertNull(result.error, result.error);
        assertNotNull("a lowercase spelling must still resolve", result.group); //$NON-NLS-1$
        assertEquals(uriOf("ActionsPanelTools"), ((InternalEObject)result.group).eProxyURI()); //$NON-NLS-1$
        // The platform index is an EXACT, case-sensitive map: it answered null for this spelling, so
        // the answer above can only have come from the case-insensitive pass over the catalogue.
        assertTrue("precondition: the exact-name lookup was tried", //$NON-NLS-1$
            provider.exactLookups.contains("actionspaneltools")); //$NON-NLS-1$
        assertNull("precondition: the exact-name lookup answered nothing for this spelling", //$NON-NLS-1$
            provider.getProxy("actionspaneltools")); //$NON-NLS-1$
    }

    @Test
    public void testRussianIdentifierIsAlsoCaseInsensitive()
    {
        StubProvider provider = catalogue();

        StandardCommandGroupResolver.Result result = StandardCommandGroupResolver.resolve(
            provider, ACTIONS_PANEL_TOOLS_RU.toLowerCase(Locale.ROOT));

        assertNull(result.error, result.error);
        assertNotNull("a lowercase Russian identifier must still resolve", result.group); //$NON-NLS-1$
        assertEquals(uriOf("ActionsPanelTools"), ((InternalEObject)result.group).eProxyURI()); //$NON-NLS-1$
    }

    // ===== R4: an unknown token is an actionable refusal, never an exception ======================

    @Test
    public void testUnknownTokenNamesTheTokenAndListsTheValidNames()
    {
        StandardCommandGroupResolver.Result result =
            StandardCommandGroupResolver.resolve(catalogue(), "NotAGroupAtAll"); //$NON-NLS-1$

        assertNull("an unknown token resolves to nothing", result.group); //$NON-NLS-1$
        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("NotAGroupAtAll")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("ActionsPanelTools")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains(ACTIONS_PANEL_TOOLS_RU));
        assertTrue(result.error, result.error.contains("NavigationPanelSeeAlso")); //$NON-NLS-1$
        assertTrue("both accepted forms are named", //$NON-NLS-1$
            result.error.contains("CommandGroup.<Name>")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains(ACCEPTS_A_BARE_STANDARD_GROUP));
    }

    @Test
    public void testStandardNamesPairEachEnglishNameWithItsRussianIdentifierOncePerGroup()
    {
        List<String> names = StandardCommandGroupResolver.standardNames(catalogue());

        assertEquals("one entry per GROUP, not per index key", 2, names.size()); //$NON-NLS-1$
        assertEquals("ActionsPanelTools (" + ACTIONS_PANEL_TOOLS_RU + ")", //$NON-NLS-1$ //$NON-NLS-2$
            names.get(0));
        assertEquals("NavigationPanelSeeAlso (" //$NON-NLS-1$
            + NAVIGATION_PANEL_SEE_ALSO_RU + ")", names.get(1)); //$NON-NLS-1$
    }

    // ===== R5: no provider / a provider that throws is an error, never an exception ===============

    @Test
    public void testNoProviderIsAnActionableErrorRatherThanAnException()
    {
        StandardCommandGroupResolver.Result result =
            StandardCommandGroupResolver.resolve(null, "ActionsPanelTools"); //$NON-NLS-1$

        assertNull(result.group);
        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("ActionsPanelTools")); //$NON-NLS-1$
        assertTrue("the refusal still names both accepted forms", //$NON-NLS-1$
            result.error.contains("CommandGroup.<Name>")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains(ACCEPTS_A_BARE_STANDARD_GROUP));
        assertFalse(result.error, result.error.contains(RETIRED_CLAIM));
        assertEquals("an unreachable catalogue lists nothing rather than throwing", //$NON-NLS-1$
            List.of(), StandardCommandGroupResolver.standardNames(null));
    }

    @Test
    public void testAThrowingProviderIsAnActionableErrorRatherThanAnException()
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        Mockito.doThrow(new IllegalStateException("catalogue unavailable")) //$NON-NLS-1$
            .when(provider).getProxy(Mockito.anyString());
        Mockito.doThrow(new IllegalStateException("catalogue unavailable")) //$NON-NLS-1$
            .when(provider).getEObjectDescriptions(Mockito.any());

        StandardCommandGroupResolver.Result result =
            StandardCommandGroupResolver.resolve(provider, "ActionsPanelTools"); //$NON-NLS-1$

        assertNull(result.group);
        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("ActionsPanelTools")); //$NON-NLS-1$
        assertEquals("a throwing catalogue lists nothing rather than throwing", //$NON-NLS-1$
            List.of(), StandardCommandGroupResolver.standardNames(provider));
    }

    @Test
    public void testAnEmptyCatalogueStillOffersBothAcceptedForms()
    {
        String hint = StandardCommandGroupResolver.addressingHint(null);

        assertTrue(hint, hint.contains("CommandGroup.<Name>")); //$NON-NLS-1$
        // The affirmative clause, not the words "STANDARD command group": the retired wording carried
        // those while saying the exact opposite, so pinning them would pass on the old text.
        assertTrue(hint, hint.contains(ACCEPTS_A_BARE_STANDARD_GROUP));
        assertFalse(hint, hint.contains(RETIRED_CLAIM));
    }

    // ===== R6: the value is a PROVIDER PROXY, never a factory object ==============================

    /**
     * The pin against the platform trap recorded for this server: a detached object built by
     * {@code McoreFactory} has no BM namespace and no resource, so the transaction's
     * {@code ReferenceValueFactory} cannot build a persistable reference to it and the commit dies
     * with "Failed to persist reference value ...Impl@hash". Only an UNRESOLVED proxy is persistable
     * - which is exactly what EDT's own command inferrer assigns. This test must go red the moment
     * the resolver hands back anything that is not a proxy.
     */
    @Test
    public void testTheResolvedValueIsAnUnresolvedProxyNotAFactoryObject()
    {
        StandardCommandGroupResolver.Result result =
            StandardCommandGroupResolver.resolve(catalogue(), "ActionsPanelTools"); //$NON-NLS-1$

        assertNotNull("precondition: the token resolves at all", result.group); //$NON-NLS-1$
        assertTrue("a persistable command-group value is an UNRESOLVED proxy", //$NON-NLS-1$
            result.group.eIsProxy());
        assertNotNull("a proxy carries the catalogue URI it stands for", //$NON-NLS-1$
            ((InternalEObject)result.group).eProxyURI());
        assertFalse("a McoreFactory-built group is NOT a proxy - the control for this pin", //$NON-NLS-1$
            McoreFactory.eINSTANCE.createStandardCommandGroup().eIsProxy());
    }

    @Test
    public void testANonProxyCatalogueAnswerIsRefusedRatherThanQueued()
    {
        // A catalogue that hands back a live, attached object is refused: queueing it would move the
        // failure to commit time, where it surfaces as an opaque BmAssertionException.
        StubProvider provider = new StubProvider();
        provider.addResolvedGroup("ActionsPanelTools"); //$NON-NLS-1$

        StandardCommandGroupResolver.Result result =
            StandardCommandGroupResolver.resolve(provider, "ActionsPanelTools"); //$NON-NLS-1$

        assertNull("a non-proxy answer must not be queued", result.group); //$NON-NLS-1$
        assertNotNull(result.error);
    }

    // ---- stub catalogue -------------------------------------------------------------------------

    private static StubProvider catalogue()
    {
        StubProvider provider = new StubProvider();
        provider.addGroup("ActionsPanelTools", ACTIONS_PANEL_TOOLS_RU); //$NON-NLS-1$
        provider.addGroup("NavigationPanelSeeAlso", NAVIGATION_PANEL_SEE_ALSO_RU); //$NON-NLS-1$
        return provider;
    }

    private static URI uriOf(String englishName)
    {
        return URI.createURI("v8:/CommandGroups").appendFragment(englishName); //$NON-NLS-1$
    }

    /**
     * The shape of the real {@code AbstractEObjectProvider}: an EXACT name map (both the English name
     * and the Russian {@code nameRu} are keys of the SAME entry) plus an ordered description list in
     * which the English description precedes the Russian one, and both share one {@code EObjectURI}.
     */
    private static final class StubProvider
        implements IEObjectProvider
    {
        final List<String> exactLookups = new ArrayList<>();

        private final List<IEObjectDescription> descriptions = new ArrayList<>();

        void addGroup(String englishName, String russianName)
        {
            URI uri = uriOf(englishName);
            descriptions.add(description(englishName, uri, true));
            descriptions.add(description(russianName, uri, true));
        }

        /** A catalogue entry whose value is a live object rather than a proxy (the R6 control). */
        void addResolvedGroup(String englishName)
        {
            descriptions.add(description(englishName, uriOf(englishName), false));
        }

        private static IEObjectDescription description(String name, URI uri, boolean asProxy)
        {
            IEObjectDescription desc = Mockito.mock(IEObjectDescription.class);
            Mockito.doReturn(QualifiedName.create(name)).when(desc).getName();
            Mockito.doReturn(uri).when(desc).getEObjectURI();
            Mockito.doReturn(McorePackage.Literals.STANDARD_COMMAND_GROUP).when(desc).getEClass();
            Mockito.doAnswer(invocation -> {
                EObject value = EcoreUtil.create(McorePackage.Literals.STANDARD_COMMAND_GROUP);
                if (asProxy)
                {
                    ((InternalEObject)value).eSetProxyURI(uri);
                }
                return value;
            }).when(desc).getEObjectOrProxy();
            return desc;
        }

        @Override
        public org.eclipse.emf.ecore.EClass getEClass()
        {
            return McorePackage.Literals.COMMAND_GROUP;
        }

        @Override
        public URI getUri(String name)
        {
            IEObjectDescription desc = getEObjectDescription(name);
            return desc == null ? null : desc.getEObjectURI();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends EObject> T createProxy(String name)
        {
            IEObjectDescription desc = getEObjectDescription(name);
            if (desc == null)
            {
                throw new IllegalArgumentException(name);
            }
            return (T)desc.getEObjectOrProxy();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends EObject> T getProxy(String name)
        {
            exactLookups.add(name);
            IEObjectDescription desc = getEObjectDescription(name);
            return desc == null ? null : (T)desc.getEObjectOrProxy();
        }

        @Override
        public Iterable<IEObjectDescription> getEObjectDescriptions(
            com.google.common.base.Predicate<IEObjectDescription> filter)
        {
            return descriptions;
        }

        @Override
        public IEObjectDescription getEObjectDescription(String name)
        {
            for (IEObjectDescription desc : descriptions)
            {
                if (desc.getName().toString().equals(name))
                {
                    return desc;
                }
            }
            return null;
        }

        @Override
        public void collectResources(java.util.Collection<URI> resources)
        {
            // nothing to collect in a stub catalogue
        }
    }
}
