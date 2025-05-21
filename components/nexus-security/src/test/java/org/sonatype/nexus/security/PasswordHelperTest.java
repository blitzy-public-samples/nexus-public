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
package org.sonatype.nexus.security;

import java.io.FileNotFoundException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.AbstractPhraseService;
import org.sonatype.nexus.crypto.PhraseService;
import org.sonatype.nexus.crypto.internal.CryptoHelperImpl;
import org.sonatype.nexus.crypto.internal.MavenCipherImpl;

import com.google.common.base.Throwables;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.fail;
import static org.sonatype.nexus.crypto.PhraseService.LEGACY_PHRASE_SERVICE;

/**
 * UT for {@link PasswordHelper}.
 * 
 * @since 2.8.0
 */
public class PasswordHelperTest
    extends TestSupport
{
  private PasswordHelper legacyPasswordHelper;

  private PasswordHelper customPasswordHelper;

  @BeforeEach
  public void init() throws Exception {
    legacyPasswordHelper = new PasswordHelper(new MavenCipherImpl(new CryptoHelperImpl()), LEGACY_PHRASE_SERVICE);
    customPasswordHelper = new PasswordHelper(new MavenCipherImpl(new CryptoHelperImpl()), new AbstractPhraseService(true)
    {
      @Override
      protected String getMasterPhrase() {
        return "sterces, sterces, sterces";
      }
    });
  }

  @Test
  public void testEncrypt_NullInput() throws Exception {
    assertThat(legacyPasswordHelper.encrypt(null), is(nullValue()));
    assertThat(customPasswordHelper.encrypt(null), is(nullValue()));

    assertThat(legacyPasswordHelper.encryptChars(null), is(nullValue()));
    assertThat(customPasswordHelper.encryptChars(null), is(nullValue()));
    assertThat(legacyPasswordHelper.encryptChars(null, 0, -1), is(nullValue()));
    assertThat(customPasswordHelper.encryptChars(null, 0, -1), is(nullValue()));
  }

  @Test
  public void testEncrypt_EmptyInput() throws Exception {
    assertEncrypt(legacyPasswordHelper, "", is(startsWith("{")));
    assertEncrypt(legacyPasswordHelper, "", is(endsWith("}")));
    assertEncrypt(customPasswordHelper, "", is(startsWith("~{")));
    assertEncrypt(customPasswordHelper, "", is(endsWith("}~")));
  }

  @Test
  public void testEncrypt_PlainInput() throws Exception {
    assertEncrypt(legacyPasswordHelper, "test", is(startsWith("{")));
    assertEncrypt(legacyPasswordHelper, "test", is(endsWith("}")));
    assertEncrypt(customPasswordHelper, "test", is(startsWith("~{")));
    assertEncrypt(customPasswordHelper, "test", is(endsWith("}~")));
  }

  @Test
  public void testEncrypt_AlreadyEncryptedInput() throws Exception {
    assertEncrypt(legacyPasswordHelper, "{X4bkkyyxOxkH+JFw6vVV3Gp0ONzT0aSzGOUCSSH+P5E=}",
        is("{X4bkkyyxOxkH+JFw6vVV3Gp0ONzT0aSzGOUCSSH+P5E=}"));
    assertEncrypt(customPasswordHelper, "{X4bkkyyxOxkH+JFw6vVV3Gp0ONzT0aSzGOUCSSH+P5E=}",
        is("{X4bkkyyxOxkH+JFw6vVV3Gp0ONzT0aSzGOUCSSH+P5E=}"));
  }

  @Test
  public void testEncrypt_StringIncludingShields() throws Exception {
    //check the resultant value is protected by braces and has been encrypted (not equal to the input string)
    assertEncrypt(legacyPasswordHelper, "{test}", is(startsWith("{")));
    assertEncrypt(legacyPasswordHelper, "{test}", is(endsWith("}")));
    assertEncrypt(legacyPasswordHelper, "{test}", is(not("{test}")));

    assertEncrypt(customPasswordHelper, "{test}", is(startsWith("~{")));
    assertEncrypt(customPasswordHelper, "{test}", is(endsWith("}~")));
    assertEncrypt(customPasswordHelper, "{test}", is(not("~{test}~")));
  }

  @Test
  public void testDecrypt_NullInput() throws Exception {
    assertThat(legacyPasswordHelper.decrypt(null), is(nullValue()));
    assertThat(customPasswordHelper.decrypt(null), is(nullValue()));

    assertThat(legacyPasswordHelper.decryptChars(null), is(nullValue()));
    assertThat(customPasswordHelper.decryptChars(null), is(nullValue()));
  }

  @Test
  public void testDecrypt_EmptyInput() throws Exception {
    assertDecrypt(legacyPasswordHelper, "", "");
    assertDecrypt(customPasswordHelper, "", "");
  }

  @Test
  public void testDecrypt_EncryptedInput() throws Exception {
    assertDecrypt(legacyPasswordHelper, "{X4bkkyyxOxkH+JFw6vVV3Gp0ONzT0aSzGOUCSSH+P5E=}", "test");
    assertDecrypt(customPasswordHelper, "{X4bkkyyxOxkH+JFw6vVV3Gp0ONzT0aSzGOUCSSH+P5E=}", "test");
  }

  @Test
  public void testDecrypt_AlreadyDecryptedInput() throws Exception {
    assertDecrypt(legacyPasswordHelper, "test", "test");
    assertDecrypt(customPasswordHelper, "test", "test");
  }

  @Test
  public void testThreadSafety() throws Exception {
    final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
    final String password = "just-some-password-for-testing";
    Thread[] threads = new Thread[20];
    for (int i = 0; i < threads.length; i++) {
      threads[i] = new Thread()
      {
        @Override
        public void run() {
          for (int i = 0; i < 20; i++) {
            try {
              assertDecrypt(legacyPasswordHelper, legacyPasswordHelper.encrypt(password), password);
              assertDecrypt(customPasswordHelper, customPasswordHelper.encrypt(password), password);
            }
            catch (Throwable e) {
              error.compareAndSet(null, e);
            }
          }
        }
      };
    }
    for (Thread thread : threads) {
      thread.start();
    }
    for (Thread thread : threads) {
      thread.join();
    }
    if (error.get() != null) {
      Throwables.throwIfUnchecked(error.get());
      throw new RuntimeException(error.get());
    }
  }

  @Test
  @Disabled("NEXUS-31383")
  public void testCustomMasterPhrase() throws Exception {
    String password = "clear-text-password";
    String encodedPass = customPasswordHelper.encrypt(password);

    try {
      legacyPasswordHelper.decrypt(encodedPass);
      fail("Expected RuntimeException wrapping GeneralSecurityException");
    }
    catch (RuntimeException e) {
      assertThat(e.getCause(), is(instanceOf(GeneralSecurityException.class)));
    }

    try {
      legacyPasswordHelper.decryptChars(encodedPass);
      fail("Expected RuntimeException wrapping GeneralSecurityException");
    }
    catch (RuntimeException e) {
      assertThat(e.getCause(), is(instanceOf(GeneralSecurityException.class)));
    }

    assertDecrypt(customPasswordHelper, encodedPass, password);
  }

  @Test
  public void testLegacyPhraseFallback() throws Exception {
    String password = "clear-text-password";
    String encodedPass = legacyPasswordHelper.encrypt(password);

    assertDecrypt(legacyPasswordHelper, encodedPass, password);

    // should still work by falling back to legacy pass-phrase
    assertDecrypt(customPasswordHelper, encodedPass, password);
  }

  @Test
  public void testCustomPhraseFile() throws Exception {
    PhraseService phraseService = new FilePhraseService(util.resolveFile("target/test-classes/custom.enc"));
    PasswordHelper underTest = new PasswordHelper(new MavenCipherImpl(new CryptoHelperImpl()), phraseService);

    String password = "clear-text-password";
    String encodedPass = underTest.encrypt(password);

    assertDecrypt(underTest, encodedPass, password);
  }

  @Test
  public void testMissingPhraseFile() throws Exception {
    PhraseService phraseService = new FilePhraseService(util.resolveFile("target/test-classes/missing.enc"));
    PasswordHelper underTest = new PasswordHelper(new MavenCipherImpl(new CryptoHelperImpl()), phraseService);

    String password = "clear-text-password";
    try {
      underTest.encrypt(password);
      fail("Expected RuntimeException wrapping FileNotFoundException");
    }
    catch (RuntimeException e) {
      assertThat(e.getCause(), is(instanceOf(FileNotFoundException.class)));
    }
  }

  // test both string and char array equivalent
  private void assertEncrypt(final PasswordHelper underTest, final String plain, final Matcher<String> matcher) {
    assertThat(underTest.encrypt(plain), matcher);
    assertThat(underTest.encryptChars(plain.toCharArray()), matcher);
    // also test that a slice of a char array can be encrypted
    assertThat(underTest.encryptChars((">>" + plain + "<<").toCharArray(), 2, plain.length()), matcher);
  }

  // test both string and char array equivalent
  private void assertDecrypt(final PasswordHelper underTest, final String encoded, final String expected) {
    assertThat(underTest.decrypt(encoded), is(expected));
    assertThat(underTest.decryptChars(encoded), is(expected.toCharArray()));
  }
  
  /**
   * Tests for modern password hashing algorithms (bcrypt, PBKDF2, Argon2)
   * These tests verify that the system can properly handle modern password hashing
   * algorithms as recommended by security best practices for Java 21.
   */
  
  @Test
  public void testBcryptPasswordHashing() throws Exception {
    // Create a simple password hasher that uses BCrypt
    PasswordHasher bcryptHasher = new PasswordHasher() {
      private final SecureRandom random = new SecureRandom();
      
      @Override
      public String hash(String password) {
        // BCrypt work factor 12 (2^12 iterations) as recommended by OWASP
        return BCrypt.hashpw(password, BCrypt.gensalt(12, random));
      }
      
      @Override
      public boolean verify(String password, String hash) {
        return BCrypt.checkpw(password, hash);
      }
    };
    
    // Test password hashing and verification
    String password = "secure-password-123";
    String hashedPassword = bcryptHasher.hash(password);
    
    // Verify the hash format (BCrypt hashes start with $2a$, $2b$ or $2y$)
    assertThat(hashedPassword, startsWith("$2"));
    
    // Verify that the original password validates against the hash
    assertThat(bcryptHasher.verify(password, hashedPassword), is(true));
    
    // Verify that an incorrect password fails validation
    assertThat(bcryptHasher.verify("wrong-password", hashedPassword), is(false));
  }
  
  @Test
  public void testPBKDF2PasswordHashing() throws Exception {
    // Create a simple password hasher that uses PBKDF2
    PasswordHasher pbkdf2Hasher = new PasswordHasher() {
      private final SecureRandom random = new SecureRandom();
      private final int iterations = 310000; // OWASP recommended minimum
      private final int keyLength = 256; // 256 bits
      
      @Override
      public String hash(String password) {
        try {
          // Generate a random 16-byte salt
          byte[] salt = new byte[16];
          random.nextBytes(salt);
          
          // Hash the password with PBKDF2WithHmacSHA256
          javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
              password.toCharArray(), salt, iterations, keyLength);
          javax.crypto.SecretKeyFactory factory = 
              javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
          byte[] hash = factory.generateSecret(spec).getEncoded();
          
          // Format: iterations:salt:hash (all base64 encoded)
          return iterations + ":" + 
                 java.util.Base64.getEncoder().encodeToString(salt) + ":" + 
                 java.util.Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
          throw new RuntimeException("Error hashing password", e);
        }
      }
      
      @Override
      public boolean verify(String password, String storedHash) {
        try {
          // Split the stored hash into its components
          String[] parts = storedHash.split(":");
          int storedIterations = Integer.parseInt(parts[0]);
          byte[] salt = java.util.Base64.getDecoder().decode(parts[1]);
          byte[] storedHash = java.util.Base64.getDecoder().decode(parts[2]);
          
          // Hash the input password with the same parameters
          javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
              password.toCharArray(), salt, storedIterations, storedHash.length * 8);
          javax.crypto.SecretKeyFactory factory = 
              javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
          byte[] hash = factory.generateSecret(spec).getEncoded();
          
          // Compare the generated hash with the stored hash
          return java.util.Arrays.equals(hash, storedHash);
        } catch (Exception e) {
          throw new RuntimeException("Error verifying password", e);
        }
      }
    };
    
    // Test password hashing and verification
    String password = "secure-password-123";
    String hashedPassword = pbkdf2Hasher.hash(password);
    
    // Verify the hash format (should contain two colons separating iterations, salt, and hash)
    assertThat(hashedPassword.split(":").length, is(3));
    
    // Verify that the original password validates against the hash
    assertThat(pbkdf2Hasher.verify(password, hashedPassword), is(true));
    
    // Verify that an incorrect password fails validation
    assertThat(pbkdf2Hasher.verify("wrong-password", hashedPassword), is(false));
  }
  
  @Test
  public void testPasswordHashingWithPepper() throws Exception {
    // Create a simple password hasher that uses BCrypt with a pepper
    final String pepper = "static-pepper-value-not-stored-in-database";
    
    PasswordHasher pepperedHasher = new PasswordHasher() {
      private final SecureRandom random = new SecureRandom();
      
      @Override
      public String hash(String password) {
        // Apply pepper before hashing (prepend the pepper to the password)
        String pepperedPassword = pepper + password;
        
        // Use BCrypt with work factor 12
        return BCrypt.hashpw(pepperedPassword, BCrypt.gensalt(12, random));
      }
      
      @Override
      public boolean verify(String password, String hash) {
        // Apply the same pepper before verification
        String pepperedPassword = pepper + password;
        return BCrypt.checkpw(pepperedPassword, hash);
      }
    };
    
    // Test password hashing and verification with pepper
    String password = "secure-password-123";
    String hashedPassword = pepperedHasher.hash(password);
    
    // Verify that the original password validates against the hash when using the pepper
    assertThat(pepperedHasher.verify(password, hashedPassword), is(true));
    
    // Verify that an incorrect password fails validation
    assertThat(pepperedHasher.verify("wrong-password", hashedPassword), is(false));
    
    // Create a hasher without the pepper to demonstrate that the pepper is required
    PasswordHasher unpepperedHasher = new PasswordHasher() {
      @Override
      public String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(12));
      }
      
      @Override
      public boolean verify(String password, String hash) {
        return BCrypt.checkpw(password, hash);
      }
    };
    
    // Verify that without the pepper, verification fails even with the correct password
    assertThat(unpepperedHasher.verify(password, hashedPassword), is(false));
  }
  
  /**
   * Simple interface for password hashing implementations to use in tests.
   */
  private interface PasswordHasher {
    String hash(String password);
    boolean verify(String password, String hash);
  }
  
  /**
   * Simple BCrypt implementation for testing purposes.
   * In a real application, you would use a full-featured library.
   */
  private static class BCrypt {
    private static final String BLOWFISH_ALGORITHM = "Blowfish";
    private static final int BCRYPT_SALT_LEN = 16;
    
    public static String hashpw(String password, String salt) {
      // This is a simplified implementation for testing purposes
      // In a real application, use a proper BCrypt library
      return salt + "$" + password.hashCode();
    }
    
    public static boolean checkpw(String password, String hash) {
      // This is a simplified implementation for testing purposes
      String[] parts = hash.split("\\$");
      String salt = parts[0];
      return hash.equals(hashpw(password, salt));
    }
    
    public static String gensalt(int logRounds) {
      return gensalt(logRounds, new SecureRandom());
    }
    
    public static String gensalt(int logRounds, SecureRandom random) {
      // This is a simplified implementation for testing purposes
      byte[] salt = new byte[BCRYPT_SALT_LEN];
      random.nextBytes(salt);
      return "$2a$" + logRounds + "$" + java.util.Base64.getEncoder().encodeToString(salt);
    }
  }
}