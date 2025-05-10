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
package org.sonatype.nexus.repository;

import java.util.function.Consumer;

import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Utility extensions for using Mockito with JUnit Jupiter (JUnit 5) in repository tests.
 * <p>
 * This class provides factory methods for static mocking using Mockito.mockStatic() and helper methods
 * for argument captor usage in the JUnit 5 environment. It facilitates gradual migration from JUnit 4
 * to JUnit 5 by providing common patterns for both test styles.
 * <p>
 * Example usage for static mocking:
 * <pre>
 * {@code
 * // In a JUnit 5 test
 * @Test
 * void testStaticMethod() {
 *   try (MockedStatic<FileUtils> fileUtils = mockStatic(FileUtils.class)) {
 *     fileUtils.when(() -> FileUtils.deleteQuietly(any(File.class))).thenReturn(true);
 *     
 *     underTest.cleanupTemporaryFiles();
 *     
 *     fileUtils.verify(() -> FileUtils.deleteQuietly(any(File.class)), times(2));
 *   }
 * }
 * }
 * </pre>
 * <p>
 * Example usage for argument captors:
 * <pre>
 * {@code
 * // In a JUnit 5 test
 * @Test
 * void testArgumentCapture() {
 *   ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
 *   
 *   underTest.processValue("test-value");
 *   
 *   verify(mockDependency).process(captor.capture());
 *   assertEquals("test-value", captor.getValue());
 * }
 * }
 * </pre>
 */
public final class MockitoJUnit5Extensions
{
  /**
   * Private constructor to prevent instantiation of utility class.
   */
  private MockitoJUnit5Extensions() {
    // empty
  }

  /**
   * Creates a {@link MockedStatic} for the specified class.
   * <p>
   * This is a convenience method for Mockito.mockStatic() to be used in a try-with-resources block.
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * try (MockedStatic<FileUtils> fileUtils = mockStatic(FileUtils.class)) {
   *   fileUtils.when(() -> FileUtils.deleteQuietly(any(File.class))).thenReturn(true);
   *   // test code
   * }
   * }
   * </pre>
   *
   * @param classToMock the class for which to create a static mock
   * @param <T> the type of the class to mock
   * @return a {@link MockedStatic} instance that should be used in a try-with-resources block
   */
  public static <T> MockedStatic<T> mockStatic(final Class<T> classToMock) {
    return Mockito.mockStatic(classToMock);
  }

  /**
   * Creates a {@link MockedConstruction} for the specified class.
   * <p>
   * This is a convenience method for Mockito.mockConstruction() to be used in a try-with-resources block.
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * try (MockedConstruction<SomeClass> mocked = mockConstruction(SomeClass.class)) {
   *   SomeClass instance = new SomeClass(); // This instance is mocked
   *   when(instance.someMethod()).thenReturn("mocked");
   *   // test code
   * }
   * }
   * </pre>
   *
   * @param classToMock the class for which to mock construction
   * @param <T> the type of the class to mock
   * @return a {@link MockedConstruction} instance that should be used in a try-with-resources block
   */
  public static <T> MockedConstruction<T> mockConstruction(final Class<T> classToMock) {
    return Mockito.mockConstruction(classToMock);
  }

  /**
   * Creates a {@link MockedConstruction} for the specified class with a preparation callback.
   * <p>
   * This is a convenience method for Mockito.mockConstruction() with a preparation callback,
   * to be used in a try-with-resources block.
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * try (MockedConstruction<SomeClass> mocked = mockConstruction(SomeClass.class, (mock, context) -> {
   *   when(mock.someMethod()).thenReturn("mocked");
   * })) {
   *   SomeClass instance = new SomeClass(); // This instance is already prepared with the mock setup
   *   // test code
   * }
   * }
   * </pre>
   *
   * @param classToMock the class for which to mock construction
   * @param preparationCallback the callback to prepare each constructed mock instance
   * @param <T> the type of the class to mock
   * @return a {@link MockedConstruction} instance that should be used in a try-with-resources block
   */
  public static <T> MockedConstruction<T> mockConstruction(
      final Class<T> classToMock,
      final MockedConstruction.MockInitializer<T> preparationCallback)
  {
    return Mockito.mockConstruction(classToMock, preparationCallback);
  }

  /**
   * Creates an {@link ArgumentCaptor} for the specified class.
   * <p>
   * This is a convenience method for ArgumentCaptor.forClass() to be used in JUnit 5 tests
   * where @Captor annotation might not be used.
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * ArgumentCaptor<String> captor = captorFor(String.class);
   * verify(mockDependency).process(captor.capture());
   * assertEquals("expected", captor.getValue());
   * }
   * </pre>
   *
   * @param clazz the class to create a captor for
   * @param <T> the type of the class
   * @return an {@link ArgumentCaptor} for the specified class
   */
  public static <T> ArgumentCaptor<T> captorFor(final Class<T> clazz) {
    return ArgumentCaptor.forClass(clazz);
  }

  /**
   * Executes a verification with an {@link ArgumentCaptor} and returns the captured value.
   * <p>
   * This is a convenience method for common captor usage pattern in JUnit 5 tests.
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * String capturedValue = captureArgument(String.class, captor -> {
   *   verify(mockDependency).process(captor.capture());
   * });
   * assertEquals("expected", capturedValue);
   * }
   * </pre>
   *
   * @param clazz the class to create a captor for
   * @param verification the verification logic that captures the argument
   * @param <T> the type of the class
   * @return the captured value
   */
  public static <T> T captureArgument(final Class<T> clazz, final Consumer<ArgumentCaptor<T>> verification) {
    ArgumentCaptor<T> captor = ArgumentCaptor.forClass(clazz);
    verification.accept(captor);
    return captor.getValue();
  }

  /**
   * Executes a verification with an {@link ArgumentCaptor} and returns all captured values.
   * <p>
   * This is a convenience method for common captor usage pattern in JUnit 5 tests when multiple values are captured.
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * List<String> capturedValues = captureArguments(String.class, captor -> {
   *   verify(mockDependency, times(3)).process(captor.capture());
   * });
   * assertEquals(3, capturedValues.size());
   * }
   * </pre>
   *
   * @param clazz the class to create a captor for
   * @param verification the verification logic that captures the arguments
   * @param <T> the type of the class
   * @return the list of captured values
   */
  public static <T> java.util.List<T> captureArguments(final Class<T> clazz, final Consumer<ArgumentCaptor<T>> verification) {
    ArgumentCaptor<T> captor = ArgumentCaptor.forClass(clazz);
    verification.accept(captor);
    return captor.getAllValues();
  }
}