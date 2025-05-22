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
package org.sonatype.nexus.extender;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for Java 21 Pattern Matching features in the Nexus extender components.
 * 
 * This test class demonstrates and validates the use of Java 21 pattern matching features
 * in the context of Nexus Repository Manager, including:
 * 
 * - Pattern matching for switch expressions with bundle state evaluation
 * - Pattern matching with sealed types for feature flag configuration
 * - Exhaustiveness checking with different bundle and service types
 * - Guarded patterns for conditional matching
 * - Record patterns for destructuring complex objects
 * - Pattern matching with instanceof for type-safe casting
 * 
 * These tests ensure that pattern matching correctly handles all expected cases
 * and provides type-safe, concise code for type checking and state handling.
 *
 * @since 3.60
 */
@DisplayName("Pattern Matching Tests for Java 21 Features")
public class PatternMatchingTest
{
  /**
   * Sealed class hierarchy for testing pattern matching with inheritance.
   */
  /**
   * Sealed interface hierarchy for feature flags.
   * This demonstrates the use of sealed types with pattern matching.
   */
  sealed interface FeatureFlag permits EnabledFeatureFlag, DisabledFeatureFlag, ConditionalFeatureFlag {
    String name();
  }
  
  /**
   * Represents a feature that is always enabled.
   */
  record EnabledFeatureFlag(String name) implements FeatureFlag {}
  
  /**
   * Represents a feature that is always disabled.
   */
  record DisabledFeatureFlag(String name) implements FeatureFlag {}
  
  /**
   * Represents a feature that is conditionally enabled based on the edition.
   */
  record ConditionalFeatureFlag(String name, String edition) implements FeatureFlag {}

  /**
   * Tests pattern matching for switch with bundle state evaluation.
   */
  @Test
  @DisplayName("Pattern matching for switch with bundle state evaluation")
  public void testBundleStatePatternMatching() {
    // Create mock bundles with different states
    Bundle activeBundle = mock(Bundle.class);
    when(activeBundle.getState()).thenReturn(Bundle.ACTIVE);
    when(activeBundle.getSymbolicName()).thenReturn("org.sonatype.nexus.active");
    
    Bundle installedBundle = mock(Bundle.class);
    when(installedBundle.getState()).thenReturn(Bundle.INSTALLED);
    when(installedBundle.getSymbolicName()).thenReturn("org.sonatype.nexus.installed");
    
    Bundle resolvedBundle = mock(Bundle.class);
    when(resolvedBundle.getState()).thenReturn(Bundle.RESOLVED);
    when(resolvedBundle.getSymbolicName()).thenReturn("org.sonatype.nexus.resolved");
    
    Bundle startingBundle = mock(Bundle.class);
    when(startingBundle.getState()).thenReturn(Bundle.STARTING);
    when(startingBundle.getSymbolicName()).thenReturn("org.sonatype.nexus.starting");
    
    // Test pattern matching for switch with bundle state
    assertEquals("Active", getBundleStateDescription(activeBundle));
    assertEquals("Installed", getBundleStateDescription(installedBundle));
    assertEquals("Resolved", getBundleStateDescription(resolvedBundle));
    assertEquals("Starting", getBundleStateDescription(startingBundle));
  }

  /**
   * Tests pattern matching with feature flag configuration objects.
   */
  @Test
  @DisplayName("Pattern matching with feature flag configuration objects")
  public void testFeatureFlagPatternMatching() {
    // Create feature flag objects
    FeatureFlag enabledFlag = new EnabledFeatureFlag("test.enabled");
    FeatureFlag disabledFlag = new DisabledFeatureFlag("test.disabled");
    FeatureFlag conditionalFlag = new ConditionalFeatureFlag("test.conditional", "PRO");
    
    // Test pattern matching with feature flags
    assertTrue(isFeatureEnabled(enabledFlag, "OSS"));
    assertFalse(isFeatureEnabled(disabledFlag, "OSS"));
    assertFalse(isFeatureEnabled(conditionalFlag, "OSS"));
    assertTrue(isFeatureEnabled(conditionalFlag, "PRO"));
  }

