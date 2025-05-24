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

import org.sonatype.nexus.repository.InvalidContentException;
import org.sonatype.nexus.repository.maven.MavenPath;

/**
 * Formatter for Maven repository error messages using Java 21 String Templates.
 * 
 * This class demonstrates the use of Java 21's String Templates feature for formatting
 * error messages related to Maven repository operations.
 */
public class MavenErrorMessageFormatter
{
  /**
   * Formats an error message for invalid Maven coordinates.
   *
   * @param repositoryName the name of the repository
   * @param path the path with invalid coordinates
   * @param reason the reason for the invalid coordinates
   * @return the formatted error message
   */
  public String formatInvalidCoordinatesError(String repositoryName, String path, String reason) {
    return STR."Invalid Maven coordinates in repository '\{repositoryName}' at path '\{path}': \{reason}";
  }

  /**
   * Formats an error message for a missing artifact.
   *
   * @param repositoryName the name of the repository
   * @param mavenPath the Maven path of the missing artifact
   * @return the formatted error message
   */
  public String formatMissingArtifactError(String repositoryName, MavenPath mavenPath) {
    String groupId = mavenPath.getCoordinates() != null ? mavenPath.getCoordinates().getGroupId() : "unknown";
    String artifactId = mavenPath.getCoordinates() != null ? mavenPath.getCoordinates().getArtifactId() : "unknown";
    String version = mavenPath.getCoordinates() != null ? mavenPath.getCoordinates().getVersion() : "unknown";
    
    return STR."Artifact not found in repository '\{repositoryName}': \{groupId}:\{artifactId}:\{version} (path: \{mavenPath.getPath()})";
  }

  /**
   * Formats an error message for repository access errors.
   *
   * @param repositoryName the name of the repository
   * @param operation the operation that failed
   * @param reason the reason for the failure
   * @return the formatted error message
   */
  public String formatRepositoryAccessError(String repositoryName, String operation, String reason) {
    return STR."Failed to \{operation} from repository '\{repositoryName}': \{reason}";
  }

  /**
   * Formats an error message for invalid metadata content.
   *
   * @param path the path to the metadata file
   * @param reason the reason for the invalid metadata
   * @return the formatted error message
   */
  public String formatInvalidMetadataError(String path, String reason) {
    return STR."Invalid Maven metadata at path '\{path}': \{reason}";
  }

  /**
   * Formats an error message for version policy violations.
   *
   * @param repositoryName the name of the repository
   * @param mavenPath the Maven path of the artifact
   * @param policy the version policy that was violated
   * @return the formatted error message
   */
  public String formatVersionPolicyViolationError(String repositoryName, MavenPath mavenPath, String policy) {
    String version = mavenPath.getCoordinates() != null ? mavenPath.getCoordinates().getVersion() : "unknown";
    
    return STR."Version policy violation in repository '\{repositoryName}': \{policy} policy does not allow version \{version} (path: \{mavenPath.getPath()})";
  }

  /**
   * Formats an error message for checksum validation failures.
   *
   * @param repositoryName the name of the repository
   * @param mavenPath the Maven path of the artifact
   * @param expectedChecksum the expected checksum
   * @param actualChecksum the actual checksum
   * @return the formatted error message
   */
  public String formatChecksumValidationError(
      String repositoryName, MavenPath mavenPath, String expectedChecksum, String actualChecksum) {
    return STR."Checksum validation failed for artifact in repository '\{repositoryName}' at path '\{mavenPath.getPath()}': expected \{expectedChecksum}, actual \{actualChecksum}";
  }

  /**
   * Formats an error message for upload validation failures.
   *
   * @param repositoryName the name of the repository
   * @param path the path of the uploaded artifact
   * @param reason the reason for the validation failure
   * @return the formatted error message
   */
  public String formatUploadValidationError(String repositoryName, String path, String reason) {
    return STR."Upload validation failed for artifact in repository '\{repositoryName}' at path '\{path}': \{reason}";
  }

  /**
   * Formats an error message from an InvalidContentException.
   *
   * @param exception the InvalidContentException
   * @return the formatted error message
   */
  public String formatInvalidContentExceptionMessage(InvalidContentException exception) {
    return STR."Invalid content at path '\{exception.getPath()}': \{exception.getMessage()}";
  }

  /**
   * Formats a complex error message with multiple variables.
   *
   * @param repositoryName the name of the repository
   * @param mavenPath the Maven path of the artifact
   * @param operation the operation that failed
   * @param reason the reason for the failure
   * @param statusCode the HTTP status code
   * @return the formatted error message
   */
  public String formatComplexError(
      String repositoryName, MavenPath mavenPath, String operation, String reason, int statusCode) {
    return STR."""
        Error during \{operation} operation in repository '\{repositoryName}':
        - Path: \{mavenPath.getPath()}
        - Status code: \{statusCode}
        - Reason: \{reason}
        """;
  }
}