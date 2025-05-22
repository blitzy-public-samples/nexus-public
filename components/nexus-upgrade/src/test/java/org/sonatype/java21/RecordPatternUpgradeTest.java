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

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.upgrade.Upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Java 21 record pattern features in the context of the Nexus upgrade framework.
 * 
 * This test class demonstrates how record patterns can be used to extract and validate
 * upgrade metadata in a more concise and type-safe manner.
 */
@Category(Java21TestGroup.class)
class RecordPatternUpgradeTest
    extends TestSupport
{
  /**
   * Record representing version information for an upgrade.
   */
  record VersionRecord(String from, String to) {
    // Validates that the version is properly formatted (e.g., "1.0.0")
    boolean isValid() {
      return from.matches("\\d+\\.\\d+\\.\\d+") && to.matches("\\d+\\.\\d+\\.\\d+");
    }
    
    // Checks if this is a major version upgrade
    boolean isMajorUpgrade() {
      return !from.startsWith(to.substring(0, 1));
    }
  }
  
  /**
   * Record representing a dependency for an upgrade.
   */
  record DependencyRecord(String model, VersionRecord version, boolean checkpoint) {
    // Checks if this dependency requires a checkpoint
    boolean requiresCheckpoint() {
      return checkpoint || version.isMajorUpgrade();
    }
  }
  
  /**
   * Record representing an upgrade with its metadata.
   */
  record UpgradeRecord(String model, VersionRecord version, List<DependencyRecord> dependencies, Upgrade implementation) {
    // Checks if this upgrade has dependencies
    boolean hasDependencies() {
      return dependencies != null && !dependencies.isEmpty();
    }
    
    // Checks if any dependencies require checkpoints
    boolean hasCheckpointDependencies() {
      if (!hasDependencies()) {
        return false;
      }
      
      return dependencies.stream().anyMatch(DependencyRecord::requiresCheckpoint);
    }
  }
  
  /**
   * Test basic record pattern matching with a simple VersionRecord.
   */
  @Test
  @DisplayName("Test basic record pattern matching with VersionRecord")
  void testBasicRecordPattern() {
    Object versionObj = new VersionRecord("1.0.0", "2.0.0");
    
    // Using record pattern to match and extract components in a single step
    if (versionObj instanceof VersionRecord(String from, String to)) {
      assertEquals("1.0.0", from);
      assertEquals("2.0.0", to);
      assertTrue(from.startsWith("1"));
      assertTrue(to.startsWith("2"));
    } else {
      // This should not happen
      throw new AssertionError("Object should be a VersionRecord");
    }
  }
  
  /**
   * Test nested record pattern matching with DependencyRecord containing a VersionRecord.
   */
  @Test
  @DisplayName("Test nested record pattern matching with DependencyRecord")
  void testNestedRecordPattern() {
    Object dependencyObj = new DependencyRecord(
        "content", 
        new VersionRecord("1.0.0", "1.1.0"), 
        true);
    
    // Using nested record pattern to match and extract components from nested records
    if (dependencyObj instanceof DependencyRecord(String model, VersionRecord(String from, String to), boolean checkpoint)) {
      assertEquals("content", model);
      assertEquals("1.0.0", from);
      assertEquals("1.1.0", to);
      assertTrue(checkpoint);
      assertFalse(from.startsWith("2"));
      assertTrue(from.startsWith(to.substring(0, 1))); // Same major version
    } else {
      // This should not happen
      throw new AssertionError("Object should be a DependencyRecord");
    }
  }
  
  /**
   * Test complex nested record pattern matching with UpgradeRecord containing a list of DependencyRecords.
   */
  @Test
  @DisplayName("Test complex nested record patterns with UpgradeRecord")
  void testComplexNestedRecordPattern() {
    // Create a mock Upgrade implementation
    Upgrade mockUpgrade = new Upgrade() {
      @Override
      public void apply() {
        // Mock implementation
      }
    };
    
    // Create an UpgradeRecord with dependencies
    UpgradeRecord upgradeRecord = new UpgradeRecord(
        "security",
        new VersionRecord("2.0.0", "3.0.0"),
        List.of(
            new DependencyRecord("content", new VersionRecord("1.5.0", "2.0.0"), false),
            new DependencyRecord("config", new VersionRecord("2.0.0", "2.1.0"), true)
        ),
        mockUpgrade
    );
    
    Object upgradeObj = upgradeRecord;
    
    // Using complex nested record pattern to match and extract components
    if (upgradeObj instanceof UpgradeRecord(String model, VersionRecord version, var dependencies, var implementation)) {
      assertEquals("security", model);
      assertTrue(version.isMajorUpgrade());
      assertEquals(2, dependencies.size());
      assertTrue(upgradeRecord.hasCheckpointDependencies());
      
      // Further pattern matching on the first dependency
      Object firstDep = dependencies.get(0);
      if (firstDep instanceof DependencyRecord(String depModel, VersionRecord depVersion, boolean checkpoint)) {
        assertEquals("content", depModel);
        assertTrue(depVersion.isMajorUpgrade());
        assertFalse(checkpoint);
        assertTrue(depVersion.isValid());
      }
      
      // Further pattern matching on the second dependency
      Object secondDep = dependencies.get(1);
      if (secondDep instanceof DependencyRecord(String depModel, VersionRecord depVersion, boolean checkpoint)) {
        assertEquals("config", depModel);
        assertFalse(depVersion.isMajorUpgrade());
        assertTrue(checkpoint);
        assertTrue(depVersion.isValid());
      }
    } else {
      // This should not happen
      throw new AssertionError("Object should be an UpgradeRecord");
    }
  }
  
  /**
   * Test pattern matching in switch expressions with record patterns.
   */
  @Test
  @DisplayName("Test pattern matching in switch expressions with record patterns")
  void testPatternMatchingInSwitch() {
    Object versionObj1 = new VersionRecord("1.0.0", "2.0.0");
    Object versionObj2 = new VersionRecord("2.0.0", "2.1.0");
    
    // Using record patterns in switch expressions
    String result1 = switch (versionObj1) {
      case VersionRecord(var from, var to) when from.startsWith("1") && to.startsWith("2") ->
        "Major upgrade from " + from + " to " + to;
      case VersionRecord(var from, var to) ->
        "Version change from " + from + " to " + to;
      default ->
        "Not a version record";
    };
    
    String result2 = switch (versionObj2) {
      case VersionRecord(var from, var to) when from.startsWith("1") && to.startsWith("2") ->
        "Major upgrade from " + from + " to " + to;
      case VersionRecord(var from, var to) ->
        "Version change from " + from + " to " + to;
      default ->
        "Not a version record";
    };
    
    assertEquals("Major upgrade from 1.0.0 to 2.0.0", result1);
    assertEquals("Version change from 2.0.0 to 2.1.0", result2);
  }
  
  /**
   * Test using var for type inference in record patterns.
   */
  @Test
  @DisplayName("Test using var for type inference in record patterns")
  void testVarInRecordPatterns() {
    Object upgradeObj = new UpgradeRecord(
        "security",
        new VersionRecord("1.0.0", "1.1.0"),
        List.of(new DependencyRecord("content", new VersionRecord("1.0.0", "1.0.1"), false)),
        null
    );
    
    // Using var for type inference in record patterns
    if (upgradeObj instanceof UpgradeRecord(var model, var version, var dependencies, var implementation)) {
      assertEquals("security", model);
      assertFalse(version.isMajorUpgrade());
      assertEquals(1, dependencies.size());
      
      // The compiler infers the correct types:
      // model is String
      // version is VersionRecord
      // dependencies is List<DependencyRecord>
      // implementation is Upgrade
      
      // We can still access methods on the inferred types
      assertTrue(version.isValid());
      assertFalse(((UpgradeRecord) upgradeObj).hasCheckpointDependencies());
    } else {
      // This should not happen
      throw new AssertionError("Object should be an UpgradeRecord");
    }
  }
}