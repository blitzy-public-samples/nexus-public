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
package org.sonatype.nexus.common.sequence;

import java.security.SecureRandom;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * {@link SplittableRandom} that automatically splits itself across threads.
 * 
 * <p>This implementation leverages Java 21's {@link RandomGenerator} interface
 * and improvements in {@link SplittableRandom} and {@link SecureRandom}.
 * It provides thread-local random number generation with enhanced security
 * and performance characteristics.</p>
 * 
 * <p>In Java 21, this implementation benefits from improved random number generation
 * algorithms and better thread safety mechanisms. It also provides access to the
 * stream-producing methods defined in the {@link RandomGenerator} interface.</p>
 *
 * @since 3.19
 */
public class ThreadLocalSplittableRandom
    extends ThreadLocal<SplittableRandom>
    implements RandomGenerator
{
  // Use SecureRandom to generate a truly random seed for the initial SplittableRandom
  // In Java 21, SecureRandom has improved performance and security characteristics
  // We use the default SecureRandom constructor to avoid potential blocking in getInstanceStrong()
  private final SplittableRandom random = new SplittableRandom(new SecureRandom().nextLong());

  /**
   * Creates a new thread-local {@link SplittableRandom} by splitting from the master random.
   * This ensures statistical independence between random sequences used by different threads.
   */
  @Override
  protected SplittableRandom initialValue() {
    // In Java 21, SplittableRandom has improved thread safety and performance
    // We still use synchronization to ensure thread safety during splitting
    synchronized (random) {
      return random.split();
    }
  }

  /**
   * Gets the thread-local {@link SplittableRandom} instance.
   * This method ensures we always use the thread-local instance when generating random numbers.
   * 
   * @return the thread-local random instance
   */
  private SplittableRandom current() {
    return get();
  }

  // RandomGenerator interface implementation methods
  // These delegate to the thread-local SplittableRandom instance

  @Override
  public boolean nextBoolean() {
    return current().nextBoolean();
  }

  @Override
  public void nextBytes(byte[] bytes) {
    current().nextBytes(bytes);
  }

  @Override
  public float nextFloat() {
    return current().nextFloat();
  }

  @Override
  public double nextDouble() {
    return current().nextDouble();
  }

  @Override
  public int nextInt() {
    return current().nextInt();
  }

  @Override
  public int nextInt(int bound) {
    return current().nextInt(bound);
  }

  @Override
  public long nextLong() {
    return current().nextLong();
  }

  @Override
  public double nextGaussian() {
    return current().nextGaussian();
  }
  
  // Stream-producing methods from RandomGenerator interface
  
  @Override
  public IntStream ints() {
    return current().ints();
  }
  
  @Override
  public IntStream ints(long streamSize) {
    return current().ints(streamSize);
  }
  
  @Override
  public IntStream ints(int randomNumberOrigin, int randomNumberBound) {
    return current().ints(randomNumberOrigin, randomNumberBound);
  }
  
  @Override
  public IntStream ints(long streamSize, int randomNumberOrigin, int randomNumberBound) {
    return current().ints(streamSize, randomNumberOrigin, randomNumberBound);
  }
  
  @Override
  public LongStream longs() {
    return current().longs();
  }
  
  @Override
  public LongStream longs(long streamSize) {
    return current().longs(streamSize);
  }
  
  @Override
  public LongStream longs(long randomNumberOrigin, long randomNumberBound) {
    return current().longs(randomNumberOrigin, randomNumberBound);
  }
  
  @Override
  public LongStream longs(long streamSize, long randomNumberOrigin, long randomNumberBound) {
    return current().longs(streamSize, randomNumberOrigin, randomNumberBound);
  }
  
  @Override
  public DoubleStream doubles() {
    return current().doubles();
  }
  
  @Override
  public DoubleStream doubles(long streamSize) {
    return current().doubles(streamSize);
  }
  
  @Override
  public DoubleStream doubles(double randomNumberOrigin, double randomNumberBound) {
    return current().doubles(randomNumberOrigin, randomNumberBound);
  }
  
  @Override
  public DoubleStream doubles(long streamSize, double randomNumberOrigin, double randomNumberBound) {
    return current().doubles(streamSize, randomNumberOrigin, randomNumberBound);
  }
}