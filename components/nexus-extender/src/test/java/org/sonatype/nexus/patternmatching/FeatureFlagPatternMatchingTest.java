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
package org.sonatype.nexus.patternmatching;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests for Java 21 pattern matching in feature flag processing.
 * 
 * This test validates that the pattern matching implementation produces identical results
 * to the original implementation while providing more concise and maintainable code.
 */
@RunWith(Parameterized.class)
public class FeatureFlagPatternMatchingTest
{
  private final String installMode;

  private final String flag;

  private final Boolean flagValue;

  private final String edition;

  private final boolean expectedResult;

  public FeatureFlagPatternMatchingTest(
      final String installMode,
      final String flag,
      final Boolean flagValue,
      final String edition,
      final boolean expectedResult)
  {
    this.installMode = installMode;
    this.flag = flag;
    this.flagValue = flagValue;
    this.edition = edition;
    this.expectedResult = expectedResult;
  }

  @Parameters(name = "{index}: installMode: {0}, flag: {1}, flagValue: {2}, edition: {3}, expectedResult: {4}")
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][]{
        // Multiple editions with enabled by default
        {"oss,pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "COMMUNITY", true},
        {"oss,pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", true},
        {"oss,pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", true},
        {"oss,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", false},
        {"oss,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", true},
        {"pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", false},
        {"pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", true},
        {"pro,community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "COMMUNITY", true},
        {"community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", false},
        {"community:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "COMMUNITY", true},
        {"featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "COMMUNITY", true},

        // OSS-specific feature flags
        {"oss:featureFlag:foo.enabled", "foo.enabled", true, "OSS", true},
        {"oss:featureFlag:foo.enabled", "foo.enabled", true, "PRO", false},
        {"oss:featureFlag:foo.enabled", "foo.enabled", false, "OSS", false},
        {"oss:featureFlag:foo.enabled", "foo.enabled", false, "PRO", false},
        {"oss:featureFlag:foo.enabled", "foo.enabled", null, "OSS", false},
        {"oss:featureFlag:foo.enabled", "foo.enabled", null, "PRO", false},
        {"oss:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", true},
        {"oss:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", false},
        {"oss:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "OSS", false},
        {"oss:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "PRO", false},
        {"oss:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "OSS", true},
        {"oss:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "PRO", false},
        
        // PRO-only feature flags
        {"pro:featureFlag:foo.enabled", "foo.enabled", true, "OSS", false},
        {"pro:featureFlag:foo.enabled", "foo.enabled", true, "PRO", true},
        {"pro:featureFlag:foo.enabled", "foo.enabled", false, "OSS", false},
        {"pro:featureFlag:foo.enabled", "foo.enabled", false, "PRO", false},
        {"pro:featureFlag:foo.enabled", "foo.enabled", null, "OSS", false},
        {"pro:featureFlag:foo.enabled", "foo.enabled", null, "PRO", false},
        {"pro:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", false},
        {"pro:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", true},
        {"pro:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "OSS", false},
        {"pro:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "PRO", false},
        {"pro:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "OSS", false},
        {"pro:featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "PRO", true},
        
        // Common feature flags
        {"featureFlag:foo.enabled", "foo.enabled", true, "OSS", true},
        {"featureFlag:foo.enabled", "foo.enabled", true, "PRO", true},
        {"featureFlag:foo.enabled", "foo.enabled", false, "OSS", false},
        {"featureFlag:foo.enabled", "foo.enabled", false, "PRO", false},
        {"featureFlag:foo.enabled", "foo.enabled", null, "OSS", false},
        {"featureFlag:foo.enabled", "foo.enabled", null, "PRO", false},
        {"featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", true},
        {"featureFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "PRO", true},
        {"featureFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "OSS", false},
        {"featureFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "PRO", false},
        {"featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "OSS", true},
        {"featureFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "PRO", true},
        
        // Malformed feature flag strings
        {"featureFlag:", "foo.enabled", null, "OSS", false},
        {"featureFlag:", "foo.enabled", null, "PRO", false},
        {"featureFlag:enabledByDefault:", "foo.enabled", null, "OSS", false},
        {"foo:featureFlag:enabledByDefault:", "foo.enabled", null, "OSS", false},
        {"fooFlag:enabledByDefault:foo.enabled", "foo.enabled", null, "OSS", false},
        {"featureFlag:", "foo.enabled", true, "OSS", false},
        {"featureFlag:", "foo.enabled", true, "PRO", false},
        {"featureFlag:enabledByDefault:", "foo.enabled", true, "OSS", false},
        {"foo:featureFlag:enabledByDefault:", "foo.enabled", true, "OSS", false},
        {"fooFlag:enabledByDefault:foo.enabled", "foo.enabled", true, "OSS", false},
        {"featureFlag:", "foo.enabled", false, "OSS", false},
        {"featureFlag:", "foo.enabled", false, "PRO", false},
        {"featureFlag:enabledByDefault:", "foo.enabled", false, "OSS", false},
        {"foo:featureFlag:enabledByDefault:", "foo.enabled", false, "OSS", false},
        {"fooFlag:enabledByDefault:foo.enabled", "foo.enabled", false, "OSS", false},
        {"", "foo.enabled", null, "OSS", false}
    });
  }

  @Before
  public void setup() {
    System.clearProperty(flag);
    if (flagValue != null) {
      System.setProperty(flag, String.valueOf(flagValue));
    }
  }

  /**
   * Test that compares the original feature flag implementation with the new pattern matching implementation.
   * Both implementations should produce identical results for all test cases.
   */
  @Test
  public void testPatternMatchingImplementation() {
    boolean originalResult = isFeatureFlagEnabledOriginal(edition, installMode);
    boolean patternMatchingResult = isFeatureFlagEnabledWithPatternMatching(edition, installMode);
    
    // Both implementations should match the expected result
    assertThat(originalResult, is(expectedResult));
    assertThat(patternMatchingResult, is(expectedResult));
    
    // Both implementations should produce identical results
    assertThat(patternMatchingResult, is(originalResult));
  }

  /**
   * Original implementation of feature flag evaluation logic.
   * This is based on the implementation in NexusContextListener.
   */
  private boolean isFeatureFlagEnabledOriginal(final String edition, final String installMode) {
    if (installMode == null || installMode.isEmpty()) {
      return false;
    }

    String[] parts = installMode.split(":");
    if (parts.length < 2) {
      return false;
    }

    String[] editions = parts[0].split(",");
    List<String> editionsList = Arrays.asList(editions);

    if (!parts[1].equals("featureFlag")) {
      return false;
    }

    if (!editionsList.isEmpty() && !editionsList.contains(edition.toLowerCase())) {
      return false;
    }

    if (parts.length == 2) {
      return Boolean.getBoolean(flag);
    }
    else if (parts.length == 3) {
      if (parts[2].equals("enabledByDefault")) {
        return flag == null || Boolean.getBoolean(flag);
      }
      else {
        return Boolean.getBoolean(parts[2]);
      }
    }
    else if (parts.length == 4) {
      if (parts[2].equals("enabledByDefault")) {
        String propertyName = parts[3];
        return propertyName.equals(flag) && (System.getProperty(flag) == null || Boolean.getBoolean(flag));
      }
      else {
        return false;
      }
    }
    else {
      return false;
    }
  }

  /**
   * New implementation using Java 21 pattern matching in switch expressions.
   * This implementation should produce identical results to the original but with more concise code.
   */
  private boolean isFeatureFlagEnabledWithPatternMatching(final String edition, final String installMode) {
    if (installMode == null || installMode.isEmpty()) {
      return false;
    }

    String[] parts = installMode.split(":");
    if (parts.length < 2) {
      return false;
    }

    String[] editions = parts[0].split(",");
    List<String> editionsList = Arrays.asList(editions);

    // Check if this is a feature flag configuration
    if (!"featureFlag".equals(parts[1])) {
      return false;
    }

    // Check if the edition is supported
    if (!editionsList.isEmpty() && !editionsList.contains(edition.toLowerCase())) {
      return false;
    }

    // Use pattern matching in switch expression to handle different feature flag formats
    return switch (parts) {
      // Case: edition:featureFlag
      case String[] p when p.length == 2 -> Boolean.getBoolean(flag);
      
      // Case: edition:featureFlag:enabledByDefault
      case String[] p when p.length == 3 && "enabledByDefault".equals(p[2]) -> 
          System.getProperty(flag) == null || Boolean.getBoolean(flag);
      
      // Case: edition:featureFlag:specificFlag
      case String[] p when p.length == 3 -> Boolean.getBoolean(p[2]);
      
      // Case: edition:featureFlag:enabledByDefault:specificFlag
      case String[] p when p.length == 4 && "enabledByDefault".equals(p[2]) && flag.equals(p[3]) ->
          System.getProperty(flag) == null || Boolean.getBoolean(flag);
      
      // Default case: malformed feature flag
      default -> false;
    };
  }
}