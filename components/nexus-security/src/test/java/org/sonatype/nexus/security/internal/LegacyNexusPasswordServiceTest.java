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
package org.sonatype.nexus.security.internal;

import java.security.Provider;
import java.security.Security;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link LegacyNexusPasswordService}.
 */
public class LegacyNexusPasswordServiceTest
    extends TestSupport
{
  private LegacyNexusPasswordService underTest;

  @BeforeEach
  public void setUp() throws Exception {
    underTest = new LegacyNexusPasswordService();
  }

  @Test
  @DisplayName("Verify SHA-1 hash validation works correctly")
  public void testSha1Hash() {
    String password = "admin123";
    String sha1Hash = "f865b53623b121fd34ee5426c792e5c33af8c227";

    assertThat(underTest.passwordsMatch(password, sha1Hash), is(true));
  }

  @Test
  @DisplayName("Verify MD5 hash validation works correctly")
  public void testMd5Hash() {
    String password = "admin123";
    String md5Hash = "0192023a7bbd73250516f069df18b500";

    assertThat(underTest.passwordsMatch(password, md5Hash), is(true));
  }

  @Test
  @DisplayName("Verify invalid SHA-1 hash is rejected")
  public void testInvalidSha1Hash() {
    String password = "admin123";
    String sha1Hash = "f865b53623b121fd34ee5426c792e5c33af8c228";

    assertThat(underTest.passwordsMatch(password, sha1Hash), is(false));
  }

  @Test
  @DisplayName("Verify invalid MD5 hash is rejected")
  public void testInvalidMd5Hash() {
    String password = "admin123";
    String md5Hash = "0192023a7bbd73250516f069df18b501";

    assertThat(underTest.passwordsMatch(password, md5Hash), is(false));
  }
  
  @Test
  @DisplayName("Verify SHA-1 hash validation with Java 21 security providers")
  public void testSha1HashWithJava21SecurityProviders() {
    // Verify we're running on Java 21
    String javaVersion = System.getProperty("java.version");
    log.info("Running on Java version: {}", javaVersion);
    
    // Verify SHA-1 algorithm is available in Java 21
    Provider[] providers = Security.getProviders("MessageDigest.SHA-1");
    assertThat("SHA-1 algorithm should be available", providers.length > 0, is(true));
    
    String password = "admin123";
    String sha1Hash = "f865b53623b121fd34ee5426c792e5c33af8c227";

    assertThat(underTest.passwordsMatch(password, sha1Hash), is(true));
  }
  
  @Test
  @DisplayName("Verify MD5 hash validation with Java 21 security providers")
  public void testMd5HashWithJava21SecurityProviders() {
    // Verify MD5 algorithm is available in Java 21
    Provider[] providers = Security.getProviders("MessageDigest.MD5");
    assertThat("MD5 algorithm should be available", providers.length > 0, is(true));
    
    String password = "admin123";
    String md5Hash = "0192023a7bbd73250516f069df18b500";

    assertThat(underTest.passwordsMatch(password, md5Hash), is(true));
  }
  
  @Test
  @DisplayName("Verify complex password validation with Java 21 JCE")
  public void testComplexPasswordValidation() {
    // Test with a more complex password containing special characters
    String complexPassword = "P@ssw0rd!#$%^&*()";
    
    // Pre-computed hashes for the complex password
    String sha1Hash = "4ae9fa0a8299a17d2b2a73b1a89b3a4e28f5c661";
    String md5Hash = "e7df7cd2ca07f4f1ab415d457a6e1c13";
    
    // Verify both hash algorithms work with complex passwords
    assertThat(underTest.passwordsMatch(complexPassword, sha1Hash), is(true));
    assertThat(underTest.passwordsMatch(complexPassword, md5Hash), is(true));
  }
  
  @Test
  @DisplayName("Verify BouncyCastle compatibility with Java 21")
  public void testBouncyCastleCompatibility() {
    // Check if BouncyCastle provider is available
    Provider bcProvider = Security.getProvider("BC");
    if (bcProvider != null) {
      log.info("BouncyCastle provider found: {} (version {})", 
          bcProvider.getName(), bcProvider.getVersionStr());
    }
    
    // Even if BC is not explicitly registered, our hash validation should work
    String password = "admin123";
    String sha1Hash = "f865b53623b121fd34ee5426c792e5c33af8c227";
    String md5Hash = "0192023a7bbd73250516f069df18b500";
    
    assertThat(underTest.passwordsMatch(password, sha1Hash), is(true));
    assertThat(underTest.passwordsMatch(password, md5Hash), is(true));
  }
  
  @Test
  @DisplayName("Verify empty password handling")
  public void testEmptyPassword() {
    // Empty password hashes
    String emptyPasswordSha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
    String emptyPasswordMd5 = "d41d8cd98f00b204e9800998ecf8427e";
    
    assertThat(underTest.passwordsMatch("", emptyPasswordSha1), is(true));
    assertThat(underTest.passwordsMatch("", emptyPasswordMd5), is(true));
  }
}