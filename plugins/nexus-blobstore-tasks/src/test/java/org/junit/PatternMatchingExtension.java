/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.junit;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit Jupiter extension for testing Java 21's pattern matching for switch features.
 * This extension provides:
 * <ul>
 *   <li>Conditional test execution based on Java version (21+) and system properties</li>
 *   <li>Parameter resolution for injecting pattern matching test data</li>
 *   <li>Utilities for generating pattern matching test scenarios</li>
 *   <li>Verification utilities for pattern matching behavior</li>
 * </ul>
 * 
 * <p>Pattern matching for switch is a feature introduced in Java 21 that allows testing an expression
 * against a number of patterns, each with a specific action. This enables more concise and safer
 * code for complex data-oriented queries.</p>
 * 
 * <p>Key features of pattern matching for switch include:</p>
 * <ul>
 *   <li>Type patterns (e.g., {@code case String s -> ...})</li>
 *   <li>Guarded patterns with when clause (e.g., {@code case String s when s.length() > 5 -> ...})</li>
 *   <li>Null handling in switch</li>
 *   <li>Exhaustiveness checking</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>
 * @ExtendWith(PatternMatchingExtension.class)
 * class PatternMatchingTest {
 *   @Test
 *   void testTypePatternMatching(TypePatternTestCase testCase) {
 *     // Test code using the injected test case
 *     Object result = switch(testCase.getTestValue()) {
 *         case String s -> s;
 *         case Integer i -> i;
 *         case null -> "null";
 *         default -> null;
 *     };
 *     assertTrue(PatternMatchingExtension.verifyTypePatternMatch(testCase, result));
 *   }
 * }
 * </pre>
 */
public class PatternMatchingExtension implements ExecutionCondition, ParameterResolver {

    private static final String JAVA_VERSION_PROPERTY = "java.version";
    private static final String PATTERN_MATCHING_ENABLED_PROPERTY = "nexus.test.patternmatching.enabled";
    private static final int REQUIRED_JAVA_VERSION = 21;

    /**
     * Base class for pattern matching test cases.
     * Provides common functionality for all pattern matching test scenarios.
     */
    public abstract static class PatternMatchingTestCase {
        private final String description;

