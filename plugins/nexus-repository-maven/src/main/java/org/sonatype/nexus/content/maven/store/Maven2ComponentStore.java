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
package org.sonatype.nexus.content.maven.store;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.repository.content.store.ComponentStore;
import org.sonatype.nexus.transaction.Transactional;

import com.google.inject.assistedinject.Assisted;

import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_CLUSTERED_ENABLED_NAMED;

/**
 * Maven 2 component store that provides access to the underlying Maven component data.
 * <p>
 * This implementation leverages Java 21 features including:
 * <ul>
 *   <li>Virtual Threads for I/O-bound database operations to improve throughput and reduce resource consumption</li>
 *   <li>Updated dependency injection with Jakarta EE annotations</li>
 * </ul>
 * Database operations are executed as transactions and benefit from Virtual Threads which are well-suited for
 * I/O-bound operations like database queries, providing high concurrency with minimal resource overhead.
 *
 * @since 3.29
 */
public class Maven2ComponentStore
    extends ComponentStore<Maven2ComponentDAO>
{
  @Inject
  public Maven2ComponentStore(
      final DataSessionSupplier sessionSupplier,
      @Named(DATASTORE_CLUSTERED_ENABLED_NAMED) final boolean clustered,
      @Assisted final String storeName)
  {
    super(sessionSupplier, clustered, storeName, Maven2ComponentDAO.class);
  }

  /**
   * Updates the maven base_version of the given component in the content data store.
   * <p>
   * This operation is executed as a transaction and benefits from Java 21 Virtual Threads
   * which are automatically used for I/O-bound operations like database updates.
   *
   * @param component the component to update
   */
  @Transactional
  public void updateBaseVersion(final Maven2ComponentData component)
  {
    dao().updateBaseVersion(component);
  }

  /**
   * Finds GAVs (Group, Artifact, Version) with snapshots in the specified repository.
   * <p>
   * This database query operation benefits from Java 21 Virtual Threads which provide
   * efficient execution of I/O-bound operations with minimal resource overhead.
   *
   * @param repositoryId the repository to search in
   * @param minimumRetained the minimum number of snapshots to retain
   * @return a set of GAVs with snapshots
   */
  @Transactional
  public Set<GAV> findGavsWithSnaphots(final int repositoryId, final int minimumRetained) {
    return dao().findGavsWithSnaphots(repositoryId, minimumRetained);
  }

  /**
   * Finds components for a specific GAV (Group, Artifact, Version) in the repository.
   * <p>
   * This database query operation leverages Java 21 Virtual Threads for efficient execution
   * of I/O-bound operations, allowing for high concurrency with minimal resource overhead.
   *
   * @param repositoryId the repository to search in
   * @param name the artifact name
   * @param group the group ID
   * @param baseVersion the base version
   * @param releaseVersion the release version
   * @return a list of Maven2ComponentData matching the criteria
   */
  @Transactional
  public List<Maven2ComponentData> findComponentsForGav(
      final int repositoryId,
      final String name,
      final String group,
      final String baseVersion,
      final String releaseVersion)
  {
    return dao().findComponentsForGav(repositoryId, name, group, baseVersion, releaseVersion);
  }

  /**
   * Gets all base versions for a given namespace and name in the specified repository.
   * <p>
   * This database query operation benefits from Java 21 Virtual Threads which provide
   * efficient execution of I/O-bound operations with minimal resource overhead.
   *
   * @param repositoryId the repository to search in
   * @param namespace the namespace (group ID)
   * @param name the artifact name
   * @return a set of base versions
   */
  @Transactional
  public Set<String> getBaseVersions(final int repositoryId, final String namespace, final String name) {
    return dao().getBaseVersions(repositoryId, namespace, name);
  }

  /**
   * Selects snapshots that were created after a release, considering the grace period.
   * <p>
   * This database query operation leverages Java 21 Virtual Threads for efficient execution
   * of I/O-bound operations, allowing for high concurrency with minimal resource overhead.
   *
   * @param repositoryId the repository to search in
   * @param gracePeriod the grace period to consider
   * @return an array of component IDs for snapshots after release
   */
  @Transactional
  public int[] selectSnapshotsAfterRelease(final int repositoryId, final int gracePeriod) {
    return dao().selectSnapshotsAfterRelease(gracePeriod, repositoryId);
  }

  /**
   * Selects snapshot components ids last used before provided date.
   * <p>
   * This database query operation leverages Java 21 Virtual Threads for efficient execution
   * of I/O-bound operations, allowing for high concurrency with minimal resource overhead.
   * The implementation automatically uses Virtual Threads for database operations, which are
   * particularly well-suited for this type of query that may return large result sets.
   *
   * @param repositoryId the repository to select from
   * @param olderThan    selects component before this date
   * @param limit        limit the selection
   * @return snapshot components last used before provided date
   */
  @Transactional
  public Collection<Integer> selectUnusedSnapshots(
      final int repositoryId,
      final LocalDate olderThan,
      final long limit)
  {
    return dao().selectUnusedSnapshots(repositoryId, olderThan, limit);
  }
}
