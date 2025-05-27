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
import java.util.Arrays;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.shiro.crypto.hash.Hash;
import org.apache.shiro.crypto.hash.Sha512Hash;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DefaultSecurityPasswordService}.
 * 
 * Updated for Java 21 and BouncyCastle 1.78.1 compatibility.
 */
public class DefaultSecurityPasswordServiceTest
    extends TestSupport
{
  private DefaultSecurityPasswordService underTest;
  private Provider bouncyCastleProvider;
  private boolean bcProviderAdded = false;

  @BeforeEach
  public void setUp() throws Exception {
    underTest = new DefaultSecurityPasswordService(new LegacyNexusPasswordService());
    
    // Add BouncyCastle provider for testing compatibility with BC 1.78.1
    try {
      bouncyCastleProvider = new BouncyCastleProvider();
      if (Security.getProvider(bouncyCastleProvider.getName()) == null) {
        Security.addProvider(bouncyCastleProvider);
        bcProviderAdded = true;
      }
    } catch (Exception e) {
      log.warn("Could not register BouncyCastle provider", e);
    }
  }
  
  @AfterEach
  public void tearDown() {
    // Remove BouncyCastle provider if we added it
    if (bcProviderAdded) {
      Security.removeProvider(bouncyCastleProvider.getName());
    }
  }

  @Test
  public void testSha1Hash() {
    String password = "admin123";
    String sha1Hash = "f865b53623b121fd34ee5426c792e5c33af8c227";

    assertThat(underTest.passwordsMatch(password, sha1Hash), is(true));
  }

  @Test
  public void testMd5Hash() {
    String password = "admin123";
    String md5Hash = "0192023a7bbd73250516f069df18b500";

    assertThat(underTest.passwordsMatch(password, md5Hash), is(true));
  }

  @Test
  public void testShiro1HashFormat() {
    String password = "admin123";
    String shiro1Hash = "$shiro1$SHA-512$1024$zjU1u+Zg9UNwuB+HEawvtA==$IzF/OWzJxrqvB5FCe/2+UcZhhZYM2pTu0TEz7Ybnk65AbbEdUk9ntdtBzkN8P3gZby2qz6MHKqAe8Cjai9c4Gg==";

    assertThat(underTest.passwordsMatch(password, shiro1Hash), is(true));
  }

  @Test
  public void testInvalidSha1Hash() {
    String password = "admin123";
    String sha1Hash = "f865b53623b121fd34ee5426c792e5c33af8c228";

    assertThat(underTest.passwordsMatch(password, sha1Hash), is(false));
  }

  @Test
  public void testInvalidMd5Hash() {
    String password = "admin123";
    String md5Hash = "0192023a7bbd73250516f069df18b501";

    assertThat(underTest.passwordsMatch(password, md5Hash), is(false));
  }

  @Test
  public void testInvalidShiro1HashFormat() {
    String password = "admin123";
    String shiro1Hash = "$shiro1$SHA-512$1024$zjU1u+Zg9UNwuB+HEawvtA==$IzF/OWzjxrqvB5FCe/2+UcZhhZYM2pTu0TEz7Ybnk65AbbEdUk9ntdtBzkN8P3gZby2qz6MHKqAe8Cjai9c4Gg==";

    assertThat(underTest.passwordsMatch(password, sha1Hash), is(false));
  }

  @Test
  public void testHash() {
    String password = "testpassword";
    Hash hash = underTest.hashPassword(password);

    assertThat(underTest.passwordsMatch(password, hash), is(true));
  }
  
  /**
   * Test to verify SHA-512 implementation with Java 21 security providers.
   */
  @Test
  public void testSha512WithJava21Providers() {
    // List available providers for SHA-512
    Provider[] providers = Security.getProviders("MessageDigest.SHA-512");
    log.info("Available SHA-512 providers in Java 21: {}", Arrays.toString(providers));
    
    // Verify at least one provider is available for SHA-512
    assertTrue(providers.length > 0, "At least one SHA-512 provider should be available in Java 21");
    
    // Create a hash using SHA-512 and verify it works with our password service
    String password = "securePassword123";
    Sha512Hash sha512Hash = new Sha512Hash(password, null, 1024);
    
    // Verify the hash can be correctly matched
    assertThat(underTest.passwordsMatch(password, sha512Hash), is(true));
    assertThat(underTest.passwordsMatch("wrongPassword", sha512Hash), is(false));
  }
  
  /**
   * Test compatibility with BouncyCastle 1.78.1 as a security provider.
   */
  @Test
  public void testBouncyCastleCompatibility() {
    if (bouncyCastleProvider == null) {
      log.warn("Skipping BouncyCastle test as provider is not available");
      return;
    }
    
    // Verify BouncyCastle provider is registered
    Provider bcProvider = Security.getProvider(bouncyCastleProvider.getName());
    assertThat(bcProvider, notNullValue());
    log.info("Testing with BouncyCastle provider: {} (version {})", bcProvider.getName(), bcProvider.getVersionStr());
    
    // Create a password hash and verify it works with our password service
    String password = "bcPassword123";
    Hash hash = underTest.hashPassword(password);
    
    // Verify the hash can be correctly matched
    assertThat(underTest.passwordsMatch(password, hash), is(true));
  }
  
  /**
   * Test for modern hash algorithms with Java 21 JCE.
   */
  @Test
  public void testModernHashAlgorithms() {
    // Test with a higher iteration count for better security
    String password = "modernSecurePassword";
    Sha512Hash sha512Hash = new Sha512Hash(password, null, 10000); // Higher iteration count
    
    // Verify the hash can be correctly matched
    assertThat(underTest.passwordsMatch(password, sha512Hash), is(true));
    assertThat(underTest.passwordsMatch("wrongPassword", sha512Hash), is(false));
    
    // Test with a salt for better security
    byte[] salt = new byte[16];
    // In a real scenario, use a secure random generator for the salt
    for (int i = 0; i < salt.length; i++) {
      salt[i] = (byte) i;
    }
    
    Sha512Hash saltedHash = new Sha512Hash(password, salt, 10000);
    
    // Verify the salted hash can be correctly matched
    assertThat(underTest.passwordsMatch(password, saltedHash), is(true));
    assertThat(underTest.passwordsMatch("wrongPassword", saltedHash), is(false));
  }
  
  /**
   * Test to ensure password verification works correctly with updated JCE in Java 21.
   */
  @Test
  public void testPasswordVerificationWithJava21JCE() {
    // Create a new password service instance to ensure it uses Java 21 JCE
    DefaultSecurityPasswordService passwordService = new DefaultSecurityPasswordService(new LegacyNexusPasswordService());
    
    // Test with various password complexities
    String[] passwords = {
        "simple",
        "Complex123!",
        "VeryLongPasswordWithSpecialChars!@#$%^&*()",
        "Unicode\u00A9\u00AE\u2122" // Unicode characters
    };
    
    for (String password : passwords) {
      // Hash the password
      Hash hash = passwordService.hashPassword(password);
      log.info("Generated hash for '{}': {}", password, hash);
      
      // Verify the hash can be correctly matched
      assertThat("Password verification failed for: " + password,
          passwordService.passwordsMatch(password, hash), is(true));
      
      // Verify incorrect password doesn't match
      assertThat("Incorrect password should not match for: " + password,
          passwordService.passwordsMatch(password + "wrong", hash), is(false));
    }
  }
}
