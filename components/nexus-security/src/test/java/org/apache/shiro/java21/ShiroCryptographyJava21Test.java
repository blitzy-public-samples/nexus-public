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
package org.apache.shiro.java21;

import org.apache.shiro.crypto.AesCipherService;
import org.apache.shiro.crypto.DefaultBlockCipherService;
import org.apache.shiro.crypto.SecureRandomNumberGenerator;
import org.apache.shiro.crypto.hash.DefaultHashService;
import org.apache.shiro.crypto.hash.Hash;
import org.apache.shiro.crypto.hash.HashRequest;
import org.apache.shiro.crypto.hash.Sha256Hash;
import org.apache.shiro.util.ByteSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.testsupport.TestSupport;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests Shiro's cryptographic operations under Java 21, focusing on validating that token signing,
 * encryption, and hashing mechanisms work correctly with Java 21's updated security providers.
 * 
 * This test class verifies:
 * 1. HMAC256 JWT signing works correctly with Java 21's JCE
 * 2. API key encryption and decryption function properly with AES
 * 3. Password hashing with SHA-256 produces consistent results
 * 4. All cryptographic operations function correctly under concurrent Virtual Thread execution
 */
public class ShiroCryptographyJava21Test
    extends TestSupport
{
  private static final String SECRET_KEY = "thisIsASecretKeyForTestingPurposesOnly";
  private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";
  private static final String TEST_PAYLOAD = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ";
  
  private SecretKey secretKey;
  private Mac hmacSha256;
  private AesCipherService aesCipherService;
  private DefaultHashService hashService;
  
  @BeforeEach
  public void setUp() throws NoSuchAlgorithmException, InvalidKeyException {
    // Create a secret key for HMAC-SHA256
    secretKey = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), HMAC_SHA256_ALGORITHM);
    
    // Initialize the Mac object
    hmacSha256 = Mac.getInstance(HMAC_SHA256_ALGORITHM);
    hmacSha256.init(secretKey);
    
    // Initialize Shiro's AES cipher service
    aesCipherService = new AesCipherService();
    
    // Initialize Shiro's hash service
    hashService = new DefaultHashService();
    hashService.setHashAlgorithmName(Sha256Hash.ALGORITHM_NAME);
    hashService.setPrivateSalt(ByteSource.Util.bytes("privateSalt"));
    hashService.setGeneratePublicSalt(true);
    hashService.setHashIterations(1024);
  }
  
  @Test
  @DisplayName("Test HMAC-SHA256 JWT signing with Java 21 JCE")
  public void testHmacSha256JwtSigning() {
    // Generate a signature for the test payload
    byte[] signature = hmacSha256.doFinal(TEST_PAYLOAD.getBytes(StandardCharsets.UTF_8));
    String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    
    // Verify the signature
    assertNotNull(encodedSignature, "Signature should not be null");
    assertFalse(encodedSignature.isEmpty(), "Signature should not be empty");
    
    // Verify that we can recreate the same signature (deterministic)
    byte[] signatureVerify = hmacSha256.doFinal(TEST_PAYLOAD.getBytes(StandardCharsets.UTF_8));
    String encodedSignatureVerify = Base64.getUrlEncoder().withoutPadding().encodeToString(signatureVerify);
    
    assertEquals(encodedSignature, encodedSignatureVerify, "Signatures should match for the same input");
  }
  
  @Test
  @DisplayName("Test API key encryption with Java 21 JCE")
  public void testApiKeyEncryption() throws Exception {
    // Sample API key
    String apiKey = "api-key-12345-abcdef";
    
    // Generate a secure random salt
    ByteSource salt = new SecureRandomNumberGenerator().nextBytes();
    
    // Encrypt the API key using Shiro's AesCipherService
    ByteSource encryptedApiKey = aesCipherService.encrypt(
        apiKey.getBytes(StandardCharsets.UTF_8), 
        salt.getBytes());
    
    // Verify encryption
    assertNotNull(encryptedApiKey, "Encrypted API key should not be null");
    assertFalse(encryptedApiKey.isEmpty(), "Encrypted API key should not be empty");
    
    // Decrypt the API key
    ByteSource decryptedApiKey = aesCipherService.decrypt(
        encryptedApiKey.getBytes(), 
        salt.getBytes());
    
    // Verify decryption works correctly
    assertEquals(apiKey, new String(decryptedApiKey.getBytes(), StandardCharsets.UTF_8), 
        "Decrypted API key should match the original");
    
    // Verify that encryption is non-deterministic (uses initialization vector)
    ByteSource encryptedApiKey2 = aesCipherService.encrypt(
        apiKey.getBytes(StandardCharsets.UTF_8), 
        salt.getBytes());
    
    // The ciphertext should be different due to the random IV
    assertNotEquals(encryptedApiKey.toBase64(), encryptedApiKey2.toBase64(), 
        "Encrypted API keys should be different due to random IV");
  }
  
  @Test
  @DisplayName("Test password hashing with Java 21 JCE")
  public void testPasswordHashing() throws Exception {
    // Sample password
    String password = "P@ssw0rd123!";
    
    // Hash the password using Shiro's HashService
    HashRequest request = new HashRequest.Builder()
        .setSource(ByteSource.Util.bytes(password))
        .setSalt(ByteSource.Util.bytes("userSalt"))
        .setAlgorithmName(Sha256Hash.ALGORITHM_NAME)
        .setIterations(1024)
        .build();
    
    Hash hashedPassword = hashService.computeHash(request);
    
    // Verify hashing
    assertNotNull(hashedPassword, "Hashed password should not be null");
    assertNotNull(hashedPassword.toBase64(), "Hashed password Base64 should not be null");
    assertFalse(hashedPassword.toBase64().isEmpty(), "Hashed password should not be empty");
    
    // Verify that hashing is deterministic for the same password and salt
    Hash hashedPasswordVerify = hashService.computeHash(request);
    
    assertEquals(hashedPassword.toBase64(), hashedPasswordVerify.toBase64(), 
        "Hashed passwords should match for the same input and salt");
    
    // Verify that different passwords produce different hashes
    String differentPassword = "AnotherP@ssw0rd!";
    HashRequest differentRequest = new HashRequest.Builder()
        .setSource(ByteSource.Util.bytes(differentPassword))
        .setSalt(ByteSource.Util.bytes("userSalt"))
        .setAlgorithmName(Sha256Hash.ALGORITHM_NAME)
        .setIterations(1024)
        .build();
    
    Hash differentHashedPassword = hashService.computeHash(differentRequest);
    
    assertNotEquals(hashedPassword.toBase64(), differentHashedPassword.toBase64(), 
        "Hashed passwords should be different for different inputs");
    
    // Verify that different salts produce different hashes for the same password
    HashRequest differentSaltRequest = new HashRequest.Builder()
        .setSource(ByteSource.Util.bytes(password))
        .setSalt(ByteSource.Util.bytes("differentSalt"))
        .setAlgorithmName(Sha256Hash.ALGORITHM_NAME)
        .setIterations(1024)
        .build();
    
    Hash differentSaltHashedPassword = hashService.computeHash(differentSaltRequest);
    
    assertNotEquals(hashedPassword.toBase64(), differentSaltHashedPassword.toBase64(), 
        "Hashed passwords should be different for different salts");
  }
  
  @Test
  @DisplayName("Test cryptographic operations with Virtual Threads")
  public void testCryptographicOperationsWithVirtualThreads() throws Exception {
    // Number of concurrent operations to perform
    int concurrentOperations = 100;
    
    // CountDownLatch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to perform cryptographic operations concurrently
      for (int i = 0; i < concurrentOperations; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Test 1: JWT Signing with HMAC-SHA256
            String threadPayload = TEST_PAYLOAD + "-" + index;
            Mac threadLocalMac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            threadLocalMac.init(secretKey);
            byte[] signature = threadLocalMac.doFinal(threadPayload.getBytes(StandardCharsets.UTF_8));
            String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
            assertFalse(encodedSignature.isEmpty(), "Signature should not be empty in virtual thread " + index);
            
            // Test 2: API Key Encryption with AES
            String apiKey = "api-key-" + index;
            ByteSource salt = new SecureRandomNumberGenerator().nextBytes();
            ByteSource encryptedApiKey = aesCipherService.encrypt(
                apiKey.getBytes(StandardCharsets.UTF_8), 
                salt.getBytes());
            ByteSource decryptedApiKey = aesCipherService.decrypt(
                encryptedApiKey.getBytes(), 
                salt.getBytes());
            assertEquals(apiKey, new String(decryptedApiKey.getBytes(), StandardCharsets.UTF_8),
                "Decryption should work correctly in virtual thread " + index);
            
            // Test 3: Password Hashing
            String password = "password-" + index;
            HashRequest request = new HashRequest.Builder()
                .setSource(ByteSource.Util.bytes(password))
                .setSalt(ByteSource.Util.bytes("salt-" + index))
                .setAlgorithmName(Sha256Hash.ALGORITHM_NAME)
                .setIterations(1024)
                .build();
            Hash hashedPassword = hashService.computeHash(request);
            assertNotNull(hashedPassword.toBase64(), 
                "Hashed password should not be null in virtual thread " + index);
          } 
          catch (Exception e) {
            fail("Exception in virtual thread " + index + ": " + e.getMessage());
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete (with timeout)
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertTrue(completed, "All virtual thread operations should complete within the timeout");
    }
  }
}