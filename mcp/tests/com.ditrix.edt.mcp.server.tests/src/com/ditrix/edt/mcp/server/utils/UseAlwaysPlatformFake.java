/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.DataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.PropertyInfo;
import com._1c.g5.v8.dt.form.service.attribute.IUseAlwaysAttributeService;
import com._1c.g5.v8.dt.form.service.datasourceinfo.IDataSourceInfoAssociationService;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;

/**
 * A stand-in for EDT's two form services behind {@link UseAlwaysSupport}: a data tree of registered
 * fields (an English and a Russian spelling each, resolved case-insensitively like the platform) and a
 * checkbox service whose read, add and remove follow EDT's {@code UseAlwaysAttributeService} - the
 * default is whatever the test registers, never a rule of ours.
 */
public final class UseAlwaysPlatformFake
{
    private static final Supplier<UseAlwaysSupport.Platform> PRODUCTION = UseAlwaysSupport.platformSource;

    private final Map<String, PropertyInfo> byPath = new HashMap<>();
    private final Map<PropertyInfo, IUseAlwaysAttributeService.UseAlways> defaults = new IdentityHashMap<>();
    private final Map<PropertyInfo, List<String>> english = new IdentityHashMap<>();
    private final List<String> setCalls = new ArrayList<>();
    private final UseAlwaysSupport.Platform platform;

    /** A fake with an empty data tree. */
    public UseAlwaysPlatformFake()
    {
        IDataSourceInfoAssociationService dataSources = mock(IDataSourceInfoAssociationService.class);
        when(dataSources.findPropertyInfo(any(Form.class), any(AbstractDataPath.class))).thenAnswer(
            call -> lookup(((AbstractDataPath)call.getArgument(1)).getSegments(), Integer.MAX_VALUE));
        when(dataSources.findPropertyInfo(any(Form.class), any(AbstractDataPath.class), anyInt())).thenAnswer(
            call -> lookup(((AbstractDataPath)call.getArgument(1)).getSegments(), (Integer)call.getArgument(2)));
        platform = new UseAlwaysSupport.Platform(dataSources, new Service());
    }

    /**
     * Registers a field of the data tree.
     *
     * @param en the English path, e.g. {@code Object.Code}
     * @param ru the Russian path, or {@code null} for the same spelling
     * @param def the checkbox default EDT gives it
     * @return the field's info
     */
    public PropertyInfo field(String en, String ru, IUseAlwaysAttributeService.UseAlways def)
    {
        List<String> enSegments = Arrays.asList(en.split("\\.")); //$NON-NLS-1$
        String ruPath = ru == null ? en : ru;
        PropertyInfo info = mock(PropertyInfo.class);
        when(info.getDataPath(ScriptVariant.ENGLISH)).thenAnswer(call -> dataPath(en));
        when(info.getDataPath(ScriptVariant.RUSSIAN)).thenAnswer(call -> dataPath(ruPath));
        when(info.getName()).thenReturn(enSegments.get(enSegments.size() - 1));
        when(info.getPropertyInfos()).thenAnswer(call -> childrenOf(enSegments));
        byPath.put(en.toLowerCase(Locale.ROOT), info);
        byPath.put(ruPath.toLowerCase(Locale.ROOT), info);
        defaults.put(info, def);
        english.put(info, enSegments);
        return info;
    }

    /** Makes {@link UseAlwaysSupport} use this fake. */
    public void install()
    {
        UseAlwaysSupport.platformSource = () -> platform;
    }

    /**
     * Makes the service lookup fail the way {@code ServiceAccess.get} does.
     *
     * @param message the lookup failure
     */
    public static void installUnavailable(String message)
    {
        UseAlwaysSupport.platformSource = () -> {
            throw new IllegalStateException(message);
        };
    }

    /** Restores the OSGi lookup. */
    public static void uninstall()
    {
        UseAlwaysSupport.platformSource = PRODUCTION;
    }