        protected PatternMatchingTestCase(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    /**
     * Test case for type pattern matching in switch expressions/statements.
     * <p>Example of type pattern in switch:</p>
     * <pre>
     * switch(obj) {
     *     case String s -> s.length();
     *     case Integer i -> i * 2;
     *     default -> 0;
     * }
     * </pre>
     */
    public static class TypePatternTestCase extends PatternMatchingTestCase {
        private final Object testValue;
        private final Class<?> expectedMatchType;
        private final boolean shouldMatch;

        public TypePatternTestCase(String description, Object testValue, Class<?> expectedMatchType, boolean shouldMatch) {
            super(description);
            this.testValue = testValue;
            this.expectedMatchType = expectedMatchType;
            this.shouldMatch = shouldMatch;
        }

        public Object getTestValue() {
            return testValue;
        }

        public Class<?> getExpectedMatchType() {
            return expectedMatchType;
        }

        public boolean shouldMatch() {
            return shouldMatch;
        }
    }

    /**
     * Test case for guarded pattern matching in switch expressions/statements.
     * <p>Example of guarded pattern in switch:</p>
     * <pre>
     * switch(obj) {
     *     case String s when s.length() > 5 -> "Long string";
     *     case String s -> "Short string";
     *     case Integer i when i > 0 -> "Positive";
     *     case Integer i -> "Zero or negative";
     *     default -> "Something else";
     * }
     * </pre>
     */
    public static class GuardedPatternTestCase extends PatternMatchingTestCase {
        private final Object testValue;
        private final Class<?> patternType;
        private final Predicate<Object> guardCondition;
        private final boolean shouldMatch;

        public GuardedPatternTestCase(String description, Object testValue, Class<?> patternType, 
                Predicate<Object> guardCondition, boolean shouldMatch) {
            super(description);
            this.testValue = testValue;
            this.patternType = patternType;
            this.guardCondition = guardCondition;
            this.shouldMatch = shouldMatch;
        }

        public Object getTestValue() {
            return testValue;
        }

        public Class<?> getPatternType() {
            return patternType;
        }

        public Predicate<Object> getGuardCondition() {
            return guardCondition;
        }

        public boolean shouldMatch() {
            return shouldMatch;
        }
    }

    /**
     * Test case for null handling in pattern matching.
     * <p>Example of null handling in switch:</p>
     * <pre>
     * switch(obj) {
     *     case null -> "It's null";
     *     case String s -> s;
     *     default -> "Not a string";
     * }
     * </pre>
     */
    public static class NullPatternTestCase extends PatternMatchingTestCase {
        private final Object testValue;
        private final boolean shouldHandleNull;

        public NullPatternTestCase(String description, Object testValue, boolean shouldHandleNull) {
            super(description);
            this.testValue = testValue;
            this.shouldHandleNull = shouldHandleNull;
        }

        public Object getTestValue() {
            return testValue;
        }

        public boolean shouldHandleNull() {
            return shouldHandleNull;
        }
    }

    /**
     * Evaluates whether the test or container should be executed based on Java version
     * and system properties.
     * 
     * @param context The extension context
     * @return A ConditionEvaluationResult indicating whether the test should be executed
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        // Check if pattern matching tests are explicitly enabled/disabled via system property
        String patternMatchingEnabled = System.getProperty(PATTERN_MATCHING_ENABLED_PROPERTY);
        if (patternMatchingEnabled != null) {
            boolean enabled = Boolean.parseBoolean(patternMatchingEnabled);
            if (!enabled) {
                return ConditionEvaluationResult.disabled("Pattern matching tests disabled via system property");
            }
        }

        // Check Java version
        String javaVersion = System.getProperty(JAVA_VERSION_PROPERTY, "");
        int majorVersion = getMajorJavaVersion(javaVersion);
        
        if (majorVersion >= REQUIRED_JAVA_VERSION) {
            return ConditionEvaluationResult.enabled("Java version " + majorVersion + " supports pattern matching");
        } else {
            return ConditionEvaluationResult.disabled(
                    "Java version " + majorVersion + " does not support pattern matching (requires Java " 
                    + REQUIRED_JAVA_VERSION + "+)");
        }
    }

    /**
     * Determines if this extension can resolve the given parameter.
     * 
     * @param parameterContext The parameter to be resolved
     * @param extensionContext The extension context
     * @return true if the parameter is a subclass of PatternMatchingTestCase
     */
    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        return PatternMatchingTestCase.class.isAssignableFrom(type);
    }

    /**
     * Resolves a parameter by providing an appropriate test case instance.
     * 
     * @param parameterContext The parameter to be resolved
     * @param extensionContext The extension context
     * @return An instance of the requested test case type
     * @throws ParameterResolutionException if the parameter cannot be resolved
     */
    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Parameter parameter = parameterContext.getParameter();
        Class<?> type = parameter.getType();
        
        if (TypePatternTestCase.class.equals(type)) {
            return createTypePatternTestCase();
        } else if (GuardedPatternTestCase.class.equals(type)) {
            return createGuardedPatternTestCase();
        } else if (NullPatternTestCase.class.equals(type)) {
            return createNullPatternTestCase();
        } else if (PatternMatchingTestCase.class.isAssignableFrom(type)) {
            // For custom subclasses, try to find a factory method in the test class
            return createCustomTestCase(parameterContext, extensionContext);
        }
        
        throw new ParameterResolutionException("Unsupported parameter type: " + type.getName());
    }

    /**
     * Creates a default TypePatternTestCase for testing.
     */
    private TypePatternTestCase createTypePatternTestCase() {
        // Create a test case with a String value that should match String pattern
        return new TypePatternTestCase(
                "String value matching String pattern",
                "test string",
                String.class,
                true);
    }

    /**
     * Creates a default GuardedPatternTestCase for testing.
     */
    private GuardedPatternTestCase createGuardedPatternTestCase() {
        // Create a test case with a String value and a guard condition on its length
        return new GuardedPatternTestCase(
                "String value with length > 5",
                "test string",
                String.class,
                obj -> ((String) obj).length() > 5,
                true);
    }

