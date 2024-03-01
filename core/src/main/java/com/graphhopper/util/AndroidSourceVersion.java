package com.graphhopper.util;

import java.util.Arrays;
import java.util.HashSet;

public class AndroidSourceVersion {

    private static final HashSet<String> JAVA_KEYWORDS = new HashSet<>(Arrays.asList(
            "strictfp", "assert", "enum", "_", "public", "protected", "private", "abstract",
            "static", "final", "transient", "volatile", "synchronized", "native", "class",
            "interface", "extends", "package", "throws", "implements", "boolean", "byte", "char",
            "short", "int", "long", "float", "double", "void", "if", "else", "try", "catch",
            "finally", "do", "while", "for", "continue", "switch", "case", "default", "break",
            "throw", "return", "this", "new", "super", "import", "instanceof", "goto", "const",
            "null", "true", "false"));

    public static boolean isKeyword(String name) {
        return JAVA_KEYWORDS.contains(name);
    }
}
