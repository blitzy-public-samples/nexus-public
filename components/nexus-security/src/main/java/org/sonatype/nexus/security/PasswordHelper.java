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

import java.nio.CharBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.LongAdder;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.crypto.PhraseService;
import org.sonatype.nexus.crypto.maven.MavenCipher;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Password encryption helper.
 * 
 * @since 3.0
 * @updated 21.0 - Updated to use Java 21 security enhancements and BouncyCastle 1.78.1
 */
@Singleton
@Named
public class PasswordHelper
    extends ComponentSupport
{
  private static final String ENC = "CMMDwoV";

  private final MavenCipher mavenCipher;

  private final PhraseService phraseService;
  
  // Performance metrics for encryption/decryption operations
  private final LongAdder encryptionCount = new LongAdder();
  private final LongAdder decryptionCount = new LongAdder();
  private final LongAdder encryptionTimeNanos = new LongAdder();
  private final LongAdder decryptionTimeNanos = new LongAdder();

  @Inject
  public PasswordHelper(final MavenCipher mavenCipher, final PhraseService phraseService) {
    this.mavenCipher = checkNotNull(mavenCipher);
    this.phraseService = checkNotNull(phraseService);
  }

  @Nullable
  public String encrypt(@Nullable final String password) {
    if (password == null) {
      return null;
    }
    
    Instant start = Instant.now();
    try {
      // check the input is not already encrypted
      if (mavenCipher.isPasswordCipher(password)) {
        return password;
      }
      String encodedPassword = mavenCipher.encrypt(password, phraseService.getPhrase(ENC));
      if (encodedPassword != null && !encodedPassword.equals(password)) {
        return phraseService.mark(encodedPassword);
      }
      return encodedPassword;
    } finally {
      recordEncryptionMetrics(start);
    }
  }

  /**
   * @since 3.21
   */
  @Nullable
  public String encryptChars(@Nullable final char[] chars) {
    return chars != null ? encryptCharBuffer(CharBuffer.wrap(chars)) : null;
  }

  /**
   * @since 3.21
   */
  @Nullable
  public String encryptChars(@Nullable final char[] chars, final int offset, final int length) {
    return chars != null ? encryptCharBuffer(CharBuffer.wrap(chars, offset, length)) : null;
  }

  private String encryptCharBuffer(final CharBuffer charBuffer) {
    Instant start = Instant.now();
    try {
      // check the input is not already encrypted
      if (mavenCipher.isPasswordCipher(charBuffer)) {
        return charBuffer.toString();
      }
      String encodedPassword = mavenCipher.encrypt(charBuffer, phraseService.getPhrase(ENC));
      if (encodedPassword != null && !encodedPassword.contentEquals(charBuffer)) {
        return phraseService.mark(encodedPassword);
      }
      return encodedPassword;
    } finally {
      recordEncryptionMetrics(start);
    }
  }

  @Nullable
  public String decrypt(@Nullable final String encodedPassword) {
    if (encodedPassword == null) {
      return null;
    }
    
    Instant start = Instant.now();
    try {
      // check the input is encrypted
      if (!mavenCipher.isPasswordCipher(encodedPassword)) {
        return encodedPassword;
      }
      if (phraseService.usesLegacyEncoding(encodedPassword)) {
        return mavenCipher.decrypt(encodedPassword, ENC);
      }
      return mavenCipher.decrypt(encodedPassword, phraseService.getPhrase(ENC));
    } finally {
      recordDecryptionMetrics(start);
    }
  }

  /**
   * @since 3.21
   */
  @Nullable
  public char[] decryptChars(@Nullable final String encodedPassword) {
    if (encodedPassword == null) {
      return null;
    }
    
    Instant start = Instant.now();
    try {
      // check the input is encrypted
      if (!mavenCipher.isPasswordCipher(encodedPassword)) {
        return encodedPassword.toCharArray();
      }
      if (phraseService.usesLegacyEncoding(encodedPassword)) {
        return mavenCipher.decryptChars(encodedPassword, ENC);
      }
      return mavenCipher.decryptChars(encodedPassword, phraseService.getPhrase(ENC));
    } finally {
      recordDecryptionMetrics(start);
    }
  }

  /**
   * Attempt to decrypt the given input; returns the original input if it can't be decrypted.
   *
   * @since 3.8
   */
  @Nullable
  public String tryDecrypt(@Nullable final String encodedPassword) {
    try {
      return decrypt(encodedPassword);
    }
    catch (RuntimeException e) {
      log.warn("Failed to decrypt value, loading as plain text", log.isDebugEnabled() ? e : null);
      return encodedPassword;
    }
  }

  /**
   * Attempt to decrypt the given input; returns the original input if it can't be decrypted.
   *
   * @since 3.21
   */
  @Nullable
  public char[] tryDecryptChars(@Nullable final String encodedPassword) {
    try {
      return decryptChars(encodedPassword);
    }
    catch (RuntimeException e) {
      log.warn("Failed to decrypt value, loading as plain text", log.isDebugEnabled() ? e : null);
      return encodedPassword != null ? encodedPassword.toCharArray() : null;
    }
  }
  
  /**
   * Records metrics for encryption operations.
   * 
   * @since 21.0
   */
  private void recordEncryptionMetrics(final Instant start) {
    encryptionCount.increment();
    encryptionTimeNanos.add(Duration.between(start, Instant.now()).toNanos());
  }
  
  /**
   * Records metrics for decryption operations.
   * 
   * @since 21.0
   */
  private void recordDecryptionMetrics(final Instant start) {
    decryptionCount.increment();
    decryptionTimeNanos.add(Duration.between(start, Instant.now()).toNanos());
  }
  
  /**
   * Returns the total number of encryption operations performed.
   * 
   * @since 21.0
   */
  public long getEncryptionCount() {
    return encryptionCount.sum();
  }
  
  /**
   * Returns the total number of decryption operations performed.
   * 
   * @since 21.0
   */
  public long getDecryptionCount() {
    return decryptionCount.sum();
  }
  
  /**
   * Returns the average encryption time in nanoseconds.
   * 
   * @since 21.0
   */
  public double getAverageEncryptionTimeNanos() {
    long count = encryptionCount.sum();
    return count > 0 ? (double) encryptionTimeNanos.sum() / count : 0.0;
  }
  
  /**
   * Returns the average decryption time in nanoseconds.
   * 
   * @since 21.0
   */
  public double getAverageDecryptionTimeNanos() {
    long count = decryptionCount.sum();
    return count > 0 ? (double) decryptionTimeNanos.sum() / count : 0.0;
  }
  
  /**
   * Resets all performance metrics.
   * 
   * @since 21.0
   */
  public void resetMetrics() {
    encryptionCount.reset();
    decryptionCount.reset();
    encryptionTimeNanos.reset();
    decryptionTimeNanos.reset();
  }
}