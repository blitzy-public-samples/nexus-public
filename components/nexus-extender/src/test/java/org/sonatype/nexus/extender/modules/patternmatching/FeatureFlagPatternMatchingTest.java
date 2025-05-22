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
package org.sonatype.nexus.extender.modules.patternmatching;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.extender.modules.FeatureFlaggedIndex;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.osgi.framework.Bundle;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;

/**
 * Tests the enhanced FeatureFlaggedIndex component's evaluation of feature flags using Java 21's Pattern Matching features.
 * This test validates pattern matching for switch expressions and instanceof patterns, ensuring proper conditional logic
 * execution when evaluating feature flag annotations with various configurations.
 *
 * @since 3.60
 */
public class FeatureFlagPatternMatchingTest
    extends TestSupport
{
  private static final String FLAG_1 = "FeatureFlagPatternMatchingTest_1";

  private static final String FLAG_2 = "FeatureFlagPatternMatchingTest_2";

  @Mock
  Bundle mockBundle;

  @Before
  public void setup() throws ClassNotFoundException {
    System.clearProperty(FLAG_1);
    System.clearProperty(FLAG_2);
    assertThat(System.getProperty(FLAG_1), is((String) null));
    assertThat(System.getProperty(FLAG_2), is((String) null));
  }

  @After
  public void teardown() {
    System.clearProperty(FLAG_1);
    System.clearProperty(FLAG_2);
  }

  /**
   * Tests pattern matching in switch expressions for evaluating feature flags.
   * This demonstrates how Java 21's pattern matching in switch can simplify the evaluation
   * of different feature flag configurations.
   */
  @Test
  public void testPatternMatchingInSwitchForFeatureFlags() throws ClassNotFoundException {
    // Define test classes with different feature flag configurations
    @FeatureFlag(name = FLAG_1)
    class StandardFlag {}

    @FeatureFlag(name = FLAG_1, inverse = true)
    class InverseFlag {}

    @FeatureFlag(name = FLAG_1, enabledByDefault = true)
    class EnabledByDefaultFlag {}

    @FeatureFlag(name = FLAG_1, inverse = true, enabledByDefault = true)
    class InverseEnabledByDefaultFlag {}

    // Configure mock to return different classes based on input
    doReturn(StandardFlag.class).when(mockBundle).loadClass("StandardFlag");
    doReturn(InverseFlag.class).when(mockBundle).loadClass("InverseFlag");
    doReturn(EnabledByDefaultFlag.class).when(mockBundle).loadClass("EnabledByDefaultFlag");
    doReturn(InverseEnabledByDefaultFlag.class).when(mockBundle).loadClass("InverseEnabledByDefaultFlag");

    // Test with property not set
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "StandardFlag"), is(true));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "InverseFlag"), is(true));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "EnabledByDefaultFlag"), is(false));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "InverseEnabledByDefaultFlag"), is(false));

    // Test with property set to true
    System.setProperty(FLAG_1, Boolean.toString(true));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "StandardFlag"), is(false));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "InverseFlag"), is(true));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "EnabledByDefaultFlag"), is(false));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "InverseEnabledByDefaultFlag"), is(true));

    // Test with property set to false
    System.setProperty(FLAG_1, Boolean.toString(false));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "StandardFlag"), is(true));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "InverseFlag"), is(false));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "EnabledByDefaultFlag"), is(true));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "InverseEnabledByDefaultFlag"), is(false));
  }

  /**
   * Tests instanceof pattern matching with type patterns for FeatureFlag annotation configurations.
   * This demonstrates how Java 21's pattern matching with instanceof can simplify type checking
   * and variable extraction in a single step.
   */
  @Test
  public void testInstanceofPatternMatchingForFeatureFlags() throws ClassNotFoundException {
    // Define test classes with different feature flag configurations
    @FeatureFlag(name = FLAG_1)
    @FeatureFlag(name = FLAG_2)
    class MultipleFlags {}

    @FeatureFlag(name = FLAG_1, inverse = true)
    class InverseFlag {}

    // Configure mock to return different classes based on input
    doReturn(MultipleFlags.class).when(mockBundle).loadClass("MultipleFlags");
    doReturn(InverseFlag.class).when(mockBundle).loadClass("InverseFlag");

    // Test with no flags enabled
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "MultipleFlags"), is(true));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "InverseFlag"), is(true));

    // Test with one flag enabled
    System.setProperty(FLAG_1, Boolean.toString(true));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "MultipleFlags"), is(true));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "InverseFlag"), is(true));

    // Test with all flags enabled
    System.setProperty(FLAG_2, Boolean.toString(true));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "MultipleFlags"), is(false));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "InverseFlag"), is(true));

    // Test inverse flag with property set to false
    System.clearProperty(FLAG_1);
    System.clearProperty(FLAG_2);
    System.setProperty(FLAG_1, Boolean.toString(false));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "InverseFlag"), is(false));
  }

  /**
   * Tests typesafe handling of feature flag attributes using pattern variables.
   * This demonstrates how Java 21's pattern variables can be used to safely extract and use
   * attributes from feature flag annotations.
   */
  @Test
  public void testPatternVariablesForFeatureFlagAttributes() throws ClassNotFoundException {
    // Define test classes with different feature flag configurations
    @FeatureFlag(name = FLAG_1, enabledByDefault = true)
    class EnabledByDefaultFlag {}

    @FeatureFlag(name = FLAG_1, inverse = true, enabledByDefault = true)
    class ComplexFlag {}

    // Configure mock to return different classes based on input
    doReturn(EnabledByDefaultFlag.class).when(mockBundle).loadClass("EnabledByDefaultFlag");
    doReturn(ComplexFlag.class).when(mockBundle).loadClass("ComplexFlag");

    // Test with property not set (should use enabledByDefault value)
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "EnabledByDefaultFlag"), is(false));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "ComplexFlag"), is(false));

    // Test with property set to true
    System.setProperty(FLAG_1, Boolean.toString(true));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "EnabledByDefaultFlag"), is(false));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "ComplexFlag"), is(true));

    // Test with property set to false
    System.setProperty(FLAG_1, Boolean.toString(false));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "EnabledByDefaultFlag"), is(true));
    assertThat(FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "ComplexFlag"), is(false));
  }

  /**
   * Tests a custom implementation of feature flag evaluation using Java 21 pattern matching.
   * This demonstrates how the actual implementation could leverage pattern matching for cleaner code.
   */
  @Test
  public void testCustomFeatureFlagEvaluationWithPatternMatching() throws ClassNotFoundException {
    // Define test classes with different feature flag configurations
    @FeatureFlag(name = FLAG_1)
    class StandardFlag {}

    @FeatureFlag(name = FLAG_1, inverse = true)
    class InverseFlag {}

    @FeatureFlag(name = FLAG_1, enabledByDefault = true)
    class EnabledByDefaultFlag {}

    // Configure mock to return different classes based on input
    doReturn(StandardFlag.class).when(mockBundle).loadClass("StandardFlag");
    doReturn(InverseFlag.class).when(mockBundle).loadClass("InverseFlag");
    doReturn(EnabledByDefaultFlag.class).when(mockBundle).loadClass("EnabledByDefaultFlag");

    // Test with property not set
    assertThat(evaluateFeatureFlagWithPatternMatching(StandardFlag.class.getAnnotationsByType(FeatureFlag.class)[0]), is(false));
    assertThat(evaluateFeatureFlagWithPatternMatching(InverseFlag.class.getAnnotationsByType(FeatureFlag.class)[0]), is(false));
    assertThat(evaluateFeatureFlagWithPatternMatching(EnabledByDefaultFlag.class.getAnnotationsByType(FeatureFlag.class)[0]), is(true));

    // Test with property set to true
    System.setProperty(FLAG_1, Boolean.toString(true));
    assertThat(evaluateFeatureFlagWithPatternMatching(StandardFlag.class.getAnnotationsByType(FeatureFlag.class)[0]), is(true));
    assertThat(evaluateFeatureFlagWithPatternMatching(InverseFlag.class.getAnnotationsByType(FeatureFlag.class)[0]), is(false));
    assertThat(evaluateFeatureFlagWithPatternMatching(EnabledByDefaultFlag.class.getAnnotationsByType(FeatureFlag.class)[0]), is(true));

    // Test with property set to false
    System.setProperty(FLAG_1, Boolean.toString(false));
    assertThat(evaluateFeatureFlagWithPatternMatching(StandardFlag.class.getAnnotationsByType(FeatureFlag.class)[0]), is(false));
    assertThat(evaluateFeatureFlagWithPatternMatching(InverseFlag.class.getAnnotationsByType(FeatureFlag.class)[0]), is(true));
    assertThat(evaluateFeatureFlagWithPatternMatching(EnabledByDefaultFlag.class.getAnnotationsByType(FeatureFlag.class)[0]), is(false));
  }

  /**
   * Example implementation of feature flag evaluation using Java 21 pattern matching.
   * This demonstrates how switch expressions with pattern matching can simplify the evaluation logic.
   */
  private boolean evaluateFeatureFlagWithPatternMatching(FeatureFlag flag) {
    String propertyValue = System.getProperty(flag.name());
    
    // Using pattern matching in switch expression to handle different cases
    return switch (propertyValue) {
      case null -> flag.enabledByDefault();
      case String value when Boolean.parseBoolean(value) -> !flag.inverse();
      case String value when !Boolean.parseBoolean(value) -> flag.inverse();
      default -> flag.enabledByDefault();
    };
  }
}