    /**
     * The platform setter calls so far, as {@code "<en path>=<checked>"}.
     *
     * @return the calls
     */
    public List<String> setCalls()
    {
        return setCalls;
    }

    /**
     * A data path.
     *
     * @param joined the dotted segments
     * @return the path
     */
    public static DataPath dataPath(String joined)
    {
        DataPath path = FormFactory.eINSTANCE.createDataPath();
        path.getSegments().addAll(Arrays.asList(joined.split("\\."))); //$NON-NLS-1$
        return path;
    }

    private PropertyInfo lookup(List<String> segments, int lastIndex)
    {
        int end = lastIndex >= segments.size() - 1 ? segments.size() : lastIndex + 1;
        return byPath.get(String.join(".", segments.subList(0, end)).toLowerCase(Locale.ROOT)); //$NON-NLS-1$
    }

    private List<PropertyInfo> childrenOf(List<String> parent)
    {
        List<PropertyInfo> children = new ArrayList<>();
        for (Map.Entry<PropertyInfo, List<String>> e : english.entrySet())
        {
            List<String> path = e.getValue();
            if (path.size() == parent.size() + 1 && path.subList(0, parent.size()).equals(parent))
            {
                children.add(e.getKey());
            }
        }
        return children;
    }

    /** EDT's UseAlwaysAttributeService, with the default taken from the registration. */
    private final class Service implements IUseAlwaysAttributeService
    {
        @Override
        public UseAlways getDefaultValue(PropertyInfo info, Form form)
        {
            UseAlways def = defaults.get(info);
            return def == null ? UseAlways.None : def;
        }

        @Override
        public UseAlways getUseAlwaysValue(PropertyInfo info, Form form)
        {
            UseAlways def = getDefaultValue(info, form);
            if (def == UseAlways.None)
            {
                return UseAlways.None;
            }
            if (contains(root(info, form), info))
            {
                return def == UseAlways.Checked ? UseAlways.Unchecked : UseAlways.Checked;
            }
            return def;
        }

        @Override
        public void setUseAlwaysValue(PropertyInfo info, boolean checked, Form form)
        {
            setCalls.add(String.join(".", english.get(info)) + "=" + checked); //$NON-NLS-1$ //$NON-NLS-2$
            UseAlways def = getDefaultValue(info, form);
            if (def == UseAlways.None)
            {
                return;
            }
            FormAttribute attribute = root(info, form);
            if (checked && def == UseAlways.Unchecked || !checked && def == UseAlways.Checked)
            {
                if (!contains(attribute, info))
                {
                    attribute.getNotDefaultUseAlwaysAttributes().add(EcoreUtil.copy(info.getDataPath(ScriptVariant.ENGLISH)));
                }
            }
            else
            {
                attribute.getNotDefaultUseAlwaysAttributes().removeIf(path -> matches(path, info));
            }
        }

        @Override
        public void clear(FormAttribute attribute, Form form)
        {
            attribute.getNotDefaultUseAlwaysAttributes().clear();
        }

        private FormAttribute root(PropertyInfo info, Form form)
        {
            String name = english.get(info).get(0);
            for (FormAttribute attribute : form.getAttributes())
            {
                if (name.equals(attribute.getName()))
                {
                    return attribute;
                }
            }
            throw new IllegalStateException("no attribute " + name); //$NON-NLS-1$
        }

        private boolean contains(FormAttribute attribute, PropertyInfo info)
        {
            return attribute.getNotDefaultUseAlwaysAttributes().stream().anyMatch(path -> matches(path, info));
        }

        /** DatapathUtil.isEqualDataPath against both spellings: the ACTIVE segments, compared exactly. */
        private boolean matches(AbstractDataPath path, PropertyInfo info)
        {
            List<String> segments = path.getSegments();
            return segments != null && (segments.equals(info.getDataPath(ScriptVariant.ENGLISH).getSegments())
                || segments.equals(info.getDataPath(ScriptVariant.RUSSIAN).getSegments()));
        }
    }
}
