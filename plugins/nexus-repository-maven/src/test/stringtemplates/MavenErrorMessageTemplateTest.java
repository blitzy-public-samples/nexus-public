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

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.maven.stringtemplates.MavenErrorMessages;
import org.sonatype.nexus.repository.maven.stringtemplates.Java21TestGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link MavenErrorMessages} using Java 21 String Templates.
 * 
 * This test class validates that error messages related to Maven repository operations
 * are correctly formatted using Java 21's String Templates feature. It ensures that
 * error messages contain all necessary information and are properly formatted for
 * user consumption across various error scenarios.
 */
@Category(Java21TestGroup.class)
public class MavenErrorMessageTemplateTest
    extends TestSupport
{
  private static final String REPO_NAME = "maven-central";
  private static final String GROUP_ID = "org.example";
  private static final String ARTIFACT_ID = "test-artifact";
  private static final String VERSION = "1.0.0";
  private static final String CLASSIFIER = "sources";
  private static final String EXTENSION = "jar";

  private MavenPath.Coordinates coordinates;
  private MavenPath mavenPath;

  @Before
  public void setUp() {
    coordinates = new MavenPath.Coordinates(
        GROUP_ID,
        ARTIFACT_ID,
        VERSION,
        CLASSIFIER,
        EXTENSION,
        null);
    
    mavenPath = new MavenPath("org/example/test-artifact/1.0.0/test-artifact-1.0.0-sources.jar", coordinates);
  }

  @Test
  public void testInvalidCoordinatesErrorMessage() {
    String errorMessage = MavenErrorMessages.invalidCoordinatesError(GROUP_ID, ARTIFACT_ID, "invalid-version");
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(GROUP_ID));
    assertThat(errorMessage, containsString(ARTIFACT_ID));
    assertThat(errorMessage, containsString("invalid-version"));
    assertThat(errorMessage, containsString("Invalid Maven coordinates"));
  }

  @Test
  public void testMissingArtifactErrorMessage() {
    String errorMessage = MavenErrorMessages.missingArtifactError(REPO_NAME, mavenPath);
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(REPO_NAME));
    assertThat(errorMessage, containsString(GROUP_ID));
    assertThat(errorMessage, containsString(ARTIFACT_ID));
    assertThat(errorMessage, containsString(VERSION));
    assertThat(errorMessage, containsString(CLASSIFIER));
    assertThat(errorMessage, containsString(EXTENSION));
    assertThat(errorMessage, containsString("not found"));
  }

  @Test
  public void testRepositoryAccessErrorMessage() {
    String errorMessage = MavenErrorMessages.repositoryAccessError(REPO_NAME, "Connection timeout");
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(REPO_NAME));
    assertThat(errorMessage, containsString("Connection timeout"));
    assertThat(errorMessage, containsString("Error accessing repository"));
  }

  @Test
  public void testInvalidMetadataErrorMessage() {
    String errorMessage = MavenErrorMessages.invalidMetadataError(mavenPath.getPath(), "Missing required elements");
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(mavenPath.getPath()));
    assertThat(errorMessage, containsString("Missing required elements"));
    assertThat(errorMessage, containsString("Invalid Maven metadata"));
  }

  @Test
  public void testChecksumMismatchErrorMessage() {
    String errorMessage = MavenErrorMessages.checksumMismatchError(
        mavenPath.getPath(), 
        "abc123", 
        "def456", 
        Maven2Format.NAME);
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(mavenPath.getPath()));
    assertThat(errorMessage, containsString("abc123"));
    assertThat(errorMessage, containsString("def456"));
    assertThat(errorMessage, containsString(Maven2Format.NAME));
    assertThat(errorMessage, containsString("Checksum mismatch"));
  }

  @Test
  public void testVersionPolicyViolationErrorMessage() {
    String errorMessage = MavenErrorMessages.versionPolicyViolationError(
        "RELEASE", 
        mavenPath.getPath(), 
        REPO_NAME);
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString("RELEASE"));
    assertThat(errorMessage, containsString(mavenPath.getPath()));
    assertThat(errorMessage, containsString(REPO_NAME));
    assertThat(errorMessage, containsString("Version policy violation"));
  }

  @Test
  public void testDuplicateArtifactErrorMessage() {
    String errorMessage = MavenErrorMessages.duplicateArtifactError(mavenPath, REPO_NAME);
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(mavenPath.getPath()));
    assertThat(errorMessage, containsString(REPO_NAME));
    assertThat(errorMessage, containsString(GROUP_ID));
    assertThat(errorMessage, containsString(ARTIFACT_ID));
    assertThat(errorMessage, containsString(VERSION));
    assertThat(errorMessage, containsString("Duplicate artifact"));
  }

  @Test
  public void testInvalidChecksumErrorMessage() {
    String errorMessage = MavenErrorMessages.invalidChecksumError(
        mavenPath.getPath(), 
        "SHA-1", 
        "invalid-checksum-format");
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(mavenPath.getPath()));
    assertThat(errorMessage, containsString("SHA-1"));
    assertThat(errorMessage, containsString("invalid-checksum-format"));
    assertThat(errorMessage, containsString("Invalid checksum format"));
  }

  @Test
  public void testStorageErrorMessage() {
    String errorMessage = MavenErrorMessages.storageError(mavenPath.getPath(), "Disk full");
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(mavenPath.getPath()));
    assertThat(errorMessage, containsString("Disk full"));
    assertThat(errorMessage, containsString("Storage error"));
  }

  @Test
  public void testPermissionDeniedErrorMessage() {
    String errorMessage = MavenErrorMessages.permissionDeniedError(REPO_NAME, "user123", "write");
    
    assertThat(errorMessage, notNullValue());
    assertThat(errorMessage, containsString(REPO_NAME));
    assertThat(errorMessage, containsString("user123"));
    assertThat(errorMessage, containsString("write"));
    assertThat(errorMessage, containsString("Permission denied"));
  }
}