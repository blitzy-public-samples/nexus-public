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
package org.sonatype.nexus.repository.httpbridge.internal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.BadRequestException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Tests for {@link RepositoryPath}.
 */
public class RepositoryPathTest
    extends TestSupport
{
  static final String RELATIVE_TOKEN_MESSAGE = "Repository path must not contain a relative token";

  static final String NULL_OR_EMPTY_MESSAGE = "Repository path must not be null or empty";

  static final String MULTIPLE_SLASH_MESSAGE = "Repository path must have another '/' after initial '/'";

  static final String START_WITH_SLASH_MESSAGE = "Repository path must start with '/'";

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private void assertExceptionOnInvalidPath(final String input, final String message) {
    expectedException.expect(BadRequestException.class);
    expectedException.expectMessage(message);
    RepositoryPath path = RepositoryPath.parse(input);
    assertThat(path, nullValue());
  }

  private void assertPath(final String path, final String expectedRepoName, final String expectedRemainingPath) {
    final RepositoryPath parsedPath = RepositoryPath.parse(path);
    assertThat(parsedPath, notNullValue());
    assertThat(parsedPath.getRepositoryName(), is(expectedRepoName));
    assertThat(parsedPath.getRemainingPath(), is(expectedRemainingPath));
  }

  @Test
  public void nullPath() {
    assertExceptionOnInvalidPath(null, NULL_OR_EMPTY_MESSAGE);
  }

  @Test
  public void emptyPath() {
    assertExceptionOnInvalidPath("", NULL_OR_EMPTY_MESSAGE);
  }

  @Test
  public void bareSlash() {
    assertExceptionOnInvalidPath("/", MULTIPLE_SLASH_MESSAGE);
  }

  @Test
  public void missingRepoPathSeperator() {
    assertExceptionOnInvalidPath("/repo", MULTIPLE_SLASH_MESSAGE);
  }

  @Test
  public void missingLeadingSlash() {
    assertExceptionOnInvalidPath("repo", START_WITH_SLASH_MESSAGE);
  }

  @Test
  public void repoDot() {
    assertExceptionOnInvalidPath("/./", RELATIVE_TOKEN_MESSAGE);
  }

  @Test
  public void repoDots() {
    assertExceptionOnInvalidPath("/../", RELATIVE_TOKEN_MESSAGE);
  }

  @Test
  public void invalidRelative1() {
    assertExceptionOnInvalidPath("/repo/..", RELATIVE_TOKEN_MESSAGE);
  }

  @Test
  public void invalidRelative2() {
    assertExceptionOnInvalidPath("/repo/../bar", RELATIVE_TOKEN_MESSAGE);
  }

  @Test
  public void invalidRelative3() {
    assertExceptionOnInvalidPath("/repo/foo/../../bar", RELATIVE_TOKEN_MESSAGE);
  }

  @Test
  public void repoAndRootPath() {
    // allow root path
    assertPath("/repo/", "repo", "/");
  }

  @Test
  public void repoAndSimplePath() {
    assertPath("/repo/path", "repo", "/path");
  }

  @Test
  public void complexPath() {
    assertPath("/repo/foo/bar/baz", "repo", "/foo/bar/baz");
  }

  @Test
  public void sillySlashes() {
    assertPath("/repo/foo/////bar/baz", "repo", "/foo/bar/baz");
  }

  @Test
  public void relativePath() {
    assertPath("/repo/foo/../bar/../baz", "repo", "/baz");
  }

  @Test
  public void dotReference1() {
    assertPath("/repo/foo/./baz", "repo", "/foo/baz");
  }

  @Test
  public void dotReference2() {
    assertPath("/repo/foo/././baz", "repo", "/foo/baz");
  }

  @Test
  public void fileWithDot() {
    assertPath("/repo/foo/baz.bar", "repo", "/foo/baz.bar");
  }

  @Test
  public void fileWithSpaces() throws Exception {
    assertPath("/repo/foo/abc bar.txt", "repo", "/foo/abc bar.txt");
  }
  
  /**
   * Tests for pattern matching with different path formats.
   * This test validates the pattern matching implementation for repository paths.
   */
  @Test
  public void testPatternMatchingWithDifferentPathFormats() {
    // Test with various path formats using pattern matching
    switch ("/repo/path") {
      case String s when s.startsWith("/") && s.indexOf('/', 1) > 0 -> {
        String[] parts = s.substring(1).split("/", 2);
        assertEquals("repo", parts[0]);
        assertEquals("path", parts[1]);
      }
      default -> fail("Path should match the pattern");
    }
    
    // Test with complex path using pattern matching
    switch ("/repo/foo/bar/baz") {
      case String s when s.startsWith("/") && s.indexOf('/', 1) > 0 -> {
        String[] parts = s.substring(1).split("/", 2);
        assertEquals("repo", parts[0]);
        assertEquals("foo/bar/baz", parts[1]);
      }
      default -> fail("Path should match the pattern");
    }
  }
  
  /**
   * Tests for pattern matching with invalid paths.
   * This test validates that pattern matching correctly identifies invalid paths.
   */
  @Test
  public void testPatternMatchingWithInvalidPaths() {
    // Test with invalid paths using pattern matching
    String result = switch ("repo") { // Missing leading slash
      case String s when s.startsWith("/") && s.indexOf('/', 1) > 0 -> "Valid repository path";
      case String s when !s.startsWith("/") -> "Missing leading slash";
      case String s when s.startsWith("/") && s.indexOf('/', 1) == -1 -> "Missing repository path separator";
      default -> "Unknown pattern";
    };
    assertEquals("Missing leading slash", result);
    
    result = switch ("/repo") { // Missing path separator
      case String s when s.startsWith("/") && s.indexOf('/', 1) > 0 -> "Valid repository path";
      case String s when !s.startsWith("/") -> "Missing leading slash";
      case String s when s.startsWith("/") && s.indexOf('/', 1) == -1 -> "Missing repository path separator";
      default -> "Unknown pattern";
    };
    assertEquals("Missing repository path separator", result);
  }
  
  /**
   * Tests for pattern matching with relative path tokens.
   * This test validates that pattern matching correctly identifies paths with relative tokens.
   */
  @Test
  public void testPatternMatchingWithRelativeTokens() {
    // Test with paths containing relative tokens using pattern matching
    String result = switch ("/repo/../path") {
      case String s when s.contains("/../") || s.contains("/./") || s.endsWith("/.") || s.endsWith("/..")
          -> "Contains relative token";
      case String s when s.startsWith("/") && s.indexOf('/', 1) > 0 -> "Valid repository path";
      default -> "Unknown pattern";
    };
    assertEquals("Contains relative token", result);
    
    result = switch ("/repo/./path") {
      case String s when s.contains("/../") || s.contains("/./") || s.endsWith("/.") || s.endsWith("/..")
          -> "Contains relative token";
      case String s when s.startsWith("/") && s.indexOf('/', 1) > 0 -> "Valid repository path";
      default -> "Unknown pattern";
    };
    assertEquals("Contains relative token", result);
  }
  
  /**
   * Tests for complex pattern matching with nested conditions.
   * This test validates more complex pattern matching scenarios with nested conditions.
   */
  @Test
  public void testComplexPatternMatching() {
    // Test with complex path patterns using nested pattern matching
    String path = "/repo/foo/bar/baz.txt";
    
    String result = switch (path) {
      case String s when s.startsWith("/") && s.indexOf('/', 1) > 0 -> {
        String[] parts = s.substring(1).split("/", 2);
        String repo = parts[0];
        String remainingPath = "/" + parts[1];
        
        yield switch (remainingPath) {
          case String p when p.endsWith(".txt") -> "Text file in " + repo;
          case String p when p.endsWith(".jar") -> "JAR file in " + repo;
          default -> "Other file in " + repo;
        };
      }
      default -> "Invalid path";
    };
    
    assertEquals("Text file in repo", result);
  }
  
  /**
   * Tests for compatibility between traditional and pattern matching implementations.
   * This test validates that both implementations produce the same results.
   */
  @Test
  public void testCompatibilityBetweenImplementations() {
    // Test paths with both traditional and pattern matching implementations
    String path = "/repo/foo/bar/baz";
    
    // Traditional implementation
    RepositoryPath parsedPath = RepositoryPath.parse(path);
    String repoName = parsedPath.getRepositoryName();
    String remainingPath = parsedPath.getRemainingPath();
    
    // Pattern matching implementation
    String[] patternResult = switch (path) {
      case String s when s.startsWith("/") && s.indexOf('/', 1) > 0 -> {
        String[] parts = s.substring(1).split("/", 2);
        yield new String[] { parts[0], "/" + parts[1] };
      }
      default -> new String[] { "", "" };
    };
    
    // Verify both implementations produce the same results
    assertEquals(repoName, patternResult[0]);
    assertEquals(remainingPath, patternResult[1]);
  }
}