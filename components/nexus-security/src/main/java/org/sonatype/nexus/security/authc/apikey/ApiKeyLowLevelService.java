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
import java.util.SequencedCollection;

import javax.annotation.Nullable;

import org.apache.shiro.subject.PrincipalCollection;

/**
 * Exposes lower level APIs of {@link ApiKeyService} which shouldn't be widely used.
 */
public interface ApiKeyLowLevelService extends ApiKeyService
{
  /**
   * Browse tokens in the domain
   * 
   * @return a sequenced collection of API keys
   */
  SequencedCollection<ApiKey> browse(String domain);

  /**
   * Browse tokens in the domain created after the provided date
   * 
   * @return a sequenced collection of API keys created after the specified date
   */
  SequencedCollection<ApiKey> browseByCreatedDate(String domain, OffsetDateTime date);

  /**
   * Browse tokens in the domain (paginated)
   * 
   * @return a sequenced collection of API keys for the specified page
   */
  SequencedCollection<ApiKey> browsePaginated(String domain, int page, int pageSize);

  /**
   * Persists an API-Key with a predetermined value.
   * Uses enhanced pattern matching to handle different input scenarios.
   *
   * @since 3.1
   */
  default void persistApiKey(final String domain, final PrincipalCollection principals, final char[] apiKey) {
    // Using Java 21 pattern matching for switch to handle different cases
    switch (domain) {
      case null -> throw new IllegalArgumentException("Domain cannot be null");
      case String s when s.isEmpty() -> throw new IllegalArgumentException("Domain cannot be empty");
      default -> persistApiKey(domain, principals, apiKey, null);
    }
  }

  /**
   * Persists an API-Key with a predetermined value.
   * 
   * @implNote Implementations should utilize Virtual Threads for this I/O-bound operation
   *           to improve scalability and performance when handling multiple concurrent requests.
   */
  void persistApiKey(String domain, PrincipalCollection principals, char[] apiKey, @Nullable OffsetDateTime created);

  /**
   * Updates an existing API-key.
   * 
   * @implNote Implementations should utilize Virtual Threads for this I/O-bound operation
   *           to improve scalability and performance when handling multiple concurrent requests.
   */
  void updateApiKeyRealm(ApiKey from, PrincipalCollection newPrincipal);
}