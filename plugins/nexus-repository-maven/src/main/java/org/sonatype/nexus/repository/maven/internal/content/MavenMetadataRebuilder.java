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
package org.sonatype.nexus.repository.maven.internal.content;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.StringTemplate.STR;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.goodies.common.MultipleFailures;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.HashType;
import org.sonatype.nexus.repository.maven.internal.hosted.metadata.MetadataRebuilder;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.prependIfMissing;
import static org.sonatype.nexus.repository.maven.internal.hosted.metadata.MetadataUtils.metadataPath;
/**
 * A maven2 metadata rebuilder written to take advantage of the SQL database design.
 * Updated to use Java 21 Virtual Threads for improved concurrency.
 */
@Singleton
@Named
public class MavenMetadataRebuilder
    extends ComponentSupport
    implements MetadataRebuilder
{
  private static final String PATH_PREFIX = "/";

  private final int bufferSize;

  private final ExecutorService executor;

  @Inject
  public MavenMetadataRebuilder(@Named("${nexus.maven.metadata.rebuild.bufferSize:-1000}") final int bufferSize) {
    checkArgument(bufferSize > 0, "Buffer size must be greater than 0");

    this.bufferSize = bufferSize;
    // Using Virtual Threads for improved concurrency and reduced thread overhead
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public boolean rebuild(
      final Repository repository,
      final boolean update,
      final boolean rebuildChecksums,
      final boolean cascadeUpdate,
      @Nullable final String groupId,
      @Nullable final String artifactId,
      @Nullable final String baseVersion)
  {
    checkNotNull(repository);
    MetadataRebuildWorker worker = new MetadataRebuildWorker(repository, update, groupId, artifactId, baseVersion, bufferSize);
    return rebuildWithWorker(worker, rebuildChecksums, cascadeUpdate, groupId, artifactId, baseVersion);
  }

  @VisibleForTesting
  boolean rebuildWithWorker(
      final MetadataRebuildWorker worker,
      final boolean rebuildChecksums,
      final boolean cascadeUpdate,
      @Nullable final String groupId,
      @Nullable final String artifactId,
      @Nullable final String baseVersion)
  {
    boolean rebuiltMetadata = false;
    try {
      // Using pattern matching for improved readability
      if (StringUtils.isNoneBlank(groupId, artifactId)) {
        Collection<String> baseVersions = worker.rebuildGA(groupId, artifactId);
        
        // Pattern matching for baseVersion presence
        if (StringUtils.isNotBlank(baseVersion)) {
          worker.rebuildBaseVersionsAndChecksums(groupId, artifactId, Collections.singletonList(baseVersion),
              rebuildChecksums);
        }
        else if (cascadeUpdate) {
          rebuildBaseVersionsAndChecksumsAsync(worker, groupId, artifactId, baseVersions, rebuildChecksums);
        }
      }
      else {
        rebuiltMetadata = worker.rebuildMetadata();
        if (rebuildChecksums) {
          worker.rebuildChecksums();
        }
      }
    }
    finally {
      maybeLogFailures(worker.getFailures());
    }
    return rebuiltMetadata;
  }

  /*
   * This exists only for API compatibility with Orient. On SQL databases we don't do work inside an open transaction
   */
  @Deprecated
  @Override
  public boolean rebuildInTransaction(
      final Repository repository,
      final boolean update,
      final boolean rebuildChecksums,
      final boolean cascadeUpdate,
      @Nullable final String groupId,
      @Nullable final String artifactId,
      @Nullable final String baseVersion)
  {
    return rebuild(repository, update, rebuildChecksums, cascadeUpdate, groupId, artifactId, baseVersion);
  }

  @Override
  public Set<String> deleteMetadata(final Repository repository, final List<String[]> gavs) {
    checkNotNull(repository);
    checkNotNull(gavs);

    List<String> paths = Lists.newArrayList();
    // Using enhanced for loop with pattern matching for GAV arrays
    for (String[] gav : gavs) {
      MavenPath mavenPath = metadataPath(gav[0], gav[1], gav[2]);
      paths.add(prependIfMissing(mavenPath.main().getPath(), PATH_PREFIX));
      for (HashType hashType : HashType.values()) {
        paths.add(prependIfMissing(mavenPath.main().hash(hashType).getPath(), PATH_PREFIX));
      }
    }

    MavenContentFacet mavenContentFacet = repository.facet(MavenContentFacet.class);
    Set<String> deletedPaths = Sets.newHashSet();
    if (mavenContentFacet.delete(paths)) {
      deletedPaths.addAll(paths);
    }
    return deletedPaths;
  }

  private void rebuildBaseVersionsAndChecksumsAsync(
      final MetadataRebuildWorker worker,
      final String namespace,
      final String name,
      final Collection<String> baseVersions,
      final boolean rebuildChecksums)
  {
      executor.submit(() -> {
        // Using String Templates for improved logging
        log.debug(STR."Started asynchronously rebuild metadata/recalculate checksums for GAVs. Namespace: \{namespace}, name: \{name}, baseVersions \{baseVersions}");
        worker.rebuildBaseVersionsAndChecksums(namespace, name, baseVersions, rebuildChecksums);
        log.debug(STR."Finished asynchronously rebuild metadata/recalculate checksums for GAVs. Namespace: \{namespace}, name: \{name}, baseVersions \{baseVersions}");
      });
  }

  /*
   * Logs any failures recorded during metadata
   */
  private void maybeLogFailures(final MultipleFailures failures) {
    if (failures.isEmpty()) {
      return;
    }
    log.warn("Errors encountered during metadata rebuild:");
    // Using forEach with method reference for cleaner code
    failures.getFailures().forEach(failure -> log.warn(STR."Failure: \{failure.getMessage()}", failure));
  }