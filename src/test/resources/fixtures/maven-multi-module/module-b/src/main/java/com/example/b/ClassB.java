package com.example.b;

import com.example.a.ClassA;

public class ClassB {
    private final ClassA classA = new ClassA();

    public String greet() {
        return classA.greet() + " and B";
    }
}
