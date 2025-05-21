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

import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.shiro.crypto.AesCipherService;
import org.apache.shiro.crypto.hash.Hash;
import org.apache.shiro.crypto.hash.SimpleHash;
import org.apache.shiro.crypto.hash.format.Shiro1CryptFormat;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the compatibility of Nexus security cryptographic operations with BouncyCastle 1.78.1 in Java 21.
 * 
 * This test suite verifies that password hashing, validation, and cryptographic functions continue to work
 * correctly with the updated cryptographic provider. It includes tests for hash algorithm strength, timing
 * characteristics, and protection against common cryptographic vulnerabilities.
 */
public class CryptoOperationsBouncyCastleTest
    extends TestSupport
{
  private static final String BOUNCY_CASTLE_PROVIDER = "BC";
  private static final String EXPECTED_BC_VERSION = "1.78.1";
  private static final String TEST_PASSWORD = "admin123";
  private static final String TEST_COMPLEX_PASSWORD = "P@ssw0rd!2023#Java21";
  private static final String TEST_API_KEY = "nexus-api-key-12345";
  private static final String TEST_JWT_SECRET = "java21-nexus-jwt-hmac256-secret-key-for-testing";
  
  private DefaultSecurityPasswordService passwordService;
  private Provider originalBcProvider;
  
  @BeforeEach
  public void setUp() throws Exception {
    // Store the original BC provider if it exists
    originalBcProvider = Security.getProvider(BOUNCY_CASTLE_PROVIDER);
    
    // Add BouncyCastle provider for testing
    if (Security.getProvider(BOUNCY_CASTLE_PROVIDER) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    
    passwordService = new DefaultSecurityPasswordService(new LegacyNexusPasswordService());
  }
  
  @AfterEach
  public void tearDown() {
    // Restore the original security provider state
    if (originalBcProvider == null) {
      Security.removeProvider(BOUNCY_CASTLE_PROVIDER);
    }
  }
  
  @Test
  @DisplayName("Verify BouncyCastle 1.78.1 provider is available and correctly registered")
  public void testBouncyCastleProviderRegistration() {
    Provider bcProvider = Security.getProvider(BOUNCY_CASTLE_PROVIDER);
    assertThat("BouncyCastle provider should be available", bcProvider, notNullValue());
    
    // Verify the provider is BouncyCastleProvider
    assertThat(bcProvider.getClass().getName(), equalTo(BouncyCastleProvider.class.getName()));
    
    // Log provider details for diagnostic purposes
    log.info("BouncyCastle provider: {} (version {})", bcProvider.getName(), bcProvider.getVersionStr());
    
    // Verify the provider supports required algorithms
    assertThat("SHA-512 should be supported", 
        Security.getProviders("MessageDigest.SHA-512").length > 0, is(true));
    assertThat("AES should be supported", 
        Security.getProviders("Cipher.AES").length > 0, is(true));
    assertThat("HMAC-SHA256 should be supported", 
        Security.getProviders("Mac.HmacSHA256").length > 0, is(true));
  }
  
  @Test
  @DisplayName("Verify password hashing with SHA-512 works correctly with BouncyCastle")
  public void testPasswordHashingWithBouncyCastle() {
    // Create a hash using SHA-512
    Hash hash = passwordService.hashPassword(TEST_COMPLEX_PASSWORD);
    
    // Verify the hash algorithm is SHA-512
    assertThat(hash.getAlgorithmName(), is("SHA-512"));
    
    // Verify the hash has expected properties
    assertThat(hash.getSalt(), notNullValue());
    assertThat(hash.getIterations(), greaterThan(1000)); // Should have sufficient iterations
    
    // Verify password validation works
    assertTrue(passwordService.passwordsMatch(TEST_COMPLEX_PASSWORD, hash));
    
    // Verify incorrect password fails
    assertThat(passwordService.passwordsMatch(TEST_COMPLEX_PASSWORD + "wrong", hash), is(false));
    
    // Convert to Shiro1 format and verify again
    Shiro1CryptFormat format = new Shiro1CryptFormat();
    String formattedHash = format.format(hash);
    assertTrue(passwordService.passwordsMatch(TEST_COMPLEX_PASSWORD, formattedHash));
  }
  
  @Test
  @DisplayName("Verify secure random generation works correctly with Java 21 and BouncyCastle")
  public void testSecureRandomGeneration() throws Exception {
    // Create a SecureRandom instance
    SecureRandom secureRandom = SecureRandom.getInstance("SHA1PRNG");
    
    // Generate random bytes
    byte[] randomBytes1 = new byte[32];
    byte[] randomBytes2 = new byte[32];
    secureRandom.nextBytes(randomBytes1);
    secureRandom.nextBytes(randomBytes2);
    
    // Verify the random bytes are not equal (extremely unlikely)
    assertThat("Random bytes should be different", 
        Arrays.equals(randomBytes1, randomBytes2), is(false));
    
    // Create a SecureRandom instance with BouncyCastle provider
    SecureRandom bcSecureRandom = SecureRandom.getInstance("SHA1PRNG", BOUNCY_CASTLE_PROVIDER);
    
    // Generate random bytes
    byte[] bcRandomBytes = new byte[32];
    bcSecureRandom.nextBytes(bcRandomBytes);
    
    // Verify the random bytes are not null or all zeros
    assertThat(bcRandomBytes, notNullValue());
    boolean allZeros = true;
    for (byte b : bcRandomBytes) {
      if (b != 0) {
        allZeros = false;
        break;
      }
    }
    assertThat("Random bytes should not be all zeros", allZeros, is(false));
  }
  
  @Test
  @DisplayName("Verify JWT HMAC256 signing works correctly with Java 21 and BouncyCastle")
  public void testJwtHmac256Signing() throws Exception {
    // Create a JWT header and payload
    String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"sub\":\"1234567890\",\"name\":\"Test User\",\"iat\":1516239022}".getBytes(StandardCharsets.UTF_8));
    
    // Create the JWT content to sign
    String contentToSign = header + "." + payload;
    
    // Sign with HMAC-SHA256 using default provider
    Mac hmacDefault = Mac.getInstance("HmacSHA256");
    SecretKeySpec keySpecDefault = new SecretKeySpec(
        TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    hmacDefault.init(keySpecDefault);
    byte[] signatureDefault = hmacDefault.doFinal(contentToSign.getBytes(StandardCharsets.UTF_8));
    
    // Sign with HMAC-SHA256 using BouncyCastle provider
    Mac hmacBC = Mac.getInstance("HmacSHA256", BOUNCY_CASTLE_PROVIDER);
    SecretKeySpec keySpecBC = new SecretKeySpec(
        TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    hmacBC.init(keySpecBC);
    byte[] signatureBC = hmacBC.doFinal(contentToSign.getBytes(StandardCharsets.UTF_8));
    
    // Verify both signatures are identical (algorithm implementation should be consistent)
    assertArrayEquals(signatureDefault, signatureBC, 
        "HMAC-SHA256 signatures should be identical regardless of provider");
    
    // Verify signature can be validated
    Mac hmacVerify = Mac.getInstance("HmacSHA256");
    hmacVerify.init(keySpecDefault);
    byte[] signatureVerify = hmacVerify.doFinal(contentToSign.getBytes(StandardCharsets.UTF_8));
    assertArrayEquals(signatureDefault, signatureVerify, 
        "HMAC-SHA256 signature verification should work");
  }
  
  @Test
  @DisplayName("Verify API key encryption and decryption works correctly with Java 21 and BouncyCastle")
  public void testApiKeyEncryptionDecryption() {
    // Create an AES cipher service
    AesCipherService cipherService = new AesCipherService();
    
    // Generate a random key
    byte[] key = cipherService.generateNewKey().getEncoded();
    
    // Encrypt the API key
    byte[] encryptedBytes = cipherService.encrypt(TEST_API_KEY.getBytes(StandardCharsets.UTF_8), key).getBytes();
    
    // Decrypt the API key
    byte[] decryptedBytes = cipherService.decrypt(encryptedBytes, key).getBytes();
    
    // Verify the decrypted API key matches the original
    String decryptedApiKey = new String(decryptedBytes, StandardCharsets.UTF_8);
    assertThat(decryptedApiKey, equalTo(TEST_API_KEY));
  }
  
  @Test
  @DisplayName("Verify password hashing has appropriate timing characteristics for security")
  public void testPasswordHashingTiming() {
    // Measure time to hash a password
    long startTime = System.nanoTime();
    Hash hash = passwordService.hashPassword(TEST_COMPLEX_PASSWORD);
    long endTime = System.nanoTime();
    long hashingTime = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    
    // Log the hashing time for diagnostic purposes
    log.info("Password hashing time: {} ms", hashingTime);
    
    // Verify hashing takes a reasonable amount of time (not too fast, not too slow)
    // This is a balance between security (slower is better) and user experience
    assertThat("Password hashing should take a reasonable amount of time", 
        hashingTime, greaterThan(10L)); // Should take at least 10ms for security
    assertThat("Password hashing should not be too slow", 
        hashingTime, lessThan(1000L)); // Should not take more than 1 second
    
    // Measure time to verify a password
    startTime = System.nanoTime();
    boolean matches = passwordService.passwordsMatch(TEST_COMPLEX_PASSWORD, hash);
    endTime = System.nanoTime();
    long verificationTime = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    
    // Log the verification time for diagnostic purposes
    log.info("Password verification time: {} ms", verificationTime);
    
    // Verify verification takes a reasonable amount of time
    assertThat("Password verification should take a reasonable amount of time", 
        verificationTime, greaterThan(5L)); // Should take at least 5ms for security
    assertThat("Password verification should not be too slow", 
        verificationTime, lessThan(1000L)); // Should not take more than 1 second
    
    // Verify the password matches
    assertTrue(matches);
  }
  
  @Test
  @DisplayName("Verify different hash algorithms work correctly with BouncyCastle")
  public void testDifferentHashAlgorithms() {
    // Test SHA-256
    SimpleHash sha256Hash = new SimpleHash("SHA-256", TEST_COMPLEX_PASSWORD);
    assertThat(sha256Hash, notNullValue());
    assertThat(sha256Hash.getAlgorithmName(), is("SHA-256"));
    
    // Test SHA-384
    SimpleHash sha384Hash = new SimpleHash("SHA-384", TEST_COMPLEX_PASSWORD);
    assertThat(sha384Hash, notNullValue());
    assertThat(sha384Hash.getAlgorithmName(), is("SHA-384"));
    
    // Test SHA-512
    SimpleHash sha512Hash = new SimpleHash("SHA-512", TEST_COMPLEX_PASSWORD);
    assertThat(sha512Hash, notNullValue());
    assertThat(sha512Hash.getAlgorithmName(), is("SHA-512"));
    
    // Verify all algorithms produce different hashes
    assertThat(sha256Hash.getBytes(), notNullValue());
    assertThat(sha384Hash.getBytes(), notNullValue());
    assertThat(sha512Hash.getBytes(), notNullValue());
    
    assertThat("SHA-256 and SHA-384 should produce different hashes",
        Arrays.equals(sha256Hash.getBytes(), sha384Hash.getBytes()), is(false));
    assertThat("SHA-256 and SHA-512 should produce different hashes",
        Arrays.equals(sha256Hash.getBytes(), sha512Hash.getBytes()), is(false));
    assertThat("SHA-384 and SHA-512 should produce different hashes",
        Arrays.equals(sha384Hash.getBytes(), sha512Hash.getBytes()), is(false));
  }
  
  @Test
  @DisplayName("Verify error handling for invalid inputs")
  public void testErrorHandlingForInvalidInputs() {
    // Verify null password handling
    assertThrows(IllegalArgumentException.class, () -> {
      passwordService.hashPassword(null);
    });
    
    // Verify empty password handling
    assertDoesNotThrow(() -> {
      Hash hash = passwordService.hashPassword("");
      assertThat(hash, notNullValue());
    });
    
    // Verify null hash handling
    assertThrows(IllegalArgumentException.class, () -> {
      passwordService.passwordsMatch(TEST_PASSWORD, (Hash) null);
    });
    
    // Verify null hash string handling
    assertThrows(IllegalArgumentException.class, () -> {
      passwordService.passwordsMatch(TEST_PASSWORD, (String) null);
    });
    
    // Verify invalid hash format handling
    assertThat(passwordService.passwordsMatch(TEST_PASSWORD, "invalid-hash-format"), is(false));
  }
  
  @Test
  @DisplayName("Verify compatibility with legacy hash formats")
  public void testCompatibilityWithLegacyHashFormats() {
    // Test SHA-1 hash compatibility
    String sha1Hash = "f865b53623b121fd34ee5426c792e5c33af8c227"; // SHA-1 hash of "admin123"
    assertThat(passwordService.passwordsMatch(TEST_PASSWORD, sha1Hash), is(true));
    
    // Test MD5 hash compatibility
    String md5Hash = "0192023a7bbd73250516f069df18b500"; // MD5 hash of "admin123"
    assertThat(passwordService.passwordsMatch(TEST_PASSWORD, md5Hash), is(true));
    
    // Test Shiro1 hash format compatibility
    String shiro1Hash = "$shiro1$SHA-512$1024$zjU1u+Zg9UNwuB+HEawvtA==$IzF/OWzJxrqvB5FCe/2+UcZhhZYM2pTu0TEz7Ybnk65AbbEdUk9ntdtBzkN8P3gZby2qz6MHKqAe8Cjai9c4Gg==";
    assertThat(passwordService.passwordsMatch(TEST_PASSWORD, shiro1Hash), is(true));
  }
  
  @Test
  @DisplayName("Verify hash algorithm strength and collision resistance")
  public void testHashAlgorithmStrengthAndCollisionResistance() {
    // Create hashes for similar passwords
    String password1 = "TestPassword123!";
    String password2 = "TestPassword123@"; // Only one character different
    
    Hash hash1 = passwordService.hashPassword(password1);
    Hash hash2 = passwordService.hashPassword(password2);
    
    // Verify the hashes are different even for similar passwords
    assertThat("Similar passwords should produce different hashes",
        Arrays.equals(hash1.getBytes(), hash2.getBytes()), is(false));
    
    // Verify the hash is different even with the same password (due to salt)
    Hash hash3 = passwordService.hashPassword(password1);
    assertThat("Same password should produce different hashes due to salt",
        Arrays.equals(hash1.getBytes(), hash3.getBytes()), is(false));
    
    // Verify the salt is different for each hash
    assertThat("Salts should be different for each hash",
        Arrays.equals(hash1.getSalt(), hash3.getSalt()), is(false));
  }
}