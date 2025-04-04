/*
 *     SPDX-License-Identifier: AGPL-3.0-only
 *
 *     Copyright (C EldoriaRPG Team and Contributor
 */
package de.eldoria.companies.configuration.elements;

import de.eldoria.companies.configuration.elements.generalsettings.Hooks;

@SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal", "RedundantNoArgConstructor", "CanBeFinal"})
public class GeneralSettings {
    private String language = "en_US";
    private int orderCheckInterval = 60;
    private boolean checkUpdates = true;
    private boolean messageBlocking = true;
    private Hooks hooks = new Hooks();

    public GeneralSettings() {
    }

    public int orderCheckInterval() {
        return orderCheckInterval;
    }

    public String language() {
        return language;
    }

    public boolean checkUpdates() {
        return checkUpdates;
    }

    public Hooks hooks() {
        return hooks;
    }

    public boolean messageBlocking() {
        return messageBlocking;
    }
}
