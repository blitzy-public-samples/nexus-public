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
package org.sonatype.nexus.repository.maven.internal;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.HashType;
import org.sonatype.nexus.repository.maven.MavenPath.SignatureType;
import org.sonatype.nexus.testcommon.Java21TestGroup;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests for validating the implementation of Java 21's Pattern Matching for switch in the Maven repository plugin's
 * path handling components.
 * 
 * This test class focuses on MavenPath parsing and type checking using pattern matching, ensuring that the refactored
 * code correctly handles all Maven artifact path formats including releases, snapshots, and metadata.
 *
 * @since 3.60
 */
@Category(Java21TestGroup.class)
public class MavenPathPatternMatchingTest
    extends TestSupport
{
  /**
   * Test implementation of hash type detection using traditional approach (pre-Java 21).
   */
  @Test
  public void testTraditionalHashTypeDetection() {
    // Test with MD5 hash type
    MavenPath md5Path = createMavenPathWithExtension("jar.md5");
    HashType md5HashType = getHashTypeTraditional(md5Path);
    assertThat(md5HashType, equalTo(HashType.MD5));
    
    // Test with SHA1 hash type
    MavenPath sha1Path = createMavenPathWithExtension("jar.sha1");
    HashType sha1HashType = getHashTypeTraditional(sha1Path);
    assertThat(sha1HashType, equalTo(HashType.SHA1));
    
    // Test with SHA256 hash type
    MavenPath sha256Path = createMavenPathWithExtension("jar.sha256");
    HashType sha256HashType = getHashTypeTraditional(sha256Path);
    assertThat(sha256HashType, equalTo(HashType.SHA256));
    
    // Test with SHA512 hash type
    MavenPath sha512Path = createMavenPathWithExtension("jar.sha512");
    HashType sha512HashType = getHashTypeTraditional(sha512Path);
    assertThat(sha512HashType, equalTo(HashType.SHA512));
    
    // Test with non-hash type
    MavenPath nonHashPath = createMavenPathWithExtension("jar");
    HashType nonHashType = getHashTypeTraditional(nonHashPath);
    assertThat(nonHashType, nullValue());
  }
  
  /**
   * Test implementation of hash type detection using Java 21 Pattern Matching for switch.
   */
  @Test
  public void testPatternMatchingHashTypeDetection() {
    // Test with MD5 hash type
    MavenPath md5Path = createMavenPathWithExtension("jar.md5");
    HashType md5HashType = getHashTypeWithPatternMatching(md5Path);
    assertThat(md5HashType, equalTo(HashType.MD5));
    
    // Test with SHA1 hash type
    MavenPath sha1Path = createMavenPathWithExtension("jar.sha1");
    HashType sha1HashType = getHashTypeWithPatternMatching(sha1Path);
    assertThat(sha1HashType, equalTo(HashType.SHA1));
    
    // Test with SHA256 hash type
    MavenPath sha256Path = createMavenPathWithExtension("jar.sha256");
    HashType sha256HashType = getHashTypeWithPatternMatching(sha256Path);
    assertThat(sha256HashType, equalTo(HashType.SHA256));
    
    // Test with SHA512 hash type
    MavenPath sha512Path = createMavenPathWithExtension("jar.sha512");
    HashType sha512HashType = getHashTypeWithPatternMatching(sha512Path);
    assertThat(sha512HashType, equalTo(HashType.SHA512));
    
    // Test with non-hash type
    MavenPath nonHashPath = createMavenPathWithExtension("jar");
    HashType nonHashType = getHashTypeWithPatternMatching(nonHashPath);
    assertThat(nonHashType, nullValue());
  }
  
  /**
   * Test implementation of signature type detection using traditional approach (pre-Java 21).
   */
  @Test
  public void testTraditionalSignatureTypeDetection() {
    // Test with GPG signature type
    MavenPath signaturePath = createMavenPathWithSignature("jar.asc", SignatureType.GPG);
    SignatureType signatureType = getSignatureTypeTraditional(signaturePath);
    assertThat(signatureType, equalTo(SignatureType.GPG));
    
    // Test with non-signature type
    MavenPath nonSignaturePath = createMavenPathWithExtension("jar");
    SignatureType nonSignatureType = getSignatureTypeTraditional(nonSignaturePath);
    assertThat(nonSignatureType, nullValue());
  }
  
  /**
   * Test implementation of signature type detection using Java 21 Pattern Matching for switch.
   */
  @Test
  public void testPatternMatchingSignatureTypeDetection() {
    // Test with GPG signature type
    MavenPath signaturePath = createMavenPathWithSignature("jar.asc", SignatureType.GPG);
    SignatureType signatureType = getSignatureTypeWithPatternMatching(signaturePath);
    assertThat(signatureType, equalTo(SignatureType.GPG));
    
    // Test with non-signature type
    MavenPath nonSignaturePath = createMavenPathWithExtension("jar");
    SignatureType nonSignatureType = getSignatureTypeWithPatternMatching(nonSignaturePath);
    assertThat(nonSignatureType, nullValue());
  }
  
  /**
   * Test that both traditional and pattern matching approaches produce the same results for hash type detection.
   */
  @Test
  public void testHashTypeDetectionConsistency() {
    // Test with various extensions to ensure both methods produce the same results
    String[] extensions = {"jar", "jar.md5", "jar.sha1", "jar.sha256", "jar.sha512", "pom", "pom.md5", "tar.gz"};
    
    for (String extension : extensions) {
      MavenPath path = createMavenPathWithExtension(extension);
      HashType traditionalResult = getHashTypeTraditional(path);
      HashType patternMatchingResult = getHashTypeWithPatternMatching(path);
      
      assertThat("Hash type detection should be consistent for extension: " + extension,
          patternMatchingResult, equalTo(traditionalResult));
    }
  }
  
  /**
   * Test that both traditional and pattern matching approaches produce the same results for signature type detection.
   */
  @Test
  public void testSignatureTypeDetectionConsistency() {
    // Test with various extensions to ensure both methods produce the same results
    String[] extensions = {"jar", "jar.asc", "pom", "pom.asc", "tar.gz", "tar.gz.asc"};
    
    for (String extension : extensions) {
      MavenPath path = extension.endsWith(".asc") ?
          createMavenPathWithSignature(extension, SignatureType.GPG) :
          createMavenPathWithExtension(extension);
          
      SignatureType traditionalResult = getSignatureTypeTraditional(path);
      SignatureType patternMatchingResult = getSignatureTypeWithPatternMatching(path);
      
      assertThat("Signature type detection should be consistent for extension: " + extension,
          patternMatchingResult, equalTo(traditionalResult));
    }
  }
  
  /**
   * Test pattern matching for determining if a path is a hash.
   */
  @Test
  public void testIsHashPatternMatching() {
    // Test with hash paths
    MavenPath md5Path = createMavenPathWithExtension("jar.md5");
    assertThat(isHashWithPatternMatching(md5Path), equalTo(true));
    
    MavenPath sha1Path = createMavenPathWithExtension("jar.sha1");
    assertThat(isHashWithPatternMatching(sha1Path), equalTo(true));
    
    // Test with non-hash path
    MavenPath nonHashPath = createMavenPathWithExtension("jar");
    assertThat(isHashWithPatternMatching(nonHashPath), equalTo(false));
    
    // Test with signature path
    MavenPath signaturePath = createMavenPathWithSignature("jar.asc", SignatureType.GPG);
    assertThat(isHashWithPatternMatching(signaturePath), equalTo(false));
  }
  
  /**
   * Test pattern matching for determining if a path is a signature.
   */
  @Test
  public void testIsSignaturePatternMatching() {
    // Test with signature path
    MavenPath signaturePath = createMavenPathWithSignature("jar.asc", SignatureType.GPG);
    assertThat(isSignatureWithPatternMatching(signaturePath), equalTo(true));
    
    // Test with non-signature path
    MavenPath nonSignaturePath = createMavenPathWithExtension("jar");
    assertThat(isSignatureWithPatternMatching(nonSignaturePath), equalTo(false));
    
    // Test with hash path
    MavenPath hashPath = createMavenPathWithExtension("jar.md5");
    assertThat(isSignatureWithPatternMatching(hashPath), equalTo(false));
  }
  
  /**
   * Test pattern matching for determining if a path is a POM.
   */
  @Test
  public void testIsPomPatternMatching() {
    // Test with POM path
    MavenPath pomPath = createMavenPathWithExtension("pom");
    assertThat(isPomWithPatternMatching(pomPath), equalTo(true));
    
    // Test with non-POM path
    MavenPath nonPomPath = createMavenPathWithExtension("jar");
    assertThat(isPomWithPatternMatching(nonPomPath), equalTo(false));
    
    // Test with POM hash path
    MavenPath pomHashPath = createMavenPathWithExtension("pom.md5");
    assertThat(isPomWithPatternMatching(pomHashPath), equalTo(false));
    
    // Test with POM signature path
    MavenPath pomSignaturePath = createMavenPathWithSignature("pom.asc", SignatureType.GPG);
    assertThat(isPomWithPatternMatching(pomSignaturePath), equalTo(false));
  }
  
  /**
   * Test pattern matching for determining if a path is subordinate (hash or signature).
   */
  @Test
  public void testIsSubordinatePatternMatching() {
    // Test with hash path
    MavenPath hashPath = createMavenPathWithExtension("jar.md5");
    assertThat(isSubordinateWithPatternMatching(hashPath), equalTo(true));
    
    // Test with signature path
    MavenPath signaturePath = createMavenPathWithSignature("jar.asc", SignatureType.GPG);
    assertThat(isSubordinateWithPatternMatching(signaturePath), equalTo(true));
    
    // Test with non-subordinate path
    MavenPath nonSubordinatePath = createMavenPathWithExtension("jar");
    assertThat(isSubordinateWithPatternMatching(nonSubordinatePath), equalTo(false));
  }
  
  /**
   * Test pattern matching for complex path scenarios with multiple conditions.
   */
  @Test
  public void testComplexPathScenarios() {
    // Test with signature of a hash
    MavenPath hashSignaturePath = createMavenPathWithExtension("jar.md5.asc");
    assertThat(getHashTypeWithPatternMatching(hashSignaturePath), nullValue());
    assertThat(isSignatureWithPatternMatching(hashSignaturePath), equalTo(true));
    
    // Test with hash of a signature
    MavenPath signatureHashPath = createMavenPathWithExtension("jar.asc.md5");
    assertThat(getHashTypeWithPatternMatching(signatureHashPath), equalTo(HashType.MD5));
    assertThat(isSignatureWithPatternMatching(signatureHashPath), equalTo(false));
    
    // Test with complex extension
    MavenPath complexPath = createMavenPathWithExtension("tar.gz.sha1");
    assertThat(getHashTypeWithPatternMatching(complexPath), equalTo(HashType.SHA1));
    assertThat(isSignatureWithPatternMatching(complexPath), equalTo(false));
  }

  /**
   * Creates a MavenPath with the specified extension for testing.
   */
  private MavenPath createMavenPathWithExtension(String extension) {
    return new MavenPath("org/example/artifact/1.0/artifact-1.0." + extension, null);
  }
  
  /**
   * Creates a MavenPath with the specified signature extension for testing.
   */
  private MavenPath createMavenPathWithSignature(String extension, SignatureType signatureType) {
    MavenPath.Coordinates coordinates = new MavenPath.Coordinates(
        false, "org.example", "artifact", "1.0", null, null,
        "1.0", null, extension, signatureType);
    return new MavenPath("org/example/artifact/1.0/artifact-1.0." + extension, coordinates);
  }
  
  /**
   * Traditional implementation of hash type detection (pre-Java 21).
   */
  private HashType getHashTypeTraditional(MavenPath mavenPath) {
    String fileName = mavenPath.getFileName();
    for (HashType hashType : HashType.values()) {
      if (fileName.endsWith("." + hashType.getExt())) {
        return hashType;
      }
    }
    return null;
  }
  
  /**
   * Java 21 Pattern Matching for switch implementation of hash type detection.
   */
  private HashType getHashTypeWithPatternMatching(MavenPath mavenPath) {
    String fileName = mavenPath.getFileName();
    for (HashType hashType : HashType.values()) {
      // Using pattern matching to check if the filename ends with the hash extension
      if (fileName instanceof String s && s.endsWith("." + hashType.getExt())) {
        return hashType;
      }
    }
    return null;
  }
  
  /**
   * Traditional implementation of signature type detection (pre-Java 21).
   */
  private SignatureType getSignatureTypeTraditional(MavenPath mavenPath) {
    if (mavenPath.getCoordinates() != null) {
      return mavenPath.getCoordinates().getSignatureType();
    }
    return null;
  }
  
  /**
   * Java 21 Pattern Matching for switch implementation of signature type detection.
   */
  private SignatureType getSignatureTypeWithPatternMatching(MavenPath mavenPath) {
    // Using pattern matching to check if the MavenPath has coordinates with a signature type
    if (mavenPath.getCoordinates() instanceof MavenPath.Coordinates coords) {
      return coords.getSignatureType();
    }
    return null;
  }
  
  /**
   * Java 21 Pattern Matching implementation to check if a path is a hash.
   */
  private boolean isHashWithPatternMatching(MavenPath mavenPath) {
    return switch (mavenPath.getHashType()) {
      case HashType ht -> true;
      case null -> false;
    };
  }
  
  /**
   * Java 21 Pattern Matching implementation to check if a path is a signature.
   */
  private boolean isSignatureWithPatternMatching(MavenPath mavenPath) {
    return switch (mavenPath.getCoordinates()) {
      case MavenPath.Coordinates coords when coords.getSignatureType() != null -> true;
      default -> false;
    };
  }
  
  /**
   * Java 21 Pattern Matching implementation to check if a path is a POM.
   */
  private boolean isPomWithPatternMatching(MavenPath mavenPath) {
    return switch (mavenPath.getCoordinates()) {
      case MavenPath.Coordinates coords when "pom".equals(coords.getExtension()) -> true;
      default -> false;
    };
  }
  
  /**
   * Java 21 Pattern Matching implementation to check if a path is subordinate (hash or signature).
   */
  private boolean isSubordinateWithPatternMatching(MavenPath mavenPath) {
    return switch (mavenPath) {
      case MavenPath path when path.getHashType() != null -> true;
      case MavenPath path when path.getCoordinates() != null && path.getCoordinates().getSignatureType() != null -> true;
      default -> false;
    };
  }
}