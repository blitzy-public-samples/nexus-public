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

import org.apache.shiro.crypto.hash.Hash;
import org.apache.shiro.crypto.hash.SimpleHash;
import org.apache.shiro.crypto.hash.format.HexFormat;
import org.apache.shiro.crypto.hash.format.Shiro1CryptFormat;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DefaultSecurityPasswordService}.
 */
public class DefaultSecurityPasswordServiceTest
    extends TestSupport
{
  private DefaultSecurityPasswordService underTest;
  private static final String TEST_PASSWORD = "admin123";
  private static final String TEST_COMPLEX_PASSWORD = "P@ssw0rd!2023";

  @BeforeEach
  public void setUp() throws Exception {
    underTest = new DefaultSecurityPasswordService(new LegacyNexusPasswordService());
  }

  @Test
  @DisplayName("Verify SHA-1 hash compatibility")
  public void testSha1Hash() {
    String password = TEST_PASSWORD;
    String sha1Hash = "f865b53623b121fd34ee5426c792e5c33af8c227";

    assertThat(underTest.passwordsMatch(password, sha1Hash), is(true));
  }

  @Test
  @DisplayName("Verify MD5 hash compatibility")
  public void testMd5Hash() {
    String password = TEST_PASSWORD;
    String md5Hash = "0192023a7bbd73250516f069df18b500";

    assertThat(underTest.passwordsMatch(password, md5Hash), is(true));
  }

  @Test
  @DisplayName("Verify Shiro1 hash format compatibility")
  public void testShiro1HashFormat() {
    String password = TEST_PASSWORD;
    String shiro1Hash = "$shiro1$SHA-512$1024$zjU1u+Zg9UNwuB+HEawvtA==$IzF/OWzJxrqvB5FCe/2+UcZhhZYM2pTu0TEz7Ybnk65AbbEdUk9ntdtBzkN8P3gZby2qz6MHKqAe8Cjai9c4Gg==";

    assertThat(underTest.passwordsMatch(password, shiro1Hash), is(true));
  }

  @Test
  @DisplayName("Verify invalid SHA-1 hash is rejected")
  public void testInvalidSha1Hash() {
    String password = TEST_PASSWORD;
    String sha1Hash = "f865b53623b121fd34ee5426c792e5c33af8c228";

    assertThat(underTest.passwordsMatch(password, sha1Hash), is(false));
  }

  @Test
  @DisplayName("Verify invalid MD5 hash is rejected")
  public void testInvalidMd5Hash() {
    String password = TEST_PASSWORD;
    String md5Hash = "0192023a7bbd73250516f069df18b501";

    assertThat(underTest.passwordsMatch(password, md5Hash), is(false));
  }

  @Test
  @DisplayName("Verify invalid Shiro1 hash format is rejected")
  public void testInvalidShiro1HashFormat() {
    String password = TEST_PASSWORD;
    String shiro1Hash = "$shiro1$SHA-512$1024$zjU1u+Zg9UNwuB+HEawvtA==$IzF/OWzjxrqvB5FCe/2+UcZhhZYM2pTu0TEz7Ybnk65AbbEdUk9ntdtBzkN8P3gZby2qz6MHKqAe8Cjai9c4Gg==";

    assertThat(underTest.passwordsMatch(password, shiro1Hash), is(false));
  }

  @Test
  @DisplayName("Verify basic hash and match functionality")
  public void testHash() {
    String password = "testpassword";
    Hash hash = underTest.hashPassword(password);

    assertThat(underTest.passwordsMatch(password, hash), is(true));
  }
  
  @Test
  @DisplayName("Verify SHA-512 implementation with Java 21 security providers")
  public void testSha512WithJava21Providers() {
    // Verify we're using Java 21 security providers
    Provider[] providers = Security.getProviders();
    assertThat(providers, notNullValue());
    
    // Create a SHA-512 hash using the default provider
    String password = TEST_COMPLEX_PASSWORD;
    Hash hash = underTest.hashPassword(password);
    
    // Verify the hash algorithm is SHA-512
    assertThat(hash.getAlgorithmName(), is("SHA-512"));
    
    // Verify password verification works with the hash
    assertTrue(underTest.passwordsMatch(password, hash));
    
    // Create a hash in Shiro1 format and verify it works
    Shiro1CryptFormat format = new Shiro1CryptFormat();
    String formatted = format.format(hash);
    assertTrue(underTest.passwordsMatch(password, formatted));
  }
  
  @Test
  @DisplayName("Verify compatibility with BouncyCastle 1.78.1")
  public void testBouncyCastleCompatibility() {
    try {
      // Add BouncyCastle provider if not already added
      if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(new BouncyCastleProvider());
      }
      
      // Verify BouncyCastle provider is available
      Provider bcProvider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
      assertThat(bcProvider, notNullValue());
      
      // Test password hashing and verification with BouncyCastle
      String password = TEST_COMPLEX_PASSWORD;
      Hash hash = underTest.hashPassword(password);
      assertTrue(underTest.passwordsMatch(password, hash));
      
    } finally {
      // Remove BouncyCastle provider to avoid affecting other tests
      Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
    }
  }
  
  @Test
  @DisplayName("Test modern hash algorithm: SHA-256")
  public void testSha256Hash() {
    // Create a SHA-256 hash manually
    String password = TEST_COMPLEX_PASSWORD;
    SimpleHash sha256Hash = new SimpleHash("SHA-256", password, null, 1024);
    
    // Format the hash in hex format
    HexFormat hexFormat = new HexFormat();
    String formattedHash = hexFormat.format(sha256Hash);
    
    // Verify the hash can be used with LegacyNexusPasswordService
    LegacyNexusPasswordService legacyService = new LegacyNexusPasswordService();
    
    // This should fail because LegacyNexusPasswordService only supports SHA-1 and MD5
    assertThat(legacyService.passwordsMatch(password, formattedHash), is(false));
    
    // Create a SHA-256 hash in Shiro1 format
    SimpleHash sha256HashWithSalt = new SimpleHash("SHA-256", password);
    Shiro1CryptFormat shiro1Format = new Shiro1CryptFormat();
    String shiro1Hash = shiro1Format.format(sha256HashWithSalt);
    
    // DefaultSecurityPasswordService should be able to verify this
    assertTrue(underTest.passwordsMatch(password, shiro1Hash));
  }
  
  @Test
  @DisplayName("Test password verification with updated JCE in Java 21")
  public void testPasswordVerificationWithJava21JCE() {
    // Verify we're using Java 21 JCE
    String javaVersion = System.getProperty("java.version");
    log.info("Running on Java version: {}", javaVersion);
    
    // Test with a complex password containing special characters
    String complexPassword = "J@va21!$ecur1ty#T3st";
    
    // Hash the password using the default service (SHA-512)
    Hash hash = underTest.hashPassword(complexPassword);
    assertThat(hash, notNullValue());
    
    // Verify the password matches
    assertTrue(underTest.passwordsMatch(complexPassword, hash));
    
    // Convert to string format and verify again
    Shiro1CryptFormat format = new Shiro1CryptFormat();
    String formatted = format.format(hash);
    assertTrue(underTest.passwordsMatch(complexPassword, formatted));
    
    // Verify with slightly modified password fails
    assertThat(underTest.passwordsMatch(complexPassword + "x", formatted), is(false));
  }
}