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

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.upgrade.plan.DependencyResolver;

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Java 21 pattern matching features with the Nexus upgrade framework.
 */
@Category(Java21TestGroup.class)
public class PatternMatchingUpgradeTest
    extends TestSupport
{
  /**
   * Base model for upgrade testing.
   */
  sealed interface UpgradeModel permits SimpleUpgrade, ComplexUpgrade, ConditionalUpgrade {
    String getVersion();
    boolean isApplicable();
  }

  /**
   * Simple upgrade model with just a version.
   */
  record SimpleUpgrade(String version) implements UpgradeModel {
    @Override
    public boolean isApplicable() {
      return true;
    }
  }

  /**
   * Complex upgrade model with version and additional metadata.
   */
  record ComplexUpgrade(String version, String description, List<String> dependencies) implements UpgradeModel {
    @Override
    public boolean isApplicable() {
      return !dependencies.isEmpty();
    }
  }

  /**
   * Conditional upgrade model that may or may not be applicable.
   */
  record ConditionalUpgrade(String version, boolean applicable) implements UpgradeModel {
    @Override
    public boolean isApplicable() {
      return applicable;
    }
  }

  /**
   * Version validator that uses pattern matching to validate version formats.
   */
  static class VersionValidator {
    /**
     * Validates a version string using pattern matching with instanceof.
     */
    boolean isValidVersion(Object version) {
      // Using pattern matching with instanceof
      if (version instanceof String s && s.matches("\\d+\\.\\d+\\.\\d+")) {
        return true;
      }
      else if (version instanceof Integer i && i > 0) {
        return true;
      }
      return false;
    }

    /**
     * Extracts major version using pattern matching with switch.
     */
    int extractMajorVersion(Object version) {
      // Using pattern matching with switch
      return switch (version) {
        case String s when s.matches("\\d+\\.\\d+\\.\\d+") -> 
            Integer.parseInt(s.split("\\.")[0]);
        case Integer i -> i;
        case null -> 0;
        default -> -1;
      };
    }
  }

  /**
   * Upgrade processor that uses pattern matching to handle different upgrade models.
   */
  static class UpgradeProcessor {
    /**
     * Processes an upgrade model using pattern matching with switch.
     */
    String processUpgrade(UpgradeModel model) {
      // Using pattern matching with switch for different model types
      return switch (model) {
        case SimpleUpgrade s -> 
            "Processing simple upgrade version " + s.version();
        case ComplexUpgrade c when c.dependencies().size() > 2 -> 
            "Processing complex upgrade version " + c.version() + " with many dependencies";
        case ComplexUpgrade c -> 
            "Processing complex upgrade version " + c.version() + " with " + c.dependencies().size() + " dependencies";
        case ConditionalUpgrade c when c.applicable() -> 
            "Processing applicable conditional upgrade version " + c.version();
        case ConditionalUpgrade c -> 
            "Skipping non-applicable conditional upgrade version " + c.version();
      };
    }

    /**
     * Determines if an upgrade is applicable using pattern matching with switch.
     */
    boolean isApplicable(Object model) {
      // Using pattern matching with switch including null handling
      return switch (model) {
        case SimpleUpgrade s -> true;
        case ComplexUpgrade c -> !c.dependencies().isEmpty();
        case ConditionalUpgrade c -> c.applicable();
        case null -> false;
        default -> false;
      };
    }
  }

  private VersionValidator versionValidator;
  private UpgradeProcessor upgradeProcessor;

  @BeforeEach
  void setUp() {
    versionValidator = new VersionValidator();
    upgradeProcessor = new UpgradeProcessor();
  }

  @Test
  void testPatternMatchingWithInstanceOf() {
    // Test pattern matching with instanceof for version validation
    assertTrue(versionValidator.isValidVersion("1.2.3"));
    assertTrue(versionValidator.isValidVersion(5));
    assertFalse(versionValidator.isValidVersion("invalid"));
    assertFalse(versionValidator.isValidVersion(null));
  }

  @Test
  void testPatternMatchingWithSwitchForVersionExtraction() {
    // Test pattern matching with switch for version extraction
    assertEquals(1, versionValidator.extractMajorVersion("1.2.3"));
    assertEquals(5, versionValidator.extractMajorVersion(5));
    assertEquals(-1, versionValidator.extractMajorVersion("invalid"));
    assertEquals(0, versionValidator.extractMajorVersion(null));
  }

  @Test
  void testPatternMatchingWithSwitchForUpgradeModels() {
    // Test pattern matching with switch for different upgrade model types
    SimpleUpgrade simpleUpgrade = new SimpleUpgrade("2.0.0");
    ComplexUpgrade complexUpgradeWithFewDeps = new ComplexUpgrade("2.1.0", "Complex upgrade", List.of("dep1", "dep2"));
    ComplexUpgrade complexUpgradeWithManyDeps = new ComplexUpgrade("2.2.0", "Complex upgrade with many deps", 
        List.of("dep1", "dep2", "dep3"));
    ConditionalUpgrade applicableUpgrade = new ConditionalUpgrade("2.3.0", true);
    ConditionalUpgrade nonApplicableUpgrade = new ConditionalUpgrade("2.4.0", false);

    // Verify pattern matching with switch handles different model types correctly
    assertEquals("Processing simple upgrade version 2.0.0", 
        upgradeProcessor.processUpgrade(simpleUpgrade));
    assertEquals("Processing complex upgrade version 2.1.0 with 2 dependencies", 
        upgradeProcessor.processUpgrade(complexUpgradeWithFewDeps));
    assertEquals("Processing complex upgrade version 2.2.0 with many dependencies", 
        upgradeProcessor.processUpgrade(complexUpgradeWithManyDeps));
    assertEquals("Processing applicable conditional upgrade version 2.3.0", 
        upgradeProcessor.processUpgrade(applicableUpgrade));
    assertEquals("Skipping non-applicable conditional upgrade version 2.4.0", 
        upgradeProcessor.processUpgrade(nonApplicableUpgrade));
  }

  @Test
  void testPatternMatchingWithSwitchForApplicability() {
    // Test pattern matching with switch for determining applicability
    SimpleUpgrade simpleUpgrade = new SimpleUpgrade("2.0.0");
    ComplexUpgrade complexUpgradeWithDeps = new ComplexUpgrade("2.1.0", "Complex upgrade", List.of("dep1"));
    ComplexUpgrade complexUpgradeWithoutDeps = new ComplexUpgrade("2.2.0", "Complex upgrade", List.of());
    ConditionalUpgrade applicableUpgrade = new ConditionalUpgrade("2.3.0", true);
    ConditionalUpgrade nonApplicableUpgrade = new ConditionalUpgrade("2.4.0", false);

    // Verify pattern matching with switch correctly determines applicability
    assertTrue(upgradeProcessor.isApplicable(simpleUpgrade));
    assertTrue(upgradeProcessor.isApplicable(complexUpgradeWithDeps));
    assertFalse(upgradeProcessor.isApplicable(complexUpgradeWithoutDeps));
    assertTrue(upgradeProcessor.isApplicable(applicableUpgrade));
    assertFalse(upgradeProcessor.isApplicable(nonApplicableUpgrade));
    assertFalse(upgradeProcessor.isApplicable(null));
    assertFalse(upgradeProcessor.isApplicable("not an upgrade model"));
  }
}