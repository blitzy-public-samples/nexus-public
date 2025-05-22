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

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests for Java 21 record pattern matching with configuration data in the Nexus Extender module.
 * 
 * @since 3.60
 */
public class ExtenderConfigRecordPatternTest
    extends TestSupport
{
  /**
   * Simple configuration record for module settings
   */
  record ModuleConfig(String name, boolean enabled, int priority) {}
  
  /**
   * Lifecycle configuration record containing module settings
   */
  record LifecycleConfig(String phase, ModuleConfig moduleConfig) {}
  
  /**
   * Nested configuration record for complex settings
   */
  record ExtenderConfig(String version, LifecycleConfig lifecycleConfig, SecurityConfig securityConfig) {}
  
  /**
   * Security configuration record
   */
  record SecurityConfig(String realm, AuthConfig authConfig) {}
  
  /**
   * Authentication configuration record
   */
  record AuthConfig(String type, boolean required) {}

  /**
   * Tests basic record pattern matching for simple configuration extraction.
   */
  @Test
  public void testBasicRecordPatternMatching() {
    ModuleConfig config = new ModuleConfig("test-module", true, 10);
    Object obj = config;
    
    // Traditional approach with instanceof and casting
    if (obj instanceof ModuleConfig) {
      ModuleConfig moduleConfig = (ModuleConfig) obj;
      assertThat(moduleConfig.name(), is("test-module"));
      assertThat(moduleConfig.enabled(), is(true));
      assertThat(moduleConfig.priority(), is(10));
    }
    
    // Java 21 record pattern matching approach
    if (obj instanceof ModuleConfig(String name, boolean enabled, int priority)) {
      assertThat(name, is("test-module"));
      assertThat(enabled, is(true));
      assertThat(priority, is(10));
    }
  }

  /**
   * Tests record pattern matching with var for type inference.
   */
  @Test
  public void testRecordPatternMatchingWithVar() {
    ModuleConfig config = new ModuleConfig("test-module", true, 10);
    Object obj = config;
    
    // Java 21 record pattern matching with var for type inference
    if (obj instanceof ModuleConfig(var name, var enabled, var priority)) {
      assertThat(name, is("test-module"));
      assertThat(enabled, is(true));
      assertThat(priority, is(10));
    }
  }

  /**
   * Tests nested record pattern matching for multi-level configuration access.
   */
  @Test
  public void testNestedRecordPatternMatching() {
    ModuleConfig moduleConfig = new ModuleConfig("test-module", true, 10);
    LifecycleConfig lifecycleConfig = new LifecycleConfig("STARTUP", moduleConfig);
    AuthConfig authConfig = new AuthConfig("basic", true);
    SecurityConfig securityConfig = new SecurityConfig("default", authConfig);
    ExtenderConfig extenderConfig = new ExtenderConfig("1.0", lifecycleConfig, securityConfig);
    
    Object obj = extenderConfig;
    
    // Traditional approach with instanceof and casting - multiple levels of access
    if (obj instanceof ExtenderConfig) {
      ExtenderConfig config = (ExtenderConfig) obj;
      LifecycleConfig lifecycle = config.lifecycleConfig();
      ModuleConfig module = lifecycle.moduleConfig();
      SecurityConfig security = config.securityConfig();
      AuthConfig auth = security.authConfig();
      
      assertThat(module.name(), is("test-module"));
      assertThat(module.enabled(), is(true));
      assertThat(auth.type(), is("basic"));
      assertThat(auth.required(), is(true));
    }
    
    // Java 21 nested record pattern matching - direct access to nested components
    if (obj instanceof ExtenderConfig(var version, 
                                     LifecycleConfig(var phase, ModuleConfig(var name, var enabled, var priority)),
                                     SecurityConfig(var realm, AuthConfig(var authType, var authRequired)))) {
      assertThat(version, is("1.0"));
      assertThat(phase, is("STARTUP"));
      assertThat(name, is("test-module"));
      assertThat(enabled, is(true));
      assertThat(priority, is(10));
      assertThat(realm, is("default"));
      assertThat(authType, is("basic"));
      assertThat(authRequired, is(true));
    }
  }

  /**
   * Tests record pattern matching with guard conditions for conditional configuration extraction.
   */
  @Test
  public void testRecordPatternMatchingWithGuards() {
    ModuleConfig enabledConfig = new ModuleConfig("enabled-module", true, 10);
    ModuleConfig disabledConfig = new ModuleConfig("disabled-module", false, 5);
    ModuleConfig highPriorityConfig = new ModuleConfig("high-priority-module", true, 100);
    
    assertThat(getModuleStatus(enabledConfig), is("Module enabled-module is active with priority 10"));
    assertThat(getModuleStatus(disabledConfig), is("Module disabled-module is inactive"));
    assertThat(getModuleStatus(highPriorityConfig), is("High priority module: high-priority-module"));
    assertThat(getModuleStatus(null), is("No module configuration found"));
  }
  
  /**
   * Helper method that uses pattern matching with guards in a switch expression.
   */
  private String getModuleStatus(Object config) {
    return switch (config) {
      case ModuleConfig(var name, var enabled, var priority) when priority > 50 -> 
          "High priority module: " + name;
      case ModuleConfig(var name, true, var priority) -> 
          "Module " + name + " is active with priority " + priority;
      case ModuleConfig(var name, false, var priority) -> 
          "Module " + name + " is inactive";
      case null -> 
          "No module configuration found";
      default -> 
          "Unknown configuration";
    };
  }

  /**
   * Tests pattern matching in switch for different configuration types.
   */
  @Test
  public void testPatternMatchingInSwitch() {
    ModuleConfig moduleConfig = new ModuleConfig("test-module", true, 10);
    LifecycleConfig lifecycleConfig = new LifecycleConfig("STARTUP", moduleConfig);
    AuthConfig authConfig = new AuthConfig("basic", true);
    
    assertThat(getConfigType(moduleConfig), is("Module configuration: test-module"));
    assertThat(getConfigType(lifecycleConfig), is("Lifecycle configuration for phase: STARTUP"));
    assertThat(getConfigType(authConfig), is("Auth configuration of type: basic"));
    assertThat(getConfigType("not-a-config"), is("Not a configuration object"));
  }
  
  /**
   * Helper method that uses pattern matching in a switch expression to determine configuration type.
   */
  private String getConfigType(Object config) {
    return switch (config) {
      case ModuleConfig(var name, var enabled, var priority) -> 
          "Module configuration: " + name;
      case LifecycleConfig(var phase, var moduleConfig) -> 
          "Lifecycle configuration for phase: " + phase;
      case AuthConfig(var type, var required) -> 
          "Auth configuration of type: " + type;
      default -> 
          "Not a configuration object";
    };
  }

  /**
   * Tests partial record pattern matching where only some components are extracted.
   */
  @Test
  public void testPartialRecordPatternMatching() {
    LifecycleConfig config = new LifecycleConfig("STARTUP", new ModuleConfig("test-module", true, 10));
    Object obj = config;
    
    // Extract only the phase from LifecycleConfig
    if (obj instanceof LifecycleConfig(String phase, var moduleConfig)) {
      assertThat(phase, is("STARTUP"));
      // We can still access the moduleConfig as a whole
      assertThat(moduleConfig.name(), is("test-module"));
    }
    
    // Extract only the name from the nested ModuleConfig
    if (obj instanceof LifecycleConfig(var phase, ModuleConfig(String name, var enabled, var priority))) {
      assertThat(phase, is("STARTUP"));
      assertThat(name, is("test-module"));
    }
  }

  /**
   * Tests record pattern matching with null handling.
   */
  @Test
  public void testRecordPatternMatchingWithNull() {
    ModuleConfig validConfig = new ModuleConfig("test-module", true, 10);
    ModuleConfig configWithNullName = new ModuleConfig(null, true, 10);
    
    // Pattern matching with non-null values
    if (validConfig instanceof ModuleConfig(var name, var enabled, var priority)) {
      assertThat(name, is("test-module"));
    }
    
    // Pattern matching with null component
    if (configWithNullName instanceof ModuleConfig(var name, var enabled, var priority)) {
      assertThat(name, is(nullValue()));
    }
    
    // Null doesn't match any record pattern
    ModuleConfig nullConfig = null;
    boolean matched = false;
    
    if (nullConfig instanceof ModuleConfig(var name, var enabled, var priority)) {
      matched = true;
    }
    
    assertThat("Null should not match any record pattern", matched, is(false));
  }
}