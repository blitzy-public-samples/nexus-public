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
package org.sonatype.nexus.common.io;

import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supplies local {@link Cooperation} points.
 *
 * @since 3.14
 */
@Named("local")
@Singleton
public class LocalCooperationFactory
    extends ScopedCooperationFactorySupport
{
  private static final Logger log = LoggerFactory.getLogger(LocalCooperationFactory.class);
  
  private final ConcurrentMap<String, CooperatingFuture<?>> localFutures = new ConcurrentHashMap<>();
  
  /**
   * Dedicated map for Virtual Thread-based cooperation futures.
   * Using a separate map provides better performance for Virtual Thread operations.
   * 
   * @since 3.60
   */
  private final ConcurrentMap<String, CooperatingFuture<?>> virtualThreadFutures = new ConcurrentHashMap<>();

  @Override
  @SuppressWarnings("unchecked")
  protected <T> CooperatingFuture<T> beginCooperation(final String scopedKey, final CooperatingFuture<T> future) {
    return (CooperatingFuture<T>) localFutures.putIfAbsent(scopedKey, future);
  }

  @Override
  protected <T> void endCooperation(final String scopedKey, final CooperatingFuture<T> future) {
    localFutures.remove(scopedKey, future);
  }

  @Override
  protected Stream<CooperatingFuture<?>> streamFutures(final String scope) {
    return localFutures.entrySet()
        .stream()
        .filter(entry -> entry.getKey().startsWith(scope))
        .map(Entry::getValue);
  }
  
  /**
   * Begins cooperation for the scoped key using the given future with Virtual Thread support.
   * This implementation uses a dedicated map for Virtual Thread futures to optimize performance.
   *
   * @param scopedKey the scoped key for cooperation
   * @param future the future to associate with this cooperation
   * @return {@code null} if the key was not already in use; otherwise the currently associated future
   * @since 3.60
   */
  @Override
  @SuppressWarnings("unchecked")
  protected <T> CooperatingFuture<T> beginVirtualThreadCooperation(final String scopedKey, final CooperatingFuture<T> future) {
    log.debug("Beginning Virtual Thread cooperation for key: {}", scopedKey);
    return (CooperatingFuture<T>) virtualThreadFutures.putIfAbsent(scopedKey, future);
  }

  /**
   * Ends cooperation for the scoped key and its associated future with Virtual Thread support.
   * This implementation uses a dedicated map for Virtual Thread futures to optimize performance.
   *
   * @param scopedKey the scoped key for cooperation
   * @param future the future to disassociate from this cooperation
   * @since 3.60
   */
  @Override
  protected <T> void endVirtualThreadCooperation(final String scopedKey, final CooperatingFuture<T> future) {
    log.debug("Ending Virtual Thread cooperation for key: {}", scopedKey);
    virtualThreadFutures.remove(scopedKey, future);
  }
  
  /**
   * Streams all futures that are currently cooperating with Virtual Thread support.
   * This implementation uses a dedicated map for Virtual Thread futures to optimize performance.
   *
   * @param scope the scope to stream futures from
   * @return stream of cooperating futures
   * @since 3.60
   */
  @Override
  protected Stream<CooperatingFuture<?>> streamVirtualThreadFutures(final String scope) {
    return virtualThreadFutures.entrySet()
        .stream()
        .filter(entry -> entry.getKey().startsWith(scope))
        .map(Entry::getValue);
  }
}