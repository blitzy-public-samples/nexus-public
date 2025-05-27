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
package java21;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Test class demonstrating Java 21's Record Patterns feature for simplified data handling in APT repository plugin.
 * 
 * @since 3.60
 */
public class AptRecordPatternsTest
{
  /**
   * Record representing a Debian package version with major, minor, and patch components.
   */
  record DebianVersion(int major, int minor, int patch) {
    @Override
    public String toString() {
      return major + "." + minor + "." + patch;
    }
    
    /**
     * Checks if this version is newer than the provided version.
     */
    public boolean isNewerThan(DebianVersion other) {
      if (major > other.major) return true;
      if (major < other.major) return false;
      if (minor > other.minor) return true;
      if (minor < other.minor) return false;
      return patch > other.patch;
    }
  }
  
  /**
   * Record representing package architecture information.
   */
  record Architecture(String name, boolean isSource) {
    public static final Architecture AMD64 = new Architecture("amd64", false);
    public static final Architecture I386 = new Architecture("i386", false);
    public static final Architecture ARM64 = new Architecture("arm64", false);
    public static final Architecture SOURCE = new Architecture("source", true);
  }
  
  /**
   * Record representing package maintainer information.
   */
  record Maintainer(String name, String email) {
    @Override
    public String toString() {
      return name + " <" + email + ">";
    }
  }
  
  /**
   * Record representing a dependency on another package.
   */
  record Dependency(String packageName, DebianVersion minVersion, boolean isRequired) {}
  
  /**
   * Record representing APT package metadata.
   */
  record PackageMetadata(
      String name,
      DebianVersion version,
      Architecture architecture,
      Maintainer maintainer,
      long size,
      Dependency[] dependencies
  ) {}
  
  /**
   * Test basic record pattern matching with instanceof.
   * Demonstrates extracting components directly from a record without intermediate variables.
   */
  @Test
  public void testBasicRecordPatternMatching() {
    Object obj = new DebianVersion(2, 1, 0);
    
    // Traditional approach (pre-Java 21)
    if (obj instanceof DebianVersion) {
      DebianVersion version = (DebianVersion) obj;
      assertEquals(2, version.major());
      assertEquals(1, version.minor());
      assertEquals(0, version.patch());
    }
    
    // Using Java 21 record pattern
    if (obj instanceof DebianVersion(int major, int minor, int patch)) {
      assertEquals(2, major);
      assertEquals(1, minor);
      assertEquals(0, patch);
    } else {
      throw new AssertionError("Record pattern did not match");
    }
  }
  
  /**
   * Test using var for type inference in record patterns.
   */
  @Test
  public void testVarInRecordPatterns() {
    Object obj = new Maintainer("John Doe", "john.doe@example.com");
    
    // Using var for type inference
    if (obj instanceof Maintainer(var name, var email)) {
      assertEquals("John Doe", name);
      assertEquals("john.doe@example.com", email);
    } else {
      throw new AssertionError("Record pattern with var did not match");
    }
  }
  
  /**
   * Test nested record pattern matching.
   * Demonstrates extracting data from nested records in a single pattern match.
   */
  @Test
  public void testNestedRecordPatterns() {
    PackageMetadata metadata = new PackageMetadata(
        "nginx",
        new DebianVersion(1, 18, 0),
        Architecture.AMD64,
        new Maintainer("Debian Nginx Maintainers", "pkg-nginx-maintainers@lists.alioth.debian.org"),
        2540536,
        new Dependency[] {
            new Dependency("libc6", new DebianVersion(2, 27, 0), true)
        }
    );
    
    Object obj = metadata;
    
    // Using nested record patterns to extract deeply nested data in one step
    if (obj instanceof PackageMetadata(String name, DebianVersion(int major, int minor, int patch), 
                                      var arch, Maintainer(var maintainerName, var maintainerEmail), 
                                      var size, var deps)) {
      assertEquals("nginx", name);
      assertEquals(1, major);
      assertEquals(18, minor);
      assertEquals(0, patch);
      assertEquals("amd64", arch.name());
      assertEquals("Debian Nginx Maintainers", maintainerName);
      assertEquals("pkg-nginx-maintainers@lists.alioth.debian.org", maintainerEmail);
      assertEquals(2540536, size);
      assertEquals(1, deps.length);
    } else {
      throw new AssertionError("Nested record pattern did not match");
    }
  }
  
