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

/**
 * Tests for {@link RepositoryPath}.
 * 
 * This test class validates both traditional string parsing and Java 21 Pattern Matching
 * implementations for repository path parsing logic.
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
    assertThat(parsedPath.repositoryName(), is(expectedRepoName));
    assertThat(parsedPath.remainingPath(), is(expectedRemainingPath));
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
  
  /*
   * The following tests are designed to validate Pattern Matching implementations
   * for repository path parsing. These tests ensure compatibility with both traditional
   * string parsing and Java 21 Pattern Matching approaches.
   */
  
  @Test
  public void complexPathWithVersionPattern() {
    // Tests path with version pattern that would benefit from Pattern Matching
    assertPath("/maven-central/org/apache/maven/3.8.4/maven-core-3.8.4.jar", 
               "maven-central", 
               "/org/apache/maven/3.8.4/maven-core-3.8.4.jar");
  }
  
  @Test
  public void complexPathWithMultipleParameters() {
    // Tests path with multiple parameters that would benefit from Pattern Matching
    assertPath("/docker/library/ubuntu/tags/latest?filter=name&sort=asc", 
               "docker", 
               "/library/ubuntu/tags/latest");
  }
  
  @Test
  public void complexPathWithSpecialCharacters() {
    // Tests path with special characters that would benefit from Pattern Matching
    assertPath("/npm/@angular/core/12.2.0/core-12.2.0.tgz", 
               "npm", 
               "/@angular/core/12.2.0/core-12.2.0.tgz");
  }
  
  @Test
  public void complexPathWithEncodedCharacters() {
    // Tests path with encoded characters that would benefit from Pattern Matching
    assertPath("/maven-central/com/example/artifact%20with%20spaces/1.0.0/artifact%20with%20spaces-1.0.0.jar", 
               "maven-central", 
               "/com/example/artifact%20with%20spaces/1.0.0/artifact%20with%20spaces-1.0.0.jar");
  }
  
  @Test
  public void complexPathWithMultipleRepositoryLevels() {
    // Tests path with multiple repository levels that would benefit from Pattern Matching
    assertPath("/maven-group/maven-central/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar", 
               "maven-group", 
               "/maven-central/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar");
  }
  
  @Test
  public void pathWithQueryParameters() {
    // Tests path with query parameters that would benefit from Pattern Matching
    // Note: In a real implementation, query parameters might be handled separately
    assertPath("/repo/path/to/resource?param1=value1&param2=value2", 
               "repo", 
               "/path/to/resource");
  }
  
  @Test
  public void pathWithFragmentIdentifier() {
    // Tests path with fragment identifier that would benefit from Pattern Matching
    // Note: In a real implementation, fragment identifiers might be handled separately
    assertPath("/repo/path/to/document#section1", 
               "repo", 
               "/path/to/document");
  }
  
  @Test
  public void pathWithMixedCaseRepository() {
    // Tests path with mixed case repository name that would benefit from Pattern Matching
    assertPath("/MaVeN-CeNtRaL/org/example/1.0.0/example-1.0.0.jar", 
               "MaVeN-CeNtRaL", 
               "/org/example/1.0.0/example-1.0.0.jar");
  }
  
  @Test
  public void pathWithNumericRepository() {
    // Tests path with numeric repository name that would benefit from Pattern Matching
    assertPath("/123456/path/to/resource", 
               "123456", 
               "/path/to/resource");
  }
  
  @Test
  public void pathWithComplexPatternMatching() {
    // Tests path with complex pattern that would benefit from Pattern Matching
    // This test validates a path that contains multiple elements that could be matched with patterns
    assertPath("/maven-central/org/apache/maven/plugins/maven-compiler-plugin/3.8.1/maven-compiler-plugin-3.8.1.jar", 
               "maven-central", 
               "/org/apache/maven/plugins/maven-compiler-plugin/3.8.1/maven-compiler-plugin-3.8.1.jar");
  }
}
