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
package org.sonatype.nexus.repository.maven.internal.stringtemplates;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.repository.InvalidContentException;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.internal.Maven2MavenPathParser;

import java.lang.StringTemplate;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for Maven error message formatting using Java 21 String Templates.
 * 
 * This test class validates that error messages related to Maven repository operations
 * are correctly formatted using Java 21's String Templates feature.
 */
@Category(Java21TestGroup.class)
public class MavenErrorMessageTemplateTest
    extends TestSupport
{
  private Maven2MavenPathParser pathParser;
  private MavenErrorMessageFormatter errorFormatter;

  @Before
  public void setup() {
    pathParser = new Maven2MavenPathParser();
    errorFormatter = new MavenErrorMessageFormatter();
  }

  /**
   * Tests error message formatting for invalid Maven coordinates.
   */
  @Test
  public void testInvalidCoordinatesErrorMessage() {
    String repositoryName = "maven-central";
    String path = "org/example/invalid/1.0/invalid-1.0.jar";
    String reason = "Missing required fields";
    
    String errorMessage = errorFormatter.formatInvalidCoordinatesError(repositoryName, path, reason);
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(repositoryName));
    assertThat(errorMessage, containsString(path));
    assertThat(errorMessage, containsString(reason));
  }

  /**
   * Tests error message formatting for missing artifacts.
   */
  @Test
  public void testMissingArtifactErrorMessage() {
    String repositoryName = "maven-central";
    MavenPath mavenPath = pathParser.parsePath("/org/example/artifact/1.0/artifact-1.0.jar");
    
    String errorMessage = errorFormatter.formatMissingArtifactError(repositoryName, mavenPath);
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(repositoryName));
    assertThat(errorMessage, containsString(mavenPath.getPath()));
    assertThat(errorMessage, containsString("org.example"));
    assertThat(errorMessage, containsString("artifact"));
    assertThat(errorMessage, containsString("1.0"));
  }

  /**
   * Tests error message formatting for repository access errors.
   */
  @Test
  public void testRepositoryAccessErrorMessage() {
    String repositoryName = "maven-central";
    String operation = "download";
    String reason = "Connection timeout";
    
    String errorMessage = errorFormatter.formatRepositoryAccessError(repositoryName, operation, reason);
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(repositoryName));
    assertThat(errorMessage, containsString(operation));
    assertThat(errorMessage, containsString(reason));
  }

  /**
   * Tests error message formatting for invalid metadata content.
   */
  @Test
  public void testInvalidMetadataErrorMessage() {
    String path = "org/example/artifact/maven-metadata.xml";
    String reason = "XML parsing error";
    
    String errorMessage = errorFormatter.formatInvalidMetadataError(path, reason);
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(path));
    assertThat(errorMessage, containsString(reason));
  }

  /**
   * Tests error message formatting for version policy violations.
   */
  @Test
  public void testVersionPolicyViolationErrorMessage() {
    String repositoryName = "maven-releases";
    MavenPath mavenPath = pathParser.parsePath("/org/example/snapshot/1.0-SNAPSHOT/snapshot-1.0-SNAPSHOT.jar");
    String policy = "RELEASE";
    
    String errorMessage = errorFormatter.formatVersionPolicyViolationError(repositoryName, mavenPath, policy);
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(repositoryName));
    assertThat(errorMessage, containsString(mavenPath.getPath()));
    assertThat(errorMessage, containsString(policy));
    assertThat(errorMessage, containsString("1.0-SNAPSHOT"));
  }

  /**
   * Tests error message formatting for checksum validation failures.
   */
  @Test
  public void testChecksumValidationErrorMessage() {
    String repositoryName = "maven-central";
    MavenPath mavenPath = pathParser.parsePath("/org/example/artifact/1.0/artifact-1.0.jar");
    String expectedChecksum = "abc123";
    String actualChecksum = "def456";
    
    String errorMessage = errorFormatter.formatChecksumValidationError(
        repositoryName, mavenPath, expectedChecksum, actualChecksum);
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(repositoryName));
    assertThat(errorMessage, containsString(mavenPath.getPath()));
    assertThat(errorMessage, containsString(expectedChecksum));
    assertThat(errorMessage, containsString(actualChecksum));
  }

  /**
   * Tests error message formatting for upload validation failures.
   */
  @Test
  public void testUploadValidationErrorMessage() {
    String repositoryName = "maven-releases";
    String path = "org/example/artifact/1.0/artifact-1.0.jar";
    String reason = "Invalid POM file";
    
    String errorMessage = errorFormatter.formatUploadValidationError(repositoryName, path, reason);
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(repositoryName));
    assertThat(errorMessage, containsString(path));
    assertThat(errorMessage, containsString(reason));
  }

  /**
   * Tests error message formatting for invalid content exceptions.
   */
  @Test
  public void testInvalidContentExceptionMessage() {
    String path = "org/example/artifact/maven-metadata.xml";
    String reason = "XML parsing error";
    
    InvalidContentException exception = new InvalidContentException(path, reason);
    String errorMessage = errorFormatter.formatInvalidContentExceptionMessage(exception);
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(path));
    assertThat(errorMessage, containsString(reason));
  }

  /**
   * Tests error message formatting for complex error scenarios with multiple variables.
   */
  @Test
  public void testComplexErrorMessage() {
    String repositoryName = "maven-central";
    MavenPath mavenPath = pathParser.parsePath("/org/example/artifact/1.0/artifact-1.0.jar");
    String operation = "download";
    String reason = "Network error";
    int statusCode = 404;
    
    String errorMessage = errorFormatter.formatComplexError(
        repositoryName, mavenPath, operation, reason, statusCode);
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(repositoryName));
    assertThat(errorMessage, containsString(mavenPath.getPath()));
    assertThat(errorMessage, containsString(operation));
    assertThat(errorMessage, containsString(reason));
    assertThat(errorMessage, containsString(String.valueOf(statusCode)));
  }
}