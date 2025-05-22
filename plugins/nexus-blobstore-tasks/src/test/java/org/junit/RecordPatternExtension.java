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
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit Jupiter extension for testing Java 21's record pattern matching features.
 * This extension enables tests to validate code that uses record patterns for data extraction
 * and nested pattern matching.
 * <p>
 * The extension provides:
 * <ul>
 *   <li>Conditional test execution based on Java version and system properties</li>
 *   <li>Parameter resolution for injecting record-based test data with various nesting levels</li>
 *   <li>Utilities for generating nested record structures</li>
 *   <li>Utilities for applying record patterns</li>
 *   <li>Utilities for verifying correct data extraction</li>
 * </ul>
 * <p>
 * Usage example:
 * <pre>
 * {@code
 * @ExtendWith(RecordPatternExtension.class)
 * class RecordPatternTest {
 *     @Test
 *     void testBasicRecordPattern(SimpleRecord record) {
 *         // Test code using record pattern matching
 *     }
 * }
 * }
 * </pre>
 *
 * @since 3.60
 */
public class RecordPatternExtension implements ExecutionCondition, ParameterResolver {

    private static final String JAVA21_TESTS_PROPERTY = "java21-tests";
    private static final int REQUIRED_JAVA_VERSION = 21;
    
    // Record definitions for test data
    public record SimpleRecord(String name, int value) {}
    public record NestedRecord(SimpleRecord inner, String description) {}
    public record DeepNestedRecord(NestedRecord nested, int level) {}
    
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        // Check if running on Java 21 or higher
        int javaVersion = getJavaVersion();
        if (javaVersion < REQUIRED_JAVA_VERSION) {
            return ConditionEvaluationResult.disabled(
                    "Record pattern tests require Java " + REQUIRED_JAVA_VERSION + 
                    " or higher, but found Java " + javaVersion);
        }
        
        // Check if java21-tests property is enabled
        boolean java21TestsEnabled = Boolean.getBoolean(JAVA21_TESTS_PROPERTY);
        if (!java21TestsEnabled) {
            return ConditionEvaluationResult.disabled(
                    "Record pattern tests are disabled. Enable with -D" + JAVA21_TESTS_PROPERTY + "=true");
        }
        
        return ConditionEvaluationResult.enabled(
                "Record pattern tests enabled on Java " + javaVersion);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        
        // Support our defined record types
        return type.equals(SimpleRecord.class) ||
               type.equals(NestedRecord.class) ||
               type.equals(DeepNestedRecord.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        
        // Create appropriate test data based on parameter type
        if (type.equals(SimpleRecord.class)) {
            return createSimpleRecord(parameterContext);
        } else if (type.equals(NestedRecord.class)) {
            return createNestedRecord(parameterContext);
        } else if (type.equals(DeepNestedRecord.class)) {
            return createDeepNestedRecord(parameterContext);
        }
        
        throw new ParameterResolutionException("Unsupported parameter type: " + type.getName());
    }
    
    /**
     * Creates a SimpleRecord instance for testing.
     * 
     * @param parameterContext the parameter context
     * @return a SimpleRecord instance
     */
    private SimpleRecord createSimpleRecord(ParameterContext parameterContext) {
        // Extract test method name to create contextual test data
        String methodName = parameterContext.getDeclaringExecutable().getName();
        return new SimpleRecord("test-" + methodName, 42);
    }
    
    /**
     * Creates a NestedRecord instance for testing.
     * 
     * @param parameterContext the parameter context
     * @return a NestedRecord instance
     */
    private NestedRecord createNestedRecord(ParameterContext parameterContext) {
        SimpleRecord inner = createSimpleRecord(parameterContext);
        return new NestedRecord(inner, "Nested record for testing pattern matching");
    }
    
    /**
     * Creates a DeepNestedRecord instance for testing.
     * 
     * @param parameterContext the parameter context
     * @return a DeepNestedRecord instance
     */
    private DeepNestedRecord createDeepNestedRecord(ParameterContext parameterContext) {
        NestedRecord nested = createNestedRecord(parameterContext);
        return new DeepNestedRecord(nested, 3);
    }
    
    /**
     * Utility method to get the current Java version.
     * 
     * @return the Java version as an integer (e.g., 17, 21)
     */
    private int getJavaVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            // Older Java versions (1.8, etc.)
            return Integer.parseInt(version.substring(2, 3));
        }
        // Newer Java versions (9, 10, 11, etc.)
        int dot = version.indexOf('.');
        if (dot != -1) {
            return Integer.parseInt(version.substring(0, dot));
        }
        return Integer.parseInt(version);
    }
    
    /**
     * Utility method to apply a record pattern match and extract components.
     * This demonstrates how record patterns can be used in code.
     * 
     * @param obj the object to match against
     * @return an Optional containing the extracted name if the pattern matches, empty otherwise
     */
    public static Optional<String> extractNameFromSimpleRecord(Object obj) {
        // Using Java 21 record pattern matching
        if (obj instanceof SimpleRecord(String name, int value)) {
            return Optional.of(name);
        }
        return Optional.empty();
    }
    
    /**
     * Utility method to apply a nested record pattern match and extract deeply nested components.
     * This demonstrates how nested record patterns can be used in code.
     * 
     * @param obj the object to match against
     * @return an Optional containing the extracted name if the pattern matches, empty otherwise
     */
    public static Optional<String> extractNameFromNestedRecord(Object obj) {
        // Using Java 21 nested record pattern matching
        if (obj instanceof DeepNestedRecord(NestedRecord(SimpleRecord(String name, int value), String desc), int level)) {
            return Optional.of(name);
        }
        return Optional.empty();
    }
    
    /**
     * Utility method to apply a record pattern match with a guard condition.
     * This demonstrates how record patterns with guards can be used in code.
     * 
     * @param obj the object to match against
     * @return an Optional containing the extracted name if the pattern matches and the guard condition is satisfied, empty otherwise
     */
    public static Optional<String> extractNameWithGuard(Object obj) {
        // Using Java 21 record pattern matching with a guard
        if (obj instanceof SimpleRecord(String name, int value) && value > 40) {
            return Optional.of(name);
        }
        return Optional.empty();
    }
    
    /**
     * Utility method to apply record pattern matching in a switch expression.
     * This demonstrates how record patterns can be used in switch expressions.
     * 
     * @param obj the object to match against
     * @return a string describing the matched pattern
     */
    public static String matchWithSwitch(Object obj) {
        // Using Java 21 record pattern matching in a switch expression
        return switch (obj) {
            case SimpleRecord(String name, int value) -> "Simple record with name: " + name;
            case NestedRecord(SimpleRecord(String name, int value), String desc) -> 
                    "Nested record with inner name: " + name;
            case DeepNestedRecord(NestedRecord(SimpleRecord(String name, int value), String desc), int level) -> 
                    "Deep nested record with name: " + name + " at level: " + level;
            default -> "No match found";
        };
    }
}