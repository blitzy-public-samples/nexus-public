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
package org.sonatype.nexus.repository.replication;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.sonatype.nexus.common.hash.HashAlgorithm;

import org.apache.commons.lang.StringUtils;
import com.google.common.hash.HashCode;

/**
 * Utility class for replication operations, providing methods for checksum verification
 * and management using Java 21 Virtual Threads for parallel processing.
 */
public class ReplicationUtils {

  public static final String CHECKSUM = "checksum";

  private ReplicationUtils() {}

  private static final List<HashAlgorithm> HASH_ALGORITHMS = Arrays.asList(
    HashAlgorithm.MD5,
    HashAlgorithm.SHA256,
    HashAlgorithm.SHA1,
    HashAlgorithm.SHA512);
    
  // Thread-safe cache for frequently accessed checksums
  private static final ConcurrentMap<String, Map<HashAlgorithm, HashCode>> CHECKSUM_CACHE = new ConcurrentHashMap<>();

  /**
   * Retrieves checksums from properties map.
   *
   * @param attributesMap The map containing checksum attributes
   * @return A map of hash algorithms to their corresponding hash codes
   */
  public static Map<HashAlgorithm, HashCode> getChecksumsFromProperties(final Map<String, Object> attributesMap) {
    Map<HashAlgorithm, HashCode> checksums = new HashMap<>();
    for (HashAlgorithm hashAlgorithm : HASH_ALGORITHMS) {
      getChecksumAttribute(attributesMap, hashAlgorithm.name())
        .ifPresent(value -> checksums.put(hashAlgorithm, HashCode.fromString(value)));
    }
    return checksums;
  }

  /**
   * Retrieves a checksum attribute from the attributes map.
   *
   * @param attributesMap The map containing checksum attributes
   * @param name The name of the checksum algorithm
   * @return An Optional containing the checksum value if present
   */
  private static Optional<String> getChecksumAttribute(final Map<String, Object> attributesMap, final String name) {
    try {
      String value = ((Map<String, Object>) attributesMap.get(CHECKSUM)).get(name).toString();
      if (StringUtils.isEmpty(value)) {
        return Optional.empty();
      }
      else {
        return Optional.of(value);
      }
    }
    catch (Exception e) {
      return Optional.empty();
    }
  }
  
  /**
   * Retrieves checksums from properties map with caching for improved performance.
   * Uses a thread-safe concurrent map to cache frequently accessed checksums.
   *
   * @param attributesMap The map containing checksum attributes
   * @param cacheKey A unique key to identify this set of checksums in the cache
   * @return A map of hash algorithms to their corresponding hash codes
   */
  public static Map<HashAlgorithm, HashCode> getChecksumsFromPropertiesCached(
      final Map<String, Object> attributesMap, final String cacheKey) {
    
    return CHECKSUM_CACHE.computeIfAbsent(cacheKey, k -> getChecksumsFromProperties(attributesMap));
  }
  
  /**
   * Verifies if two checksum maps are equal, optimized for threaded environments.
   * Uses an optimized comparison strategy that avoids unnecessary object creation.
   *
   * @param checksums1 First checksum map
   * @param checksums2 Second checksum map
   * @return true if the checksums match, false otherwise
   */
  public static boolean verifyChecksums(
      final Map<HashAlgorithm, HashCode> checksums1, 
      final Map<HashAlgorithm, HashCode> checksums2) {
    
    if (checksums1 == checksums2) {
      return true; // Same instance, must be equal
    }
    
    if (checksums1 == null || checksums2 == null || checksums1.size() != checksums2.size()) {
      return false;
    }
    
    // Optimized comparison that avoids creating intermediate collections
    return checksums1.entrySet().stream()
        .allMatch(entry -> {
          HashCode hash2 = checksums2.get(entry.getKey());
          return hash2 != null && entry.getValue().equals(hash2);
        });
  }
  
  /**
   * Verifies multiple checksums in parallel using Virtual Threads.
   * This method leverages Java 21 Virtual Threads for efficient concurrent processing.
   *
   * @param checksumPairs List of checksum pairs to verify
   * @return Map of verification results for each pair
   */
  public static Map<String, Boolean> verifyChecksumsInParallel(
      final Map<String, ChecksumPair> checksumPairs) {
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      
      // Submit all verification tasks to the virtual thread executor
      Map<String, Future<Boolean>> futures = checksumPairs.entrySet().stream()
          .collect(Collectors.toMap(
              Map.Entry::getKey,
              entry -> executor.submit(() -> verifyChecksums(
                  entry.getValue().getSource(), 
                  entry.getValue().getTarget())
              )
          ));
      
      // Collect results as they complete
      return futures.entrySet().stream()
          .collect(Collectors.toMap(
              Map.Entry::getKey,
              entry -> {
                try {
                  return entry.getValue().get();
                } catch (Exception e) {
                  // Log exception if needed
                  return false; // Consider verification failed if exception occurs
                }
              }
          ));
    }
  }
  
  /**
   * Processes a batch of checksums in parallel using Virtual Threads.
   * Applies the provided function to each checksum map concurrently.
   *
   * @param <R> The result type
   * @param checksumMaps Map of checksum maps to process
   * @param processor Function to apply to each checksum map
   * @return Map of processing results for each checksum map
   */
  public static <R> Map<String, R> processChecksumsInParallel(
      final Map<String, Map<HashAlgorithm, HashCode>> checksumMaps,
      final Function<Map<HashAlgorithm, HashCode>, R> processor) {
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      
      // Submit all processing tasks to the virtual thread executor
      Map<String, Future<R>> futures = checksumMaps.entrySet().stream()
          .collect(Collectors.toMap(
              Map.Entry::getKey,
              entry -> executor.submit(() -> processor.apply(entry.getValue()))
          ));
      
      // Collect results as they complete
      return futures.entrySet().stream()
          .collect(Collectors.toMap(
              Map.Entry::getKey,
              entry -> {
                try {
                  return entry.getValue().get();
                } catch (Exception e) {
                  // Log exception if needed
                  return null; // Return null if processing fails
                }
              }
          ));
    }
  }
  
  /**
   * Container class for a pair of checksum maps to be compared.
   */
  public static class ChecksumPair {
    private final Map<HashAlgorithm, HashCode> source;
    private final Map<HashAlgorithm, HashCode> target;
    
    public ChecksumPair(Map<HashAlgorithm, HashCode> source, Map<HashAlgorithm, HashCode> target) {
      this.source = source;
      this.target = target;
    }
    
    public Map<HashAlgorithm, HashCode> getSource() {
      return source;
    }
    
    public Map<HashAlgorithm, HashCode> getTarget() {
      return target;
    }
  }
}