  /**
   * Tests exhaustiveness checking with different bundle types.
   */
  @Test
  @DisplayName("Exhaustiveness checking with different bundle types")
  public void testExhaustivenessChecking() {
    // Create mock bundle context and bundles
    BundleContext bundleContext = mock(BundleContext.class);
    
    Bundle systemBundle = mock(Bundle.class);
    when(systemBundle.getBundleId()).thenReturn(0L);
    when(systemBundle.getSymbolicName()).thenReturn("org.osgi.framework");
    
    Bundle nexusBundle = mock(Bundle.class);
    when(nexusBundle.getBundleId()).thenReturn(1L);
    when(nexusBundle.getSymbolicName()).thenReturn("org.sonatype.nexus.core");
    
    Bundle pluginBundle = mock(Bundle.class);
    when(pluginBundle.getBundleId()).thenReturn(2L);
    when(pluginBundle.getSymbolicName()).thenReturn("org.sonatype.nexus.plugin");
    
    // Test exhaustiveness checking with bundle types
    assertEquals(BundleType.SYSTEM, getBundleType(systemBundle));
    assertEquals(BundleType.NEXUS_CORE, getBundleType(nexusBundle));
    assertEquals(BundleType.PLUGIN, getBundleType(pluginBundle));
  }

  /**
   * Tests pattern matching with nested patterns.
   */
  @Test
  @DisplayName("Pattern matching with nested patterns")
  public void testNestedPatternMatching() {
    // Create feature flag objects with nested structure
    FeatureFlag enabledFlag = new EnabledFeatureFlag("test.enabled");
    FeatureFlag conditionalFlag = new ConditionalFeatureFlag("test.conditional", "PRO");
    
    // Test nested pattern matching
    assertEquals("Enabled: test.enabled", getFeatureFlagDescription(enabledFlag));
    assertEquals("Conditional: test.conditional for edition PRO", getFeatureFlagDescription(conditionalFlag));
  }
  
  /**
   * Tests pattern matching with guarded patterns.
   */
  @Test
  @DisplayName("Pattern matching with guarded patterns")
  public void testGuardedPatternMatching() {
    // Create mock service references with different properties
    ServiceReference<?> nexusServiceRef = mock(ServiceReference.class);
    when(nexusServiceRef.getProperty("service.vendor")).thenReturn("Sonatype");
    when(nexusServiceRef.getProperty("service.name")).thenReturn("NexusService");
    
    ServiceReference<?> thirdPartyServiceRef = mock(ServiceReference.class);
    when(thirdPartyServiceRef.getProperty("service.vendor")).thenReturn("ThirdParty");
    when(thirdPartyServiceRef.getProperty("service.name")).thenReturn("ExternalService");
    
    ServiceReference<?> unknownServiceRef = mock(ServiceReference.class);
    when(unknownServiceRef.getProperty("service.name")).thenReturn("UnknownService");
    
    // Test guarded pattern matching
    assertEquals("Sonatype NexusService", getServiceDescription(nexusServiceRef));
    assertEquals("ThirdParty ExternalService", getServiceDescription(thirdPartyServiceRef));
    assertEquals("Unknown service: UnknownService", getServiceDescription(unknownServiceRef));
  }
  
  /**
   * Tests pattern matching with record patterns and instanceof.
   */
  @Test
  @DisplayName("Pattern matching with record patterns and instanceof")
  public void testRecordPatternMatching() {
    // Create feature flag objects
    Object enabledFlag = new EnabledFeatureFlag("test.enabled");
    Object disabledFlag = new DisabledFeatureFlag("test.disabled");
    Object conditionalFlag = new ConditionalFeatureFlag("test.conditional", "PRO");
    Object nonFlag = "Not a feature flag";
    
    // Test pattern matching with instanceof and record patterns
    assertEquals("test.enabled", extractFeatureName(enabledFlag));
    assertEquals("test.disabled", extractFeatureName(disabledFlag));
    assertEquals("test.conditional", extractFeatureName(conditionalFlag));
    assertNull(extractFeatureName(nonFlag));
  }

