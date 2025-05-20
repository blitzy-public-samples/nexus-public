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
package org.sonatype.nexus.pattern;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.RepositoryContent;
import org.sonatype.nexus.testcommon.Java21TestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for Java 21 pattern matching against repository content type hierarchies.
 * 
 * This test validates that pattern matching can be used to handle format-specific content types
 * more cleanly than traditional type casting approaches.
 *
 * @since 3.60
 */
@Category(Java21TestGroup.class)
public class ContentTypeHierarchyPatternTest
    extends TestSupport
{
  /**
   * Test interface hierarchy to demonstrate pattern matching.
   */
  interface FormatContent extends RepositoryContent {
    String getFormat();
  }

  interface MavenContent extends FormatContent {
    String getGroupId();
    String getArtifactId();
    String getVersion();
  }

  interface NpmContent extends FormatContent {
    String getScope();
    String getPackageName();
  }

  interface DockerContent extends FormatContent {
    String getImageName();
    String getTag();
  }

  /**
   * Test implementation classes for the content hierarchy.
   */
  static class MavenAsset implements Asset, MavenContent {
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String path;

    public MavenAsset(String groupId, String artifactId, String version, String path) {
      this.groupId = groupId;
      this.artifactId = artifactId;
      this.version = version;
      this.path = path;
    }

    @Override
    public String getGroupId() {
      return groupId;
    }

    @Override
    public String getArtifactId() {
      return artifactId;
    }

    @Override
    public String getVersion() {
      return version;
    }

    @Override
    public String getFormat() {
      return "maven";
    }

    @Override
    public String path() {
      return path;
    }

    // Stub implementations for Asset interface
    @Override public String kind() { return "maven"; }
    @Override public java.util.Optional<Component> component() { return java.util.Optional.empty(); }
    @Override public boolean blob() { return false; }
    @Override public java.util.Optional<AssetBlob> blob(boolean includeDeleted) { return java.util.Optional.empty(); }
    @Override public java.util.Optional<java.time.OffsetDateTime> lastDownloaded() { return java.util.Optional.empty(); }
    @Override public java.util.Optional<String> blobStoreName() { return java.util.Optional.empty(); }
    @Override public java.util.Optional<Long> blobSize() { return java.util.Optional.empty(); }
    @Override public java.time.OffsetDateTime created() { return java.time.OffsetDateTime.now(); }
    @Override public java.time.OffsetDateTime lastUpdated() { return java.time.OffsetDateTime.now(); }
    @Override public org.sonatype.nexus.common.collect.NestedAttributesMap attributes() { return null; }
    @Override public org.sonatype.nexus.common.collect.NestedAttributesMap attributes(String key) { return null; }
  }

  static class NpmAsset implements Asset, NpmContent {
    private final String scope;
    private final String packageName;
    private final String path;

    public NpmAsset(String scope, String packageName, String path) {
      this.scope = scope;
      this.packageName = packageName;
      this.path = path;
    }

    @Override
    public String getScope() {
      return scope;
    }

    @Override
    public String getPackageName() {
      return packageName;
    }

    @Override
    public String getFormat() {
      return "npm";
    }

    @Override
    public String path() {
      return path;
    }

    // Stub implementations for Asset interface
    @Override public String kind() { return "npm"; }
    @Override public java.util.Optional<Component> component() { return java.util.Optional.empty(); }
    @Override public boolean blob() { return false; }
    @Override public java.util.Optional<AssetBlob> blob(boolean includeDeleted) { return java.util.Optional.empty(); }
    @Override public java.util.Optional<java.time.OffsetDateTime> lastDownloaded() { return java.util.Optional.empty(); }
    @Override public java.util.Optional<String> blobStoreName() { return java.util.Optional.empty(); }
    @Override public java.util.Optional<Long> blobSize() { return java.util.Optional.empty(); }
    @Override public java.time.OffsetDateTime created() { return java.time.OffsetDateTime.now(); }
    @Override public java.time.OffsetDateTime lastUpdated() { return java.time.OffsetDateTime.now(); }
    @Override public org.sonatype.nexus.common.collect.NestedAttributesMap attributes() { return null; }
    @Override public org.sonatype.nexus.common.collect.NestedAttributesMap attributes(String key) { return null; }
  }

  static class DockerAsset implements Asset, DockerContent {
    private final String imageName;
    private final String tag;
    private final String path;

    public DockerAsset(String imageName, String tag, String path) {
      this.imageName = imageName;
      this.tag = tag;
      this.path = path;
    }

    @Override
    public String getImageName() {
      return imageName;
    }

    @Override
    public String getTag() {
      return tag;
    }

    @Override
    public String getFormat() {
      return "docker";
    }

    @Override
    public String path() {
      return path;
    }

    // Stub implementations for Asset interface
    @Override public String kind() { return "docker"; }
    @Override public java.util.Optional<Component> component() { return java.util.Optional.empty(); }
    @Override public boolean blob() { return false; }
    @Override public java.util.Optional<AssetBlob> blob(boolean includeDeleted) { return java.util.Optional.empty(); }
    @Override public java.util.Optional<java.time.OffsetDateTime> lastDownloaded() { return java.util.Optional.empty(); }
    @Override public java.util.Optional<String> blobStoreName() { return java.util.Optional.empty(); }
    @Override public java.util.Optional<Long> blobSize() { return java.util.Optional.empty(); }
    @Override public java.time.OffsetDateTime created() { return java.time.OffsetDateTime.now(); }
    @Override public java.time.OffsetDateTime lastUpdated() { return java.time.OffsetDateTime.now(); }
    @Override public org.sonatype.nexus.common.collect.NestedAttributesMap attributes() { return null; }
    @Override public org.sonatype.nexus.common.collect.NestedAttributesMap attributes(String key) { return null; }
  }

  /**
   * Test that demonstrates pattern matching for content type hierarchy.
   * This shows how Java 21 pattern matching can be used to handle different content types
   * in a more concise and type-safe way compared to traditional instanceof + casting.
   */
  @Test
  public void testPatternMatchingForContentTypes() {
    // Create test assets of different types
    Asset mavenAsset = new MavenAsset("org.example", "example-lib", "1.0.0", "/org/example/example-lib/1.0.0/example-lib-1.0.0.jar");
    Asset npmAsset = new NpmAsset("@scope", "package", "/@scope/package/1.2.3/package-1.2.3.tgz");
    Asset dockerAsset = new DockerAsset("nginx", "latest", "/v2/nginx/manifests/latest");

    // Test pattern matching with switch expression
    String formatInfo = switch (mavenAsset) {
      case MavenContent maven -> String.format("Maven: %s:%s:%s", 
          maven.getGroupId(), maven.getArtifactId(), maven.getVersion());
      case NpmContent npm -> String.format("NPM: %s/%s", 
          npm.getScope(), npm.getPackageName());
      case DockerContent docker -> String.format("Docker: %s:%s", 
          docker.getImageName(), docker.getTag());
      case Asset asset -> String.format("Unknown asset: %s", asset.path());
      default -> "Not a recognized content type";
    };

    assertThat(formatInfo, is(equalTo("Maven: org.example:example-lib:1.0.0")));

    // Test NPM asset with pattern matching
    String npmInfo = switch (npmAsset) {
      case MavenContent maven -> String.format("Maven: %s:%s:%s", 
          maven.getGroupId(), maven.getArtifactId(), maven.getVersion());
      case NpmContent npm -> String.format("NPM: %s/%s", 
          npm.getScope(), npm.getPackageName());
      case DockerContent docker -> String.format("Docker: %s:%s", 
          docker.getImageName(), docker.getTag());
      case Asset asset -> String.format("Unknown asset: %s", asset.path());
      default -> "Not a recognized content type";
    };

    assertThat(npmInfo, is(equalTo("NPM: @scope/package")));

    // Test Docker asset with pattern matching
    String dockerInfo = switch (dockerAsset) {
      case MavenContent maven -> String.format("Maven: %s:%s:%s", 
          maven.getGroupId(), maven.getArtifactId(), maven.getVersion());
      case NpmContent npm -> String.format("NPM: %s/%s", 
          npm.getScope(), npm.getPackageName());
      case DockerContent docker -> String.format("Docker: %s:%s", 
          docker.getImageName(), docker.getTag());
      case Asset asset -> String.format("Unknown asset: %s", asset.path());
      default -> "Not a recognized content type";
    };

    assertThat(dockerInfo, is(equalTo("Docker: nginx:latest")));
  }

  /**
   * Test that demonstrates pattern matching with guard conditions.
   * This shows how pattern matching can be combined with additional conditions
   * to create more specific matching rules.
   */
  @Test
  public void testPatternMatchingWithGuards() {
    // Create test assets
    Asset mavenSnapshotAsset = new MavenAsset("org.example", "example-lib", "1.0.0-SNAPSHOT", 
        "/org/example/example-lib/1.0.0-SNAPSHOT/example-lib-1.0.0-SNAPSHOT.jar");
    Asset mavenReleaseAsset = new MavenAsset("org.example", "example-lib", "1.0.0", 
        "/org/example/example-lib/1.0.0/example-lib-1.0.0.jar");

    // Test pattern matching with guards for snapshot vs release versions
    String versionType = switch (mavenSnapshotAsset) {
      case MavenContent maven when maven.getVersion().endsWith("-SNAPSHOT") -> 
          String.format("Snapshot version: %s", maven.getVersion());
      case MavenContent maven -> 
          String.format("Release version: %s", maven.getVersion());
      default -> "Not a Maven asset";
    };

    assertThat(versionType, is(equalTo("Snapshot version: 1.0.0-SNAPSHOT")));

    // Test with release version
    String releaseVersionType = switch (mavenReleaseAsset) {
      case MavenContent maven when maven.getVersion().endsWith("-SNAPSHOT") -> 
          String.format("Snapshot version: %s", maven.getVersion());
      case MavenContent maven -> 
          String.format("Release version: %s", maven.getVersion());
      default -> "Not a Maven asset";
    };

    assertThat(releaseVersionType, is(equalTo("Release version: 1.0.0")));
  }

  /**
   * Test that compares traditional instanceof approach with pattern matching.
   * This demonstrates how pattern matching reduces boilerplate and improves readability.
   */
  @Test
  public void testComparisonWithTraditionalApproach() {
    // Create test asset
    Asset mavenAsset = new MavenAsset("org.example", "example-lib", "1.0.0", 
        "/org/example/example-lib/1.0.0/example-lib-1.0.0.jar");

    // Traditional approach with instanceof and casting
    String traditionalResult;
    if (mavenAsset instanceof MavenContent) {
      MavenContent maven = (MavenContent) mavenAsset;
      traditionalResult = String.format("Maven: %s:%s:%s", 
          maven.getGroupId(), maven.getArtifactId(), maven.getVersion());
    } else if (mavenAsset instanceof NpmContent) {
      NpmContent npm = (NpmContent) mavenAsset;
      traditionalResult = String.format("NPM: %s/%s", 
          npm.getScope(), npm.getPackageName());
    } else if (mavenAsset instanceof DockerContent) {
      DockerContent docker = (DockerContent) mavenAsset;
      traditionalResult = String.format("Docker: %s:%s", 
          docker.getImageName(), docker.getTag());
    } else {
      traditionalResult = String.format("Unknown asset: %s", mavenAsset.path());
    }

    // Pattern matching approach
    String patternMatchingResult = switch (mavenAsset) {
      case MavenContent maven -> String.format("Maven: %s:%s:%s", 
          maven.getGroupId(), maven.getArtifactId(), maven.getVersion());
      case NpmContent npm -> String.format("NPM: %s/%s", 
          npm.getScope(), npm.getPackageName());
      case DockerContent docker -> String.format("Docker: %s:%s", 
          docker.getImageName(), docker.getTag());
      case Asset asset -> String.format("Unknown asset: %s", asset.path());
      default -> "Not a recognized content type";
    };

    // Both approaches should yield the same result
    assertThat(traditionalResult, is(equalTo("Maven: org.example:example-lib:1.0.0")));
    assertThat(patternMatchingResult, is(equalTo(traditionalResult)));
  }

  /**
   * Test pattern matching with nested patterns.
   * This demonstrates how pattern matching can be used to extract data from nested structures.
   */
  @Test
  public void testNestedPatternMatching() {
    // Create a mock component with a nested asset
    Component component = mock(Component.class);
    MavenAsset mavenAsset = new MavenAsset("org.example", "example-lib", "1.0.0", 
        "/org/example/example-lib/1.0.0/example-lib-1.0.0.jar");
    
    // Set up the component to return the asset
    when(component.kind()).thenReturn("maven");
    java.util.Optional<Asset> optionalAsset = java.util.Optional.of(mavenAsset);
    
    // Test nested pattern matching with component and asset
    String result = switch (component) {
      case Component c when c.kind().equals("maven") && optionalAsset.isPresent() -> {
        Asset asset = optionalAsset.get();
        yield switch (asset) {
          case MavenContent maven -> String.format("Maven component with artifact: %s:%s:%s", 
              maven.getGroupId(), maven.getArtifactId(), maven.getVersion());
          default -> "Maven component with non-Maven asset";
        };
      }
      default -> "Non-Maven component";
    };

    assertThat(result, is(equalTo("Maven component with artifact: org.example:example-lib:1.0.0")));
  }

  /**
   * Test pattern matching with exhaustive cases.
   * This demonstrates how pattern matching ensures all possible cases are handled.
   */
  @Test
  public void testExhaustivePatternMatching() {
    // Create an array of different asset types
    Asset[] assets = new Asset[] {
      new MavenAsset("org.example", "example-lib", "1.0.0", "/org/example/example-lib/1.0.0/example-lib-1.0.0.jar"),
      new NpmAsset("@scope", "package", "/@scope/package/1.2.3/package-1.2.3.tgz"),
      new DockerAsset("nginx", "latest", "/v2/nginx/manifests/latest")
    };

    // Process each asset with pattern matching
    for (Asset asset : assets) {
      String format = switch (asset) {
        case MavenContent ignored -> "maven";
        case NpmContent ignored -> "npm";
        case DockerContent ignored -> "docker";
        default -> "unknown";
      };

      // Verify that the format matches the asset's actual format
      if (asset instanceof FormatContent) {
        FormatContent formatContent = (FormatContent) asset;
        assertThat(format, is(equalTo(formatContent.getFormat())));
      }
    }
  }
}