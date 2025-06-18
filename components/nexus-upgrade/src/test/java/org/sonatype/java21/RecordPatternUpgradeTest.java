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
import java.util.Optional;

import org.sonatype.java21.Java21TestGroup;
import org.sonatype.nexus.common.upgrade.Upgrade;
import org.sonatype.nexus.common.upgrade.Upgrades;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for validating Java 21 record pattern features with the Nexus upgrade framework.
 */
@Category(Java21TestGroup.class)
public class RecordPatternUpgradeTest
{
  /**
   * Record representing version information for an upgrade.
   */
  record VersionRecord(String from, String to) {}

  /**
   * Record representing a dependency for an upgrade.
   */
  record DependencyRecord(String model, String version) {}

  /**
   * Record representing an upgrade with its metadata.
   */
  record UpgradeRecord(
      String model,
      VersionRecord version,
      List<DependencyRecord> dependencies,
      Upgrade implementation) {}

  /**
   * Tests basic record pattern matching for upgrade metadata extraction.
   */
  @Test
  @DisplayName("Extract upgrade metadata using record patterns")
  void testBasicRecordPatternMatching() {
    // Create an upgrade record
    UpgradeRecord upgradeRecord = new UpgradeRecord(
        "security",
        new VersionRecord("1.0", "2.0"),
        List.of(new DependencyRecord("core", "3.0")),
        createMockUpgrade("security", "1.0", "2.0")
    );

    // Use record pattern to extract metadata
    if (upgradeRecord instanceof UpgradeRecord(String model, VersionRecord version, var dependencies, var implementation)) {
      assertEquals("security", model);
      assertEquals("1.0", version.from());
      assertEquals("2.0", version.to());
      assertEquals(1, dependencies.size());
      assertNotNull(implementation);
    } else {
      // This should never happen if record pattern matching works correctly
      assertTrue(false, "Record pattern matching failed");
    }
  }

  /**
   * Tests nested record pattern matching for complex upgrade metadata extraction.
   */
  @Test
  @DisplayName("Extract nested upgrade metadata using record patterns")
  void testNestedRecordPatternMatching() {
    // Create an upgrade record with nested records
    UpgradeRecord upgradeRecord = new UpgradeRecord(
        "repository",
        new VersionRecord("2.0", "3.0"),
        List.of(
            new DependencyRecord("core", "4.0"),
            new DependencyRecord("security", "2.0")
        ),
        createMockUpgrade("repository", "2.0", "3.0")
    );

    // Use nested record pattern to extract metadata
    if (upgradeRecord instanceof UpgradeRecord(String model, VersionRecord(String from, String to), var dependencies, var implementation)) {
      assertEquals("repository", model);
      assertEquals("2.0", from);
      assertEquals("3.0", to);
      assertEquals(2, dependencies.size());
      assertNotNull(implementation);

      // Extract the first dependency using record pattern
      if (dependencies.get(0) instanceof DependencyRecord(String depModel, String depVersion)) {
        assertEquals("core", depModel);
        assertEquals("4.0", depVersion);
      } else {
        assertTrue(false, "Dependency record pattern matching failed");
      }
    } else {
      assertTrue(false, "Nested record pattern matching failed");
    }
  }

  /**
   * Tests record pattern matching in switch expressions for upgrade processing.
   */
  @Test
  @DisplayName("Process upgrades using record patterns in switch expressions")
  void testRecordPatternInSwitchExpression() {
    // Create different types of upgrade records
    UpgradeRecord securityUpgrade = new UpgradeRecord(
        "security",
        new VersionRecord("1.0", "2.0"),
        List.of(),
        createMockUpgrade("security", "1.0", "2.0")
    );

    UpgradeRecord repositoryUpgrade = new UpgradeRecord(
        "repository",
        new VersionRecord("2.0", "3.0"),
        List.of(new DependencyRecord("core", "4.0")),
        createMockUpgrade("repository", "2.0", "3.0")
    );

    // Process upgrades using switch expression with record patterns
    String result = processUpgrade(securityUpgrade);
    assertEquals("Security upgrade from 1.0 to 2.0", result);

    result = processUpgrade(repositoryUpgrade);
    assertEquals("Repository upgrade from 2.0 to 3.0 with core dependency", result);
  }

  /**
   * Tests record pattern matching with Optional for handling nullable upgrade components.
   */
  @Test
  @DisplayName("Handle optional upgrade components using record patterns")
  void testRecordPatternWithOptional() {
    // Create an upgrade record wrapped in Optional
    Optional<UpgradeRecord> maybeUpgrade = Optional.of(new UpgradeRecord(
        "config",
        new VersionRecord("1.5", "2.5"),
        List.of(),
        createMockUpgrade("config", "1.5", "2.5")
    ));

    // Use record pattern with Optional
    if (maybeUpgrade.isPresent() && maybeUpgrade.get() instanceof UpgradeRecord(String model, VersionRecord(String from, String to), var dependencies, var implementation)) {
      assertEquals("config", model);
      assertEquals("1.5", from);
      assertEquals("2.5", to);
      assertTrue(dependencies.isEmpty());
      assertNotNull(implementation);
    } else {
      assertTrue(false, "Optional record pattern matching failed");
    }

    // Test with empty Optional
    Optional<UpgradeRecord> emptyUpgrade = Optional.empty();
    if (emptyUpgrade.isPresent() && emptyUpgrade.get() instanceof UpgradeRecord(var model, var version, var dependencies, var implementation)) {
      assertTrue(false, "Should not match empty Optional");
    } else {
      assertFalse(emptyUpgrade.isPresent());
    }
  }

  /**
   * Process an upgrade record using switch expression with record patterns.
   */
  private String processUpgrade(Object upgrade) {
    return switch (upgrade) {
      case UpgradeRecord(String model, VersionRecord(String from, String to), var dependencies, var implementation) 
          when model.equals("security") -> 
          "Security upgrade from " + from + " to " + to;

      case UpgradeRecord(String model, VersionRecord(String from, String to), var dependencies, var implementation) 
          when model.equals("repository") && !dependencies.isEmpty() -> 
          "Repository upgrade from " + from + " to " + to + " with core dependency";

      case UpgradeRecord(String model, VersionRecord(String from, String to), var dependencies, var implementation) -> 
          "Generic upgrade from " + from + " to " + to + " for model " + model;

      default -> "Unknown upgrade type";
    };
  }

  /**
   * Creates a mock upgrade implementation with the specified metadata.
   */
  private Upgrade createMockUpgrade(String model, String from, String to) {
    return new MockUpgrade(model, from, to);
  }

  /**
   * Mock implementation of Upgrade interface for testing.
   */
  @Upgrades(model = "test", from = "1.0", to = "2.0")
  private static class MockUpgrade implements Upgrade {
    private final String model;
    private final String from;
    private final String to;

    public MockUpgrade(String model, String from, String to) {
      this.model = model;
      this.from = from;
      this.to = to;
    }

    @Override
    public void apply() throws Exception {
      // Mock implementation
    }
  }
}