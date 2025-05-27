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

import java.util.HashMap;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.test.Java21TestGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for Java 21 pattern matching against repository content type hierarchies.
 * 
 * This test validates that pattern matching correctly distinguishes between different content types
 * in the type hierarchy and applies appropriate processing logic. It demonstrates how pattern matching
 * can be used to handle format-specific content types more elegantly than traditional type casting
 * and instanceof checks.
 * 
 * The test compares traditional type checking approaches with Java 21 pattern matching features:
 * 1. Pattern matching in if statements (instanceof with binding variable)
 * 2. Pattern matching in switch statements (type patterns)
 * 3. Pattern matching with guard conditions (when clauses)
 *
 * These patterns are particularly useful for repository format handlers where different content types
 * require format-specific processing logic.
 */
@Category(Java21TestGroup.class)
public class ContentTypeHierarchyPatternTest
    extends TestSupport
{
  /**
   * Define a simple content type hierarchy for testing that mimics the repository content types.
   * This hierarchy represents a simplified version of the actual content types used in
   * Nexus Repository Manager, with format-specific implementations for Maven, NPM, Docker, and Raw.
   */
  interface Content {
    String getPath();
  }
  
  /**
   * Base abstract class for all content types, providing common path functionality.
   */
  abstract static class AbstractContent implements Content {
    private final String path;
    
    protected AbstractContent(String path) {
      this.path = path;
    }
    
    @Override
    public String getPath() {
      return path;
    }
  }
  
  /**
   * Maven-specific content type with Maven coordinates (groupId, artifactId, version).
   * This represents Maven artifacts in the repository.
   */
  static class MavenContent extends AbstractContent {
    private final String groupId;
    private final String artifactId;
    private final String version;
    
    public MavenContent(String path, String groupId, String artifactId, String version) {
      super(path);
      this.groupId = groupId;
      this.artifactId = artifactId;
      this.version = version;
    }
    
    public String getGroupId() {
      return groupId;
    }
    
    public String getArtifactId() {
      return artifactId;
    }
    
    public String getVersion() {
      return version;
    }
  }
  
  /**
   * NPM-specific content type with package name and version.
   * This represents NPM packages in the repository.
   */
  static class NpmContent extends AbstractContent {
    private final String packageName;
    private final String packageVersion;
    
    public NpmContent(String path, String packageName, String packageVersion) {
      super(path);
      this.packageName = packageName;
      this.packageVersion = packageVersion;
    }
    
    public String getPackageName() {
      return packageName;
    }
    
    public String getPackageVersion() {
      return packageVersion;
    }
  }
  
  /**
   * Docker-specific content type with repository name and tag.
   * This represents Docker images in the repository.
   */
  static class DockerContent extends AbstractContent {
    private final String repository;
    private final String tag;
    
    public DockerContent(String path, String repository, String tag) {
      super(path);
      this.repository = repository;
      this.tag = tag;
    }
    
    public String getRepository() {
      return repository;
    }
    
    public String getTag() {
      return tag;
    }
  }
  
  /**
   * Raw content type with MIME type information.
   * This represents generic files in the repository that aren't associated with
   * a specific package format.
   */
  static class RawContent extends AbstractContent {
    private final String contentType;
    
    public RawContent(String path, String contentType) {
      super(path);
      this.contentType = contentType;
    }
    
    public String getContentType() {
      return contentType;
    }
  }
  
  // Test content instances representing different repository formats
  private MavenContent mavenContent;
  private NpmContent npmContent;
  private DockerContent dockerContent;
  private RawContent rawContent;
  private Map<String, Content> contentMap; // Map of format to content instance
  
  @Before
  public void setUp() {
    mavenContent = new MavenContent("/com/example/app/1.0/app-1.0.jar", "com.example", "app", "1.0");
    npmContent = new NpmContent("/example-package/-/example-package-2.0.0.tgz", "example-package", "2.0.0");
    dockerContent = new DockerContent("/v2/example-image/manifests/latest", "example-image", "latest");
    rawContent = new RawContent("/example/file.txt", "text/plain");
    
    contentMap = new HashMap<>();
    contentMap.put("maven", mavenContent);
    contentMap.put("npm", npmContent);
    contentMap.put("docker", dockerContent);
    contentMap.put("raw", rawContent);
  }
  
  /**
   * Test traditional approach using instanceof and casting.
   * 
   * This represents the pre-Java 21 approach where instanceof checks are followed by explicit casting.
   * This pattern is verbose and error-prone, as the cast operation could fail at runtime if the
   * instanceof check is modified without updating the corresponding cast.
   */
  @Test
  public void testTraditionalTypeChecking() {
    for (Content content : contentMap.values()) {
      String formatSpecificInfo = getFormatSpecificInfoTraditional(content);
      assertFormatSpecificInfo(content, formatSpecificInfo);
    }
  }
  
  /**
   * Test Java 21 pattern matching in if statements.
   * 
   * This demonstrates the Java 21 pattern matching feature in if statements, where the instanceof
   * check and variable binding are combined in a single operation. This eliminates the need for
   * explicit casting and reduces the risk of runtime ClassCastExceptions.
   * 
   * This approach is particularly useful for repository format handlers that need to apply
   * format-specific logic based on content type.
   */
  @Test
  public void testPatternMatchingInIfStatements() {
    for (Content content : contentMap.values()) {
      String formatSpecificInfo = getFormatSpecificInfoWithPatternMatchingIf(content);
      assertFormatSpecificInfo(content, formatSpecificInfo);
    }
  }
  
  /**
   * Test Java 21 pattern matching in switch statements.
   * 
   * This demonstrates Java 21's pattern matching for switch, which allows for concise and type-safe
   * handling of different content types. The switch expression directly matches against types and
   * binds variables in a single step, making the code more readable and maintainable.
   * 
   * This pattern is ideal for repository managers and format handlers that need to process
   * different content types with format-specific logic, such as in RepositoryManagerImpl.
   */
  @Test
  public void testPatternMatchingInSwitchStatements() {
    for (Content content : contentMap.values()) {
      String formatSpecificInfo = getFormatSpecificInfoWithPatternMatchingSwitch(content);
      assertFormatSpecificInfo(content, formatSpecificInfo);
    }
  }
  
  /**
   * Test Java 21 pattern matching with guard conditions.
   * 
   * This demonstrates the most powerful form of Java 21 pattern matching, combining type patterns
   * with conditional guards (when clauses). This allows for highly specific matching based on both
   * the type and the content of objects, enabling precise control flow based on complex conditions.
   * 
   * This approach is particularly valuable for repository format handlers that need to apply different
   * processing logic based on both content type and specific attributes of the content, such as
   * version numbers, paths, or other metadata.
   */
  @Test
  public void testPatternMatchingWithGuards() {
    for (Content content : contentMap.values()) {
      String formatSpecificInfo = getFormatSpecificInfoWithGuards(content);
      assertFormatSpecificInfo(content, formatSpecificInfo);
    }
  }
  
  /**
   * Traditional approach using instanceof and casting.
   * 
   * This method demonstrates the pre-Java 21 approach to type checking and casting.
   * It requires separate instanceof checks and explicit casts, leading to more verbose
   * and potentially error-prone code.
   */
  private String getFormatSpecificInfoTraditional(Content content) {
    if (content instanceof MavenContent) {
      MavenContent mavenContent = (MavenContent) content;
      return String.format("Maven: %s:%s:%s", 
          mavenContent.getGroupId(), mavenContent.getArtifactId(), mavenContent.getVersion());
    } 
    else if (content instanceof NpmContent) {
      NpmContent npmContent = (NpmContent) content;
      return String.format("NPM: %s@%s", 
          npmContent.getPackageName(), npmContent.getPackageVersion());
    } 
    else if (content instanceof DockerContent) {
      DockerContent dockerContent = (DockerContent) content;
      return String.format("Docker: %s:%s", 
          dockerContent.getRepository(), dockerContent.getTag());
    } 
    else if (content instanceof RawContent) {
      RawContent rawContent = (RawContent) content;
      return String.format("Raw: %s (%s)", 
          content.getPath(), rawContent.getContentType());
    } 
    else {
      return String.format("Unknown: %s", content.getPath());
    }
  }
  
  /**
   * Java 21 pattern matching in if statements.
   * 
   * This method demonstrates pattern matching in if statements, which combines
   * the instanceof check and variable binding in a single operation. This eliminates
   * the need for explicit casting and makes the code more concise and type-safe.
   */
  private String getFormatSpecificInfoWithPatternMatchingIf(Content content) {
    if (content instanceof MavenContent mavenContent) {
      return String.format("Maven: %s:%s:%s", 
          mavenContent.getGroupId(), mavenContent.getArtifactId(), mavenContent.getVersion());
    } 
    else if (content instanceof NpmContent npmContent) {
      return String.format("NPM: %s@%s", 
          npmContent.getPackageName(), npmContent.getPackageVersion());
    } 
    else if (content instanceof DockerContent dockerContent) {
      return String.format("Docker: %s:%s", 
          dockerContent.getRepository(), dockerContent.getTag());
    } 
    else if (content instanceof RawContent rawContent) {
      return String.format("Raw: %s (%s)", 
          content.getPath(), rawContent.getContentType());
    } 
    else {
      return String.format("Unknown: %s", content.getPath());
    }
  }
  
  /**
   * Java 21 pattern matching in switch statements.
   * 
   * This method demonstrates pattern matching for switch, which allows for concise
   * and type-safe handling of different content types. The switch expression directly
   * matches against types and binds variables in a single step, making the code more
   * readable and maintainable.
   */
  private String getFormatSpecificInfoWithPatternMatchingSwitch(Content content) {
    return switch (content) {
      case MavenContent mavenContent -> 
          String.format("Maven: %s:%s:%s", 
              mavenContent.getGroupId(), mavenContent.getArtifactId(), mavenContent.getVersion());
      case NpmContent npmContent -> 
          String.format("NPM: %s@%s", 
              npmContent.getPackageName(), npmContent.getPackageVersion());
      case DockerContent dockerContent -> 
          String.format("Docker: %s:%s", 
              dockerContent.getRepository(), dockerContent.getTag());
      case RawContent rawContent -> 
          String.format("Raw: %s (%s)", 
              content.getPath(), rawContent.getContentType());
      default -> 
          String.format("Unknown: %s", content.getPath());
    };
  }
  
  /**
   * Java 21 pattern matching with guard conditions.
   * 
   * This method demonstrates the most powerful form of Java 21 pattern matching,
   * combining type patterns with conditional guards (when clauses). This allows for
   * highly specific matching based on both the type and the content of objects,
   * enabling precise control flow based on complex conditions.
   */
  private String getFormatSpecificInfoWithGuards(Content content) {
    return switch (content) {
      case MavenContent mavenContent when "com.example".equals(mavenContent.getGroupId()) -> 
          String.format("Example Maven: %s:%s", 
              mavenContent.getArtifactId(), mavenContent.getVersion());
      case MavenContent mavenContent -> 
          String.format("Maven: %s:%s:%s", 
              mavenContent.getGroupId(), mavenContent.getArtifactId(), mavenContent.getVersion());
      case NpmContent npmContent when npmContent.getPackageVersion().startsWith("2.") -> 
          String.format("NPM v2: %s", 
              npmContent.getPackageName());
      case NpmContent npmContent -> 
          String.format("NPM: %s@%s", 
              npmContent.getPackageName(), npmContent.getPackageVersion());
      case DockerContent dockerContent when "latest".equals(dockerContent.getTag()) -> 
          String.format("Docker latest: %s", 
              dockerContent.getRepository());
      case DockerContent dockerContent -> 
          String.format("Docker: %s:%s", 
              dockerContent.getRepository(), dockerContent.getTag());
      case RawContent rawContent when rawContent.getContentType().startsWith("text/") -> 
          String.format("Text: %s", 
              content.getPath());
      case RawContent rawContent -> 
          String.format("Raw: %s (%s)", 
              content.getPath(), rawContent.getContentType());
      default -> 
          String.format("Unknown: %s", content.getPath());
    };
  }
  
  /**
   * Helper method to assert that format-specific info is correct for each content type.
   * 
   * This method validates that the output from each pattern matching approach is consistent
   * and correct for the given content type. It uses traditional instanceof checks for validation
   * to ensure that the pattern matching implementations produce the expected results.
   */
  private void assertFormatSpecificInfo(Content content, String formatSpecificInfo) {
    if (content instanceof MavenContent) {
      if ("com.example".equals(((MavenContent) content).getGroupId())) {
        assertThat(formatSpecificInfo, is(equalTo("Example Maven: app:1.0")));
      } else {
        assertThat(formatSpecificInfo, is(equalTo("Maven: com.example:app:1.0")));
      }
    } 
    else if (content instanceof NpmContent) {
      if (((NpmContent) content).getPackageVersion().startsWith("2.")) {
        assertThat(formatSpecificInfo, is(equalTo("NPM v2: example-package")));
      } else {
        assertThat(formatSpecificInfo, is(equalTo("NPM: example-package@2.0.0")));
      }
    } 
    else if (content instanceof DockerContent) {
      if ("latest".equals(((DockerContent) content).getTag())) {
        assertThat(formatSpecificInfo, is(equalTo("Docker latest: example-image")));
      } else {
        assertThat(formatSpecificInfo, is(equalTo("Docker: example-image:latest")));
      }
    } 
    else if (content instanceof RawContent) {
      if (((RawContent) content).getContentType().startsWith("text/")) {
        assertThat(formatSpecificInfo, is(equalTo("Text: /example/file.txt")));
      } else {
        assertThat(formatSpecificInfo, is(equalTo("Raw: /example/file.txt (text/plain)")));
      }
    } 
    else {
      assertThat(formatSpecificInfo, is(equalTo("Unknown: " + content.getPath())));
    }
  }
}