/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool to get 1C:EDT version.
 */
public class GetEdtVersionTool implements IMcpTool
{
    public static final String NAME = "get_edt_version"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Returns the running 1C:EDT version as a plain version string." //$NON-NLS-1$
            + " Returns \"Unknown\" when the version cannot be determined."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.TEXT;
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object().build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        return getEdtVersion();
    }
    
    /**
     * Returns the EDT version.
     * 
     * @return EDT version or "Unknown" if cannot be determined
     */
    public static String getEdtVersion()
    {
        try
        {
            // Method 1: via eclipse.buildId system property (e.g., "2025.2.0.454")
            String buildId = System.getProperty("eclipse.buildId"); //$NON-NLS-1$
            if (buildId != null && !buildId.isEmpty())
            {
                return buildId;
            }

            // Method 2: via Eclipse product
            String productVersion = versionFromProduct();
            if (productVersion != null)
            {
                return productVersion;
            }

            // Methods 3 & 4: search the installed bundles for an EDT bundle
            String bundleVersion = versionFromBundles();
            if (bundleVersion != null)
            {
                return bundleVersion;
            }

            // Method 5: via system property
            String product = System.getProperty("eclipse.product"); //$NON-NLS-1$
            if (product != null)
            {
                return product;
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to get EDT version", e); //$NON-NLS-1$
        }

        return "Unknown"; //$NON-NLS-1$
    }

    /**
     * Resolves the version string from the running Eclipse product's defining bundle.
     *
     * @return the (possibly marketing-formatted) version, or {@code null} when no product
     *     or defining bundle is available
     */
    private static String versionFromProduct()
    {
        if (Platform.getProduct() != null)
        {
            Bundle productBundle = Platform.getProduct().getDefiningBundle();
            if (productBundle != null)
            {
                return formatVersion(productBundle.getVersion().toString());
            }
        }
        return null;
    }

    /**
     * Searches the installed bundles for an EDT bundle: first the EDT RCP bundle
     * ({@code com._1c.g5.v8.dt.rcp}), then any bundle whose symbolic name starts with
     * {@code com._1c.g5.v8.dt}.
     *
     * @return the (possibly marketing-formatted) version of the first matching bundle,
     *     or {@code null} when none is found
     */
    private static String versionFromBundles()
    {
        Bundle coreBundle = Platform.getBundle("org.eclipse.core.runtime"); //$NON-NLS-1$
        if (coreBundle == null || coreBundle.getBundleContext() == null)
        {
            return null;
        }
        Bundle[] bundles = coreBundle.getBundleContext().getBundles();

        // Method 3: search for EDT RCP bundle
        for (Bundle bundle : bundles)
        {
            String symbolicName = bundle.getSymbolicName();
            if (symbolicName != null && symbolicName.equals("com._1c.g5.v8.dt.rcp")) //$NON-NLS-1$
            {
                return formatVersion(bundle.getVersion().toString());
            }
        }

        // Method 4: search for any EDT bundle
        for (Bundle bundle : bundles)
        {
            String symbolicName = bundle.getSymbolicName();
            if (symbolicName != null && symbolicName.startsWith("com._1c.g5.v8.dt")) //$NON-NLS-1$
            {
                return formatVersion(bundle.getVersion().toString());
            }
        }
        return null;
    }

    /**
     * Formats an OSGi version: when a marketing version can be derived, returns
     * {@code "<marketing> (<version>)"}; otherwise returns the raw version unchanged.
     *
     * @param version the raw OSGi version string
     * @return the formatted version string
     */
    private static String formatVersion(String version)
    {
        String marketingVersion = convertToMarketingVersion(version);
        if (marketingVersion != null)
        {
            return marketingVersion + " (" + version + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return version;
    }
    
    /**
     * Converts OSGi bundle version to marketing version.
     * 
     * @param version OSGi version string
     * @return marketing version or null if cannot convert
     */
    private static String convertToMarketingVersion(String version)
    {
        try
        {
            // Parse qualifier: v202512221402 -> YYYYMMDDHHMI
            int qualifierIdx = version.indexOf(".v"); //$NON-NLS-1$
            if (qualifierIdx > 0 && version.length() > qualifierIdx + 6)
            {
                String qualifier = version.substring(qualifierIdx + 2);
                if (qualifier.length() >= 6)
                {
                    String year = qualifier.substring(0, 4);
                    String month = qualifier.substring(4, 6);
                    int monthNum = Integer.parseInt(month);
                    int release = monthNum <= 6 ? 1 : 2;
                    return year + "." + release + ".0"; //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        catch (Exception e)
        {
            // Ignore parsing errors
        }
        return null;
    }
}
