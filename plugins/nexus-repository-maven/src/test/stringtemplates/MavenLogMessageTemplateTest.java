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
package org.sonatype.nexus.repository.maven.stringtemplates;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.maven.category.Java21TestGroup;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for validating Java 21's String Templates feature for formatting log messages in the Maven repository plugin.
 * 
 * @since 3.60
 */
@Category(Java21TestGroup.class)
public class MavenLogMessageTemplateTest
    extends TestSupport
{
  private static final String REPOSITORY_NAME = "maven-central";
  private static final String GROUP_ID = "org.sonatype.nexus";
  private static final String ARTIFACT_ID = "nexus-repository-maven";
  private static final String VERSION = "3.60.0-SNAPSHOT";
  private static final String EXTENSION = "jar";
  private static final String CLASSIFIER = "sources";
  
  /**
   * Test that artifact upload log messages are correctly formatted using String Templates.
   */
  @Test
  public void testArtifactUploadLogMessage() {
    String message = formatArtifactUploadLogMessage(REPOSITORY_NAME, GROUP_ID, ARTIFACT_ID, VERSION, EXTENSION, CLASSIFIER);
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString(EXTENSION));
    assertThat(message, containsString(CLASSIFIER));
    assertThat(message, containsString("uploaded"));
  }
  
  /**
   * Test that artifact download log messages are correctly formatted using String Templates.
   */
  @Test
  public void testArtifactDownloadLogMessage() {
    String message = formatArtifactDownloadLogMessage(REPOSITORY_NAME, GROUP_ID, ARTIFACT_ID, VERSION, EXTENSION, CLASSIFIER);
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString(EXTENSION));
    assertThat(message, containsString(CLASSIFIER));
    assertThat(message, containsString("downloaded"));
  }
  
  /**
   * Test that metadata update log messages are correctly formatted using String Templates.
   */
  @Test
  public void testMetadataUpdateLogMessage() {
    String message = formatMetadataUpdateLogMessage(REPOSITORY_NAME, GROUP_ID, ARTIFACT_ID, VERSION);
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString("metadata"));
    assertThat(message, containsString("updated"));
  }
  
  /**
   * Test that repository operation log messages are correctly formatted using String Templates.
   */
  @Test
  public void testRepositoryOperationLogMessage() {
    String operation = "rebuild-metadata";
    String message = formatRepositoryOperationLogMessage(REPOSITORY_NAME, operation);
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString(operation));
    assertThat(message, containsString("operation"));
    assertThat(message, containsString("completed"));
  }
  
  /**
   * Test that log messages with null values are handled correctly using String Templates.
   */
  @Test
  public void testLogMessageWithNullValues() {
    String message = formatArtifactUploadLogMessage(REPOSITORY_NAME, GROUP_ID, ARTIFACT_ID, VERSION, EXTENSION, null);
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString(EXTENSION));
    assertThat(message, containsString("<no classifier>"));
    assertThat(message, containsString("uploaded"));
  }
  
  /**
   * Test that log messages with empty values are handled correctly using String Templates.
   */
  @Test
  public void testLogMessageWithEmptyValues() {
    String message = formatArtifactUploadLogMessage(REPOSITORY_NAME, GROUP_ID, ARTIFACT_ID, VERSION, "", "");
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString("<no extension>"));
    assertThat(message, containsString("<no classifier>"));
    assertThat(message, containsString("uploaded"));
  }
  
  /**
   * Test that log messages with special characters are handled correctly using String Templates.
   */
  @Test
  public void testLogMessageWithSpecialCharacters() {
    String groupId = "org.example.special-chars";
    String artifactId = "artifact_with.special-chars";
    String version = "1.0.0-SNAPSHOT+build.123";
    
    String message = formatArtifactUploadLogMessage(REPOSITORY_NAME, groupId, artifactId, version, EXTENSION, CLASSIFIER);
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString(groupId));
    assertThat(message, containsString(artifactId));
    assertThat(message, containsString(version));
    assertThat(message, containsString(EXTENSION));
    assertThat(message, containsString(CLASSIFIER));
    assertThat(message, containsString("uploaded"));
  }
  
  /**
   * Test that log messages with Maven coordinates are correctly formatted using String Templates.
   */
  @Test
  public void testLogMessageWithMavenCoordinates() {
    String coordinates = formatMavenCoordinates(GROUP_ID, ARTIFACT_ID, VERSION, EXTENSION, CLASSIFIER);
    String message = formatCoordinatesLogMessage(REPOSITORY_NAME, coordinates, "processed");
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString(EXTENSION));
    assertThat(message, containsString(CLASSIFIER));
    assertThat(message, containsString("processed"));
  }
  
  /**
   * Test that format specifiers in log messages are correctly handled using String Templates.
   */
  @Test
  public void testLogMessageWithFormatSpecifiers() {
    String message = formatLogMessageWithFormatSpecifiers(REPOSITORY_NAME, GROUP_ID, ARTIFACT_ID, VERSION);
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString("Repository name: " + REPOSITORY_NAME));
    assertThat(message, containsString("GroupId: " + GROUP_ID));
    assertThat(message, containsString("ArtifactId: " + ARTIFACT_ID));
    assertThat(message, containsString("Version: " + VERSION));
  }
  
  /**
   * Test that multi-line log messages are correctly formatted using String Templates.
   */
  @Test
  public void testMultiLineLogMessage() {
    String message = formatMultiLineLogMessage(REPOSITORY_NAME, GROUP_ID, ARTIFACT_ID, VERSION, EXTENSION, CLASSIFIER);
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString(EXTENSION));
    assertThat(message, containsString(CLASSIFIER));
    assertThat(message, containsString("Repository:"));
    assertThat(message, containsString("Maven Coordinates:"));
    assertThat(message, containsString("Status:"));
  }
  
  /**
   * Formats an artifact upload log message using String Templates.
   */
  private String formatArtifactUploadLogMessage(String repository, String groupId, String artifactId, String version,
                                               String extension, String classifier) {
    String classifierStr = classifier != null ? classifier : "<no classifier>";
    String extensionStr = extension != null && !extension.isEmpty() ? extension : "<no extension>";
    
    return STR."Artifact \{groupId}:\{artifactId}:\{version}:\{classifierStr}:\{extensionStr} uploaded to repository \{repository}";
  }
  
  /**
   * Formats an artifact download log message using String Templates.
   */
  private String formatArtifactDownloadLogMessage(String repository, String groupId, String artifactId, String version,
                                                String extension, String classifier) {
    String classifierStr = classifier != null ? classifier : "<no classifier>";
    String extensionStr = extension != null && !extension.isEmpty() ? extension : "<no extension>";
    
    return STR."Artifact \{groupId}:\{artifactId}:\{version}:\{classifierStr}:\{extensionStr} downloaded from repository \{repository}";
  }
  
  /**
   * Formats a metadata update log message using String Templates.
   */
  private String formatMetadataUpdateLogMessage(String repository, String groupId, String artifactId, String version) {
    return STR."Maven metadata for \{groupId}:\{artifactId}:\{version} updated in repository \{repository}";
  }
  
  /**
   * Formats a repository operation log message using String Templates.
   */
  private String formatRepositoryOperationLogMessage(String repository, String operation) {
    return STR."Repository operation '\{operation}' completed successfully on repository \{repository}";
  }
  
  /**
   * Formats Maven coordinates using String Templates.
   */
  private String formatMavenCoordinates(String groupId, String artifactId, String version, String extension, String classifier) {
    if (classifier != null && !classifier.isEmpty()) {
      return STR."\{groupId}:\{artifactId}:\{version}:\{classifier}:\{extension}";
    } else {
      return STR."\{groupId}:\{artifactId}:\{version}:\{extension}";
    }
  }
  
  /**
   * Formats a log message with Maven coordinates using String Templates.
   */
  private String formatCoordinatesLogMessage(String repository, String coordinates, String action) {
    return STR."Maven artifact \{coordinates} \{action} in repository \{repository}";
  }
  
  /**
   * Formats a log message with format specifiers using String Templates.
   */
  private String formatLogMessageWithFormatSpecifiers(String repository, String groupId, String artifactId, String version) {
    return STR."""
        Maven artifact details:
        Repository name: \{repository}
        GroupId: \{groupId}
        ArtifactId: \{artifactId}
        Version: \{version}
        Format: \{Maven2Format.NAME}
        """;
  }
  
  /**
   * Formats a multi-line log message using String Templates.
   */
  private String formatMultiLineLogMessage(String repository, String groupId, String artifactId, String version,
                                         String extension, String classifier) {
    String coordinates = formatMavenCoordinates(groupId, artifactId, version, extension, classifier);
    
    return STR."""
        Maven Artifact Processing:
        Repository: \{repository}
        Maven Coordinates: \{coordinates}
        Status: Successfully processed
        Timestamp: \{java.time.LocalDateTime.now()}
        """;
  }
}