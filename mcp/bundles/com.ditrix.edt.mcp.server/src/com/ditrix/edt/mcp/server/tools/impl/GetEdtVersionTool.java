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
        return "Get 1C:EDT version"; //$NON-NLS-1$
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
            if (Platform.getProduct() != null)
            {
                Bundle productBundle = Platform.getProduct().getDefiningBundle();
                if (productBundle != null)
                {
                    String version = productBundle.getVersion().toString();
                    String marketingVersion = convertToMarketingVersion(version);
                    if (marketingVersion != null)
                    {
                        return marketingVersion + " (" + version + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    return version;
                }
            }
            
            // Method 3: search for EDT RCP bundle
            Bundle coreBundle = Platform.getBundle("org.eclipse.core.runtime"); //$NON-NLS-1$
            if (coreBundle != null && coreBundle.getBundleContext() != null)
            {
                Bundle[] bundles = coreBundle.getBundleContext().getBundles();
                for (Bundle bundle : bundles)
                {
                    String symbolicName = bundle.getSymbolicName();
                    if (symbolicName != null && symbolicName.equals("com._1c.g5.v8.dt.rcp")) //$NON-NLS-1$
                    {
                        String version = bundle.getVersion().toString();
                        String marketingVersion = convertToMarketingVersion(version);
                        if (marketingVersion != null)
                        {
                            return marketingVersion + " (" + version + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        return version;
                    }
                }
                
                // Method 4: search for any EDT bundle
                for (Bundle bundle : bundles)
                {
                    String symbolicName = bundle.getSymbolicName();
                    if (symbolicName != null && symbolicName.startsWith("com._1c.g5.v8.dt")) //$NON-NLS-1$
                    {
                        String version = bundle.getVersion().toString();
                        String marketingVersion = convertToMarketingVersion(version);
                        if (marketingVersion != null)
                        {
                            return marketingVersion + " (" + version + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        return version;
                    }
                }
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
