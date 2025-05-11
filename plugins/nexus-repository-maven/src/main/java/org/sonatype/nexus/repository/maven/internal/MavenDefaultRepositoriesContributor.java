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
package org.sonatype.nexus.repository.maven.internal;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.WritePolicy;
import org.sonatype.nexus.repository.manager.DefaultRepositoriesContributor;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.maven.ContentDisposition;
import org.sonatype.nexus.repository.maven.LayoutPolicy;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.sonatype.nexus.repository.maven.internal.recipes.Maven2GroupRecipe;
import org.sonatype.nexus.repository.maven.internal.recipes.Maven2HostedRecipe;
import org.sonatype.nexus.repository.maven.internal.recipes.Maven2ProxyRecipe;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Map.entry;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.DATA_STORE_NAME;

/**
 * Provide default hosted and proxy repositories for Maven.
 *
 * @since 3.0
 */
@Named
@Singleton
public class MavenDefaultRepositoriesContributor
    implements DefaultRepositoriesContributor
{
  private static final String STORAGE = "storage";

  private static final String MAVEN = "maven";

  static final String DEFAULT_RELEASE_REPO = "maven-releases";

  static final String DEFAULT_SNAPSHOT_REPO = "maven-snapshots";

  static final String DEFAULT_CENTRAL_REPO = "maven-central";

  static final String DEFAULT_PUBLIC_REPO = "maven-public";

  private final RepositoryManager repositoryManager;

  @Inject
  public MavenDefaultRepositoriesContributor(final RepositoryManager repositoryManager) {
    this.repositoryManager = checkNotNull(repositoryManager);
  }

  @Override
  public List<Configuration> getRepositoryConfigurations() {
    return List.of(
        newConfiguration(DEFAULT_RELEASE_REPO, Maven2HostedRecipe.NAME,
            map(
                MAVEN, maven(VersionPolicy.RELEASE, LayoutPolicy.STRICT),
                STORAGE, storage(BlobStoreManager.DEFAULT_BLOBSTORE_NAME, WritePolicy.ALLOW_ONCE))),
        newConfiguration(DEFAULT_SNAPSHOT_REPO, Maven2HostedRecipe.NAME,
            map(
                MAVEN, maven(VersionPolicy.SNAPSHOT, LayoutPolicy.STRICT),
                STORAGE, storage(BlobStoreManager.DEFAULT_BLOBSTORE_NAME, WritePolicy.ALLOW))),
        newConfiguration(DEFAULT_CENTRAL_REPO, Maven2ProxyRecipe.NAME,
            map(
                "httpclient", Map.of(
                    "connection", map(
                        "blocked", false,
                        "autoBlock", true)),
                MAVEN, maven(VersionPolicy.RELEASE, LayoutPolicy.PERMISSIVE),
                "proxy", map(
                    "remoteUrl", "https://repo1.maven.org/maven2/",
                    "contentMaxAge", -1,
                    "metadataMaxAge", 1440),
                "negativeCache", map(
                    "enabled", true,
                    "timeToLive", 1440),
                STORAGE, storage(BlobStoreManager.DEFAULT_BLOBSTORE_NAME))),
        newConfiguration(DEFAULT_PUBLIC_REPO, Maven2GroupRecipe.NAME,
            map(
                MAVEN, maven(VersionPolicy.MIXED, LayoutPolicy.PERMISSIVE),
                "group",
                Map.of("memberNames", List.of(DEFAULT_RELEASE_REPO, DEFAULT_SNAPSHOT_REPO, DEFAULT_CENTRAL_REPO)),
                STORAGE, storage(BlobStoreManager.DEFAULT_BLOBSTORE_NAME))));
  }

  private Configuration newConfiguration(
      final String repositoryName,
      final String recipeName,
      final Map<String, Map<String, Object>> attributes)
  {
    Configuration config = repositoryManager.newConfiguration();
    config.setRepositoryName(repositoryName);
    config.setRecipeName(recipeName);
    config.setOnline(true);
    config.setAttributes(attributes);

    return config;
  }

  private static Map<String, Object> maven(
      final VersionPolicy versionPolicy,
      final LayoutPolicy layout)
  {
    return map(
        "versionPolicy", versionPolicy.toString(),
        "layoutPolicy", layout.toString(),
        "contentDisposition", ContentDisposition.INLINE);
  }

  private static Map<String, Object> storage(final String blobstoreName, final WritePolicy policy) {
    return map(
        "blobStoreName", blobstoreName,
        "writePolicy", policy.toString(),
        "strictContentTypeValidation", false,
        DATA_STORE_NAME, "nexus");
  }

  private static Map<String, Object> storage(final String blobstoreName) {
    return map(
        "blobStoreName", blobstoreName,
        "strictContentTypeValidation", false,
        DATA_STORE_NAME, "nexus");
  }

  /**
   * Creates a map with two entries.
   * 
   * @param <E> the type of values in the map
   * @param key1 the first key
   * @param value1 the first value
   * @param key2 the second key
   * @param value2 the second value
   * @return an immutable map with the specified entries
   */
  private static <E> Map<String, E> map(
      final String key1,
      final E value1,
      final String key2,
      final E value2)
  {
    return Map.of(key1, value1, key2, value2);
  }

  /**
   * Creates a map with three entries.
   * 
   * @param <E> the type of values in the map
   * @param key1 the first key
   * @param value1 the first value
   * @param key2 the second key
   * @param value2 the second value
   * @param key3 the third key
   * @param value3 the third value
   * @return an immutable map with the specified entries
   */
  private static <E> Map<String, E> map(
      final String key1,
      final E value1,
      final String key2,
      final E value2,
      final String key3,
      final E value3)
  {
    return Map.of(key1, value1, key2, value2, key3, value3);
  }

  /**
   * Creates a map with four entries.
   * 
   * @param <E> the type of values in the map
   * @param key1 the first key
   * @param value1 the first value
   * @param key2 the second key
   * @param value2 the second value
   * @param key3 the third key
   * @param value3 the third value
   * @param key4 the fourth key
   * @param value4 the fourth value
   * @return an immutable map with the specified entries
   */
  private static <E> Map<String, E> map(
      final String key1,
      final E value1,
      final String key2,
      final E value2,
      final String key3,
      final E value3,
      final String key4,
      final E value4)
  {
    return Map.of(key1, value1, key2, value2, key3, value3, key4, value4);
  }

  /**
   * Creates a map with five entries.
   * 
   * @param <E> the type of values in the map
   * @param key the first key
   * @param value the first value
   * @param key1 the second key
   * @param value1 the second value
   * @param key2 the third key
   * @param value2 the third value
   * @param key3 the fourth key
   * @param value3 the fourth value
   * @param key4 the fifth key
   * @param value4 the fifth value
   * @return an immutable map with the specified entries
   */
  private static <E> Map<String, E> map(
      final String key,
      final E value,
      final String key1,
      final E value1,
      final String key2,
      final E value2,
      final String key3,
      final E value3,
      final String key4,
      final E value4)
  {
    return Map.ofEntries(
        entry(key, value),
        entry(key1, value1),
        entry(key2, value2),
        entry(key3, value3),
        entry(key4, value4)
    );
  }
}