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
package org.sonatype.nexus.content.maven.internal.snapshot;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.content.maven.store.GAV;
import org.sonatype.nexus.content.maven.store.Maven2ComponentData;
import org.sonatype.nexus.logging.task.ProgressLogIntervalHelper;
import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.store.ComponentData;
import org.sonatype.nexus.repository.content.store.InternalIds;
import org.sonatype.nexus.repository.maven.RemoveSnapshotsFacet;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.sonatype.nexus.repository.maven.internal.group.MavenGroupFacet;
import org.sonatype.nexus.repository.maven.tasks.RemoveSnapshotsConfig;
import org.sonatype.nexus.repository.types.GroupType;

import com.google.common.annotations.VisibleForTesting;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.PROGRESS;

/**
 * Facet handling the removal of snapshots from a repository.
 * 
 * This implementation leverages Java 21 features including:
 * - Virtual Threads for concurrent processing of repositories and snapshot candidates
 * - String Templates for more readable and efficient logging
 * - Pattern Matching for type checking
 *
 * @since 3.30
 */
@Named
public class RemoveSnapshotsFacetImpl
    extends FacetSupport
    implements RemoveSnapshotsFacet
{
  private final Type groupType;

  @Inject
  public RemoveSnapshotsFacetImpl(
      @Named(GroupType.NAME) final Type groupType)
  {
    this.groupType = checkNotNull(groupType);
  }

  @Override
  public void removeSnapshots(final RemoveSnapshotsConfig config) {
    Repository repository = getRepository();
    String repositoryName = repository.getName();
    log.info(STR."Beginning snapshot removal on repository '\{repositoryName}' with configuration: \{config}");

    if (groupType.equals(repository.getType())) {
      processGroup(repository.facet(MavenGroupFacet.class), config);
    }
    else {
      processRepository(repository, config);
    }

    log.info(STR."Completed snapshot removal on repository '\{repositoryName}'");
  }

  /**
   * Iterate over group members which may contain snapshots and recursively apply the snapshot removal.
   * Uses Virtual Threads to process group members concurrently for improved performance.
   */
  private void processGroup(final MavenGroupFacet groupFacet, final RemoveSnapshotsConfig config) {
    List<Repository> snapshotRepos = groupFacet.members().stream()
        .filter(member -> isSnapshotRepo(member) || groupType.equals(member.getType()))
        .collect(Collectors.toList());
    
    // Use virtual threads for concurrent processing of group members
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<Void>> futures = snapshotRepos.stream()
          .map(member -> CompletableFuture.runAsync(() -> 
              member.facet(RemoveSnapshotsFacet.class).removeSnapshots(config), executor))
          .collect(Collectors.toList());
      
      // Wait for all tasks to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
  }

  /**
   * Determine whether or not the given repo could contain snapshots.
   */
  private boolean isSnapshotRepo(final Repository member) {
    return member.facet(MavenContentFacet.class).getVersionPolicy() != VersionPolicy.RELEASE;
  }

  /**
   * Examine all snapshots in the given repo, delete those that match our configuration criteria and flag which GAVs
   * require a metadata update.
   */
  @VisibleForTesting
  void processRepository(
      final Repository repository, final RemoveSnapshotsConfig config)
  {
    log.info(STR."Begin processing snapshots in repository '\{repository.getName()}'");

    deleteRedundantSnapshots(repository, config);

    deleteSnapshotsForReleasedComponents(repository, config);

    log.info(STR."Finished processing snapshots with more than \{config.getMinimumRetained()} versions created before \{OffsetDateTime.now().minusDays(Math.max(config.getSnapshotRetentionDays(), 0))}");
  }

  /**
   * Find all GAVs that qualify for deletion.
   */
  @VisibleForTesting
  Set<GAV> findSnapshotCandidates(final Repository repository, final int minimumRetained)
  {
    log.info(PROGRESS, STR."Searching for GAVs with snapshots that qualify for deletion on repository '\{repository.getName()}'");

    MavenContentFacet facet = repository.facet(MavenContentFacet.class);
    return facet.findGavsWithSnaphots(minimumRetained);
  }

  /**
   * Find all components (snapshot *OR* release) for a given GAV
   */
  @VisibleForTesting
  List<Maven2ComponentData> findComponentsForGav(final Repository repository, final GAV gav)
  {
    MavenContentFacet facet = repository.facet(MavenContentFacet.class);
    String releaseVersion = gav.baseVersion.replace("-SNAPSHOT", "");
    return facet.findComponentsForGav(gav.name, gav.group, gav.baseVersion, releaseVersion);
  }

  /**
   * Given a list of all components (snapshot & release) for a GAV,
   * determine which ones to delete based on desired minimum and last update date
   */
  @VisibleForTesting
  Set<Maven2ComponentData> getSnapshotsToDelete(
      final RemoveSnapshotsConfig config,
      final List<Maven2ComponentData> components)
  {
    Set<Maven2ComponentData> snapshotsToDelete = new HashSet<>();
    OffsetDateTime retentionTimeBorder = OffsetDateTime.now().minusDays(Math.max(config.getSnapshotRetentionDays(), 0));
    int keptSnapshotsCount = 0;
    if (config.getMinimumRetained() >= 0) {
      for (Maven2ComponentData component : components) {
        if (retentionTimeBorder.isAfter(calculateLastUpdated(component)) && keptSnapshotsCount >= config.getMinimumRetained()) {
          snapshotsToDelete.add(component);
        }
        else {
          keptSnapshotsCount++;
        }
      }
    }
    return snapshotsToDelete;
  }

  /**
   * Calculate component last updated based {@link AssetBlob#blobCreated()}
   */
  @VisibleForTesting
  OffsetDateTime calculateLastUpdated(final ComponentData component) {
    OffsetDateTime lastUpdated = component.lastUpdated();
    if (component.getAssets() != null) {
      lastUpdated = component.getAssets().stream()
          .map(Asset::blob)
          .filter(Optional::isPresent)
          .map(assetBlob -> assetBlob.get().blobCreated())
          .max(OffsetDateTime::compareTo)
          .orElse(lastUpdated);
    }
    return lastUpdated;
  }

  /**
   * Delete snapshots based on desired minimum and last update date.
   * Uses Virtual Threads to process snapshot candidates concurrently for improved performance.
   */
  @VisibleForTesting
  void deleteRedundantSnapshots(final Repository repository, final RemoveSnapshotsConfig config)
  {
    Set<GAV> snapshotCandidates = findSnapshotCandidates(repository, Math.max(config.getMinimumRetained(), 0));
    log.debug(STR."Found \{snapshotCandidates.size()} snapshot GAVs to analyze");

    Set<GAV> gavsWithDeletions = new HashSet<>();
    try (ProgressLogIntervalHelper intervalLogger = new ProgressLogIntervalHelper(log, 60)) {
      Set<Maven2ComponentData> redundantSnapshots = new HashSet<>();
      
      // Use virtual threads for concurrent processing of snapshot candidates
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<CompletableFuture<Set<Maven2ComponentData>>> futures = snapshotCandidates.stream()
            .map(snapshotCandidate -> CompletableFuture.supplyAsync(() -> {
              log.debug(STR."Processing GAV = \{snapshotCandidate}");
              List<Maven2ComponentData> components = findComponentsForGav(repository, snapshotCandidate);
              if (!components.isEmpty()) {
                Set<Maven2ComponentData> snapshotsToDelete = getSnapshotsToDelete(config, components);
                if (!snapshotsToDelete.isEmpty()) {
                  log.debug(STR."Found \{snapshotsToDelete.size()} snapshots to remove for GAV = \{snapshotCandidate}");
                  synchronized (gavsWithDeletions) {
                    gavsWithDeletions.add(snapshotCandidate);
                  }
                  return snapshotsToDelete;
                }
              }
              return Set.<Maven2ComponentData>of();
            }, executor))
            .collect(Collectors.toList());
        
        // Collect results from all futures
        for (CompletableFuture<Set<Maven2ComponentData>> future : futures) {
          redundantSnapshots.addAll(future.join());
        }
      }
      
      if (!redundantSnapshots.isEmpty()) {
        MavenContentFacet facet = repository.facet(MavenContentFacet.class);
        facet.deleteComponents(redundantSnapshots.stream().mapToInt(InternalIds::internalComponentId).toArray());
      }

      intervalLogger.flush();

      log.info(STR."Elapsed time: \{intervalLogger.getElapsed()}, deleted \{redundantSnapshots.size()} components from \{gavsWithDeletions.size()} distinct GAVs");
    }
  }

  /**
   * Delete snapshots for released components after grace period
   */
  @VisibleForTesting
  void deleteSnapshotsForReleasedComponents(final Repository repository, final RemoveSnapshotsConfig config)
  {
    if (config.getRemoveIfReleased()) {
      MavenContentFacet facet = repository.facet(MavenContentFacet.class);
      int[] snapshotsAfterReleaseToDelete = facet.selectSnapshotsAfterRelease(Math.max(config.getGracePeriod(), 0));
      facet.deleteComponents(snapshotsAfterReleaseToDelete);
      log.info(STR."Deleted \{snapshotsAfterReleaseToDelete.length} snapshots for released components");
    }
  }
}