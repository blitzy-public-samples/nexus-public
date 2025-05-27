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

import org.sonatype.nexus.repository.maven.MavenPath;

/**
 * Utility class for generating Maven repository error messages using Java 21 String Templates.
 * 
 * This class provides methods for creating consistent, informative error messages
 * for various Maven repository error scenarios using Java 21's String Templates feature.
 */
public final class MavenErrorMessages
{
  private MavenErrorMessages() {
    // Utility class, no instances
  }

  /**
   * Creates an error message for invalid Maven coordinates.
   *
   * @param groupId the group ID
   * @param artifactId the artifact ID
   * @param version the invalid version
   * @return formatted error message
   */
  public static String invalidCoordinatesError(String groupId, String artifactId, String version) {
    return STR."Invalid Maven coordinates: \{groupId}:\{artifactId}:\{version}. Please provide valid Maven coordinates.";
  }

  /**
   * Creates an error message for a missing artifact.
   *
   * @param repositoryName the repository name
   * @param mavenPath the Maven path of the missing artifact
   * @return formatted error message
   */
  public static String missingArtifactError(String repositoryName, MavenPath mavenPath) {
    MavenPath.Coordinates coords = mavenPath.getCoordinates();
    String coordsStr = coords != null 
        ? STR."\{coords.getGroupId()}:\{coords.getArtifactId()}:\{coords.getVersion()}:\{coords.getClassifier() != null ? coords.getClassifier() : ""}:\{coords.getExtension()}"
        : mavenPath.getPath();
    
    return STR."Artifact \{coordsStr} not found in repository '\{repositoryName}'.";
  }

  /**
   * Creates an error message for repository access errors.
   *
   * @param repositoryName the repository name
   * @param errorDetails details about the error
   * @return formatted error message
   */
  public static String repositoryAccessError(String repositoryName, String errorDetails) {
    return STR."Error accessing repository '\{repositoryName}': \{errorDetails}";
  }

  /**
   * Creates an error message for invalid Maven metadata.
   *
   * @param path the path to the metadata file
   * @param reason the reason the metadata is invalid
   * @return formatted error message
   */
  public static String invalidMetadataError(String path, String reason) {
    return STR."Invalid Maven metadata at \{path}: \{reason}";
  }

  /**
   * Creates an error message for checksum mismatches.
   *
   * @param path the path to the artifact
   * @param expected the expected checksum
   * @param actual the actual checksum
   * @param format the format name
   * @return formatted error message
   */
  public static String checksumMismatchError(String path, String expected, String actual, String format) {
    return STR."Checksum mismatch for \{format} artifact at \{path}. Expected: \{expected}, Actual: \{actual}";
  }

  /**
   * Creates an error message for version policy violations.
   *
   * @param policy the version policy (RELEASE, SNAPSHOT, MIXED)
   * @param path the path to the artifact
   * @param repositoryName the repository name
   * @return formatted error message
   */
  public static String versionPolicyViolationError(String policy, String path, String repositoryName) {
    return STR."Version policy violation: \{policy} policy in repository '\{repositoryName}' does not allow artifact at \{path}";
  }

  /**
   * Creates an error message for duplicate artifacts.
   *
   * @param mavenPath the Maven path of the duplicate artifact
   * @param repositoryName the repository name
   * @return formatted error message
   */
  public static String duplicateArtifactError(MavenPath mavenPath, String repositoryName) {
    MavenPath.Coordinates coords = mavenPath.getCoordinates();
    String artifactInfo = coords != null 
        ? STR."\{coords.getGroupId()}:\{coords.getArtifactId()}:\{coords.getVersion()}"
        : mavenPath.getPath();
    
    return STR."Duplicate artifact \{artifactInfo} found in repository '\{repositoryName}'. Path: \{mavenPath.getPath()}";
  }

  /**
   * Creates an error message for invalid checksum formats.
   *
   * @param path the path to the checksum file
   * @param checksumType the type of checksum (MD5, SHA-1, etc.)
   * @param invalidValue the invalid checksum value
   * @return formatted error message
   */
  public static String invalidChecksumError(String path, String checksumType, String invalidValue) {
    return STR."Invalid checksum format for \{checksumType} at \{path}: '\{invalidValue}'";
  }

  /**
   * Creates an error message for storage errors.
   *
   * @param path the path where the storage error occurred
   * @param reason the reason for the storage error
   * @return formatted error message
   */
  public static String storageError(String path, String reason) {
    return STR."Storage error while processing \{path}: \{reason}";
  }

  /**
   * Creates an error message for permission denied errors.
   *
   * @param repositoryName the repository name
   * @param user the user who was denied permission
   * @param action the action that was attempted (read, write, etc.)
   * @return formatted error message
   */
  public static String permissionDeniedError(String repositoryName, String user, String action) {
    return STR."Permission denied: User '\{user}' does not have '\{action}' permission for repository '\{repositoryName}'";
  }
}