  /**
   * Enum representing different bundle types for exhaustiveness checking.
   * Pattern matching for switch ensures all cases are handled.
   */
  enum BundleType {
    SYSTEM,       // System bundle (bundle ID 0)
    NEXUS_CORE,   // Core Nexus bundles
    PLUGIN        // Plugin bundles
  }

  /**
   * Gets a description of the bundle state using pattern matching for switch.
   * 
   * This demonstrates how pattern matching for switch expressions provides a more
   * concise and readable alternative to traditional switch statements when working
   * with OSGi bundle states.
   */
  private String getBundleStateDescription(Bundle bundle) {
    return switch (bundle.getState()) {
      case Bundle.ACTIVE -> "Active";
      case Bundle.INSTALLED -> "Installed";
      case Bundle.RESOLVED -> "Resolved";
      case Bundle.STARTING -> "Starting";
      case Bundle.STOPPING -> "Stopping";
      case Bundle.UNINSTALLED -> "Uninstalled";
      default -> "Unknown";
    };
  }

  /**
   * Determines if a feature is enabled based on its type and the current edition.
   * Uses pattern matching for switch with sealed types.
   * 
   * This demonstrates how pattern matching with sealed types ensures exhaustiveness
   * at compile time, eliminating the need for a default case while guaranteeing
   * that all possible subtypes are handled.
   */
  private boolean isFeatureEnabled(FeatureFlag flag, String currentEdition) {
    return switch (flag) {
      case EnabledFeatureFlag ef -> true;
      case DisabledFeatureFlag df -> false;
      case ConditionalFeatureFlag cf -> cf.edition().equalsIgnoreCase(currentEdition);
    };
  }

  /**
   * Gets the bundle type using pattern matching for switch with exhaustiveness checking.
   * 
   * This demonstrates how pattern matching with guarded patterns allows for more
   * complex conditions beyond simple value matching, combining the switch expression
   * with additional predicates for powerful and expressive pattern matching.
   */
  private BundleType getBundleType(Bundle bundle) {
    return switch (bundle.getBundleId()) {
      case 0L -> BundleType.SYSTEM;
      case Long l when bundle.getSymbolicName().startsWith("org.sonatype.nexus.core") -> BundleType.NEXUS_CORE;
      default -> BundleType.PLUGIN;
    };
  }

  /**
   * Gets a description of the feature flag using nested pattern matching.
   */
  private String getFeatureFlagDescription(FeatureFlag flag) {
    return switch (flag) {
      case EnabledFeatureFlag(String name) -> "Enabled: " + name;
      case DisabledFeatureFlag(String name) -> "Disabled: " + name;
      case ConditionalFeatureFlag(String name, String edition) -> "Conditional: " + name + " for edition " + edition;
    };
  }
  
  /**
   * Gets a description of the service using guarded pattern matching.
   */
  private String getServiceDescription(ServiceReference<?> serviceRef) {
    return switch (serviceRef) {
      case ServiceReference<?> ref when "Sonatype".equals(ref.getProperty("service.vendor")) ->
          "Sonatype " + ref.getProperty("service.name");
      case ServiceReference<?> ref when "ThirdParty".equals(ref.getProperty("service.vendor")) ->
          "ThirdParty " + ref.getProperty("service.name");
      case ServiceReference<?> ref when ref.getProperty("service.name") != null ->
          "Unknown service: " + ref.getProperty("service.name");
      default -> "Unidentified service";
    };
  }
  
  /**
   * Extracts the feature name using instanceof pattern matching.
   */
  private String extractFeatureName(Object obj) {
    if (obj instanceof EnabledFeatureFlag(String name)) {
      return name;
    } else if (obj instanceof DisabledFeatureFlag(String name)) {
      return name;
    } else if (obj instanceof ConditionalFeatureFlag(String name, String edition)) {
      return name;
    } else {
      return null;
    }
  }
  
