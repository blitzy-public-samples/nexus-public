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
package org.sonatype.nexus.repository.maven;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.sonatype.nexus.common.hash.HashAlgorithm;

import com.google.common.collect.ImmutableList;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Maven repository path. Every item in repository may have hashes, stored on paths with proper suffixes, and artifact
 * paths have non-null coordinates.
 *
 * @since 3.0
 * @since Java 21 Updated to use Java 21 features like record patterns and pattern matching for instanceof
 */
@Immutable
public class MavenPath
{
  public enum HashType
  {
    SHA1("sha1", HashAlgorithm.SHA1),
    SHA256("sha256", HashAlgorithm.SHA256),
    SHA512("sha512", HashAlgorithm.SHA512),
    MD5("md5", HashAlgorithm.MD5);

    /**
     * {@link HashAlgorithm}s corresponding to {@link HashType}s.
     */
    public static final List<HashAlgorithm> ALGORITHMS = ImmutableList
        .of(SHA1.getHashAlgorithm(), MD5.getHashAlgorithm(), SHA256.getHashAlgorithm(), SHA512.getHashAlgorithm());

    private final String ext;

    private final HashAlgorithm hashAlgorithm;

    HashType(final String ext, final HashAlgorithm hashAlgorithm) {
      this.ext = ext;
      this.hashAlgorithm = hashAlgorithm;
    }

    public String getExt() {
      return ext;
    }

    public HashAlgorithm getHashAlgorithm() {
      return hashAlgorithm;
    }
  }

  public enum SignatureType
  {
    GPG("asc");

    private final String ext;

    SignatureType(final String ext) {
      this.ext = ext;
    }

    public String getExt() {
      return ext;
    }
  }

  /**
   * Maven coordinates for a path.
   * 
   * @since Java 21 Implemented as a record for improved immutability and conciseness
   */
  public record Coordinates(
      boolean snapshot,
      @Nonnull String groupId,
      @Nonnull String artifactId,
      @Nonnull String version,
      @Nullable Long timestamp,
      @Nullable Integer buildNumber,
      @Nonnull String baseVersion,
      @Nullable String classifier,
      @Nonnull String extension,
      @Nullable SignatureType signatureType)
  {
    /**
     * Constructor with validation for required fields.
     */
    public Coordinates {
      checkNotNull(groupId);
      checkNotNull(artifactId);
      checkNotNull(version);
      checkNotNull(baseVersion);
      checkNotNull(extension);
      
      // Ensure timestamp and buildNumber are only set for snapshots
      if (!snapshot) {
        timestamp = null;
        buildNumber = null;
      }
    }

    /**
     * @return true if this represents a snapshot version
     */
    public boolean isSnapshot() {
      return snapshot;
    }
  }

  private final String path;

  private final String fileName;

  private final HashType hashType;

  private final Coordinates coordinates;

  public MavenPath(final String path, final Coordinates coordinates)
  {
    checkNotNull(path);
    checkArgument(!path.startsWith("/"), "Path must not start with '/'");
    this.path = path;
    this.fileName = this.path.substring(path.lastIndexOf('/') + 1);
    HashType ht = null;
    for (HashType v : HashType.values()) {
      if (this.fileName.endsWith("." + v.getExt())) {
        ht = v;
        break;
      }
    }
    this.hashType = ht;
    this.coordinates = coordinates;
  }

  @Nonnull
  public String getPath() {
    return path;
  }

  @Nonnull
  public String getFileName() {
    return fileName;
  }

  /**
   * Returns hash type if this path points at Maven hash file, otherwise {@code null}.
   */
  @Nullable
  public HashType getHashType() {
    return hashType;
  }

  /**
   * Returns the Maven coordinates if this path is an artifact path, otherwise {@code null}.
   */
  @Nullable
  public Coordinates getCoordinates() {
    return coordinates;
  }

  /**
   * Returns {@code true} if this path is subordinate (is hash or signature) of another path.
   *
   * @see {@link #subordinateOf()}
   */
  public boolean isSubordinate() {
    return isHash() || isSignature();
  }

  /**
   * Returns {@code true} if this path represents a hash.
   */
  public boolean isHash() {
    return hashType != null;
  }

  /**
   * Returns {@code true} if this path represents a signature.
   */
  public boolean isSignature() {
    return coordinates != null && coordinates.signatureType() != null;
  }

  /**
   * Returns {@code true} if this path represents an artifact POM.
   */
  public boolean isPom() {
    return coordinates != null && "pom".equals(coordinates.extension());
  }

  /**
   * Returns the "main", non-subordinate path of this path. The "main" path is never a hash nor a signature.
   */
  @Nonnull
  public MavenPath main() {
    MavenPath mavenPath = this;
    while (mavenPath.isSubordinate()) {
      mavenPath = mavenPath.subordinateOf();
    }
    return mavenPath;
  }

