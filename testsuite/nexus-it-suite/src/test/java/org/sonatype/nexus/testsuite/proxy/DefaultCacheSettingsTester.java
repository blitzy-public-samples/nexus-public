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
package org.sonatype.nexus.testsuite.proxy;

import java.util.concurrent.TimeUnit;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;

import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.NegativeCacheFacet;
import org.sonatype.nexus.repository.cache.NegativeCacheKey;
import org.sonatype.nexus.repository.cache.internal.NegativeCacheFacetImpl;
import org.sonatype.nexus.repository.view.Status;

import org.ehcache.config.CacheRuntimeConfiguration;
import org.ehcache.config.ResourceType;
import org.ehcache.jsr107.Eh107Configuration;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Utility class for verifying cache configuration settings in Nexus Repository Manager.
 * This class provides methods to validate that cache settings are correctly applied
 * based on the system defaults and repository configuration.
 * 
 * <p>Used in integration tests to ensure that caching behavior is consistent and correctly
 * configured across different repository types and formats.</p>
 * 
 * @since 3.3
 */
public class DefaultCacheSettingsTester
{
  /**
   * Default heap size for negative cache entries.
   * This value represents the maximum number of entries that can be stored in the negative cache.
   */
  public static final long DEFAULT_HEAP_SIZE = 10000L;

  /**
   * Verifies that the negative cache settings for a repository match the expected configuration.
   * For 'negativeCache' backing NegativeCacheFacet activities, we expect specific ExpiryPolicy 
   * and heap size settings for all Proxy repositories.
   *
   * <p>This method checks:</p>
   * <ul>
   *   <li>If negative caching is enabled for the repository</li>
   *   <li>The time-to-live (TTL) setting matches the repository configuration</li>
   *   <li>The expiry policy is correctly configured with the specified TTL</li>
   *   <li>The heap size matches the expected default value</li>
   * </ul>
   *
   * @param repository the repository to verify negative cache settings for
   * @param cacheManager the cache manager that manages the repository's negative cache
   */
  public static void verifyNegativeCacheSettings(Repository repository, CacheManager cacheManager) {
    NestedAttributesMap cacheConfig = repository.getConfiguration().attributes("negativeCache");
    boolean enabled = cacheConfig.get("enabled", Boolean.class);
    if (enabled) {
      int ttl = cacheConfig.get("timeToLive", Integer.class);
      Cache<NegativeCacheKey, Status> cache = cacheManager.getCache(
          ((NegativeCacheFacetImpl) repository.facet(NegativeCacheFacet.class)).getCacheName(), NegativeCacheKey.class,
          Status.class);
      CompleteConfiguration<NegativeCacheKey, Status> completeConfiguration =
          cache.getConfiguration(CompleteConfiguration.class);

      assertThat(completeConfiguration.getExpiryPolicyFactory(),
          is(CreatedExpiryPolicy.factoryOf(new Duration(TimeUnit.MINUTES, ttl))));
      verifyHeapSize(cache, DEFAULT_HEAP_SIZE);
    }
  }

  /**
   * Verifies that the underlying cache configuration matches the expected heap size.
   * This method unwraps the cache configuration to access the low-level EhCache settings
   * and validates the heap resource pool size.
   *
   * @param cache the cache instance to verify
   * @param heapSize the expected heap size (maximum number of entries)
   */
  public static void verifyHeapSize(Cache<NegativeCacheKey, Status> cache, long heapSize) {
    Eh107Configuration<NegativeCacheKey, Status> eh107Configuration = cache.getConfiguration(Eh107Configuration.class);
    CacheRuntimeConfiguration runtimeConfiguration = eh107Configuration.unwrap(CacheRuntimeConfiguration.class);
    assertThat(runtimeConfiguration.getResourcePools().getPoolForResource(ResourceType.Core.HEAP).getSize(),
        is(heapSize));
  }
}