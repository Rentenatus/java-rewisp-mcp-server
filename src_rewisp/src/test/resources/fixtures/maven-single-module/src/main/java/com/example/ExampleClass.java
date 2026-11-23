package com.example;

/**
 * Beispielklasse für Integrationstests.
 * Enthält Methoden, die durch Recipes umbenannt werden können.
 */
public class ExampleClass {

    private String oldField = "old";
    private String newField = "new";

    /**
     * Alte Methode - kann durch RenameMethodRecipe umbenannt werden.
     */
    public String getStartDate() {
        return oldField;
    }

    /**
     * Neue Methode - Ziel für RenameMethodRecipe.
     */
    public String getContractStart() {
        return newField;
    }

    /**
     * Methode mit alter API - soll nach Transformation nicht mehr existieren.
     */
    public void processOld() {
        String date = getStartDate();
        System.out.println("Processing: " + date);
    }

    /**
     * Methode mit neuer API.
     */
    public void processNew() {
        String date = getContractStart();
        System.out.println("Processing: " + date);
    }
}