  /**
   * Returns the "parent" path, that this path is subordinate of, or this instance if it is not a subordinate.
   */
  @Nonnull
  public MavenPath subordinateOf() {
    if (hashType != null) {
      int hashSuffixLen = hashType.getExt().length() + 1; // the dot
      Coordinates mainCoordinates = null;
      if (coordinates != null) {
        mainCoordinates = new Coordinates(
            coordinates.snapshot(),
            coordinates.groupId(),
            coordinates.artifactId(),
            coordinates.version(),
            coordinates.timestamp(),
            coordinates.buildNumber(),
            coordinates.baseVersion(),
            coordinates.classifier(),
            coordinates.extension().substring(0, coordinates.extension().length() - hashSuffixLen),
            coordinates.signatureType()
        );
      }
      return new MavenPath(
          path.substring(0, path.length() - hashSuffixLen),
          mainCoordinates
      );
    }
    else if (coordinates != null && coordinates.signatureType() != null) {
      int signatureSuffixLen = coordinates.signatureType().getExt().length() + 1; // the dot
      Coordinates mainCoordinates = new Coordinates(
          coordinates.snapshot(),
          coordinates.groupId(),
          coordinates.artifactId(),
          coordinates.version(),
          coordinates.timestamp(),
          coordinates.buildNumber(),
          coordinates.baseVersion(),
          coordinates.classifier(),
          coordinates.extension().substring(0, coordinates.extension().length() - signatureSuffixLen),
          null
      );
      return new MavenPath(
          path.substring(0, path.length() - signatureSuffixLen),
          mainCoordinates
      );
    }
    return this;
  }

  /**
   * Returns path of passed in hash type that is subordinate of this path. This path cannot be hash.
   */
  @Nonnull
  public MavenPath hash(final HashType hashType) {
    return hash(hashType.getExt());
  }

  /**
   * Returns path of passed in hash type that is subordinate of this path. This path cannot be hash.
   */
  @Nonnull
  public MavenPath hash(final HashAlgorithm hashType) {
    return hash(hashType.name());
  }

  /**
   * Returns path of passed in hash type that is subordinate of this path. This path cannot be hash.
   */
  @Nonnull
  private MavenPath hash(final String hashExtension) {
    checkNotNull(hashExtension);
    checkArgument(hashType == null, "This path is already a hash: %s", this);
    Coordinates hashCoordinates = null;
    if (coordinates != null) {
      hashCoordinates = new Coordinates(
          coordinates.snapshot(),
          coordinates.groupId(),
          coordinates.artifactId(),
          coordinates.version(),
          coordinates.timestamp(),
          coordinates.buildNumber(),
          coordinates.baseVersion(),
          coordinates.classifier(),
          coordinates.extension() + "." + hashExtension,
          coordinates.signatureType()
      );
    }
    return new MavenPath(
        path + "." + hashExtension,
        hashCoordinates
    );
  }

  /**
   * Returns path of passed in signature type that is subordinate of this path. This path cannot be hash nor signature.
   */
  @Nonnull
  public MavenPath signature(final SignatureType signatureType) {
    checkNotNull(signatureType);
    checkArgument(hashType == null, "This path is already a hash: %s", this);
    checkArgument(coordinates != null, "Only artifact paths may have signatures: %s", this);
    checkArgument(coordinates.signatureType() == null, "This path is already a signature: %s", this);
    Coordinates signatureCoordinates = new Coordinates(
        coordinates.snapshot(),
        coordinates.groupId(),
        coordinates.artifactId(),
        coordinates.version(),
        coordinates.timestamp(),
        coordinates.buildNumber(),
        coordinates.baseVersion(),
        coordinates.classifier(),
        coordinates.extension() + "." + signatureType.getExt(),
        signatureType
    );
    return new MavenPath(
        path + "." + signatureType.getExt(),
        signatureCoordinates
    );
  }

  /**
   * Returns path pointing to given extension and optional classifier within this same GAV. Only usable for artifact
   * paths, those having non-null {@link #getCoordinates()}.
   */
  @Nonnull
  public MavenPath locate(final String extension, @Nullable final String classifier) {
    checkNotNull(extension);
    checkArgument(coordinates != null, "Only artifact paths may locate: %s", this);

    MavenPath origin = main();
    Coordinates newCoordinates = new Coordinates(
        origin.coordinates.snapshot(),
        origin.coordinates.groupId(),
        origin.coordinates.artifactId(),
        origin.coordinates.version(),
        origin.coordinates.timestamp(),
        origin.coordinates.buildNumber(),
        origin.coordinates.baseVersion(),
        classifier,
        extension,
        null
    );
    // strip ".ext"
    String newPath = origin.path.substring(0, origin.path.length() - origin.coordinates.extension().length() - 1);
    if (origin.coordinates.classifier() != null) {
      // strip "-classifier"
      newPath = newPath.substring(0, newPath.length() - origin.coordinates.classifier().length() - 1);
    }
    if (classifier != null) {
      newPath += "-" + classifier;
    }
    newPath += "." + extension;
    return new MavenPath(
        newPath,
        newCoordinates
    );
  }

  /**
   * Returns path pointing to POM within this same GAV. Only usable for artifact paths, those having non-null {@link
   * #getCoordinates()}.
   */
  @Nonnull
  public MavenPath locatePom() {
    return locate("pom", null);
  }

  /**
   * Returns path pointing to non-classifier artifact within this same GAV. Only usable for artifact paths, those having
   * non-null {@link #getCoordinates()}.
   */
  @Nonnull
  public MavenPath locateMainArtifact(final String extension) {
    return locate(extension, null);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    // Using pattern matching for instanceof (Java 21 feature)
    return o instanceof MavenPath that && path.equals(that.path);
  }

  @Override
  public int hashCode() {
    return path.hashCode();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "path='" + path + '\'' +
        ", hashType=" + hashType +
        '}';
  }
}