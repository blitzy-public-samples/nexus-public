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

import static java.lang.StringTemplate.RAW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.StringTemplate;
import java.lang.StringTemplate.Processor;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit Jupiter extension for testing Java 21's string template features.
 * <p>
 * This extension enables tests to validate code that uses string templates for improved
 * string formatting and interpolation. It implements {@link ExecutionCondition} to conditionally
 * enable tests based on Java version and system properties, and {@link ParameterResolver} to
 * inject template processors and test data.
 * <p>
 * The extension provides utilities for creating and applying string templates, comparing
 * template results with expected outputs, and validating template processor behavior.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @ExtendWith(StringTemplateExtension.class)
 * class StringTemplateTest {
 *     @Test
 *     void testStringInterpolation(StringTemplate.Processor<String, RuntimeException> processor) {
 *         String name = "World";
 *         StringTemplate template = RAW."Hello, \{name}!";
 *         String result = processor.process(template);
 *         assertEquals("Hello, World!", result);
 *     }
 * }
 * }
 * </pre>
 *
 * @since 3.60
 */
public class StringTemplateExtension
    implements ExecutionCondition, ParameterResolver
{
  private static final Logger log = LoggerFactory.getLogger(StringTemplateExtension.class);

  private static final String JAVA_VERSION_PROPERTY = "java.version";
  private static final String JAVA_21_VERSION_PREFIX = "21";
  private static final String ENABLE_PREVIEW_PROPERTY = "enable.preview.features";
  private static final String ENABLE_STRING_TEMPLATES_PROPERTY = "enable.string.templates";

  /**
   * Annotation to mark parameters that should be resolved as string template processors.
   */
  public @interface StringTemplateProcessor {
    /**
     * The type of processor to inject.
     */
    ProcessorType value() default ProcessorType.STR;
  }

  /**
   * Types of string template processors that can be injected.
   */
  public enum ProcessorType {
    /**
     * Standard string interpolation processor (STR).
     */
    STR,

    /**
     * Formatted string interpolation processor (FMT).
     */
    FMT,

    /**
     * Raw string template processor (RAW).
     */
    RAW,

    /**
     * Custom processor that validates template fragments and values.
     */
    VALIDATOR
  }

  /**
   * Evaluates whether string template tests should be executed based on Java version
   * and system properties.
   *
   * @param context the extension context
   * @return result indicating whether the test should be executed
   */
  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(final ExtensionContext context) {
    String javaVersion = System.getProperty(JAVA_VERSION_PROPERTY, "");
    boolean isJava21OrHigher = javaVersion.startsWith(JAVA_21_VERSION_PREFIX) ||
        Integer.parseInt(javaVersion.split("\\.")[0]) > 21;

    if (!isJava21OrHigher) {
      return ConditionEvaluationResult.disabled(
          "String template tests require Java 21 or higher, but found: " + javaVersion);
    }

    boolean previewEnabled = Boolean.parseBoolean(System.getProperty(ENABLE_PREVIEW_PROPERTY, "true"));
    if (!previewEnabled) {
      return ConditionEvaluationResult.disabled("Preview features are disabled");
    }

    boolean stringTemplatesEnabled = Boolean.parseBoolean(
        System.getProperty(ENABLE_STRING_TEMPLATES_PROPERTY, "true"));
    if (!stringTemplatesEnabled) {
      return ConditionEvaluationResult.disabled("String templates are explicitly disabled");
    }

    return ConditionEvaluationResult.enabled("Java 21+ with string templates enabled");
  }

  /**
   * Determines if this resolver supports the given parameter.
   *
   * @param parameterContext the context for the parameter
   * @param extensionContext the extension context
   * @return true if this resolver supports the parameter
   */
  @Override
  public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) {
    Parameter parameter = parameterContext.getParameter();
    Class<?> type = parameter.getType();

    // Support StringTemplate parameters
    if (StringTemplate.class.isAssignableFrom(type)) {
      return true;
    }

    // Support StringTemplate.Processor parameters
    if (Processor.class.isAssignableFrom(type)) {
      return true;
    }

    // Support parameters annotated with @StringTemplateProcessor
    return parameterContext.isAnnotated(StringTemplateProcessor.class);
  }

  /**
   * Resolves the parameter value for the given parameter context.
   *
   * @param parameterContext the context for the parameter
   * @param extensionContext the extension context
   * @return the resolved parameter value
   * @throws ParameterResolutionException if parameter resolution fails
   */
  @Override
  public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext)
      throws ParameterResolutionException
  {
    Parameter parameter = parameterContext.getParameter();
    Class<?> type = parameter.getType();

    // Resolve StringTemplate parameters
    if (StringTemplate.class.isAssignableFrom(type)) {
      return createSampleStringTemplate();
    }

    // Resolve StringTemplate.Processor parameters
    if (Processor.class.isAssignableFrom(type)) {
      ProcessorType processorType = ProcessorType.STR; // Default

      // Check for @StringTemplateProcessor annotation
      Optional<StringTemplateProcessor> annotation = parameterContext.findAnnotation(StringTemplateProcessor.class);
      if (annotation.isPresent()) {
        processorType = annotation.get().value();
      }

      return getProcessor(processorType);
    }

    throw new ParameterResolutionException("Unsupported parameter type: " + type.getName());
  }

  /**
   * Creates a sample StringTemplate for testing.
   *
   * @return a sample StringTemplate instance
   */
  public static StringTemplate createSampleStringTemplate() {
    String value = "test";
    return RAW."Sample template with \{value}";
  }

  /**
   * Creates a StringTemplate with the given fragments and values.
   *
   * @param fragments the template fragments
   * @param values the template values
   * @return a StringTemplate instance
   */
  public static StringTemplate createStringTemplate(final List<String> fragments, final List<Object> values) {
    return StringTemplate.of(fragments, values);
  }

  /**
   * Returns a string template processor based on the specified type.
   *
   * @param type the processor type
   * @return the corresponding processor instance
   */
  @SuppressWarnings("unchecked")
  public static <T> Processor<T, RuntimeException> getProcessor(final ProcessorType type) {
    switch (type) {
      case STR:
        return (Processor<T, RuntimeException>) STR;
      case FMT:
        try {
          // FMT is not directly accessible, attempt to get it via reflection
          Class<?> fmtClass = Class.forName("java.lang.StringTemplate");
          return (Processor<T, RuntimeException>) fmtClass.getField("FMT").get(null);
        }
        catch (Exception e) {
          log.warn("Failed to get FMT processor, falling back to STR", e);
          return (Processor<T, RuntimeException>) STR;
        }
      case RAW:
        return (Processor<T, RuntimeException>) RAW;
      case VALIDATOR:
        return createValidatorProcessor();
      default:
        return (Processor<T, RuntimeException>) STR;
    }
  }

  /**
   * Creates a validator processor that checks template fragments and values.
   *
   * @return a validator processor
   */
  @SuppressWarnings("unchecked")
  private static <T> Processor<T, RuntimeException> createValidatorProcessor() {
    return template -> {
      assertNotNull(template, "Template must not be null");
      assertNotNull(template.fragments(), "Template fragments must not be null");
      assertNotNull(template.values(), "Template values must not be null");

      // Validate that fragments.size() == values.size() + 1
      assertEquals(template.fragments().size(), template.values().size() + 1,
          "Template fragments size should be values size + 1");

      // Return the interpolated string as the result
      return (T) template.interpolate();
    };
  }

  /**
   * Validates that a string template produces the expected output when processed.
   *
   * @param template the string template to validate
   * @param expected the expected output
   * @param processor the processor to use
   */
  public static void validateTemplateOutput(
      final StringTemplate template,
      final String expected,
      final Processor<String, RuntimeException> processor)
  {
    String actual = processor.process(template);
    assertEquals(expected, actual, "Template output does not match expected value");
  }

  /**
   * Validates that a string template produces the expected output when interpolated.
   *
   * @param template the string template to validate
   * @param expected the expected output
   */
  public static void validateTemplateInterpolation(final StringTemplate template, final String expected) {
    String actual = template.interpolate();
    assertEquals(expected, actual, "Template interpolation does not match expected value");
  }
}