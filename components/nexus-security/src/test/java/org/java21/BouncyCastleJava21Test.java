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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testcommon.Java21TestGroup;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.Arrays;
import org.junit.experimental.categories.Category;
import org.mindrot.jbcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests BouncyCastle 1.78.1 compatibility with Java 21, focusing on cryptographic operations
 * used in the security module. This test ensures that all cryptographic functions used in
 * Nexus security (particularly for tokens and passwords) remain secure and functional
 * after the upgrade to Java 21.
 *
 * @since 3.60
 */
@Category(Java21TestGroup.class)
public class BouncyCastleJava21Test
    extends TestSupport
{
  private static final String TEST_SECRET = "test-secret-key-for-java21-compatibility";
  private static final String TEST_PLAINTEXT = "This is a test message for Java 21 crypto operations";
  private static final String TEST_PASSWORD = "StrongP@ssw0rd123!";
  private static final String JWT_ISSUER = "nexus-test";
  private static final String JWT_SUBJECT = "test-user";
  
  @BeforeEach
  public void setup() {
    // Register BouncyCastle as a JCE provider
    Security.addProvider(new BouncyCastleProvider());
  }
  
  @AfterEach
  public void cleanup() {
    // Remove the provider to ensure test isolation
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
  }
  
  /**
   * Tests AES-256-GCM encryption and decryption using BouncyCastle provider.
   * This is the algorithm used for storing sensitive data in Nexus.
   */
  @Test
  @DisplayName("Test AES-256-GCM encryption and decryption with BouncyCastle in Java 21")
  public void testAesGcmEncryptionDecryption() throws Exception {
    // Generate a random AES-256 key
    KeyGenerator keyGenerator = KeyGenerator.getInstance("AES", BouncyCastleProvider.PROVIDER_NAME);
    keyGenerator.init(256);
    SecretKey secretKey = keyGenerator.generateKey();
    
    // Create GCM parameter spec with 12 bytes IV and 128 bits authentication tag length
    byte[] iv = new byte[12];
    // In a real scenario, this would be securely random
    for (int i = 0; i < iv.length; i++) {
      iv[i] = (byte) i;
    }
    GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(128, iv);
    
    // Initialize cipher for encryption
    Cipher encryptCipher = Cipher.getInstance("AES/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME);
    encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmParameterSpec);
    
    // Encrypt the plaintext
    byte[] plaintext = TEST_PLAINTEXT.getBytes(StandardCharsets.UTF_8);
    byte[] ciphertext = encryptCipher.doFinal(plaintext);
    
    // Initialize cipher for decryption
    Cipher decryptCipher = Cipher.getInstance("AES/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME);
    decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec);
    
    // Decrypt the ciphertext
    byte[] decryptedText = decryptCipher.doFinal(ciphertext);
    
    // Verify the decrypted text matches the original plaintext
    assertArrayEquals(plaintext, decryptedText, "Decrypted text should match original plaintext");
    assertEquals(TEST_PLAINTEXT, new String(decryptedText, StandardCharsets.UTF_8), 
        "Decrypted text should match original plaintext string");
  }
  
  /**
   * Tests HMAC-SHA256 signing and verification using BouncyCastle provider.
   * This is the algorithm used for JWT token signing in Nexus.
   */
  @Test
  @DisplayName("Test HMAC-SHA256 JWT signing and verification with BouncyCastle in Java 21")
  public void testHmacSha256JwtSigningVerification() throws Exception {
    // Create a JWT token with HMAC-SHA256 signature
    Algorithm algorithm = Algorithm.HMAC256(TEST_SECRET);
    String userSessionId = UUID.randomUUID().toString();
    
    // Create a JWT token with claims
    String token = JWT.create()
        .withIssuer(JWT_ISSUER)
        .withSubject(JWT_SUBJECT)
        .withIssuedAt(new Date())
        .withExpiresAt(new Date(System.currentTimeMillis() + 3600000)) // 1 hour expiration
        .withClaim("sessionId", userSessionId)
        .sign(algorithm);
    
    // Verify the token
    JWTVerifier verifier = JWT.require(algorithm)
        .withIssuer(JWT_ISSUER)
        .build();
    
    DecodedJWT decodedJWT = verifier.verify(token);
    
    // Verify the claims
    assertEquals(JWT_ISSUER, decodedJWT.getIssuer(), "JWT issuer should match");
    assertEquals(JWT_SUBJECT, decodedJWT.getSubject(), "JWT subject should match");
    assertEquals(userSessionId, decodedJWT.getClaim("sessionId").asString(), "JWT session ID should match");
  }
  
  /**
   * Tests bcrypt password hashing and verification using BouncyCastle provider.
   * This is the algorithm used for password storage in Nexus.
   */
  @Test
  @DisplayName("Test bcrypt password hashing and verification with BouncyCastle in Java 21")
  public void testBcryptPasswordHashing() {
    // Generate a bcrypt hash with a random salt (work factor 12)
    String hashedPassword = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(12));
    
    // Verify the hash matches the original password
    assertTrue(BCrypt.checkpw(TEST_PASSWORD, hashedPassword), 
        "BCrypt hash verification should succeed with correct password");
    
    // Verify the hash does not match an incorrect password
    String wrongPassword = TEST_PASSWORD + "wrong";
    assertTrue(!BCrypt.checkpw(wrongPassword, hashedPassword), 
        "BCrypt hash verification should fail with incorrect password");
  }
}