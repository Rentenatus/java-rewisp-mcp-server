package com.example.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * Service-Klasse für Policy-Verwaltung.
 */
public class PolicyService {

    private final List<Policy> policies = new ArrayList<>();

    public void addPolicy(Policy policy) {
        policies.add(policy);
    }

    public List<Policy> getAllPolicies() {
        return new ArrayList<>(policies);
    }

    /**
     * Methode, die alte API verwendet - soll nach Transformation nicht mehr existieren.
     */
    public String getFirstPolicyStartDate() {
        if (!policies.isEmpty()) {
            return policies.get(0).getStartDate(); // Alte Methode
        }
        return null;
    }

    /**
     * Methode, die neue API verwendet.
     */
    public String getFirstPolicyContractStart() {
        if (!policies.isEmpty()) {
            return policies.get(0).getContractStart(); // Neue Methode
        }
        return null;
    }
}
