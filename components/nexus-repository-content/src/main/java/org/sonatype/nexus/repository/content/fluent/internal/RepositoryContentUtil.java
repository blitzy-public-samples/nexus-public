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
package org.sonatype.nexus.repository.content.fluent.internal;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SequencedSet;
import java.util.SequencedCollection;
import java.util.LinkedHashSet;

import javax.annotation.Nullable;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.constraints.FluentQueryConstraint;
import org.sonatype.nexus.repository.types.GroupType;

import static java.util.Collections.singleton;

/**
 * Utility methods used by both {@link FluentAssetsImpl} and {@link FluentComponentsImpl}
 *
 * @since 3.27
 */
final class RepositoryContentUtil
{
  private RepositoryContentUtil() {
  }

  /**
   * Checks if the given repository is a group repository using pattern matching.
   * 
   * @param repository the repository to check
   * @return true if the repository is a group repository, false otherwise
   */
  static boolean isGroupRepository(final Repository repository) {
    // Using Java 21 pattern matching for instanceof to check repository type
    return repository.getType() instanceof GroupType;
  }

  /**
   * Get repository IDs based on constraints or fallback to the content facet's repository ID.
   * 
   * @param constraints the query constraints or null if none
   * @param contentFacet the content facet
   * @param repository the repository
   * @return a set of repository IDs
   */
  static Set<Integer> getRepositoryIds(
      @Nullable final List<FluentQueryConstraint> constraints,
      final ContentFacet contentFacet,
      final Repository repository)
  {
    // no constraints supplied, just use the repository of the contentFacet supplied
    if (constraints == null || constraints.isEmpty()) {
      return singleton(contentFacet.contentRepositoryId());
    }

    // Use SequencedSet to maintain order of repository IDs
    // Java 21 Sequenced Collections API provides better handling of ordered collections
    SequencedSet<Integer> repositoryIds = new LinkedHashSet<>();
    
    // Process each constraint and collect repository IDs
    for (var constraint : constraints) {
      // Use pattern matching to handle different types of collections
      Collection<Integer> ids = constraint.getRepositoryIds(repository);
      if (ids instanceof SequencedCollection<Integer> seqIds) {
        // Add elements in their encounter order if it's a sequenced collection
        seqIds.forEach(repositoryIds::add);
      } else {
        // Otherwise just add all elements
        repositoryIds.addAll(ids);
      }
    }

    // if we get to this point and no repository has been selected based on constraints, fallback to the repository
    // of the contentFacet supplied
    if (repositoryIds.isEmpty()) {
      repositoryIds.add(contentFacet.contentRepositoryId());
    }

    return repositoryIds;
  }
}