  /**
   * Tests pattern matching for feature flag installation mode parsing.
   * This test is inspired by the actual feature flag parsing in NexusContextListener.
   */
  @Test
  @DisplayName("Pattern matching for feature flag installation mode parsing")
  public void testFeatureFlagInstallModePatternMatching() {
    // Test various installation mode strings
    assertTrue(isFeatureFlagEnabledForEdition("featureFlag:enabledByDefault:test.flag", "OSS"));
    assertTrue(isFeatureFlagEnabledForEdition("featureFlag:enabledByDefault:test.flag", "PRO"));
    
    assertTrue(isFeatureFlagEnabledForEdition("oss:featureFlag:enabledByDefault:test.flag", "OSS"));
    assertFalse(isFeatureFlagEnabledForEdition("oss:featureFlag:enabledByDefault:test.flag", "PRO"));
    
    assertTrue(isFeatureFlagEnabledForEdition("pro:featureFlag:enabledByDefault:test.flag", "PRO"));
    assertFalse(isFeatureFlagEnabledForEdition("pro:featureFlag:enabledByDefault:test.flag", "OSS"));
    
    assertTrue(isFeatureFlagEnabledForEdition("oss,pro:featureFlag:enabledByDefault:test.flag", "OSS"));
    assertTrue(isFeatureFlagEnabledForEdition("oss,pro:featureFlag:enabledByDefault:test.flag", "PRO"));
    
    assertFalse(isFeatureFlagEnabledForEdition("malformed:feature:flag", "OSS"));
  }
  
  /**
   * Demonstrates pattern matching with Optional and Map.
   */
  @Test
  @DisplayName("Pattern matching with Optional and Map")
  public void testOptionalAndMapPatternMatching() {
    // Create test data
    Map<String, String> configMap = Map.of(
        "nexus.feature.enabled", "true",
        "nexus.edition", "PRO"
    );
    
    Optional<String> presentValue = Optional.of("test.value");
    Optional<String> emptyValue = Optional.empty();
    
    // Test pattern matching with Optional
    assertEquals("Value: test.value", formatOptionalValue(presentValue));
    assertEquals("No value present", formatOptionalValue(emptyValue));
    
    // Test pattern matching with Map entries
    assertEquals("Feature is enabled", evaluateConfigEntry("nexus.feature.enabled", configMap));
    assertEquals("Edition is PRO", evaluateConfigEntry("nexus.edition", configMap));
    assertEquals("Configuration not found: unknown.key", evaluateConfigEntry("unknown.key", configMap));
  }
  
  /**
   * Formats an optional value using pattern matching.
   */
  private String formatOptionalValue(Optional<String> optional) {
    return switch (optional) {
      case Optional.empty() -> "No value present";
      case Optional<String> opt when opt.get().length() > 10 -> "Long value: " + opt.get();
      case Optional<String> opt -> "Value: " + opt.get();
    };
  }
  
  /**
   * Evaluates a configuration entry using pattern matching with Map.Entry.
   */
  private String evaluateConfigEntry(String key, Map<String, String> configMap) {
    return switch (key) {
      case String k when configMap.containsKey(k) && "true".equals(configMap.get(k)) -> "Feature is enabled";
      case String k when configMap.containsKey(k) && configMap.get(k).startsWith("PRO") -> "Edition is PRO";
      case String k when configMap.containsKey(k) -> "Configuration found: " + configMap.get(k);
      default -> "Configuration not found: " + key;
    };
  }
  
  /**
   * Determines if a feature flag is enabled for a specific edition based on the installation mode.
   * This is a simplified version of the logic in NexusContextListener.isFeatureFlagEnabled.
   * 
   * Demonstrates pattern matching with regular expressions and guarded patterns.
   */
  private boolean isFeatureFlagEnabledForEdition(String installMode, String currentEdition) {
    if (installMode == null) {
      return false;
    }
    
    // Pattern matching with regular expressions
    return switch (installMode) {
      // Common feature flag enabled by default for all editions
      case String s when s.matches("featureFlag:enabledByDefault:.+") -> true;
      
      // Edition-specific feature flag enabled by default
      case String s when s.matches("([a-z,]+):featureFlag:enabledByDefault:.+") -> {
        String editions = s.split(":")[0].toLowerCase();
        yield editions.contains(currentEdition.toLowerCase());
      }
      
      // Malformed feature flag
      default -> false;
    };
  }
}