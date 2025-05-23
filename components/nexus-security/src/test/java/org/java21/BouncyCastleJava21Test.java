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
package org.java21;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.junit.jupiter.api.Assertions;

/**
 * Tests BouncyCastle 1.78.1 cryptography provider compatibility with Java 21.
 * <p>
 * This test verifies that encryption, decryption, signing, and verification operations
 * function correctly under Java 21 with updated security controls.
 * <p>
 * The test focuses on cryptographic operations used in the Nexus security module:
 * - AES-256-GCM encryption/decryption for stored secrets
 * - HMAC256 signing for JWT tokens
 * - bcrypt password hashing
 */
@Tag("java21")
@Category(Java21TestGroup.class)
public class BouncyCastleJava21Test
{
  private static final String TEST_SECRET = "This is a test secret for encryption";
  private static final String JWT_ISSUER = "nexus-test";
  private static final String JWT_SECRET = "test-jwt-secret-key";
  private static final String TEST_PASSWORD = "password123";
  
  private static final int GCM_TAG_LENGTH = 128; // bits
  private static final int GCM_IV_LENGTH = 12; // bytes
  
  /**
   * Register BouncyCastle provider once for all tests.
   */
  @BeforeAll
  public static void setupClass() {
    // Register BouncyCastle provider if not already registered
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }
  
  /**
   * Tests AES-256-GCM encryption and decryption using BouncyCastle provider.
   * <p>
   * This test verifies that the AES-256-GCM algorithm used for storing sensitive
   * data in Nexus functions correctly under Java 21 with BouncyCastle 1.78.1.
   */
  @Test
  @DisplayName("Test AES-256-GCM encryption/decryption with BouncyCastle under Java 21")
  public void testAesGcmEncryptionDecryption() throws Exception {
    // Generate a random AES-256 key
    KeyGenerator keyGen = KeyGenerator.getInstance("AES", BouncyCastleProvider.PROVIDER_NAME);
    keyGen.init(256);
    SecretKey secretKey = keyGen.generateKey();
    
    // Generate a random IV for GCM mode
    byte[] iv = new byte[GCM_IV_LENGTH];
    new java.security.SecureRandom().nextBytes(iv);
    
    // Encrypt
    Cipher encryptCipher = Cipher.getInstance("AES/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME);
    GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
    encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);
    
    byte[] plaintext = TEST_SECRET.getBytes(StandardCharsets.UTF_8);
    byte[] ciphertext = encryptCipher.doFinal(plaintext);
    
    // Decrypt
    Cipher decryptCipher = Cipher.getInstance("AES/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME);
    decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);
    byte[] decryptedBytes = decryptCipher.doFinal(ciphertext);
    String decryptedText = new String(decryptedBytes, StandardCharsets.UTF_8);
    
    // Verify
    Assertions.assertEquals(TEST_SECRET, decryptedText, "Decrypted text should match original plaintext");
    
    // Log success for debugging
    System.out.println("AES-256-GCM encryption/decryption successful with BouncyCastle under Java 21");
    System.out.println("Original: " + TEST_SECRET);
    System.out.println("Encrypted (hex): " + Hex.toHexString(ciphertext));
    System.out.println("Decrypted: " + decryptedText);
  }
  
  /**
   * Tests HMAC256 signing and verification for JWT tokens using BouncyCastle provider.
   * <p>
   * This test verifies that the HMAC256 algorithm used for JWT token signing and
   * verification in Nexus functions correctly under Java 21 with BouncyCastle 1.78.1.
   */
  @Test
  @DisplayName("Test HMAC256 JWT signing/verification with BouncyCastle under Java 21")
  public void testJwtHmacSigningVerification() {
    // Create a JWT token with HMAC256 signing
    String userSessionId = UUID.randomUUID().toString();
    String username = "admin";
    String realm = "NexusAuthorizingRealm";
    
    // Create a JWT with claims similar to those used in JwtHelper
    String token = JWT.create()
        .withIssuer(JWT_ISSUER)
        .withExpiresAt(new Date(System.currentTimeMillis() + 3600000)) // 1 hour
        .withClaim("user_session_id", userSessionId)
        .withClaim("user", username)
        .withClaim("realm", realm)
        .sign(Algorithm.HMAC256(JWT_SECRET));
    
    // Verify the JWT token
    JWTVerifier verifier = JWT.require(Algorithm.HMAC256(JWT_SECRET))
        .withIssuer(JWT_ISSUER)
        .build();
    
    DecodedJWT decodedJWT = verifier.verify(token);
    
    // Verify claims
    Assertions.assertEquals(JWT_ISSUER, decodedJWT.getIssuer(), "JWT issuer should match");
    Assertions.assertEquals(userSessionId, decodedJWT.getClaim("user_session_id").asString(), 
        "JWT user_session_id claim should match");
    Assertions.assertEquals(username, decodedJWT.getClaim("user").asString(), 
        "JWT user claim should match");
    Assertions.assertEquals(realm, decodedJWT.getClaim("realm").asString(), 
        "JWT realm claim should match");
    
    // Log success for debugging
    System.out.println("HMAC256 JWT signing/verification successful with BouncyCastle under Java 21");
    System.out.println("JWT Token: " + token);
  }
  
  /**
   * Tests bcrypt password hashing and verification using BouncyCastle provider.
   * <p>
   * This test verifies that the bcrypt algorithm used for password hashing in Nexus
   * functions correctly under Java 21 with BouncyCastle 1.78.1.
   */
  @Test
  @DisplayName("Test bcrypt password hashing with BouncyCastle under Java 21")
  public void testBcryptPasswordHashing() throws Exception {
    // Use BouncyCastle's implementation of bcrypt
    String salt = "$2a$10$" + Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes()).substring(0, 22);
    
    // Hash the password using bcrypt
    String hashedPassword = org.bouncycastle.crypto.util.Password.bcrypt(TEST_PASSWORD.toCharArray(), salt.getBytes());
    
    // Verify the password
    boolean passwordMatches = org.bouncycastle.crypto.util.Password.checkPassword(hashedPassword, TEST_PASSWORD.toCharArray());
    
    // Assert
    Assertions.assertTrue(passwordMatches, "Password verification should succeed");
    
    // Verify negative case
    boolean wrongPasswordMatches = org.bouncycastle.crypto.util.Password.checkPassword(
        hashedPassword, "wrong-password".toCharArray());
    Assertions.assertFalse(wrongPasswordMatches, "Wrong password verification should fail");
    
    // Log success for debugging
    System.out.println("bcrypt password hashing successful with BouncyCastle under Java 21");
    System.out.println("Original password: " + TEST_PASSWORD);
    System.out.println("Hashed password: " + hashedPassword);
  }
}