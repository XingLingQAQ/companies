/*
 *     SPDX-License-Identifier: AGPL-3.0-only
 *
 *     Copyright (C EldoriaRPG Team and Contributor
 */
package de.eldoria.companies.commands;

import de.eldoria.companies.commands.companyadmin.*;
import de.eldoria.companies.configuration.Configuration;
import de.eldoria.companies.data.repository.ACompanyData;
import de.eldoria.companies.services.LevelService;
import de.eldoria.companies.util.Permission;
import de.eldoria.eldoutilities.commands.command.AdvancedCommand;
import de.eldoria.eldoutilities.commands.command.CommandMeta;
import de.eldoria.eldoutilities.commands.defaultcommands.DefaultDebug;
import de.eldoria.messageblocker.blocker.MessageBlocker;
import org.bukkit.plugin.Plugin;

public class CompanyAdmin extends AdvancedCommand {
    public CompanyAdmin(Plugin plugin, Configuration configuration, ACompanyData companyData,
                        MessageBlocker messageBlocker, LevelService levelService) {
        super(plugin, CommandMeta.builder("companyadmin")
                .withSubCommand(new Level(plugin, configuration, messageBlocker))
                .withSubCommand(new CalcLevel(plugin, levelService))
                .withSubCommand(new Delete(plugin, companyData))
                .withSubCommand(new Reload(plugin, configuration))
                .withSubCommand(new TransferOwner(plugin, companyData))
                .withSubCommand(new Rename(plugin, companyData))
                .withSubCommand(new DefaultDebug(plugin, Permission.Admin.ADMIN))
                .build());
    }
}
