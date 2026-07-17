package io.okdocs.compliance.api.config;

import io.okdocs.compliance.contracts.enums.UserPlan;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

/** Product entitlements for recurring site monitoring. */
@ConfigurationProperties(prefix = "compliance.monitoring")
public record MonitoringProperties(Map<UserPlan, Integer> maxMonitors) {
    public MonitoringProperties {
        Map<UserPlan, Integer> defaults = new EnumMap<>(UserPlan.class);
        defaults.put(UserPlan.FREE, 0);
        defaults.put(UserPlan.PRO, 2);
        defaults.put(UserPlan.BUSINESS, 10);
        if (maxMonitors != null) {
            defaults.putAll(maxMonitors);
        }
        maxMonitors = Map.copyOf(defaults);
    }

    public int maxMonitorsFor(UserPlan plan) {
        return maxMonitors.getOrDefault(plan, 0);
    }
}
