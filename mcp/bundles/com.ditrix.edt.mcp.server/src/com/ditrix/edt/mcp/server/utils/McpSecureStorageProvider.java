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
 * Supplies an <b>empty</b> master password for the Eclipse default secure storage so that EDT's
 * {@code IInfobaseAccessManager.updateSettings} (used by {@code set_infobase_credentials} and
 * {@code create_infobase}) never raises the blocking <b>"Secure Storage — please enter a new master
 * password"</b> dialog on a fresh / headless stand (issue #194). On such a stand writing the infobase
 * connection credentials initializes the Eclipse keyring, which otherwise prompts for a master password
 * and hangs the unattended call.
 *
 * <p><b>No master password by design.</b> On a trusted-caller server the local keyring only protects
 * infobase connection settings, which are re-settable; there is nothing to defend against a local
 * attacker that filesystem access would not already expose. So instead of inventing a secret we supply
 * an empty passphrase — there is no credential literal in source (an empty value is not a secret, so
 * S6437 does not apply).
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
    @Override
    public PBEKeySpec getPassword(IPreferencesContainer container, int passwordType)
    {
        // No master password: supply an empty passphrase so the keyring opens unattended without a
        // prompt and no secret literal lives in source.
        return new PBEKeySpec(new char[0]);
    }

    @Override
    public boolean retryOnError(Exception e, IPreferencesContainer container)
    {
        return false; // the empty password is fixed; retrying cannot help
    }
}
