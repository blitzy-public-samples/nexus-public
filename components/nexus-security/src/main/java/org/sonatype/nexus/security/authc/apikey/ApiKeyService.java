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
package org.sonatype.nexus.security.authc.apikey;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.apache.shiro.subject.PrincipalCollection;

/**
 * Persistent mapping between principals (such as user IDs) and API-Keys.
 * <p>
 * Implementation notes for Java 21:
 * <ul>
 *   <li>Implementations should utilize Virtual Threads for database operations to improve concurrency and throughput,
 *       especially for high-volume API key validation scenarios.</li>
 *   <li>Cryptographic operations should use Java 21-compatible providers (e.g., BouncyCastle 1.77+) and leverage
 *       the enhanced security features of the JDK, including stronger defaults for TLS and cipher suites.</li>
 *   <li>Optional handling should be enhanced with Java 21 pattern matching capabilities for more concise and
 *       type-safe code in implementations.</li>
 *   <li>Implementations should ensure thread-safety when using Virtual Threads, particularly for shared state access.</li>
 * </ul>
 *
 * @since 3.0
 */
public interface ApiKeyService
{
  /**
   * Creates an API-Key and assigns it to the given principals in given domain.
   * <p>
   * Implementations should use Java 21-compatible cryptographic providers for secure key generation
   * and leverage Virtual Threads for database operations to improve performance under high concurrency.
   */
  char[] createApiKey(String domain, PrincipalCollection principals);

  /**
   * Gets the current API-Key assigned to the given principals in given domain.
   * <p>
   * Implementations should leverage Java 21 pattern matching for Optional handling to improve code readability
   * and type safety. Database operations should utilize Virtual Threads for improved concurrency.
   *
   * @return An Optional containing the API key if found, or empty if no key has been assigned
   */
  Optional<ApiKey> getApiKey(String domain, PrincipalCollection principals);

  /**
   * Retrieves the principals associated with the given API-Key in given domain.
   * <p>
   * Implementations should use Java 21-compatible cryptographic providers for secure token validation
   * and leverage Virtual Threads for database lookups to handle high-volume authentication scenarios efficiently.
   *
   * @return An Optional containing the API key if valid, or empty if the key is invalid or stale
   */
  Optional<ApiKey> getApiKeyByToken(String domain, char[] apiKey);

  /**
   * Count all the keys for the provided domain.
   * <p>
   * Implementations should utilize Virtual Threads for database operations to minimize overhead
   * when counting large numbers of keys.
   */
  int count(String domain);

  /**
   * Deletes the API-Key associated with the given principals in given domain.
   * <p>
   * Implementations should leverage Virtual Threads for database operations to improve performance
   * and ensure proper cleanup of cryptographic material.
   */
  int deleteApiKey(String domain, PrincipalCollection principals);

  /**
   * Deletes every API-Key associated with the given principals in every domain.
   * <p>
   * Implementations should utilize Virtual Threads for concurrent deletion operations across multiple domains
   * to improve performance during user removal or credential rotation scenarios.
   */
  int deleteApiKeys(PrincipalCollection principals);

  /**
   * Deletes all API-Keys for the specified domain.
   * <p>
   * Implementations should leverage Virtual Threads for bulk deletion operations to minimize impact
   * on system performance when cleaning up large numbers of keys.
   */
  int deleteApiKeys(String domain);

  /**
   * Remove all expired API-Keys.
   * <p>
   * Implementations should utilize Virtual Threads for database cleanup operations to improve performance
   * during maintenance tasks. Consider using Java 21's enhanced date/time handling for expiration calculations.
   */
  int deleteApiKeys(OffsetDateTime expiration);
}