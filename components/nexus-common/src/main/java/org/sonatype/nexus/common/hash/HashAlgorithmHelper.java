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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import com.google.common.hash.HashCode;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Helper class for calculating hash values using various algorithms.
 * This implementation supports FIPS-approved hash algorithms and providers when available.
 *
 * @since 3.35
 * @deprecated please use the appropriate ingest methods on {@code StorageFacet} and {@code FluentBlobs}
 */
@Deprecated
@Named
@Singleton
public class HashAlgorithmHelper
{
  /**
   * Set of FIPS-approved hash algorithms
   */
  private static final Set<String> FIPS_APPROVED_ALGORITHMS = new HashSet<>(Arrays.asList(
      "SHA-224", "SHA-256", "SHA-384", "SHA-512", "SHA-512/224", "SHA-512/256", "SHA3-224", 
      "SHA3-256", "SHA3-384", "SHA3-512"));

  private final int bufferSize;
  
  private final String preferredProvider;

  /**
   * Creates a new instance with the specified buffer size and no preferred provider.
   *
   * @param bufferSize the buffer size to use when reading files
   */
  @Inject
  public HashAlgorithmHelper(@Named("${nexus.calculateChecksums.bufferSize:-32768}") final int bufferSize) {
    this(bufferSize, null);
  }

  /**
   * Creates a new instance with the specified buffer size and preferred provider.
   *
   * @param bufferSize the buffer size to use when reading files
   * @param preferredProvider the name of the preferred security provider, or null for the default provider
   */
  public HashAlgorithmHelper(final int bufferSize, final String preferredProvider) {
    checkState(bufferSize > 0, String.format("Buffer size should be a positive value: %s", bufferSize));
    this.bufferSize = bufferSize;
    this.preferredProvider = preferredProvider;
  }

  /**
   * Calculate checksums by given hash algorithms.
   *
   * @param content the file to calculate checksums for
   * @param hashAlgorithms the hash algorithms to use
   * @return a map of hash algorithms to their corresponding hash codes
   * @throws IOException if an I/O error occurs
   * @throws HashAlgorithmException if a hash algorithm is not available or not supported
   */
  public Map<HashAlgorithm, HashCode> calculateChecksums(
      final File content,
      final Iterable<HashAlgorithm> hashAlgorithms) throws IOException, HashAlgorithmException
  {
    checkNotNull(content);
    checkNotNull(hashAlgorithms);

    Map<HashAlgorithm, MessageDigest> hashAlgorithmMessageDigestMap = new HashMap<>();
    for (HashAlgorithm hashAlgorithm : hashAlgorithms) {
      MessageDigest messageDigest = getInstance(hashAlgorithm.name());
      hashAlgorithmMessageDigestMap.put(hashAlgorithm, messageDigest);
    }

    return calculateChecksumsBuffered(content, hashAlgorithmMessageDigestMap, bufferSize);
  }

  /**
   * Checks if the specified algorithm is FIPS-approved.
   *
   * @param algorithm the algorithm to check
   * @return true if the algorithm is FIPS-approved, false otherwise
   */
  public boolean isFipsApprovedAlgorithm(String algorithm) {
    return FIPS_APPROVED_ALGORITHMS.contains(algorithm);
  }

  /**
   * Calculate checksums using the specified buffer size.
   */
  private Map<HashAlgorithm, HashCode> calculateChecksumsBuffered(
      final File content,
      final Map<HashAlgorithm, MessageDigest> hashAlgorithmMessageDigestMap,
      final int bufferSize) throws IOException
  {
    try (InputStream inputStream = new FileInputStream(content)) {
      byte[] buffer = new byte[bufferSize];
      int bytesRead;
      
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        if (bytesRead > 0) {
          for (MessageDigest messageDigest : hashAlgorithmMessageDigestMap.values()) {
            messageDigest.update(buffer, 0, bytesRead);
          }
        }
      }
    }

    return hashAlgorithmMessageDigestMap
        .entrySet()
        .stream()
        .collect(Collectors.toMap(
            Entry::getKey,
            x -> HashCode.fromBytes(x.getValue().digest()),
            (x, y) -> x, LinkedHashMap::new));
  }

  /**
   * Gets a MessageDigest instance for the specified algorithm, using the preferred provider if available.
   *
   * @param algorithm the hash algorithm name
   * @return a MessageDigest instance for the specified algorithm
   * @throws HashAlgorithmException if the algorithm is not available or not supported
   */
  private MessageDigest getInstance(final String algorithm) throws HashAlgorithmException {
    try {
      // If a preferred provider is specified, try to use it first
      if (preferredProvider != null) {
        try {
          return MessageDigest.getInstance(algorithm, preferredProvider);
        }
        catch (NoSuchProviderException | NoSuchAlgorithmException e) {
          // Fall back to default provider if preferred provider is not available
        }
      }
      
      // Try to find a FIPS-approved provider if available
      Optional<Provider> fipsProvider = findFipsProvider();
      if (fipsProvider.isPresent()) {
        try {
          return MessageDigest.getInstance(algorithm, fipsProvider.get());
        }
        catch (NoSuchAlgorithmException e) {
          // Fall back to default provider if algorithm is not available in FIPS provider
        }
      }
      
      // Use the default provider as a fallback
      return MessageDigest.getInstance(algorithm);
    }
    catch (NoSuchAlgorithmException e) {
      throw new HashAlgorithmException("Hash algorithm not available: " + algorithm, e);
    }
  }
  
  /**
   * Attempts to find a FIPS-approved security provider.
   *
   * @return an Optional containing a FIPS-approved provider if available, or empty if none is found
   */
  private Optional<Provider> findFipsProvider() {
    // Look for common FIPS provider names
    for (String providerName : Arrays.asList("SunPKCS11-FIPS", "BCFIPS", "SUN-FIPS")) {
      Provider provider = Security.getProvider(providerName);
      if (provider != null) {
        return Optional.of(provider);
      }
    }
    
    // Check if any installed provider has "FIPS" in its name or info
    for (Provider provider : Security.getProviders()) {
      if (provider.getName().toUpperCase().contains("FIPS") || 
          provider.getInfo().toUpperCase().contains("FIPS")) {
        return Optional.of(provider);
      }
    }
    
    return Optional.empty();
  }
}