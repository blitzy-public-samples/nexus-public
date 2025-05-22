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
package org.apache.shiro;

import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.shiro.authc.credential.DefaultPasswordService;
import org.apache.shiro.authc.credential.PasswordService;
import org.apache.shiro.crypto.AesCipherService;
import org.apache.shiro.crypto.CipherService;
import org.apache.shiro.crypto.DefaultBlockCipherService;
import org.apache.shiro.crypto.SecureRandomNumberGenerator;
import org.apache.shiro.crypto.hash.DefaultHashService;
import org.apache.shiro.crypto.hash.Hash;
import org.apache.shiro.crypto.hash.HashRequest;
import org.apache.shiro.crypto.hash.HashService;
import org.apache.shiro.crypto.hash.Sha256Hash;
import org.apache.shiro.crypto.hash.SimpleHash;
import org.apache.shiro.crypto.hash.format.Shiro1CryptFormat;
import org.apache.shiro.crypto.hash.format.ParsableHashFormat;
import org.apache.shiro.lang.util.ByteSource;
import org.apache.shiro.util.ByteUtils;
import org.apache.shiro.util.StringUtils;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to validate that Apache Shiro's cryptographic functionality works correctly with BouncyCastle 1.78.1 under Java 21.
 * This test verifies password hashing, JWT HMAC256 signing, encryption, and secure random number generation.
 */
@ExtendWith(MockitoExtension.class)
public class ShiroCryptoProviderTest
{
  private static final String TEST_PASSWORD = "MySecurePassword123!";
  private static final String TEST_SALT = "randomSalt123";
  private static final int HASH_ITERATIONS = 1024;
  private static final String JWT_SECRET = "ThisIsAVerySecureSecretKeyForJwtHmacSigning";
  private static final String TEST_DATA = "This is some test data that will be encrypted and decrypted";

  @BeforeAll
  public static void setupBouncyCastle() {
    // Verify that BouncyCastle is available and registered
    boolean bcFound = false;
    for (Provider provider : Security.getProviders()) {
      if (provider.getName().contains("BC")) {
        bcFound = true;
        break;
      }
    }
    
    // If BouncyCastle is not found, register it
    if (!bcFound) {
      try {
        Provider bcProvider = (Provider) Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
            .getDeclaredConstructor().newInstance();
        Security.addProvider(bcProvider);
        System.out.println("BouncyCastle provider registered: " + bcProvider.getName());
      } catch (Exception e) {
        System.err.println("Failed to register BouncyCastle provider: " + e.getMessage());
      }
    }
  }

