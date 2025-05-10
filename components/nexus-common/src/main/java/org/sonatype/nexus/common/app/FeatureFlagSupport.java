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

import java.lang.annotation.Annotation;
import java.util.Optional;

/**
 * Utility class for working with {@link FeatureFlag} annotations.
 * <p>
 * This class provides modern Java idioms for interacting with feature flags and system properties.
 * It is compatible with Java 21 reflection and annotation processing.
 *
 * @since 3.41
 */
public final class FeatureFlagSupport
{
  private FeatureFlagSupport() {
    // Prevent instantiation
  }

  /**
   * Checks if a feature flag is enabled for the given annotation.
   *
   * @param featureFlag the feature flag annotation to check
   * @return true if the feature is enabled, false otherwise
   */
  public static boolean isEnabled(final FeatureFlag featureFlag) {
    if (featureFlag == null) {
      return true; // No feature flag means the feature is always enabled
    }
    
    String propertyName = featureFlag.name();
    boolean enabledByDefault = featureFlag.enabledByDefault();
    boolean inverse = featureFlag.inverse();
    
    return isFeatureEnabled(propertyName, enabledByDefault, inverse);
  }

  /**
   * Checks if a feature flag is enabled for the given class.
   *
   * @param clazz the class to check for feature flag annotations
   * @return true if all feature flags on the class are enabled, false otherwise
   */
  public static boolean isEnabled(final Class<?> clazz) {
    if (clazz == null) {
      return true; // No class means no feature flags to check
    }
    
    // Check for individual feature flag
    FeatureFlag featureFlag = clazz.getAnnotation(FeatureFlag.class);
    if (featureFlag != null && !isEnabled(featureFlag)) {
      return false;
    }
    
    // Check for feature flag group
    FeatureFlagGroup featureFlagGroup = clazz.getAnnotation(FeatureFlagGroup.class);
    if (featureFlagGroup != null) {
      for (FeatureFlag flag : featureFlagGroup.value()) {
        if (!isEnabled(flag)) {
          return false;
        }
      }
    }
    
    // Check package-level feature flags
    Package pkg = clazz.getPackage();
    if (pkg != null) {
      featureFlag = pkg.getAnnotation(FeatureFlag.class);
      if (featureFlag != null && !isEnabled(featureFlag)) {
        return false;
      }
      
      featureFlagGroup = pkg.getAnnotation(FeatureFlagGroup.class);
      if (featureFlagGroup != null) {
        for (FeatureFlag flag : featureFlagGroup.value()) {
          if (!isEnabled(flag)) {
            return false;
          }
        }
      }
    }
    
    return true;
  }

  /**
   * Checks if a feature is enabled based on the system property value.
   *
   * @param propertyName the name of the system property to check
   * @param enabledByDefault whether the feature is enabled by default if the property is not set
   * @param inverse whether to invert the logic (true becomes false and vice versa)
   * @return true if the feature is enabled, false otherwise
   */
  private static boolean isFeatureEnabled(final String propertyName, 
                                         final boolean enabledByDefault, 
                                         final boolean inverse) {
    // Use Optional to handle the system property in a more modern way
    boolean propertyValue = Optional.ofNullable(System.getProperty(propertyName))
        .map(Boolean::parseBoolean)
        .orElse(enabledByDefault);
    
    return inverse ? !propertyValue : propertyValue;
  }
}