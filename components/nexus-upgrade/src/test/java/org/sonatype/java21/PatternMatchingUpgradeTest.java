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
package org.sonatype.java21;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.upgrade.plan.DependencyResolver;
import org.sonatype.nexus.upgrade.plan.DependencyResolver.CyclicDependencyException;
import org.sonatype.nexus.upgrade.plan.DependencyResolver.UnresolvedDependencyException;
import org.sonatype.nexus.upgrade.plan.DependencySource;
import org.sonatype.nexus.upgrade.plan.DependencySource.DependsOnAware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Java 21 pattern matching features with the Nexus upgrade framework.
 */
@Category(Java21TestGroup.class)
public class PatternMatchingUpgradeTest
    extends TestSupport
{
  /**
   * Base class for upgrade model types used in pattern matching tests.
   */
  static abstract class UpgradeModel
      implements DependencySource<UpgradeModel>, DependsOnAware<UpgradeModel>
  {
    String id;
    String version;
    List<Dependency<UpgradeModel>> dependencies = new ArrayList<>();
    Collection<UpgradeModel> dependsOn;

    @Override
    public List<Dependency<UpgradeModel>> getDependencies() {
      return dependencies;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "{"
          + "id='" + id + '\''
          + ", version='" + version + '\''
          + '}';
    }

    @Override
    public void setDependsOn(final Collection<UpgradeModel> dependsOn) {
      this.dependsOn = dependsOn;
    }

    @Override
    public boolean equals(final Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      UpgradeModel model = (UpgradeModel) o;
      return Objects.equals(id, model.id) && Objects.equals(version, model.version);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, version);
    }
  }

  /**
   * Schema upgrade model type for pattern matching tests.
   */
  static class SchemaUpgrade extends UpgradeModel {
    String schemaName;
    
    SchemaUpgrade(String id, String version, String schemaName) {
      this.id = id;
      this.version = version;
      this.schemaName = schemaName;
    }
  }

  /**
   * Data upgrade model type for pattern matching tests.
   */
  static class DataUpgrade extends UpgradeModel {
    String dataType;
    
    DataUpgrade(String id, String version, String dataType) {
      this.id = id;
      this.version = version;
      this.dataType = dataType;
    }
  }

  /**
   * Configuration upgrade model type for pattern matching tests.
   */
  static class ConfigUpgrade extends UpgradeModel {
    String configKey;
    
    ConfigUpgrade(String id, String version, String configKey) {
      this.id = id;
      this.version = version;
      this.configKey = configKey;
    }
  }

  /**
   * Record representing a version range for pattern matching tests.
   */
  record VersionRange(String from, String to) {
    boolean includes(String version) {
      return version.compareTo(from) >= 0 && version.compareTo(to) <= 0;
    }
  }

  private DependencyResolver<UpgradeModel> resolver;

  @BeforeEach
  public void setUp() {
    resolver = new DependencyResolver<>();
  }

  /**
   * Tests pattern matching with instanceof for upgrade model types.
   */
  @Test
  public void testPatternMatchingWithInstanceOf() {
    // Create different types of upgrade models
    UpgradeModel schemaUpgrade = new SchemaUpgrade("schema.users", "1.0", "users");
    UpgradeModel dataUpgrade = new DataUpgrade("data.roles", "2.0", "roles");
    UpgradeModel configUpgrade = new ConfigUpgrade("config.security", "3.0", "security.enabled");
    
    // Use pattern matching with instanceof to process different model types
    String result = processUpgradeModel(schemaUpgrade);
    assertEquals("Schema upgrade for users", result);
    
    result = processUpgradeModel(dataUpgrade);
    assertEquals("Data upgrade for roles", result);
    
    result = processUpgradeModel(configUpgrade);
    assertEquals("Config upgrade for security.enabled", result);
  }

  /**
   * Helper method that uses pattern matching with instanceof to process different upgrade model types.
   */
  private String processUpgradeModel(UpgradeModel model) {
    // Using pattern matching with instanceof to avoid explicit casting
    if (model instanceof SchemaUpgrade schemaUpgrade) {
      return "Schema upgrade for " + schemaUpgrade.schemaName;
    } else if (model instanceof DataUpgrade dataUpgrade) {
      return "Data upgrade for " + dataUpgrade.dataType;
    } else if (model instanceof ConfigUpgrade configUpgrade) {
      return "Config upgrade for " + configUpgrade.configKey;
    } else {
      return "Unknown upgrade type";
    }
  }

  /**
   * Tests pattern matching for switch expressions with upgrade model types.
   */
  @Test
  public void testPatternMatchingForSwitch() {
    // Create different types of upgrade models
    UpgradeModel schemaUpgrade = new SchemaUpgrade("schema.users", "1.0", "users");
    UpgradeModel dataUpgrade = new DataUpgrade("data.roles", "2.0", "roles");
    UpgradeModel configUpgrade = new ConfigUpgrade("config.security", "3.0", "security.enabled");
    
    // Use pattern matching with switch to process different model types
    String schemaResult = processUpgradeModelWithSwitch(schemaUpgrade);
    assertEquals("Schema upgrade for users (version 1.0)", schemaResult);
    
    String dataResult = processUpgradeModelWithSwitch(dataUpgrade);
    assertEquals("Data upgrade for roles (version 2.0)", dataResult);
    
    String configResult = processUpgradeModelWithSwitch(configUpgrade);
    assertEquals("Config upgrade for security.enabled (version 3.0)", configResult);
    
    // Test with null model
    String nullResult = processUpgradeModelWithSwitch(null);
    assertEquals("No upgrade model provided", nullResult);
  }

  /**
   * Helper method that uses pattern matching with switch expressions to process different upgrade model types.
   */
  private String processUpgradeModelWithSwitch(UpgradeModel model) {
    // Using pattern matching with switch expressions
    return switch (model) {
      case SchemaUpgrade schemaUpgrade -> 
          "Schema upgrade for " + schemaUpgrade.schemaName + " (version " + schemaUpgrade.version + ")";
      case DataUpgrade dataUpgrade -> 
          "Data upgrade for " + dataUpgrade.dataType + " (version " + dataUpgrade.version + ")";
      case ConfigUpgrade configUpgrade -> 
          "Config upgrade for " + configUpgrade.configKey + " (version " + configUpgrade.version + ")";
      case null -> "No upgrade model provided";
      default -> "Unknown upgrade type";
    };
  }

  /**
   * Tests pattern matching with switch expressions and guarded patterns for version validation.
   */
  @Test
  public void testPatternMatchingWithGuardedPatterns() {
    // Create upgrade models with different versions
    UpgradeModel oldSchema = new SchemaUpgrade("schema.users", "1.0", "users");
    UpgradeModel newSchema = new SchemaUpgrade("schema.users", "2.5", "users");
    UpgradeModel oldData = new DataUpgrade("data.roles", "1.5", "roles");
    UpgradeModel newData = new DataUpgrade("data.roles", "3.0", "roles");
    
    // Define version ranges for testing
    VersionRange oldRange = new VersionRange("1.0", "2.0");
    VersionRange newRange = new VersionRange("2.0", "3.0");
    
    // Test version validation with guarded patterns
    assertTrue(isUpgradeInRange(oldSchema, oldRange));
    assertFalse(isUpgradeInRange(oldSchema, newRange));
    assertTrue(isUpgradeInRange(newSchema, newRange));
    assertFalse(isUpgradeInRange(newSchema, oldRange));
    assertTrue(isUpgradeInRange(oldData, oldRange));
    assertTrue(isUpgradeInRange(newData, newRange));
  }

  /**
   * Helper method that uses pattern matching with guarded patterns to validate upgrade versions.
   */
  private boolean isUpgradeInRange(UpgradeModel model, VersionRange range) {
    return switch (model) {
      // Using guarded patterns with 'when' clause to add additional conditions
      case SchemaUpgrade schemaUpgrade when range.includes(schemaUpgrade.version) -> true;
      case DataUpgrade dataUpgrade when range.includes(dataUpgrade.version) -> true;
      case ConfigUpgrade configUpgrade when range.includes(configUpgrade.version) -> true;
      default -> false;
    };
  }

  /**
   * Tests pattern matching with instanceof for complex dependency resolution.
   */
  @Test
  public void testDependencyResolutionWithPatternMatching() {
    // Create upgrade models with dependencies
    SchemaUpgrade usersSchema = new SchemaUpgrade("schema.users", "1.0", "users");
    DataUpgrade rolesData = new DataUpgrade("data.roles", "1.0", "roles");
    ConfigUpgrade securityConfig = new ConfigUpgrade("config.security", "1.0", "security.enabled");
    
    // Set up dependencies using pattern matching
    setupDependencies(usersSchema, List.of(rolesData));
    setupDependencies(rolesData, List.of(securityConfig));
    
    // Add models to resolver
    resolver.add(usersSchema, rolesData, securityConfig);
    
    // Resolve dependencies
    List<UpgradeModel> resolved = resolver.resolve().ordered;
    
    // Verify resolution order using pattern matching
    assertEquals(3, resolved.size());
    
    // Verify correct ordering using pattern matching with instanceof
    assertTrue(resolved.get(0) instanceof ConfigUpgrade);
    assertTrue(resolved.get(1) instanceof DataUpgrade);
    assertTrue(resolved.get(2) instanceof SchemaUpgrade);
  }

  /**
   * Helper method to set up dependencies between upgrade models using pattern matching.
   */
  private void setupDependencies(UpgradeModel model, List<UpgradeModel> dependencies) {
    // Using pattern matching to handle different model types
    switch (model) {
      case SchemaUpgrade schemaUpgrade -> {
        for (UpgradeModel dependency : dependencies) {
          schemaUpgrade.dependencies.add(createDependency(dependency.id));
        }
      }
      case DataUpgrade dataUpgrade -> {
        for (UpgradeModel dependency : dependencies) {
          dataUpgrade.dependencies.add(createDependency(dependency.id));
        }
      }
      case ConfigUpgrade configUpgrade -> {
        for (UpgradeModel dependency : dependencies) {
          configUpgrade.dependencies.add(createDependency(dependency.id));
        }
      }
      default -> throw new IllegalArgumentException("Unsupported upgrade model type");
    }
  }

  /**
   * Creates a dependency that requires an upgrade model with the given identifier.
   */
  private Dependency<UpgradeModel> createDependency(final String id) {
    return new Dependency<UpgradeModel>() {
      @Override
      public boolean satisfiedBy(final UpgradeModel other) {
        return other.id.equals(id);
      }

      @Override
      public String toString() {
        return "DEPENDS_ON(" + id + ")";
      }
    };
  }

  /**
   * Tests pattern matching with switch expressions for error handling.
   */
  @Test
  public void testErrorHandlingWithPatternMatching() {
    // Test cyclic dependency detection
    SchemaUpgrade schema1 = new SchemaUpgrade("schema.a", "1.0", "a");
    SchemaUpgrade schema2 = new SchemaUpgrade("schema.b", "1.0", "b");
    
    // Create a cycle: schema1 -> schema2 -> schema1
    setupDependencies(schema1, List.of(schema2));
    setupDependencies(schema2, List.of(schema1));
    
    resolver.add(schema1, schema2);
    
    // Verify that a cyclic dependency exception is thrown
    Exception exception = assertThrows(CyclicDependencyException.class, () -> {
      resolver.resolve();
    });
    
    // Use pattern matching to extract information from the exception
    String errorMessage = switch (exception) {
      case CyclicDependencyException cyclicEx -> "Cyclic dependency detected";
      case UnresolvedDependencyException unresolvedEx -> "Unresolved dependency";
      default -> "Unknown error";
    };
    
    assertEquals("Cyclic dependency detected", errorMessage);
  }
}