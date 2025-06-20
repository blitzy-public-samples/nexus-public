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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for Java 21 record pattern matching with Nexus Extender configuration objects.
 * 
 * @since 3.60
 */
public class ExtenderConfigRecordPatternTest
    extends TestSupport
{
  /**
   * Simple record representing a module configuration.
   */
  record ModuleConfig(String name, String version, boolean enabled) {}

  /**
   * Record representing lifecycle configuration.
   */
  record LifecycleConfig(String phase, int priority) {}

  /**
   * Nested record representing a complete extender configuration.
   */
  record ExtenderConfig(ModuleConfig module, LifecycleConfig lifecycle) {}

  /**
   * Record with optional fields that might be null.
   */
  record OptionalConfig(String name, String description, ModuleConfig module) {}

  @Test
  public void testBasicRecordPatternMatching() {
    Object config = new ModuleConfig("test-module", "1.0.0", true);
    
    // Traditional approach with instanceof and casting
    if (config instanceof ModuleConfig) {
      ModuleConfig moduleConfig = (ModuleConfig) config;
      assertThat(moduleConfig.name(), is("test-module"));
      assertThat(moduleConfig.version(), is("1.0.0"));
      assertThat(moduleConfig.enabled(), is(true));
    }
    else {
      fail("Expected ModuleConfig instance");
    }
    
    // Java 21 approach with record pattern matching
    if (config instanceof ModuleConfig(String name, String version, boolean enabled)) {
      assertThat(name, is("test-module"));
      assertThat(version, is("1.0.0"));
      assertThat(enabled, is(true));
    }
    else {
      fail("Expected ModuleConfig pattern match");
    }
  }

  @Test
  public void testNestedRecordPatternMatching() {
    ModuleConfig moduleConfig = new ModuleConfig("test-module", "1.0.0", true);
    LifecycleConfig lifecycleConfig = new LifecycleConfig("KERNEL", 10);
    ExtenderConfig config = new ExtenderConfig(moduleConfig, lifecycleConfig);
    
    // Traditional approach with nested access
    if (config instanceof ExtenderConfig) {
      ExtenderConfig extenderConfig = config;
      ModuleConfig module = extenderConfig.module();
      LifecycleConfig lifecycle = extenderConfig.lifecycle();
      
      assertThat(module.name(), is("test-module"));
      assertThat(module.version(), is("1.0.0"));
      assertThat(lifecycle.phase(), is("KERNEL"));
      assertThat(lifecycle.priority(), is(10));
    }
    else {
      fail("Expected ExtenderConfig instance");
    }
    
    // Java 21 approach with nested record pattern matching
    if (config instanceof ExtenderConfig(ModuleConfig(String name, String version, boolean enabled), 
                                        LifecycleConfig(String phase, int priority))) {
      assertThat(name, is("test-module"));
      assertThat(version, is("1.0.0"));
      assertThat(enabled, is(true));
      assertThat(phase, is("KERNEL"));
      assertThat(priority, is(10));
    }
    else {
      fail("Expected nested ExtenderConfig pattern match");
    }
  }

  @Test
  public void testRecordPatternMatchingWithVar() {
    Object config = new ModuleConfig("test-module", "1.0.0", true);
    
    // Java 21 approach with var for type inference
    if (config instanceof ModuleConfig(var name, var version, var enabled)) {
      assertThat(name, is("test-module"));
      assertThat(version, is("1.0.0"));
      assertThat(enabled, is(true));
    }
    else {
      fail("Expected ModuleConfig pattern match with var");
    }
  }

  @Test
  public void testRecordPatternMatchingWithGuards() {
    Object config = new ModuleConfig("test-module", "1.0.0", true);
    
    // Java 21 approach with pattern matching and guard condition
    if (config instanceof ModuleConfig(String name, String version, boolean enabled) && enabled) {
      assertThat(name, is("test-module"));
      assertThat(version, is("1.0.0"));
    }
    else {
      fail("Expected ModuleConfig pattern match with guard");
    }
    
    // Alternative approach with pattern matching in switch with guard
    String result = switch (config) {
      case ModuleConfig(String name, String version, boolean enabled) when enabled -> 
          name + "-" + version;
      case ModuleConfig(String name, String version, boolean enabled) -> 
          name + "-disabled";
      default -> "unknown";
    };
    
    assertThat(result, is("test-module-1.0.0"));
  }

  @Test
  public void testRecordPatternMatchingWithNullHandling() {
    OptionalConfig config = new OptionalConfig("test-optional", null, null);
    
    // Traditional approach with null checks
    String description = config.description();
    ModuleConfig module = config.module();
    
    assertThat(description, nullValue());
    assertThat(module, nullValue());
    
    // Java 21 approach with pattern matching and null handling
    if (config instanceof OptionalConfig(String name, String desc, ModuleConfig mod)) {
      assertThat(name, is("test-optional"));
      assertThat(desc, nullValue());
      assertThat(mod, nullValue());
    }
    else {
      fail("Expected OptionalConfig pattern match");
    }
    
    // Create a non-null config for comparison
    OptionalConfig fullConfig = new OptionalConfig(
        "test-optional", 
        "A description", 
        new ModuleConfig("inner-module", "2.0.0", false));
    
    // Pattern matching with nested patterns and null-safe access
    String moduleVersion = switch (fullConfig) {
      case OptionalConfig(var n, var d, ModuleConfig(var mn, var mv, var me)) -> mv;
      case OptionalConfig(var n, var d, var m) when m == null -> "N/A";
      default -> "unknown";
    };
    
    assertThat(moduleVersion, is("2.0.0"));
    
    // Same check with the null module config
    String nullModuleVersion = switch (config) {
      case OptionalConfig(var n, var d, ModuleConfig(var mn, var mv, var me)) -> mv;
      case OptionalConfig(var n, var d, var m) when m==null -> "N/A";
      default -> "unknown";
    };
    
    assertThat(nullModuleVersion, is("N/A"));
  }

  @Test
  public void testExtractConfigurationWithPatternMatching() {
    // Create a complex configuration structure
    ModuleConfig moduleConfig = new ModuleConfig("test-module", "1.0.0", true);
    LifecycleConfig lifecycleConfig = new LifecycleConfig("KERNEL", 10);
    ExtenderConfig config = new ExtenderConfig(moduleConfig, lifecycleConfig);
    
    // Extract configuration using pattern matching
    String moduleInfo = extractModuleInfo(config);
    assertThat(moduleInfo, is("Module: test-module v1.0.0 (enabled)"));
    
    // Extract with disabled module
    ModuleConfig disabledModule = new ModuleConfig("test-module", "1.0.0", false);
    ExtenderConfig disabledConfig = new ExtenderConfig(disabledModule, lifecycleConfig);
    
    String disabledInfo = extractModuleInfo(disabledConfig);
    assertThat(disabledInfo, is("Module: test-module v1.0.0 (disabled)"));
  }
  
  /**
   * Extracts module information using pattern matching.
   */
  private String extractModuleInfo(Object config) {
    return switch (config) {
      case ExtenderConfig(ModuleConfig(String name, String version, boolean enabled), var lifecycle) when enabled ->
          String.format("Module: %s v%s (enabled)", name, version);
      case ExtenderConfig(ModuleConfig(String name, String version, boolean enabled), var lifecycle) ->
          String.format("Module: %s v%s (disabled)", name, version);
      default -> "Unknown configuration";
    };
  }
}