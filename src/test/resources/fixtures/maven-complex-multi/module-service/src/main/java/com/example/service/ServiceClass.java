package com.example.service;

import com.example.core.CoreClass;

/**
 * Service Modul - verwendet Core Modul.
 */
public class ServiceClass {

    private final CoreClass core = new CoreClass();

    public String getServiceValue() {
        return "service-" + core.getCoreValue();
    }
}