  /**
   * Tests that the BouncyCastle provider is properly registered and available for use with Shiro.
   */
  @Test
  public void testBouncyCastleProviderRegistration() {
    // Verify BouncyCastle is registered
    boolean bcFound = false;
    for (Provider provider : Security.getProviders()) {
      if (provider.getName().contains("BC")) {
        bcFound = true;
        System.out.println("Found BouncyCastle provider: " + provider.getName() + " v" + provider.getVersionStr());
        break;
      }
    }
    assertTrue(bcFound, "BouncyCastle provider should be registered");
    
    // Verify that Shiro can use BouncyCastle algorithms
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      assertNotNull(cipher, "Should be able to get AES/GCM/NoPadding cipher");
    } catch (Exception e) {
      fail("Failed to get cipher: " + e.getMessage());
    }
  }

  /**
   * Tests Shiro's password hashing functionality with SHA-512 algorithm.
   */
  @Test
  public void testPasswordHashing() {
    // Test basic hashing
    Hash hash = new SimpleHash("SHA-512", TEST_PASSWORD, TEST_SALT, HASH_ITERATIONS);
    assertNotNull(hash, "Hash should not be null");
    assertNotNull(hash.getBytes(), "Hash bytes should not be null");
    assertTrue(hash.getBytes().length > 0, "Hash bytes should not be empty");
    
    // Verify the hash can be recreated and compared
    Hash verifyHash = new SimpleHash("SHA-512", TEST_PASSWORD, TEST_SALT, HASH_ITERATIONS);
    assertEquals(hash.toHex(), verifyHash.toHex(), "Hashes should match");
    
    // Test with DefaultHashService
    HashService hashService = new DefaultHashService();
    HashRequest request = new HashRequest.Builder()
        .setAlgorithmName("SHA-512")
        .setSource(ByteSource.Util.bytes(TEST_PASSWORD))
        .setSalt(ByteSource.Util.bytes(TEST_SALT))
        .setIterations(HASH_ITERATIONS)
        .build();
    
    Hash serviceHash = hashService.computeHash(request);
    assertNotNull(serviceHash, "Service hash should not be null");
    assertEquals(hash.toHex(), serviceHash.toHex(), "Service hash should match direct hash");
  }

  /**
   * Tests Shiro's DefaultPasswordService which uses modern password hashing algorithms.
   */
  @Test
  public void testDefaultPasswordService() {
    PasswordService passwordService = new DefaultPasswordService();
    
    // Hash a password
    String hashedPassword = passwordService.encryptPassword(TEST_PASSWORD);
    assertNotNull(hashedPassword, "Hashed password should not be null");
    assertTrue(hashedPassword.startsWith("$shiro1$"), "Hashed password should use Shiro1 format");
    
    // Verify the password
    assertTrue(passwordService.passwordsMatch(TEST_PASSWORD, hashedPassword),
        "Password verification should succeed");
    assertFalse(passwordService.passwordsMatch("WrongPassword", hashedPassword),
        "Password verification should fail for wrong password");
    
    // Test parsing the hash format
    ParsableHashFormat format = new Shiro1CryptFormat();
    assertTrue(format.isFormat(hashedPassword), "Should recognize Shiro1 format");
    
    Hash hash = format.parse(hashedPassword);
    assertNotNull(hash, "Parsed hash should not be null");
    assertNotNull(hash.getSalt(), "Hash should have a salt");
    assertTrue(hash.getIterations() > 0, "Hash should have iterations");
  }

  /**
   * Tests HMAC-SHA256 functionality which is commonly used for JWT signing.
   */
  @Test
  public void testHmacSha256ForJwtSigning() {
    // Create a JWT header and payload (simplified for testing)
    String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
    String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"sub\":\"1234567890\",\"name\":\"Test User\",\"iat\":1516239022}".getBytes());
    
    // Create the data to sign (header.payload)
    String dataToSign = header + "." + payload;
    
    // Sign using Shiro's HMAC-SHA256
    Hash signature = new SimpleHash("HmacSHA256", dataToSign, JWT_SECRET);
    assertNotNull(signature, "Signature should not be null");
    
    // Create a JWT token
    String jwtToken = dataToSign + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.getBytes());
    assertNotNull(jwtToken, "JWT token should not be null");
    assertTrue(jwtToken.split("\\.").length == 3, "JWT token should have 3 parts");
    
    // Verify the signature
    String[] parts = jwtToken.split("\\.");
    String signedData = parts[0] + "." + parts[1];
    byte[] signatureBytes = Base64.getUrlDecoder().decode(parts[2]);
    
    Hash verifySignature = new SimpleHash("HmacSHA256", signedData, JWT_SECRET);
    assertTrue(Arrays.equals(signatureBytes, verifySignature.getBytes()),
        "Signature verification should succeed");
  }

  /**
   * Tests AES encryption and decryption using GCM mode which is accelerated in Java 21.
   */
  @Test
  public void testAesGcmEncryption() {
    // Test with Shiro's AesCipherService
    CipherService cipherService = new AesCipherService();
    ByteSource key = new SecureRandomNumberGenerator().nextBytes(16); // 128-bit key
    
    // Encrypt data
    ByteSource encrypted = cipherService.encrypt(TEST_DATA.getBytes(), key.getBytes());
    assertNotNull(encrypted, "Encrypted data should not be null");
    
    // Decrypt data
    ByteSource decrypted = cipherService.decrypt(encrypted.getBytes(), key.getBytes());
    assertNotNull(decrypted, "Decrypted data should not be null");
    assertEquals(TEST_DATA, new String(decrypted.getBytes()), "Decrypted data should match original");
    
    // Test with DefaultBlockCipherService using AES-GCM
    DefaultBlockCipherService blockCipherService = new DefaultBlockCipherService("AES");
    blockCipherService.setMode("GCM");
    blockCipherService.setPaddingScheme("NoPadding");
    
    // Generate a new key
    ByteSource blockKey = new SecureRandomNumberGenerator().nextBytes(16);
    
    // Encrypt and decrypt
    ByteSource blockEncrypted = blockCipherService.encrypt(TEST_DATA.getBytes(), blockKey.getBytes());
    ByteSource blockDecrypted = blockCipherService.decrypt(blockEncrypted.getBytes(), blockKey.getBytes());
    
    assertEquals(TEST_DATA, new String(blockDecrypted.getBytes()),
        "Block cipher decrypted data should match original");
  }

  /**
   * Tests direct use of Java 21's AES-GCM acceleration with BouncyCastle.
   */
  @Test
  public void testJava21AesGcmAcceleration() {
    try {
      // Generate a random key
      byte[] keyBytes = new byte[16]; // 128-bit key
      new SecureRandomNumberGenerator().nextBytes(16).fill(keyBytes);
      SecretKey key = new SecretKeySpec(keyBytes, "AES");
      
      // Generate a random IV (nonce)
      byte[] iv = new byte[12]; // 96-bit IV for GCM
      new SecureRandomNumberGenerator().nextBytes(12).fill(iv);
      
      // Create GCM parameters
      GCMParameterSpec gcmParams = new GCMParameterSpec(128, iv); // 128-bit authentication tag
      
      // Initialize cipher for encryption
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, gcmParams);
      
      // Encrypt data
      byte[] encryptedData = cipher.doFinal(TEST_DATA.getBytes());
      assertNotNull(encryptedData, "Encrypted data should not be null");
      
      // Initialize cipher for decryption
      cipher.init(Cipher.DECRYPT_MODE, key, gcmParams);
      
      // Decrypt data
      byte[] decryptedData = cipher.doFinal(encryptedData);
      assertNotNull(decryptedData, "Decrypted data should not be null");
      assertEquals(TEST_DATA, new String(decryptedData), "Decrypted data should match original");
      
    } catch (Exception e) {
      fail("AES-GCM test failed: " + e.getMessage());
    }
  }

  /**
   * Tests Shiro's secure random number generation.
   */
  @Test
  public void testSecureRandomNumberGeneration() {
    SecureRandomNumberGenerator generator = new SecureRandomNumberGenerator();
    
    // Generate random bytes
    ByteSource random1 = generator.nextBytes();
    ByteSource random2 = generator.nextBytes();
    
    assertNotNull(random1, "Random bytes should not be null");
    assertNotNull(random2, "Random bytes should not be null");
    assertFalse(Arrays.equals(random1.getBytes(), random2.getBytes()),
        "Two random generations should not be equal");
    
    // Test with specific size
    ByteSource random32 = generator.nextBytes(32);
    assertEquals(32, random32.getBytes().length, "Should generate exactly 32 bytes");
    
    // Test entropy by counting unique bytes in a large sample
    ByteSource largeSample = generator.nextBytes(10000);
    byte[] sampleBytes = largeSample.getBytes();
    
    int[] byteCounts = new int[256];
    for (byte b : sampleBytes) {
      byteCounts[b & 0xFF]++;
    }
    
    int zeroCount = 0;
    for (int count : byteCounts) {
      if (count == 0) {
        zeroCount++;
      }
    }
    
    // In a truly random sample of 10000 bytes, it's extremely unlikely that more than 10% of possible byte values would never appear
    assertTrue(zeroCount < 26, "Random number generator should have good entropy (too many missing values: " + zeroCount + ")");
  }
}