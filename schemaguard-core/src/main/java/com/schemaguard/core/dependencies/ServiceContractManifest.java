package com.schemaguard.core.dependencies;

import java.util.List;
import java.util.Map;

public record ServiceContractManifest(
    int version,
    Map<String, ServiceDefinition> services
) {
    public record ServiceDefinition(
        List<TableReadContract> reads
    ) {}

    public record TableReadContract(
        String table,
        List<String> columns
    ) {}
}