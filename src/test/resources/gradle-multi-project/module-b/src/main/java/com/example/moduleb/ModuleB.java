package com.example.moduleb;

import com.example.modulea.ModuleA;

/**
 * Sample class from Module B that depends on Module A
 */
public class ModuleB {
    
    public static String greet() {
        return ModuleA.greeting() + " and Module B";
    }
    
    public static int multiply(int a, int b) {
        return ModuleA.add(a, b) * 2;
    }
}
