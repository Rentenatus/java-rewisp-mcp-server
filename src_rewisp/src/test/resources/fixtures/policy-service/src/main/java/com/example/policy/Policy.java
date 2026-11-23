package com.example.policy;

/**
 * Policy-Klasse - z.B. für Versicherungs-Policies.
 */
public class Policy {

    private String policyNumber;
    private String customerName;
    private String startDate;

    public Policy(String policyNumber, String customerName, String startDate) {
        this.policyNumber = policyNumber;
        this.customerName = customerName;
        this.startDate = startDate;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public String getCustomerName() {
        return customerName;
    }

    /**
     * Alte Methode - soll durch Recipe umbenannt werden.
     */
    public String getStartDate() {
        return startDate;
    }

    /**
     * Neue Methode - Ziel für Recipe.
     */
    public String getContractStart() {
        return startDate;
    }

    @Override
    public String toString() {
        return "Policy{policyNumber='" + policyNumber + "', customerName='" + 
               customerName + "', startDate='" + startDate + "'}";
    }
}
