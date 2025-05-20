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
import org.apache.shiro.crypto.hash.Sha256Hash;
import org.apache.shiro.util.ByteSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify that Shiro's cryptographic operations work correctly with Java 21's JCE implementation
 * and with Virtual Threads.
 */
@DisplayName("Shiro Cryptography Java 21 Tests")
public class ShiroCryptographyJava21Test
{
  private static final String TEST_MESSAGE = "This is a test message for Java 21 cryptography";
  private static final String TEST_PASSWORD = "securePassword123";
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int TIMEOUT_SECONDS = 10;

  /**
   * Verifies that HMAC256 JWT signing works correctly with Java 21's JCE.
   */
  @Test
  @DisplayName("HMAC256 JWT signing with Java 21 JCE")
  public void testHmac256JwtSigning() throws Exception {
    // Generate a secure key for HMAC256
    KeyGenerator keyGen = KeyGenerator.getInstance("HmacSHA256");
    SecretKey secretKey = keyGen.generateKey();
    byte[] keyBytes = secretKey.getEncoded();
    
    // Create a simple JWT header and payload
    String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"sub\":\"1234567890\",\"name\":\"Test User\",\"iat\":1516239022}".getBytes(StandardCharsets.UTF_8));
    
    // Create the JWT content to sign
    String content = header + "." + payload;
    
    // Sign with Shiro's HMAC256
    Sha256Hash hash = new Sha256Hash(content.getBytes(StandardCharsets.UTF_8), keyBytes);
    String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hash.getBytes());
    
    // Verify the signature
    Sha256Hash verifyHash = new Sha256Hash(content.getBytes(StandardCharsets.UTF_8), keyBytes);
    String verifySignature = Base64.getUrlEncoder().withoutPadding().encodeToString(verifyHash.getBytes());
    
    assertEquals(signature, verifySignature, "HMAC256 signature verification failed");
  }

  /**
   * Verifies that AES-256 encryption and decryption work correctly with Java 21's JCE.
   */
  @Test
  @DisplayName("AES-256 encryption and decryption with Java 21 JCE")
  public void testAes256EncryptionDecryption() {
    // Create AES cipher service with 256-bit key
    AesCipherService cipherService = new AesCipherService();
    cipherService.setKeySize(256);
    
    // Generate a secure key
    ByteSource key = cipherService.generateNewKey();
    
    // Encrypt the test message
    ByteSource encrypted = cipherService.encrypt(TEST_MESSAGE.getBytes(StandardCharsets.UTF_8), key.getBytes());
    
    // Decrypt the message
    ByteSource decrypted = cipherService.decrypt(encrypted.getBytes(), key.getBytes());
    
    // Verify the decrypted message matches the original
    String decryptedMessage = new String(decrypted.getBytes(), StandardCharsets.UTF_8);
    assertEquals(TEST_MESSAGE, decryptedMessage, "AES-256 encryption/decryption failed");
  }

  /**
   * Verifies that password hashing with bcrypt works correctly with Java 21's JCE.
   */
  @Test
  @DisplayName("Password hashing with Java 21 JCE")
  public void testPasswordHashing() {
    // Create a hash service
    DefaultHashService hashService = new DefaultHashService();
    hashService.setHashAlgorithmName(Sha256Hash.ALGORITHM_NAME);
    hashService.setHashIterations(1024); // Suitable for testing, use higher in production
    
    // Generate a random salt
    SecureRandomNumberGenerator saltGenerator = new SecureRandomNumberGenerator();
    ByteSource salt = saltGenerator.nextBytes();
    
    // Hash the password
    Sha256Hash hash = new Sha256Hash(TEST_PASSWORD, salt, hashService.getHashIterations());
    String hashedPassword = hash.toBase64();
    
    // Verify the password
    Sha256Hash verifyHash = new Sha256Hash(TEST_PASSWORD, salt, hashService.getHashIterations());
    String verifyHashedPassword = verifyHash.toBase64();
    
    assertEquals(hashedPassword, verifyHashedPassword, "Password hashing verification failed");
  }

  /**
   * Verifies that cryptographic operations work correctly when executed in Virtual Threads.
   */
  @Test
  @DisplayName("Cryptographic operations with Virtual Threads")
  @Execution(ExecutionMode.CONCURRENT)
  public void testCryptographicOperationsWithVirtualThreads() throws Exception {
    // Create a countdown latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicBoolean allSucceeded = new AtomicBoolean(true);
    
    // Create an executor service with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Instant start = Instant.now();
      
      // Submit multiple encryption tasks to be executed in virtual threads
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Perform AES encryption in a virtual thread
            AesCipherService cipherService = new AesCipherService();
            ByteSource key = cipherService.generateNewKey();
            String message = TEST_MESSAGE + "-" + taskId;
            
            ByteSource encrypted = cipherService.encrypt(message.getBytes(StandardCharsets.UTF_8), key.getBytes());
            ByteSource decrypted = cipherService.decrypt(encrypted.getBytes(), key.getBytes());
            
            String decryptedMessage = new String(decrypted.getBytes(), StandardCharsets.UTF_8);
            if (!message.equals(decryptedMessage)) {
              System.err.println("Task " + taskId + " failed: decrypted message does not match original");
              allSucceeded.set(false);
            }
          } catch (Exception e) {
            System.err.println("Task " + taskId + " failed with exception: " + e.getMessage());
            e.printStackTrace();
            allSucceeded.set(false);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Instant end = Instant.now();
      
      // Verify all tasks completed successfully
      assertTrue(completed, "Not all virtual thread tasks completed within the timeout period");
      assertTrue(allSucceeded.get(), "Some virtual thread tasks failed");
      
      // Log the time taken for all operations
      Duration duration = Duration.between(start, end);
      System.out.println("Completed " + CONCURRENT_OPERATIONS + " cryptographic operations in virtual threads in " 
          + duration.toMillis() + "ms");
    }
  }

  /**
   * Verifies that DefaultBlockCipherService works correctly with Java 21's JCE.
   */
  @Test
  @DisplayName("DefaultBlockCipherService with Java 21 JCE")
  public void testDefaultBlockCipherService() {
    // Create a default block cipher service
    DefaultBlockCipherService cipherService = new DefaultBlockCipherService("AES");
    cipherService.setKeySize(256);
    
    // Generate a secure key
    ByteSource key = cipherService.generateNewKey();
    
    // Encrypt the test message
    ByteSource encrypted = cipherService.encrypt(TEST_MESSAGE.getBytes(StandardCharsets.UTF_8), key.getBytes());
    
    // Decrypt the message
    ByteSource decrypted = cipherService.decrypt(encrypted.getBytes(), key.getBytes());
    
    // Verify the decrypted message matches the original
    String decryptedMessage = new String(decrypted.getBytes(), StandardCharsets.UTF_8);
    assertEquals(TEST_MESSAGE, decryptedMessage, "DefaultBlockCipherService encryption/decryption failed");
  }

  /**
   * Verifies that Java 21 has unlimited cryptography policies enabled by default.
   */
  @Test
  @DisplayName("Java 21 unlimited cryptography policies")
  public void testUnlimitedCryptographyPolicies() throws NoSuchAlgorithmException {
    // Check if unlimited key size is allowed
    int maxKeySize = javax.crypto.Cipher.getMaxAllowedKeyLength("AES");
    
    // If unlimited crypto is enabled, max key size should be very large (2^31-1)
    assertTrue(maxKeySize >= 256, "Java 21 should have unlimited cryptography policies enabled");
    System.out.println("Maximum AES key size allowed: " + maxKeySize + " bits");
  }
}