    /**
     * Creates a default NullPatternTestCase for testing.
     */
    private NullPatternTestCase createNullPatternTestCase() {
        // Create a test case with a null value
        return new NullPatternTestCase(
                "Null value handling",
                null,
                true);
    }

    /**
     * Attempts to create a custom test case by looking for factory methods in the test class.
     */
    private PatternMatchingTestCase createCustomTestCase(
            ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> testClass = extensionContext.getRequiredTestClass();
        Class<?> parameterType = parameterContext.getParameter().getType();
        
        // Look for factory methods in the test class
        Optional<Method> factoryMethod = Arrays.stream(testClass.getDeclaredMethods())
                .filter(method -> method.getReturnType().equals(parameterType))
                .filter(method -> method.getParameterCount() == 0)
                .filter(method -> method.isAnnotationPresent(Factory.class))
                .findFirst();
        
        if (factoryMethod.isPresent()) {
            try {
                Method method = factoryMethod.get();
                method.setAccessible(true);
                Object instance = extensionContext.getTestInstance().orElse(null);
                return (PatternMatchingTestCase) method.invoke(instance);
            } catch (Exception e) {
                throw new ParameterResolutionException("Failed to invoke factory method", e);
            }
        }
        
        throw new ParameterResolutionException("No factory method found for type: " + parameterType.getName());
    }

