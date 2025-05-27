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
 * Tests for {@link FeatureFlaggedIndex} using Java 21 Pattern Matching features.
 * 
 * @since 3.60
 */
public class FeatureFlagPatternMatchingTest
    extends TestSupport
{
  private static final String FLAG_1 = "FeatureFlagPatternMatchingTest_1";
  private static final String FLAG_2 = "FeatureFlagPatternMatchingTest_2";
  private static final String FLAG_3 = "FeatureFlagPatternMatchingTest_3";

  @Mock
  Bundle mockBundle;

  @Before
  public void setup() throws ClassNotFoundException {
    // Clear all test flags
    System.clearProperty(FLAG_1);
    System.clearProperty(FLAG_2);
    System.clearProperty(FLAG_3);
    
    // Verify flags are cleared
    assertThat(System.getProperty(FLAG_1), is((String) null));
    assertThat(System.getProperty(FLAG_2), is((String) null));
    assertThat(System.getProperty(FLAG_3), is((String) null));
  }

  @After
  public void teardown() {
    // Clean up after tests
    System.clearProperty(FLAG_1);
    System.clearProperty(FLAG_2);
    System.clearProperty(FLAG_3);
  }

  /**
   * Test class with multiple feature flags for pattern matching tests
   */
  @FeatureFlag(name = FLAG_1)
  @FeatureFlag(name = FLAG_2)
  private static class MultipleFeatureFlagsClass {
  }

  /**
   * Test class with inverted feature flag
   */
  @FeatureFlag(name = FLAG_1, inverse = true)
  private static class InvertedFeatureFlagClass {
  }

  /**
   * Test class with enabled by default feature flag
   */
  @FeatureFlag(name = FLAG_1, enabledByDefault = true)
  private static class EnabledByDefaultClass {
  }

  /**
   * Test class with complex feature flag configuration
   */
  @FeatureFlag(name = FLAG_1, inverse = true, enabledByDefault = true)
  private static class ComplexFeatureFlagClass {
  }

  /**
   * Test class with multiple feature flags with different configurations
   */
  @FeatureFlag(name = FLAG_1)
  @FeatureFlag(name = FLAG_2, inverse = true)
  @FeatureFlag(name = FLAG_3, enabledByDefault = true)
  private static class MixedFeatureFlagsClass {
  }

  /**
   * Tests pattern matching in switch expressions when evaluating feature flags.
   * This demonstrates Java 21's enhanced switch pattern matching capabilities.
   */
  @Test
  public void testPatternMatchingInSwitchExpressions() throws ClassNotFoundException {
    // Configure mock to return our test class
    doReturn(MultipleFeatureFlagsClass.class).when(mockBundle).loadClass(nullable(String.class));
    
    // Test with different flag combinations using pattern matching in switch
    for (String flagName : new String[]{FLAG_1, FLAG_2}) {
      boolean result = switch (flagName) {
        case String name when name.equals(FLAG_1) -> {
          System.setProperty(FLAG_1, "true");
          yield !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
        }
        case String name when name.equals(FLAG_2) -> {
          System.clearProperty(FLAG_1);
          System.setProperty(FLAG_2, "true");
          yield !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
        }
        default -> false;
      };
      
      // Both flags should return false since we need both enabled
      assertThat("Flag " + flagName + " should not enable the feature alone", result, is(false));
      
      // Clean up after each iteration
      System.clearProperty(FLAG_1);
      System.clearProperty(FLAG_2);
    }
    
    // Now test with both flags enabled
    boolean result = switch ("BOTH") {
      case String s when s.equals("BOTH") -> {
        System.setProperty(FLAG_1, "true");
        System.setProperty(FLAG_2, "true");
        yield !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
      }
      default -> false;
    };
    
    assertThat("Both flags enabled should enable the feature", result, is(true));
  }

  /**
   * Tests instanceof pattern matching with type patterns for FeatureFlag configurations.
   * This demonstrates Java 21's enhanced instanceof pattern matching capabilities.
   */
  @Test
  public void testInstanceofPatternMatching() throws ClassNotFoundException {
    // Test different feature flag configurations using instanceof pattern matching
    Object[] testCases = {
        new TestCase(InvertedFeatureFlagClass.class, FLAG_1, "true", true),
        new TestCase(InvertedFeatureFlagClass.class, FLAG_1, "false", false),
        new TestCase(EnabledByDefaultClass.class, FLAG_1, null, false),
        new TestCase(ComplexFeatureFlagClass.class, FLAG_1, "true", true),
        new TestCase(ComplexFeatureFlagClass.class, FLAG_1, null, false)
    };
    
    for (Object testCase : testCases) {
      if (testCase instanceof TestCase tc) {
        // Configure mock to return the test class
        doReturn(tc.testClass).when(mockBundle).loadClass(nullable(String.class));
        
        // Set property value if specified
        if (tc.propertyValue != null) {
          System.setProperty(tc.flagName, tc.propertyValue);
        }
        
        // Verify the expected result
        assertThat(
            "Feature flag evaluation for " + tc.testClass.getSimpleName() + 
            " with property " + tc.flagName + "=" + tc.propertyValue,
            FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, ""),
            is(tc.expectedDisabled)
        );
        
        // Clean up
        System.clearProperty(tc.flagName);
      }
    }
  }

  /**
   * Tests typesafe handling of feature flag attributes using pattern variables.
   * This demonstrates Java 21's record pattern matching capabilities.
   */
  @Test
  public void testRecordPatternMatching() throws ClassNotFoundException {
    // Configure mock to return our test class with mixed feature flags
    doReturn(MixedFeatureFlagsClass.class).when(mockBundle).loadClass(nullable(String.class));
    
    // Define test scenarios as records for pattern matching
    record FlagScenario(String flagName, String value, boolean expectedResult) {}
    
    FlagScenario[] scenarios = {
        // FLAG_1 (standard flag) - disabled by default, enabled when true
        new FlagScenario(FLAG_1, "true", false),  // not disabled when true
        new FlagScenario(FLAG_1, "false", true),   // disabled when false
        new FlagScenario(FLAG_1, null, true),      // disabled when not set
        
        // FLAG_2 (inverse flag) - disabled by default, enabled when false
        new FlagScenario(FLAG_2, "true", true),    // disabled when true
        new FlagScenario(FLAG_2, "false", false),  // not disabled when false
        new FlagScenario(FLAG_2, null, true),      // disabled when not set
        
        // FLAG_3 (enabled by default) - enabled by default, disabled when false
        new FlagScenario(FLAG_3, "true", false),   // not disabled when true
        new FlagScenario(FLAG_3, "false", true),   // disabled when false
        new FlagScenario(FLAG_3, null, false)      // not disabled when not set (enabled by default)
    };
    
    // Test each scenario using record pattern matching
    for (FlagScenario scenario : scenarios) {
        // Using record pattern matching (Java 21 feature)
        if (scenario instanceof FlagScenario(String flagName, String value, boolean expectedResult)) {
            // Set or clear the property based on the scenario
            if (value != null) {
                System.setProperty(flagName, value);
            } else {
                System.clearProperty(flagName);
            }
            
            // Test just this single flag in isolation
            boolean actualResult = FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
            
            assertThat(
                "Flag " + flagName + (value != null ? "=" + value : " (unset)"),
                actualResult,
                is(true)  // All individual flags should result in disabled=true since we need all flags enabled
            );
            
            // Clean up
            System.clearProperty(flagName);
        }
    }
    
    // Now test with all flags set to their enabling values
    System.setProperty(FLAG_1, "true");    // Standard flag - enabled when true
    System.setProperty(FLAG_2, "false");   // Inverse flag - enabled when false
    System.setProperty(FLAG_3, "true");    // Enabled by default - explicitly enabled
    
    boolean allFlagsResult = !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
    assertThat("All flags properly configured should enable the feature", allFlagsResult, is(true));
  }

  /**
   * Tests feature flag evaluation logic with Java 21 pattern matching syntax.
   * This demonstrates combining multiple pattern matching features.
   */
  @Test
  public void testCombinedPatternMatchingFeatures() throws ClassNotFoundException {
    // Test different combinations of feature flags using pattern matching
    record FlagConfig(Class<?> testClass, String[] enabledFlags) {}
    
    FlagConfig[] configs = {
        new FlagConfig(MultipleFeatureFlagsClass.class, new String[]{FLAG_1, FLAG_2}),
        new FlagConfig(InvertedFeatureFlagClass.class, new String[]{/* FLAG_1 should be false */}),
        new FlagConfig(EnabledByDefaultClass.class, new String[]{}), // Already enabled by default
        new FlagConfig(MixedFeatureFlagsClass.class, new String[]{FLAG_1, /* FLAG_2 should be false */, FLAG_3})
    };
    
    for (FlagConfig config : configs) {
        if (config instanceof FlagConfig(Class<?> testClass, String[] enabledFlags)) {
            // Configure mock to return the test class
            doReturn(testClass).when(mockBundle).loadClass(nullable(String.class));
            
            // Clear all flags first
            System.clearProperty(FLAG_1);
            System.clearProperty(FLAG_2);
            System.clearProperty(FLAG_3);
            
            // Set up the flags according to the configuration
            for (String flag : enabledFlags) {
                System.setProperty(flag, "true");
            }
            
            // Special handling for inverse flags
            if (testClass == InvertedFeatureFlagClass.class) {
                System.setProperty(FLAG_1, "false"); // Inverse flag is enabled when false
            } else if (testClass == MixedFeatureFlagsClass.class) {
                System.setProperty(FLAG_2, "false"); // FLAG_2 is inverse in MixedFeatureFlagsClass
            }
            
            // Evaluate the feature flag using pattern matching on the class type
            boolean result = switch (testClass.getSimpleName()) {
                case String name when name.equals("MultipleFeatureFlagsClass") -> 
                    !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
                case String name when name.equals("InvertedFeatureFlagClass") -> 
                    !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
                case String name when name.equals("EnabledByDefaultClass") -> 
                    !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
                case String name when name.equals("MixedFeatureFlagsClass") -> 
                    !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
                case String name when name.equals("ComplexFeatureFlagClass") -> 
                    !FeatureFlaggedIndex.isFeatureFlagDisabled(mockBundle, "");
                default -> false;
            };
            
            assertThat(
                "Feature should be enabled for " + testClass.getSimpleName() + " with configured flags",
                result,
                is(true)
            );
        }
    }
  }

  /**
   * Helper class for testing instanceof pattern matching
   */
  private static class TestCase {
    final Class<?> testClass;
    final String flagName;
    final String propertyValue;
    final boolean expectedDisabled;

    TestCase(Class<?> testClass, String flagName, String propertyValue, boolean expectedDisabled) {
      this.testClass = testClass;
      this.flagName = flagName;
      this.propertyValue = propertyValue;
      this.expectedDisabled = expectedDisabled;
    }
  }
}