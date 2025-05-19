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
package org.sonatype.nexus.common.filter;

import java.util.LinkedList;
import java.util.List;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnel;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toList;

/**
 * Creates a bloom filter that increases in size as the number of elements increase to keep the probability of a
 * false positive down to a minimum when mightContain is called.
 *
 * For more information on how a standard bloom filter works see here https://llimllib.github.io/bloomfilter-tutorial/
 *
 * Some stats on the probabilities can be found here https://github.com/google/guava/issues/2520#issuecomment-231233736
 *
 * @since 3.11
 */
public class ScalableBloomFilter<T>
{
  private final List<BloomFilter<T>> filters = new LinkedList<>();

  private final Funnel<? super T> funnel;

  private final int filterCapacity;

  private final double falsePositiveProbability;

  /**
   * Record to hold filter parameters for validation
   */
  private record FilterParams(int capacity, double fpp) {}

  public ScalableBloomFilter(
      final Funnel<? super T> funnel,
      final int filterCapacity,
      final double falsePositiveProbability)
  {
    // Use Pattern Matching for switch to validate parameters with more expressive error handling
    switch (new FilterParams(filterCapacity, falsePositiveProbability)) {
      case FilterParams(int capacity, double fpp) when capacity <= 0 && fpp <= 0 ->
          throw new IllegalArgumentException("filter capacity and fpp must be greater than 0");
      case FilterParams(int capacity, _) when capacity <= 0 ->
          throw new IllegalArgumentException("filter capacity must be greater than 0");
      case FilterParams(_, double fpp) when fpp <= 0 ->
          throw new IllegalArgumentException("fpp must be greater than 0");
      default -> {}
    }

    this.funnel = checkNotNull(funnel);
    this.filterCapacity = filterCapacity;
    this.falsePositiveProbability = falsePositiveProbability;
  }

  /**
   * Determines whether across all filters there is a chance that this element has already been added.
   *
   * @param input - the element to check.
   * @return whether the element may exist in the filter.
   */
  public boolean mightContain(final T input) {
    // Use Pattern Matching for switch to handle different filter states more elegantly
    return switch (filters.size()) {
      case 0 -> false; // Empty filter list can't contain anything
      case 1 -> filters.get(0).mightContain(input); // Single filter optimization
      default -> { // Multiple filters case
        for (var filter : filters) {
          if (filter.mightContain(input)) {
            yield true;
          }
        }
        yield false;
      }
    };
  }

  /**
   * Adds an element to the filter if chances are it isn't already contained (i.e. mightContain returns false).
   *
   * @param input - element to add
   * @return - whether the element was added or not
   */
  public boolean put(final T input) {
    return !mightContain(input) && getFilter().put(input);
  }

  /**
   * @return the probability of encountering a false positive.
   */
  public double expectedFpp() {
    // Use Pattern Matching to clarify probability calculation logic based on filter count
    return switch (filters.size()) {
      case 0 -> 0.0; // No filters means no false positives
      case 1 -> filters.get(0).expectedFpp(); // Single filter optimization
      default -> {
        var probabilities = filters.stream()
            .mapToDouble(BloomFilter::expectedFpp)
            .boxed()
            .collect(toList());
        
        double probabilitySum = 0.0;
        double combinatorialAnd = 0.0;

        // Calculate sum of individual probabilities
        for (int i = 0; i < probabilities.size(); i++) {
          var probability = probabilities.get(i);
          probabilitySum += probability;
          
          // Calculate pairwise combinations
          for (int j = i + 1; j < probabilities.size(); j++) {
            combinatorialAnd += (probability * probabilities.get(j));
          }
        }

        // Calculate the product of all probabilities
        var andProbability = filters.stream()
            .mapToDouble(BloomFilter::expectedFpp)
            .reduce((a, b) -> a * b)
            .getAsDouble();

        // These events are not mutually exclusive so the formula for calculating the probability is
        // P(A) + P(B) + P(C) ... - P(A and B) - P(A and C) - P(B and C) ... + P(A and B and C...)
        yield probabilitySum - combinatorialAnd + andProbability;
      }
    };
  }

  private BloomFilter<T> getFilter() {
    // Use Pattern Matching for switch to express filter creation logic more clearly
    return switch (filters.size()) {
      case 0 -> {
        // Create first filter if none exists
        var filter = createFilter();
        filters.add(filter);
        yield filter;
      }
      case var size when size == filterCapacity -> {
        // Create new filter when capacity is reached
        // expectedFpp() is an O(n) call so we create a new filter on count instead
        var filter = createFilter();
        filters.add(filter);
        yield filter;
      }
      default -> filters.get(filters.size() - 1); // Return the last filter
    };
  }

  private BloomFilter<T> createFilter() {
    // Using Java 21's improved type inference
    return BloomFilter.create(funnel, filterCapacity, falsePositiveProbability);
  }
}