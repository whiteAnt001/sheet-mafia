package org.whiteant.sheetmafia.config;

import java.security.Principal;

public record StompPrincipal(String name) implements Principal {

    @Override
    public String getName() {
        return name;
    }
}
