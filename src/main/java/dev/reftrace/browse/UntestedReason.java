package dev.reftrace.browse;

import tools.jackson.databind.EnumNamingStrategies;
import tools.jackson.databind.annotation.EnumNaming;

@EnumNaming(EnumNamingStrategies.LowerCamelCaseStrategy.class)
public enum UntestedReason {
    QR_UNREADABLE,
    QR_HIDDEN,
    CLICK_NO_NAVIGATION,
    CLICK_FAILED,
    LINK_NOT_FOUND,
    DOM_CHANGED,
    PAGE_LOAD_TIMEOUT,
    NAVIGATION_ERROR,
    HTTP_STATUS,
    BROWSER_CRASH,
    INTERNAL;

    public boolean bySite() {
        return switch (this) {
            case HTTP_STATUS, QR_UNREADABLE, QR_HIDDEN -> true;
            case CLICK_NO_NAVIGATION, CLICK_FAILED, LINK_NOT_FOUND, DOM_CHANGED, PAGE_LOAD_TIMEOUT,
                 NAVIGATION_ERROR, BROWSER_CRASH, INTERNAL -> false;
        };
    }
}
