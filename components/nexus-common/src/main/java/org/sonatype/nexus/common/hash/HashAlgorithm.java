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
package org.sonatype.nexus.common.hash;

import java.util.Map;
import java.util.Optional;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Optional.ofNullable;

/**
 * A hash algorithm name paired with a {@link HashFunction}.
 *
 * @since 3.0
 */
public class HashAlgorithm
{
  /**
   * MD5 hash algorithm.
   * 
   * WARNING: MD5 is not cryptographically secure or collision-resistant and is not recommended for use in new code.
   * It should be used for legacy compatibility reasons only. MD5 is not FIPS-approved.
   */
  public static final HashAlgorithm MD5 = new HashAlgorithm("md5", Hashing.md5());

  /**
   * SHA-1 hash algorithm.
   * 
   * WARNING: SHA-1 is not cryptographically secure or collision-resistant and is not recommended for use in new code.
   * It should be used for legacy compatibility reasons only. SHA-1 is not FIPS-approved for most use cases.
   */
  public static final HashAlgorithm SHA1 = new HashAlgorithm("sha1", Hashing.sha1());

  /**
   * SHA-256 hash algorithm.
   * 
   * SHA-256 is a FIPS-approved secure hash algorithm from the SHA-2 family.
   * Recommended for general use where cryptographic security is required.
   */
  public static final HashAlgorithm SHA256 = new HashAlgorithm("sha256", Hashing.sha256());

  /**
   * SHA-512 hash algorithm.
   * 
   * SHA-512 is a FIPS-approved secure hash algorithm from the SHA-2 family.
   * Provides stronger security than SHA-256 with better performance on 64-bit platforms.
   */
  public static final HashAlgorithm SHA512 = new HashAlgorithm("sha512", Hashing.sha512());

  /**
   * SHA3-256 hash algorithm.
   * 
   * SHA3-256 is a FIPS-approved secure hash algorithm from the SHA-3 family.
   * It provides strong security properties and resistance against quantum computing attacks.
   * Available since Java 9.
   * 
   * Note: This implementation uses Java's MessageDigest directly as Guava doesn't provide a native SHA3 implementation.
   */
  public static final HashAlgorithm SHA3_256 = new HashAlgorithm("sha3-256", 
    new MessageDigestHashFunction("SHA3-256", 32));

  /**
   * SHA3-512 hash algorithm.
   * 
   * SHA3-512 is a FIPS-approved secure hash algorithm from the SHA-3 family.
   * It provides stronger security properties than SHA3-256 with a larger digest size.
   * Available since Java 9.
   * 
   * Note: This implementation uses Java's MessageDigest directly as Guava doesn't provide a native SHA3 implementation.
   */
  public static final HashAlgorithm SHA3_512 = new HashAlgorithm("sha3-512", 
    new MessageDigestHashFunction("SHA3-512", 64));

  /**
   * Map of all supported hash algorithms by name.
   * 
   * Note: SHA3 algorithms are included conditionally based on JVM support.
   */
  public static final Map<String, HashAlgorithm> ALL_HASH_ALGORITHMS;
  
  /**
   * Implementation of HashFunction that delegates to Java's MessageDigest.
   * This is used for algorithms not directly supported by Guava's Hashing class,
   * such as SHA-3 family algorithms.
   */
  private static class MessageDigestHashFunction implements HashFunction {
    private final String algorithm;
    private final int bits;
    
    /**
     * Creates a new MessageDigestHashFunction.
     *
     * @param algorithm the MessageDigest algorithm name
     * @param bytes the size of the digest in bytes
     */
    public MessageDigestHashFunction(String algorithm, int bytes) {
      this.algorithm = algorithm;
      this.bits = bytes * 8;
    }
    
    @Override
    public Hasher newHasher() {
      try {
        return new MessageDigestHasher(MessageDigest.getInstance(algorithm));
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("Algorithm " + algorithm + " not available", e);
      }
    }
    
    @Override
    public int bits() {
      return bits;
    }
    
    @Override
    public HashCode hashBytes(byte[] input) {
      try {
        return HashCode.fromBytes(MessageDigest.getInstance(algorithm).digest(input));
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("Algorithm " + algorithm + " not available", e);
      }
    }
    
    @Override
    public HashCode hashString(CharSequence input, Charset charset) {
      return hashBytes(input.toString().getBytes(charset));
    }
    
