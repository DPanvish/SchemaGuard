package com.schemaguard.core.rules;

public record TableProfile(
    String fullyQualifiedTable,
    SizeClass sizeClass,
    boolean productionCritical,
    WriteRateClass writeRateClass,
    boolean hasMaintenanceWindow
) {
    public enum SizeClass {
        SMALL,
        MEDIUM,
        LARGE,
        UNKNOWN
    }

    public enum WriteRateClass {
        LOW,
        MODERATE,
        HIGH,
        UNKNOWN
    }

    public static TableProfile unknown(String fullyQualifiedTable) {
        return new TableProfile(
                fullyQualifiedTable,
                SizeClass.UNKNOWN,
                false,
                WriteRateClass.UNKNOWN,
                false
        );
    }

    public boolean isLargeOrUnknownOrCritical() {
        return productionCritical || sizeClass == SizeClass.LARGE || sizeClass == SizeClass.UNKNOWN;
    }
}