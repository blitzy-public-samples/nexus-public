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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.mockito.Mock;
import org.mockito.MockSettings;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.Spy;
import org.mockito.exceptions.base.MockitoException;
import org.mockito.internal.util.reflection.FieldSetter;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;

/**
 * JUnit Jupiter extension for integrating with Mockito 4.11.0.
 * <p>
 * This extension enables the use of Mockito annotations (@Mock, @Spy, @InjectMocks, @Captor)
 * within JUnit 5 tests by handling the initialization and cleanup of mocks before and after
 * test execution.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @ExtendWith(MockitoExtension.class)
 * class ExampleTest {
 *     @Mock
 *     private List<String> mockedList;
 *
 *     @Test
 *     void shouldDoSomething() {
 *         mockedList.add("one");
 *         verify(mockedList).add("one");
 *         assertEquals(0, mockedList.size());
 *     }
 * }
 * }
 * </pre>
 * <p>
 * The extension also supports lenient mocking mode configuration through the @MockitoSettings annotation.
 * <p>
 * Example with lenient mocking:
 * <pre>
 * {@code
 * @ExtendWith(MockitoExtension.class)
 * @MockitoSettings(strictness = Strictness.LENIENT)
 * class ExampleLenientTest {
 *     @Mock
 *     private List<String> mockedList;
 *
 *     @Test
 *     void shouldAllowUnusedStubs() {
 *         when(mockedList.get(0)).thenReturn("first");
 *         // The stub above is not used, but no UnnecessaryStubbingException will be thrown
 *         // because of the LENIENT strictness setting
 *     }
 * }
 * }
 * </pre>
 *
 * @since 3.30.0
 */
public class MockitoExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final String MOCKITO_SESSION_KEY = "mockitoSession";

    /**
     * Annotation to configure Mockito strictness settings for a test class.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface MockitoSettings {
        /**
         * Strictness level for the test class.
         *
         * @return the strictness level
         */
        Strictness strictness() default Strictness.STRICT_STUBS;
    }

    @Override
    public void beforeEach(final ExtensionContext context) throws Exception {
        Strictness strictness = getStrictness(context);
        MockitoSession session = MockitoAnnotations.openMocks(context.getRequiredTestInstance());
        getStore(context).put(MOCKITO_SESSION_KEY, session);
    }

    @Override
    public void afterEach(final ExtensionContext context) throws Exception {
        MockitoSession session = getStore(context).get(MOCKITO_SESSION_KEY, MockitoSession.class);
        if (session != null) {
            session.finishMocking();
        }
    }

    @Override
    public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Parameter parameter = parameterContext.getParameter();
        return parameter.isAnnotationPresent(Mock.class) || parameter.isAnnotationPresent(Spy.class);
    }

    @Override
    public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Parameter parameter = parameterContext.getParameter();
        Class<?> parameterType = parameter.getType();

        if (parameter.isAnnotationPresent(Mock.class)) {
            Mock mockAnnotation = parameter.getAnnotation(Mock.class);
            return createMock(parameterType, mockAnnotation, getStrictness(extensionContext));
        } else if (parameter.isAnnotationPresent(Spy.class)) {
            return createSpy(parameterType);
        }

        throw new ParameterResolutionException(
                "Failed to resolve parameter " + parameter.getName() + " in " + parameterContext.getDeclaringExecutable());
    }

    /**
     * Creates a mock object of the given type with the specified settings.
     *
     * @param type the class to mock
     * @param mockAnnotation the mock annotation
     * @param strictness the strictness level to apply
     * @return the mock object
     */
    private Object createMock(final Class<?> type, final Mock mockAnnotation, final Strictness strictness) {
        MockSettings settings = org.mockito.Mockito.withSettings();

        if (mockAnnotation.extraInterfaces().length > 0) {
            settings.extraInterfaces(mockAnnotation.extraInterfaces());
        }

        if (mockAnnotation.name().length() > 0) {
            settings.name(mockAnnotation.name());
        }

        if (mockAnnotation.serializable()) {
            settings.serializable();
        }

        if (mockAnnotation.lenient() || strictness == Strictness.LENIENT) {
            settings.lenient();
        }

        settings.defaultAnswer(mockAnnotation.answer());

        return org.mockito.Mockito.mock(type, settings);
    }

    /**
     * Creates a spy object of the given type.
     *
     * @param type the class to spy on
     * @return the spy object
     */
    private Object createSpy(final Class<?> type) {
        try {
            Object instance = type.getDeclaredConstructor().newInstance();
            return org.mockito.Mockito.spy(instance);
        } catch (Exception e) {
            throw new MockitoException("Failed to create spy for " + type, e);
        }
    }

    /**
     * Gets the strictness level from the test class annotation or returns the default.
     *
     * @param context the extension context
     * @return the strictness level
     */
    private Strictness getStrictness(final ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        MockitoSettings annotation = testClass.getAnnotation(MockitoSettings.class);
        return annotation != null ? annotation.strictness() : Strictness.STRICT_STUBS;
    }

    /**
     * Gets the store for this extension from the extension context.
     *
     * @param context the extension context
     * @return the store
     */
    private Store getStore(final ExtensionContext context) {
        return context.getStore(Namespace.create(getClass(), context.getRequiredTestInstance()));
    }
}