  /**
   * Test record pattern matching in switch expressions.
   * Demonstrates using record patterns in switch cases for concise data extraction and processing.
   */
  @Test
  public void testRecordPatternInSwitch() {
    Object obj1 = new DebianVersion(2, 0, 0);
    Object obj2 = new Architecture("arm64", false);
    Object obj3 = "not a record";
    
    // Using record patterns in switch expressions
    String result1 = switch (obj1) {
      case DebianVersion(int major, int minor, int patch) ->
          "Debian version " + major + "." + minor + "." + patch;
      case Architecture(String name, boolean isSource) ->
          "Architecture: " + name + (isSource ? " (source)" : "");
      default -> "Unknown object";
    };
    
    String result2 = switch (obj2) {
      case DebianVersion(int major, int minor, int patch) ->
          "Debian version " + major + "." + minor + "." + patch;
      case Architecture(String name, boolean isSource) ->
          "Architecture: " + name + (isSource ? " (source)" : "");
      default -> "Unknown object";
    };
    
    String result3 = switch (obj3) {
      case DebianVersion(int major, int minor, int patch) ->
          "Debian version " + major + "." + minor + "." + patch;
      case Architecture(String name, boolean isSource) ->
          "Architecture: " + name + (isSource ? " (source)" : "");
      default -> "Unknown object";
    };
    
    assertEquals("Debian version 2.0.0", result1);
    assertEquals("Architecture: arm64", result2);
    assertEquals("Unknown object", result3);
  }
  
  /**
   * Test practical use case: version comparison using record patterns.
   * Demonstrates how record patterns can simplify version comparison logic.
   */
  @Test
  public void testVersionComparison() {
    Object version1 = new DebianVersion(1, 0, 0);
    Object version2 = new DebianVersion(2, 0, 0);
    
    boolean isNewer = compareVersions(version1, version2);
    assertFalse("Version 1.0.0 should not be newer than 2.0.0", isNewer);
    
    isNewer = compareVersions(version2, version1);
    assertTrue("Version 2.0.0 should be newer than 1.0.0", isNewer);
  }
  
  /**
   * Helper method that uses record patterns to compare Debian versions.
   */
  private boolean compareVersions(Object v1, Object v2) {
    // Using record patterns to extract version components directly
    if (v1 instanceof DebianVersion(int major1, int minor1, int patch1) && 
        v2 instanceof DebianVersion(int major2, int minor2, int patch2)) {
      
      if (major1 > major2) return true;
      if (major1 < major2) return false;
      if (minor1 > minor2) return true;
      if (minor1 < minor2) return false;
      return patch1 > patch2;
    }
    return false;
  }
  
  /**
   * Test practical use case: dependency resolution using record patterns.
   * Demonstrates how record patterns can simplify dependency resolution logic.
   */
  @Test
  public void testDependencyResolution() {
    PackageMetadata nginx = new PackageMetadata(
        "nginx",
        new DebianVersion(1, 18, 0),
        Architecture.AMD64,
        new Maintainer("Debian Nginx Maintainers", "pkg-nginx-maintainers@lists.alioth.debian.org"),
        2540536,
        new Dependency[] {
            new Dependency("libc6", new DebianVersion(2, 27, 0), true),
            new Dependency("libssl1.1", new DebianVersion(1, 1, 0), true),
            new Dependency("nginx-common", new DebianVersion(1, 18, 0), true)
        }
    );
    
    // Using record patterns to check if a package has a specific required dependency
    boolean hasLibc6 = hasDependency(nginx, "libc6", true);
    boolean hasLibssl = hasDependency(nginx, "libssl1.1", true);
    boolean hasOptionalDep = hasDependency(nginx, "nginx-doc", false);
    
    assertTrue("Nginx should have libc6 as a required dependency", hasLibc6);
    assertTrue("Nginx should have libssl1.1 as a required dependency", hasLibssl);
    assertFalse("Nginx should not have nginx-doc as a required dependency", hasOptionalDep);
  }
  
  /**
   * Helper method that uses record patterns to check if a package has a specific dependency.
   */
  private boolean hasDependency(Object pkg, String depName, boolean required) {
    if (pkg instanceof PackageMetadata(var name, var version, var arch, var maintainer, var size, Dependency[] deps)) {
      for (Dependency dep : deps) {
        if (dep instanceof Dependency(String packageName, var minVersion, boolean isRequired)) {
          if (packageName.equals(depName) && (!required || isRequired)) {
            return true;
          }
        }
      }
    }
    return false;
  }
}