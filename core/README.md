# Graphhopper Core v3.2 Android

This is a fork of the [Graphhopper Core v3.2](https://github.com/graphhopper/graphhopper/tree/3.2) library with an improvement of the logic to parse profiles with a custom model in Android, because the original version of Graphhopper is not designed for Java and is not guaranteed to work on Android. The part with the custom model doesn't work in Android.

## Main problem

For a profile with a custom model Graphhopper generates in the runtime a new class that extends `CustomWeightingHelper`. The new generated class has different conditions that affect the speed and the priority. This statements are retrieved from routing files and that's why the class cannot be created before the compilation of the project. The problem with the original Graphhopper Core library is that it uses some tools to generate a class from a string in Java. This part doesn't work in Android, because of different Virtual Machines.

## Solution

Instead of Janino that is used for the Java Runtime compilation [Dexmaker](https://github.com/linkedin/dexmaker) is used to generate the `CustomWeightingHelper` class. This library creates Dalvik .dex files instead of Java .class files. It is different from the original class generation, because the library works with "low-level" instructions. So, all the statements from the configuration file are parsed and converted into Dexmaker instructions.

## Modified and new files

1. `build.gradle` - converted from the POM to Gradle, replaced Janino with Dexmaker.
2. `CustomWeightingHelperCreator.java` - new helper class, that parses all statemements, creates Dexmaker instructions and returns an instance of the loaded class.
3. `CustomModelParser.java` - instead of Janino class generation uses `CustomWeightingHelperCreator` now.
4. `Graphhopper.java` - checking loaded profiles is disabled, because it doesn't work with a new custom profile.
5. `com.graphhopper.routing.weighting.custom.boolean_expression_helper` - helper classes used to normalize boolean conditions.

## Main challenges

Dexmaker works with low-level instruction, so generating a simple class is not that easy. Main tasks to generate the class are:
1. Declare local variables depending on the statements and areas lists.
2. Implement the `init` methods to find and assign values into those local variables.
3. Implement `getSpeed` and `getPriority` methods. Their logic is the same, it just uses different lists of statements.

To be able to implement statements and operations the following algorithm is implemented:
1. Get all conditions from the statement and replace them with IDs. Save conditions separately to the list.
2. Process conditions and prepare a list - parse conditions from a string to a model.
3. Simplify conditions into CNF.
4. Implement a loop with instructions.

## Future updates

When updating the Graphhopper to later versions in future Dexmaker instructions should be modified according to the [Graphhopper Documentation for custom models](https://github.com/graphhopper/graphhopper/blob/3.2/docs/core/custom-models.md).
