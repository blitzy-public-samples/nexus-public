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
package org.sonatype.nexus.wonderland;

/**
 * Manages cache (and expiration) of authentication tickets.
 * <p>
 * This interface is compatible with Java 21's enhanced security model and can leverage
 * the improved cryptographic providers and security features available in Java 21.
 * <p>
 * Implementations should consider using Java 21 Virtual Threads for asynchronous cache operations
 * to improve throughput and responsiveness, especially for high-concurrency scenarios. Virtual Threads
 * provide significant performance benefits for I/O-bound operations like authentication verification
 * and token management without the overhead of traditional platform threads.
 * <p>
 * Note: When implementing with Virtual Threads, be cautious with ThreadLocal usage for caching expensive
 * objects, as Virtual Threads are not pooled or reused. Instead, consider using shared immutable objects
 * or other thread-safe caching mechanisms.
 * <p>
 * This interface is compatible with Apache Shiro 1.13.0 and can be integrated with Shiro's authentication
 * framework while maintaining the security guarantees provided by both Shiro and Java 21.
 *
 * @since 2.7
 */
public interface AuthTicketCache
{
  String EXPIRE = "${wonderland.authTicketCache.expireAfter:-20s}";

  /**
   * Add token to the cache.
   * <p>
   * Implementations may leverage Java 21 Virtual Threads for non-blocking, high-throughput
   * token caching operations, particularly in high-concurrency environments.
   */
  void add(String user, String token, String realmName);

  /**
   * Remove token from cache.
   * <p>
   * Implementations may leverage Java 21 Virtual Threads for non-blocking, high-throughput
   * token removal operations, particularly in high-concurrency environments.
   *
   * @return True if the token existed (was added and not yet expired)
   */
  boolean remove(String user, String token, String realmName);
}