    /**
     * Extracts the major version number from a Java version string.
     * Handles both the new version format (Java 9+): "11.0.2", "17.0.1", "21.0.1", etc.
     * and the old version format (Java 8 and earlier): "1.8.0_292", etc.
     * 
     * @param javaVersion The Java version string from system properties
     * @return The major Java version as an integer (e.g., 8, 11, 17, 21)
     */
    private int getMajorJavaVersion(String javaVersion) {
        // Handle the new version format (Java 9+): "11.0.2", "17.0.1", etc.
        if (javaVersion.matches("^\\d+\\.\\.+")) {
            return Integer.parseInt(javaVersion.split("\\.")[0]);
        }
        
        // Handle the old version format (Java 8 and earlier): "1.8.0_292", etc.
        if (javaVersion.startsWith("1.")) {
            String[] parts = javaVersion.split("\\.");
            if (parts.length >= 2) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    // Fall through to default
                }
            }
        }
        
        // Default to 0 if we can't parse the version
        return 0;
    }

    /**
     * Annotation to mark factory methods for custom test cases.
     * Methods annotated with @Factory should return a subclass of PatternMatchingTestCase
     * and take no parameters. These methods will be used to create test case instances
     * when a parameter of the corresponding type is requested.
     * 
     * <p>Example:</p>
     * <pre>
     * @Factory
     * public CustomPatternTestCase createCustomTestCase() {
     *     return new CustomPatternTestCase("Custom test", ...); 
     * }
     * </pre>
     */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
    public @interface Factory {
    }

    /**
     * Generates a list of type pattern test cases for common scenarios.
     */
    public static List<TypePatternTestCase> generateTypePatternTestCases() {
        List<TypePatternTestCase> testCases = new ArrayList<>();
        
        // String type patterns
        testCases.add(new TypePatternTestCase(
                "String value matching String pattern",
                "test string",
                String.class,
                true));
        testCases.add(new TypePatternTestCase(
                "String value matching CharSequence pattern",
                "test string",
                CharSequence.class,
                true));
        testCases.add(new TypePatternTestCase(
                "String value not matching Integer pattern",
                "test string",
                Integer.class,
                false));
        
        // Integer type patterns
        testCases.add(new TypePatternTestCase(
                "Integer value matching Integer pattern",
                42,
                Integer.class,
                true));
        testCases.add(new TypePatternTestCase(
                "Integer value matching Number pattern",
                42,
                Number.class,
                true));
        testCases.add(new TypePatternTestCase(
                "Integer value not matching String pattern",
                42,
                String.class,
                false));
        
        // List type patterns
        testCases.add(new TypePatternTestCase(
                "ArrayList value matching ArrayList pattern",
                new ArrayList<>(),
                ArrayList.class,
                true));
        testCases.add(new TypePatternTestCase(
                "ArrayList value matching List pattern",
                new ArrayList<>(),
                List.class,
                true));
        
        // Null value
        testCases.add(new TypePatternTestCase(
                "Null value not matching any type pattern",
                null,
                String.class,
                false));
        
        return testCases;
    }

    /**
     * Generates a list of guarded pattern test cases for common scenarios.
     */
    public static List<GuardedPatternTestCase> generateGuardedPatternTestCases() {
        List<GuardedPatternTestCase> testCases = new ArrayList<>();
        
        // String with length conditions
        testCases.add(new GuardedPatternTestCase(
                "String with length > 5",
                "test string",
                String.class,
                obj -> ((String) obj).length() > 5,
                true));
        testCases.add(new GuardedPatternTestCase(
                "String with length < 5",
                "test",
                String.class,
                obj -> ((String) obj).length() < 5,
                true));
        testCases.add(new GuardedPatternTestCase(
                "String with length = 4",
                "test",
                String.class,
                obj -> ((String) obj).length() == 4,
                true));
        testCases.add(new GuardedPatternTestCase(
                "String with length = 10 (false)",
                "test",
                String.class,
                obj -> ((String) obj).length() == 10,
                false));
        
        // Integer with value conditions
        testCases.add(new GuardedPatternTestCase(
                "Integer > 10",
                42,
                Integer.class,
                obj -> ((Integer) obj) > 10,
                true));
        testCases.add(new GuardedPatternTestCase(
                "Integer < 10",
                5,
                Integer.class,
                obj -> ((Integer) obj) < 10,
                true));
        testCases.add(new GuardedPatternTestCase(
                "Integer = 42",
                42,
                Integer.class,
                obj -> ((Integer) obj) == 42,
                true));
        testCases.add(new GuardedPatternTestCase(
                "Integer = 100 (false)",
                42,
                Integer.class,
                obj -> ((Integer) obj) == 100,
                false));
        
        return testCases;
    }

    /**
     * Generates a list of null pattern test cases for common scenarios.
     */
    public static List<NullPatternTestCase> generateNullPatternTestCases() {
        List<NullPatternTestCase> testCases = new ArrayList<>();
        
        testCases.add(new NullPatternTestCase(
                "Null value with null handling",
                null,
                true));
        testCases.add(new NullPatternTestCase(
                "Non-null value with null handling",
                "test string",
                true));
        testCases.add(new NullPatternTestCase(
                "Null value without null handling",
                null,
                false));
        
        return testCases;
    }

    /**
     * Utility method to verify a type pattern match result.
     * 
     * @param testCase The test case to verify
     * @param result The result from the pattern matching operation
     * @return true if the result matches the expected outcome, false otherwise
     */
    public static boolean verifyTypePatternMatch(TypePatternTestCase testCase, Object result) {
        if (testCase.shouldMatch()) {
            return result != null;
        } else {
            return result == null;
        }
    }

    /**
     * Utility method to verify a guarded pattern match result.
     * 
     * @param testCase The test case to verify
     * @param result The result from the pattern matching operation
     * @return true if the result matches the expected outcome, false otherwise
     */
    public static boolean verifyGuardedPatternMatch(GuardedPatternTestCase testCase, Object result) {
        if (testCase.shouldMatch()) {
            return result != null && testCase.getGuardCondition().test(testCase.getTestValue());
        } else {
            return result == null || !testCase.getGuardCondition().test(testCase.getTestValue());
        }
    }

    /**
     * Utility method to verify a null pattern match result.
     * 
     * @param testCase The test case to verify
     * @param result The result from the pattern matching operation
     * @return true if the result matches the expected outcome, false otherwise
     */
    public static boolean verifyNullPatternMatch(NullPatternTestCase testCase, Object result) {
        if (testCase.getTestValue() == null) {
            return testCase.shouldHandleNull() == (result != null);
        } else {
            return result != null;
        }
    }
}