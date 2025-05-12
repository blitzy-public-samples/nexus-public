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
package org.sonatype.nexus.repository.maven.internal.validation;

import java.io.InputStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.InvalidContentException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.apache.commons.io.IOUtils.toInputStream;

/**
 * Tests for {@link MavenMetadataContentValidator} which validates Maven metadata XML content
 * against the repository path to ensure consistency between the path and the metadata content.
 */
public class MavenMetadataContentValidatorTest
    extends TestSupport
{
  // Test paths for validation scenarios
  private static final String VALID_PATH = "group/artifact/maven-metadata.xml";

  private static final String VALID_PATH_GROUP_ONLY = "group/maven-metadata.xml";

  private static final String VALID_SNAPSHOT_PATH = "group/artifact/1.0-SNAPSHOT/maven-metadata.xml";

  private static final String INVALID_PATH = "differentGroup/differentArtifact/maven-metadata.xml";

  private MavenMetadataContentValidator underTest;

  @BeforeEach
  public void setup() throws Exception {
    underTest = new MavenMetadataContentValidator();
  }

  /**
   * Verifies that an empty metadata file is rejected with an appropriate exception.
   */
  @Test
  @DisplayName("Empty metadata should be rejected")
  public void throwInvalidContentWhenMetadataEmpty() {
    InputStream mavenMetadata = toInputStream("");

    assertThrows(InvalidContentException.class, () -> {
      underTest.validate(VALID_PATH, mavenMetadata);
    });
  }

  /**
   * Verifies that non-XML content is rejected with an appropriate exception.
   */
  @Test
  @DisplayName("Non-XML content should be rejected")
  public void throwInvalidContentWhenMetadataNotMetadata() {
    InputStream mavenMetadata = toInputStream("This is not metadata");

    assertThrows(InvalidContentException.class, () -> {
      underTest.validate(VALID_PATH, mavenMetadata);
    });
  }

  /**
   * Verifies that metadata with group/artifact that doesn't match the repository path is rejected.
   */
  @Test
  @DisplayName("Metadata with mismatched path should be rejected")
  public void throwInvalidContentWhenMetadataDoesNotMatchPath() {
    InputStream mavenMetadata = toInputStream("<metadata>\n" +
        "  <groupId>group</groupId>\n" +
        "  <artifactId>artifact</artifactId>\n" +
        "</metadata>\n");

    assertThrows(InvalidContentException.class, () -> {
      underTest.validate(INVALID_PATH, mavenMetadata);
    });
  }

  /**
   * Verifies that metadata without a groupId element is not validated against the path.
   */
  @Test
  @DisplayName("Metadata without groupId should not be validated")
  public void doNotValidateWhenGroupNotFound() {
    InputStream mavenMetadata = toInputStream("<metadata>\n" +
        "  <artifactId>artifact</artifactId>\n" +
        "</metadata>\n");

    underTest.validate(INVALID_PATH, mavenMetadata);
  }

  /**
   * Verifies that metadata with an empty groupId element is not validated against the path.
   */
  @Test
  @DisplayName("Metadata with empty groupId should not be validated")
  public void doNotValidateWhenGroupEmpty() {
    InputStream mavenMetadata = toInputStream("<metadata>\n" +
        "  <groupId></groupId>\n" +
        "  <artifactId>artifact</artifactId>\n" +
        "</metadata>\n");

    underTest.validate(INVALID_PATH, mavenMetadata);
  }

  /**
   * Verifies that metadata without an artifactId element is rejected when the path includes an artifact.
   */
  @Test
  @DisplayName("Metadata without artifactId should be rejected when path includes artifact")
  public void throwInvalidContentWhenArtifactNotFound() {
    InputStream mavenMetadata = toInputStream("<metadata>\n" +
        "  <groupId>group</groupId>\n" +
        "</metadata>\n");

    assertThrows(InvalidContentException.class, () -> {
      underTest.validate(VALID_PATH, mavenMetadata);
    });
  }
  
  /**
   * Verifies that metadata with an empty artifactId element is rejected when the path includes an artifact.
   */
  @Test
  @DisplayName("Metadata with empty artifactId should be rejected when path includes artifact")
  public void throwInvalidContentWhenArtifactEmpty() {
    InputStream mavenMetadata = toInputStream("<metadata>\n" +
        "  <groupId>group</groupId>\n" +
        "  <artifactId></artifactId>\n" +
        "</metadata>\n");

    assertThrows(InvalidContentException.class, () -> {
      underTest.validate(VALID_PATH, mavenMetadata);
    });
  }

  /**
   * Verifies that valid metadata with matching group/artifact is accepted.
   */
  @Test
  @DisplayName("Valid metadata with matching path should be accepted")
  public void noExceptionWhenValidContentAndMatchesPath() {
    InputStream mavenMetadata = toInputStream("<metadata>\n" +
        "  <groupId>group</groupId>\n" +
        "  <artifactId>artifact</artifactId>\n" +
        "</metadata>\n");

    underTest.validate(VALID_PATH, mavenMetadata);
  }

  /**
   * Verifies that valid snapshot metadata with matching group/artifact/version is accepted.
   */
  @Test
  @DisplayName("Valid snapshot metadata with matching path should be accepted")
  public void noExceptionWhenValidContentAndMatchesPathForSnapshotMetadata() {
    InputStream mavenMetadata = toInputStream("<metadata>\n" +
        "  <groupId>group</groupId>\n" +
        "  <artifactId>artifact</artifactId>\n" +
        "  <version>1.0-SNAPSHOT</version>\n" +
        "</metadata>\n");

    underTest.validate(VALID_SNAPSHOT_PATH, mavenMetadata);
  }

  /**
   * Verifies that valid metadata with a released version is accepted.
   */
  @Test
  @DisplayName("Valid metadata with released version should be accepted")
  public void noExceptionWhenValidContentAndMatchesPathWithReleasedVersion() {
    InputStream mavenMetadata = toInputStream("<metadata>\n" +
        "  <groupId>group</groupId>\n" +
        "  <artifactId>artifact</artifactId>\n" +
        "  <version>1.0</version>\n" +
        "</metadata>\n");

    underTest.validate(VALID_PATH, mavenMetadata);
  }

  /**
   * Verifies that valid group-level metadata is accepted when the path only includes a group.
   */
  @Test
  @DisplayName("Valid group-level metadata with matching path should be accepted")
  public void noExceptionWhenGroupOnlyWithCorrectPath() {
    InputStream mavenMetadata = toInputStream("<metadata>\n" +
        "  <groupId>group</groupId>\n" +
        "</metadata>\n");

    underTest.validate(VALID_PATH_GROUP_ONLY, mavenMetadata);
  }
}
