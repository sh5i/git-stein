package com.example;

import java.util.List;
import java.util.ArrayList;

/**
 * A sample class for testing blob converters.
 */
public class Hello {
    private String name;

    private int count = 0;

    public Hello(String name) {
        this.name = name;
    }

    public String greet() {
        return "Hello, " + name + "!";
    }

    public List<String> greetMany(int times) {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            results.add(greet());
            count++;
        }
        return results;
    }

    public int getCount() {
        return count;
    }

    enum Color {
        RED, GREEN, BLUE
    }
}
