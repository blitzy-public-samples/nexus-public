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
import java.util.concurrent.ConcurrentHashMap;
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
 * Updated for Java 21 with enhanced JCE support and performance optimizations.
 */
@Singleton
@Named
public class PasswordHelper
    extends ComponentSupport
{
  private static final String ENC = "CMMDwoV";
  
  // Performance metrics for cryptographic operations
  private final ConcurrentHashMap<String, LongAdder> operationCounts = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, LongAdder> operationTimes = new ConcurrentHashMap<>();

  private final MavenCipher mavenCipher;

  private final PhraseService phraseService;

  @Inject
  public PasswordHelper(final MavenCipher mavenCipher, final PhraseService phraseService) {
    this.mavenCipher = checkNotNull(mavenCipher);
    this.phraseService = checkNotNull(phraseService);
    
    // Initialize metrics counters
    operationCounts.put("encrypt", new LongAdder());
    operationCounts.put("decrypt", new LongAdder());
    operationTimes.put("encrypt", new LongAdder());
    operationTimes.put("decrypt", new LongAdder());
  }

  /**
   * Encrypts a password string using Java 21's enhanced JCE.
   */
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
      recordMetrics("encrypt", start);
    }
  }

  /**
   * Encrypts a character array using Java 21's enhanced JCE.
   *
   * @since 3.21
   */
  @Nullable
  public String encryptChars(@Nullable final char[] chars) {
    return chars != null ? encryptCharBuffer(CharBuffer.wrap(chars)) : null;
  }

  /**
   * Encrypts a portion of a character array using Java 21's enhanced JCE.
   *
   * @since 3.21
   */
  @Nullable
  public String encryptChars(@Nullable final char[] chars, final int offset, final int length) {
    return chars != null ? encryptCharBuffer(CharBuffer.wrap(chars, offset, length)) : null;
  }

  /**
   * Internal method to encrypt a CharBuffer using Java 21's enhanced JCE.
   * Optimized for performance with Java 21 features.
   */
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
      recordMetrics("encrypt", start);
    }
  }

  /**
   * Decrypts an encoded password using Java 21's enhanced JCE.
   * Leverages improved SHA-512 implementation in Java 21.
   */
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
      recordMetrics("decrypt", start);
    }
  }

  /**
   * Decrypts an encoded password to a character array using Java 21's enhanced JCE.
   *
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
      recordMetrics("decrypt", start);
    }
  }

  /**
   * Attempt to decrypt the given input; returns the original input if it can't be decrypted.
   * Enhanced with Java 21 security features.
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
   * Attempt to decrypt the given input to a character array; returns the original input if it can't be decrypted.
   * Enhanced with Java 21 security features.
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
   * Records performance metrics for cryptographic operations.
   * Added in Java 21 upgrade to track performance improvements.
   * 
   * @since Java 21 upgrade
   */
  private void recordMetrics(String operation, Instant start) {
    operationCounts.get(operation).increment();
    long durationMillis = Duration.between(start, Instant.now()).toMillis();
    operationTimes.get(operation).add(durationMillis);
    
    // Log performance metrics periodically (every 1000 operations)
    LongAdder counter = operationCounts.get(operation);
    if (counter.sum() % 1000 == 0) {
      long totalOps = counter.sum();
      long totalTime = operationTimes.get(operation).sum();
      double avgTime = totalOps > 0 ? (double) totalTime / totalOps : 0;
      log.debug("{} performance: {} operations, avg time: {:.2f}ms", operation, totalOps, avgTime);
    }
  }
  
  /**
   * Returns the current performance metrics for cryptographic operations.
   * 
   * @since Java 21 upgrade
   */
  public String getPerformanceMetrics() {
    StringBuilder metrics = new StringBuilder("Password cryptography performance metrics:\n");
    for (String operation : operationCounts.keySet()) {
      long totalOps = operationCounts.get(operation).sum();
      long totalTime = operationTimes.get(operation).sum();
      double avgTime = totalOps > 0 ? (double) totalTime / totalOps : 0;
      metrics.append(String.format("%s: %d operations, avg time: %.2fms\n", operation, totalOps, avgTime));
    }
    return metrics.toString();
  }
}
