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
package org.apache.java21;

import org.apache.shiro.authc.credential.DefaultPasswordService;
import org.apache.shiro.authc.credential.PasswordService;
import org.apache.shiro.codec.Base64;
import org.apache.shiro.crypto.AesCipherService;
import org.apache.shiro.crypto.CipherService;
import org.apache.shiro.crypto.SecureRandomNumberGenerator;
import org.apache.shiro.crypto.hash.DefaultHashService;
import org.apache.shiro.crypto.hash.Hash;
import org.apache.shiro.crypto.hash.HashRequest;
import org.apache.shiro.crypto.hash.HashService;
import org.apache.shiro.crypto.hash.Sha256Hash;
import org.apache.shiro.crypto.hash.SimpleHash;
import org.apache.shiro.util.ByteSource;

// JWT related imports
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.testsupport.TestSupport;

import java.security.Security;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Validates that Apache Shiro 2.0.0's cryptographic operations are compatible with Java 21's updated security framework.
 * This test ensures proper functioning of Shiro's password hashing, encryption/decryption, key management, and JWT operations.
 * It verifies that all cryptographic providers used by Shiro work correctly with Java 21's revamped security manager and module system.
 */
public class ShiroSecurityCryptographyTest
    extends TestSupport
{
  private static final String TEST_PASSWORD = "MySecretPassword123";
  private static final String TEST_PLAINTEXT = "This is a test message for encryption and decryption";
  private static final String TEST_SALT = "randomSalt";
  private static final int HASH_ITERATIONS = 1024;

  private PasswordService passwordService;
  private HashService hashService;
  private CipherService cipherService;
  private SecureRandomNumberGenerator randomGenerator;

  @BeforeEach
  public void setUp() {
    // Initialize Shiro's cryptographic services
    passwordService = new DefaultPasswordService();
    
    hashService = new DefaultHashService();
    ((DefaultHashService) hashService).setHashAlgorithmName(Sha256Hash.ALGORITHM_NAME);
    ((DefaultHashService) hashService).setPrivateSalt(ByteSource.Util.bytes("private-salt"));
    ((DefaultHashService) hashService).setGeneratePublicSalt(true);
    ((DefaultHashService) hashService).setHashIterations(HASH_ITERATIONS);
    
    cipherService = new AesCipherService();
    ((AesCipherService) cipherService).setKeySize(256); // Use Java 21's default stronger key size
    
    randomGenerator = new SecureRandomNumberGenerator();
    randomGenerator.setDefaultNextBytesSize(16); // 128 bits
  }

  @Test
  @DisplayName("Test password hashing and verification with Java 21 security providers")
  public void testPasswordHashingAndVerification() {
    // Hash a password
    String hashedPassword = passwordService.encryptPassword(TEST_PASSWORD);
    
    // Verify the password service created a valid hash
    assertThat(hashedPassword, notNullValue());
    assertThat(hashedPassword.length() > 0, is(true));
    
    // Verify the password matches its hash
    assertThat(passwordService.passwordsMatch(TEST_PASSWORD, hashedPassword), is(true));
    
    // Verify an incorrect password doesn't match
    assertThat(passwordService.passwordsMatch("WrongPassword", hashedPassword), is(false));
  }

  @Test
  @DisplayName("Test manual password hashing with salt and iterations in Java 21")
  public void testManualPasswordHashing() {
    // Create a salted hash with iterations
    Hash hash = new SimpleHash(
        Sha256Hash.ALGORITHM_NAME,
        TEST_PASSWORD,
        TEST_SALT,
        HASH_ITERATIONS);
    
    // Verify the hash properties
    assertThat(hash.getAlgorithmName(), equalTo(Sha256Hash.ALGORITHM_NAME));
    assertThat(hash.getSalt(), equalTo(ByteSource.Util.bytes(TEST_SALT)));
    assertThat(hash.getIterations(), equalTo(HASH_ITERATIONS));
    
    // Verify we can recreate the same hash with the same inputs
    Hash sameHash = new SimpleHash(
        Sha256Hash.ALGORITHM_NAME,
        TEST_PASSWORD,
        TEST_SALT,
        HASH_ITERATIONS);
    
    assertThat(hash.toHex(), equalTo(sameHash.toHex()));
    
    // Verify different salt produces different hash
    Hash differentSaltHash = new SimpleHash(
        Sha256Hash.ALGORITHM_NAME,
        TEST_PASSWORD,
        "differentSalt",
        HASH_ITERATIONS);
    
    assertThat(hash.toHex(), not(equalTo(differentSaltHash.toHex())));
  }

  @Test
  @DisplayName("Test hash service with Java 21 security providers")
  public void testHashService() {
    // Create a hash request
    HashRequest request = new HashRequest.Builder()
        .setAlgorithmName(Sha256Hash.ALGORITHM_NAME)
        .setSource(ByteSource.Util.bytes(TEST_PASSWORD))
        .setSalt(ByteSource.Util.bytes(TEST_SALT))
        .setIterations(HASH_ITERATIONS)
        .build();
    
    // Compute the hash
    Hash computedHash = hashService.computeHash(request);
    
    // Verify the hash properties
    assertThat(computedHash, notNullValue());
    assertThat(computedHash.getAlgorithmName(), equalTo(Sha256Hash.ALGORITHM_NAME));
    assertThat(computedHash.getIterations(), equalTo(HASH_ITERATIONS));
    
    // Verify the hash is consistent
    Hash secondHash = hashService.computeHash(request);
    assertThat(computedHash.toHex(), equalTo(secondHash.toHex()));
  }

  @Test
  @DisplayName("Test encryption and decryption with Java 21's updated cryptographic framework")
  public void testEncryptionAndDecryption() {
    // Generate a random key
    ByteSource key = randomGenerator.nextBytes();
    
    // Encrypt the plaintext
    ByteSource encrypted = cipherService.encrypt(TEST_PLAINTEXT.getBytes(), key.getBytes());
    
    // Verify the encrypted data is not the same as the plaintext
    assertThat(Arrays.equals(encrypted.getBytes(), TEST_PLAINTEXT.getBytes()), is(false));
    
    // Decrypt the encrypted data
    ByteSource decrypted = cipherService.decrypt(encrypted.getBytes(), key.getBytes());
    
    // Verify the decrypted data matches the original plaintext
    String decryptedText = new String(decrypted.getBytes());
    assertThat(decryptedText, equalTo(TEST_PLAINTEXT));
  }

  @Test
  @DisplayName("Test secure random number generation with Java 21")
  public void testSecureRandomNumberGeneration() {
    // Generate random bytes
    ByteSource random1 = randomGenerator.nextBytes();
    ByteSource random2 = randomGenerator.nextBytes();
    
    // Verify the random bytes are not null and have the expected length
    assertThat(random1, notNullValue());
    assertThat(random2, notNullValue());
    assertThat(random1.getBytes().length, equalTo(16)); // Should be 16 bytes (128 bits)
    
    // Verify the random bytes are different (this has an extremely small chance of failing)
    assertThat(Arrays.equals(random1.getBytes(), random2.getBytes()), is(false));
  }

  @Test
  @DisplayName("Test Base64 encoding and decoding with Java 21")
  public void testBase64EncodingAndDecoding() {
    // Encode a string to Base64
    String encoded = Base64.encodeToString(TEST_PLAINTEXT.getBytes());
    
    // Verify the encoded string is not the same as the original
    assertThat(encoded, not(equalTo(TEST_PLAINTEXT)));
    
    // Decode the Base64 string
    byte[] decoded = Base64.decode(encoded);
    
    // Verify the decoded bytes match the original plaintext
    String decodedText = new String(decoded);
    assertThat(decodedText, equalTo(TEST_PLAINTEXT));
  }

  @Test
  @DisplayName("Test JWT token creation and verification with HMAC256 signing in Java 21")
  public void testJwtHmac256Signing() {
    // Generate a secure key for HMAC-SHA256
    SecretKey key = Keys.secretKeyFor(SignatureAlgorithm.HS256);
    
    // Current time and expiration time (5 minutes from now)
    long now = System.currentTimeMillis();
    long expiration = now + 300000; // 5 minutes
    
    // Create claims for the JWT
    Map<String, Object> claims = new HashMap<>();
    claims.put("username", "testuser");
    claims.put("roles", "admin,user");
    
    // Build the JWT token
    String jwtToken = Jwts.builder()
        .setSubject("testuser")
        .setIssuedAt(new Date(now))
        .setExpiration(new Date(expiration))
        .addClaims(claims)
        .signWith(key, SignatureAlgorithm.HS256)
        .compact();
    
    // Verify the token is not null and has content
    assertThat(jwtToken, notNullValue());
    assertThat(jwtToken.length() > 0, is(true));
    
    log.info("Generated JWT token: {}", jwtToken);
    
    // Parse and verify the JWT token
    Jws<Claims> parsedToken = Jwts.parserBuilder()
        .setSigningKey(key)
        .build()
        .parseClaimsJws(jwtToken);
    
    // Verify the parsed token contains the expected claims
    Claims parsedClaims = parsedToken.getBody();
    assertThat(parsedClaims.getSubject(), equalTo("testuser"));
    assertThat(parsedClaims.get("username", String.class), equalTo("testuser"));
    assertThat(parsedClaims.get("roles", String.class), equalTo("admin,user"));
    
    // Verify the token's expiration time is correct
    assertThat(parsedClaims.getExpiration().getTime() >= expiration, is(true));
  }

  @Test
  @DisplayName("Verify Java 21 security providers are available")
  public void testSecurityProviders() {
    // List all security providers
    String providers = Arrays.toString(Security.getProviders());
    log.info("Available security providers: {}", providers);
    
    // Verify that essential providers are available
    assertThat(providers.contains("SUN"), is(true));
    assertThat(providers.contains("SunJCE"), is(true));
    assertThat(providers.contains("SunRsaSign"), is(true));
  }
}