    @Override
    public HashCode hashInt(int input) {
      Hasher hasher = newHasher();
      hasher.putInt(input);
      return hasher.hash();
    }
    
    @Override
    public HashCode hashLong(long input) {
      Hasher hasher = newHasher();
      hasher.putLong(input);
      return hasher.hash();
    }
  }
  
  /**
   * Implementation of Hasher that delegates to Java's MessageDigest.
   */
  private static class MessageDigestHasher implements Hasher {
    private final MessageDigest digest;
    
    public MessageDigestHasher(MessageDigest digest) {
      this.digest = digest;
    }
    
    @Override
    public Hasher putByte(byte b) {
      digest.update(b);
      return this;
    }
    
    @Override
    public Hasher putBytes(byte[] bytes) {
      digest.update(bytes);
      return this;
    }
    
    @Override
    public Hasher putBytes(byte[] bytes, int off, int len) {
      digest.update(bytes, off, len);
      return this;
    }
    
    @Override
    public HashCode hash() {
      return HashCode.fromBytes(digest.digest());
    }
    
    // Implement other Hasher methods by delegating to putBytes
    // These implementations match Guava's behavior
    
    @Override
    public Hasher putShort(short s) {
      putByte((byte) (s >> 8));
      putByte((byte) s);
      return this;
    }
    
    @Override
    public Hasher putInt(int i) {
      putByte((byte) (i >> 24));
      putByte((byte) (i >> 16));
      putByte((byte) (i >> 8));
      putByte((byte) i);
      return this;
    }
    
    @Override
    public Hasher putLong(long l) {
      putInt((int) (l >> 32));
      putInt((int) l);
      return this;
    }
    
    @Override
    public Hasher putFloat(float f) {
      return putInt(Float.floatToRawIntBits(f));
    }
    
    @Override
    public Hasher putDouble(double d) {
      return putLong(Double.doubleToRawLongBits(d));
    }
    
    @Override
    public Hasher putBoolean(boolean b) {
      return putByte(b ? (byte) 1 : (byte) 0);
    }
    
    @Override
    public Hasher putChar(char c) {
      return putShort((short) c);
    }
    
    @Override
    public Hasher putString(CharSequence charSequence, Charset charset) {
      return putBytes(charSequence.toString().getBytes(charset));
    }
    
    @Override
    public <T> Hasher putObject(T instance, com.google.common.hash.Funnel<? super T> funnel) {
      funnel.funnel(instance, this);
      return this;
    }
  }
  
  static {
    // Initialize the map with algorithms that are always available
    ImmutableMap.Builder<String, HashAlgorithm> builder = ImmutableMap.builder();
    builder.put(MD5.name, MD5)
           .put(SHA1.name, SHA1)
           .put(SHA256.name, SHA256)
           .put(SHA512.name, SHA512);
    
    // Add SHA3 algorithms if supported by the JVM (Java 9+)
    try {
      MessageDigest.getInstance("SHA3-256");
      builder.put(SHA3_256.name, SHA3_256)
             .put(SHA3_512.name, SHA3_512);
    } catch (NoSuchAlgorithmException e) {
      // SHA3 algorithms not supported in this JVM, skip them
    }
    
    ALL_HASH_ALGORITHMS = builder.build();
  }
  
  private final String name;

  private final HashFunction function;

  /**
   * Creates a new HashAlgorithm with the specified name and hash function.
   *
   * @param name the algorithm name (lowercase, e.g., "sha256")
   * @param function the Guava HashFunction implementation
   */
  public HashAlgorithm(String name, HashFunction function) {
    this.name = checkNotNull(name);
    this.function = checkNotNull(function);
  }

  /**
   * Returns the algorithm name.
   *
   * @return the algorithm name (lowercase, e.g., "sha256")
   */
  public String name() {
    return name;
  }

  /**
   * Returns the hash function implementation.
   *
   * @return the Guava HashFunction implementation
   */
  public HashFunction function() {
    return function;
  }

  /**
   * Returns the HashAlgorithm for the specified algorithm name, if available.
   *
   * @param algorithm the algorithm name (case-sensitive)
   * @return the HashAlgorithm if found, or empty if not available
   */
  public static Optional<HashAlgorithm> getHashAlgorithm(final String algorithm) {
    return ofNullable(ALL_HASH_ALGORITHMS.get(algorithm));
  }