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
package org.sonatype.nexus.common.app;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Flags packages or components that should only exist when the named system property is {@code true}.
 * <p>
 * This annotation is processed at runtime through reflection to determine if a feature should be enabled.
 * The system property specified by {@link #name()} is checked to determine if the annotated element
 * should be enabled.
 * <p>
 * Usage example:
 * <pre>
 * // Enable when "my.feature" system property is true
 * {@literal @}FeatureFlag(name = "my.feature")
 * public class MyFeature {
 *   // Feature implementation
 * }
 * 
 * // Enable when "my.feature" system property is true, or when not specified (default is true)
 * {@literal @}FeatureFlag(name = "my.feature", enabledByDefault = true)
 * public class MyDefaultEnabledFeature {
 *   // Feature implementation
 * }
 * 
 * // Enable when "my.feature" system property is false (inverse logic)
 * {@literal @}FeatureFlag(name = "my.feature", inverse = true)
 * public class MyInverseFeature {
 *   // Feature implementation
 * }
 * </pre>
 *
 * @since 3.19
 */
@Retention(RUNTIME)
@Target({PACKAGE, TYPE})
@Repeatable(FeatureFlagGroup.class)
public @interface FeatureFlag
{
  /**
   * The name of the system property to check.
   * <p>
   * The property will be accessed using {@code Boolean.getBoolean(name)} which checks
   * if the property is defined and equal to "true" (case-insensitive).
   *
   * @return the system property name
   */
  String name();

  /**
   * Determines the default state when the system property is not set.
   * <p>
   * If {@code true}, the feature will be enabled by default when the system property is not set.
   * If {@code false}, the feature will be disabled by default when the system property is not set.
   *
   * @return whether the feature is enabled by default
   */
  boolean enabledByDefault() default false;

  /**
   * The feature flag is enabled when the property evaluates to false instead of true.
   * <p>
   * This inverts the normal logic, so the feature is enabled when the property is {@code false}
   * and disabled when the property is {@code true}.
   *
   * @return whether to use inverse logic for the property value
   */
  boolean inverse() default false;
}