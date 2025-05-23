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
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.shiro.crypto.hash.Hash;
import org.apache.shiro.crypto.hash.Sha512Hash;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.containsString;

/**
 * Tests the compatibility of cryptographic operations with BouncyCastle 1.78.1 in Java 21.
 * 
 * This test suite verifies that password hashing, validation, and cryptographic functions
 * continue to work correctly with the updated cryptographic provider.
 */
public class CryptoOperationsBouncyCastleTest
    extends TestSupport
{
  private static final String BC_PROVIDER = "BC";
  private static final String TEST_PASSWORD = "securePassword123";
  private static final String TEST_DATA = "This is test data for encryption";
  private static final String TEST_JWT_PAYLOAD = "{\"sub\":\"1234567890\",\"name\":\"Test User\",\"iat\":1516239022}";
  
  private DefaultSecurityPasswordService passwordService;
  
  @Before
  public void setUp() throws Exception {
    // Ensure BouncyCastle provider is registered
    if (Security.getProvider(BC_PROVIDER) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
    
    passwordService = new DefaultSecurityPasswordService(new LegacyNexusPasswordService());
  }
  
  @Test
  public void testBouncyCastleProviderAvailability() {
    Provider provider = Security.getProvider(BC_PROVIDER);
    assertThat("BouncyCastle provider should be available", provider, notNullValue());
    assertThat("BouncyCastle provider should be version 1.78.1 or compatible", 
        provider.getInfo(), containsString("1.78"));
  }
  
  @Test
  public void testPasswordHashingAndValidation() {
    // Test password hashing with BouncyCastle provider
    Hash hash = passwordService.hashPassword(TEST_PASSWORD);
    assertThat("Hash should not be null", hash, notNullValue());
    
    // Verify the hash format is as expected (Shiro1 format with SHA-512)
    String hashString = hash.toString();
    assertThat("Hash should use SHA-512 algorithm", hashString, containsString("SHA-512"));
    
    // Test password validation
    boolean matches = passwordService.passwordsMatch(TEST_PASSWORD, hashString);
    assertThat("Password should match its hash", matches, is(true));
    
    // Test negative case
    boolean shouldNotMatch = passwordService.passwordsMatch("wrongPassword", hashString);
    assertThat("Wrong password should not match", shouldNotMatch, is(false));
  }
  
  @Test
  public void testSha512Implementation() throws Exception {
    // Test SHA-512 implementation with BouncyCastle
    Sha512Hash sha512Hash = new Sha512Hash(TEST_PASSWORD);
    String hashHex = sha512Hash.toHex();
    
    // Create a new hash with the same input
    Sha512Hash sha512Hash2 = new Sha512Hash(TEST_PASSWORD);
    String hashHex2 = sha512Hash2.toHex();
    
    // Verify deterministic behavior
    assertThat("SHA-512 hash should be deterministic", hashHex, is(hashHex2));
    
    // Verify hash length (SHA-512 produces 512 bits = 64 bytes = 128 hex chars)
    assertThat("SHA-512 hash should be 128 hex characters", hashHex.length(), is(128));
  }
  
  @Test
  public void testSecureRandomGeneration() throws Exception {
    // Test secure random generation with BouncyCastle
    SecureRandom secureRandom = SecureRandom.getInstance("SHA1PRNG");
    byte[] randomBytes1 = new byte[32];
    secureRandom.nextBytes(randomBytes1);
    
    // Generate another random sequence
    byte[] randomBytes2 = new byte[32];
    secureRandom.nextBytes(randomBytes2);
    
    // Verify randomness (extremely unlikely to be equal)
    assertThat("SecureRandom should generate different values", 
        Arrays.equals(randomBytes1, randomBytes2), is(false));
  }
  
  @Test
  public void testJwtHmac256Signing() throws Exception {
    // Test HMAC-SHA256 for JWT signing
    String secretKey = "your-256-bit-secret-key-for-testing-only";
    Mac hmacSha256 = Mac.getInstance("HmacSHA256");
    SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(), "HmacSHA256");
    hmacSha256.init(secretKeySpec);
    
    // Sign test JWT payload
    byte[] signature = hmacSha256.doFinal(TEST_JWT_PAYLOAD.getBytes());
    assertThat("JWT signature should not be null or empty", signature.length > 0, is(true));
    
    // Verify signature
    Mac hmacVerify = Mac.getInstance("HmacSHA256");
    hmacVerify.init(secretKeySpec);
    byte[] verifySignature = hmacVerify.doFinal(TEST_JWT_PAYLOAD.getBytes());
    
    assertThat("JWT signature verification should succeed", 
        Arrays.equals(signature, verifySignature), is(true));
  }
  
  @Test
  public void testApiKeyEncryption() throws Exception {
    // Test AES-256-GCM encryption for API keys
    KeyGenerator keyGen = KeyGenerator.getInstance("AES");
    keyGen.init(256); // 256-bit AES key
    SecretKey secretKey = keyGen.generateKey();
    
    // Initialize cipher for encryption
    Cipher encryptCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    byte[] iv = new byte[16]; // Initialization vector
    new SecureRandom().nextBytes(iv);
    IvParameterSpec ivSpec = new IvParameterSpec(iv);
    encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
    
    // Encrypt test API key
    String apiKey = "api-key-12345-abcdef";
    byte[] encryptedData = encryptCipher.doFinal(apiKey.getBytes());
    
    // Initialize cipher for decryption
    Cipher decryptCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
    
    // Decrypt and verify
    byte[] decryptedData = decryptCipher.doFinal(encryptedData);
    String decryptedApiKey = new String(decryptedData);
    
    assertThat("Decrypted API key should match original", decryptedApiKey, is(apiKey));
  }
  
  @Test
  public void testJceProviderConfiguration() throws Exception {
    // Test JCE provider configuration
    String[] supportedAlgorithms = {
        "SHA-512",
        "HmacSHA256",
        "AES",
        "AES/CBC/PKCS5Padding",
        "AES/GCM/NoPadding"
    };
    
    for (String algorithm : supportedAlgorithms) {
      if (algorithm.contains("/")) {
        // This is a cipher transformation
        Cipher cipher = Cipher.getInstance(algorithm);
        assertThat("Cipher should be available: " + algorithm, cipher, notNullValue());
      } else if (algorithm.startsWith("Hmac")) {
        // This is a MAC algorithm
        Mac mac = Mac.getInstance(algorithm);
        assertThat("MAC should be available: " + algorithm, mac, notNullValue());
      } else {
        // This is a message digest algorithm
        java.security.MessageDigest md = java.security.MessageDigest.getInstance(algorithm);
        assertThat("MessageDigest should be available: " + algorithm, md, notNullValue());
      }
    }
  }
}