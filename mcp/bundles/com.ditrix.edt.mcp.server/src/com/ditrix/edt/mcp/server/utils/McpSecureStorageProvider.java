/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import javax.crypto.spec.PBEKeySpec;

import org.eclipse.equinox.security.storage.provider.IPreferencesContainer;
import org.eclipse.equinox.security.storage.provider.PasswordProvider;

/**
 * Supplies a stable master password for the Eclipse default secure storage so that EDT's
 * {@code IInfobaseAccessManager.updateSettings} (used by {@code set_infobase_credentials} and
 * {@code create_infobase}) never raises the blocking <b>"Secure Storage — please enter a new master
 * password"</b> dialog on a fresh / headless stand (issue #194). On such a stand writing the infobase
 * connection credentials initializes the Eclipse keyring, which otherwise prompts for a master password
 * and hangs the unattended call.
 *
 * <p>Registered via the {@code org.eclipse.equinox.security.secureStorage} extension at a priority just
 * above the platform's interactive {@code DefaultPasswordProvider} (priority 2) — so on a headless stand
 * where the prompt is the only alternative this provider wins, while on a desktop the OS keyring providers
 * (Windows DPAPI / GNOME keyring), which sit at higher priorities, keep handling secrets.
 *
 * <p><b>Safe by construction.</b> Equinox stamps each encrypted value with the moduleID of the provider
 * that wrote it and decrypts via that same provider (by moduleID, NOT by priority), so this provider only
 * ever owns the values it itself wrote — a user's existing entries keep decrypting with their own provider
 * and cannot be corrupted by this one.
 */
public final class McpSecureStorageProvider extends PasswordProvider
{
    /**
     * Master password for the LOCAL Eclipse keyring — NOT an infobase user password. A fixed constant so
     * the keyring re-opens every session without a prompt; on a trusted-caller server the keyring only
     * protects infobase connection settings.
     */
    private static final String MASTER = "edt-mcp-unattended-secure-storage-v1"; //$NON-NLS-1$

    @Override
    public PBEKeySpec getPassword(IPreferencesContainer container, int passwordType)
    {
        // Always supply the stable password: the moduleID design (above) means we only ever own the
        // values we encrypt, so this cannot affect a user's existing secure-storage entries, and on a
        // desktop the higher-priority OS keyring providers are chosen ahead of us anyway.
        return new PBEKeySpec(MASTER.toCharArray());
    }

    @Override
    public boolean retryOnError(Exception e, IPreferencesContainer container)
    {
        return false; // a wrong stable password must not loop
    }
}
