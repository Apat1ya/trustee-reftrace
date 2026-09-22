package dev.reftrace.browse;

import tools.jackson.databind.EnumNamingStrategies;
import tools.jackson.databind.annotation.EnumNaming;

@EnumNaming(EnumNamingStrategies.LowerCamelCaseStrategy.class)
public enum How {
    HREF,
    CLICK,
    QR
}
