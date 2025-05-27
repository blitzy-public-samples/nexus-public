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
import java.util.SequencedSet;
import java.util.Set;
import java.util.logging.Logger;

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
  private static final Logger log = Logger.getLogger(RepositoryContentUtil.class.getName());
  
  private RepositoryContentUtil() {
  }

  /**
   * Checks if the given repository is a group repository using pattern matching.
   *
   * @param repository the repository to check
   * @return true if the repository is a group repository, false otherwise
   */
  static boolean isGroupRepository(final Repository repository) {
    // Using Java 21 enhanced pattern matching for repository type checking
    if (repository.getType() instanceof GroupType groupType) {
      // Using String Templates for more readable logging
      log.fine(STR."Repository \{repository.getName()} is a group repository with type \{groupType.getValue()}");
      return true;
    }
    return false;
  }

  /**
   * Gets repository IDs based on the provided constraints, content facet, and repository.
   * Uses Java 21 Sequenced Collections API for optimized handling of repository IDs.
   *
   * @param constraints the query constraints, may be null
   * @param contentFacet the content facet
   * @param repository the repository
   * @return a set of repository IDs
   */
  static Set<Integer> getRepositoryIds(
      @Nullable final List<FluentQueryConstraint> constraints,
      final ContentFacet contentFacet,
      final Repository repository)
  {
    // No constraints supplied, just use the repository of the contentFacet supplied
    if (constraints == null || constraints.isEmpty()) {
      Integer repoId = contentFacet.contentRepositoryId();
      log.fine(STR."No constraints supplied, using repository ID: \{repoId}");
      return singleton(repoId);
    }

    // Use SequencedSet to maintain insertion order and provide enhanced operations with Java 21 Sequenced Collections API
    SequencedSet<Integer> repositoryIds = constraints.stream()
        .flatMap(constraint -> {
          // Using pattern matching to handle different constraint types
          Collection<Integer> ids = constraint.getRepositoryIds(repository);
          log.fine(STR."Constraint \{constraint.getClass().getSimpleName()} provided \{ids.size()} repository IDs");
          return ids.stream();
        })
        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

    // If we get to this point and no repository has been selected based on constraints, fallback to the repository
    // of the contentFacet supplied
    if (repositoryIds.isEmpty()) {
      Integer repoId = contentFacet.contentRepositoryId();
      log.fine(STR."No repository IDs from constraints, falling back to repository ID: \{repoId}");
      repositoryIds.add(repoId);
    } else {
      // Using Sequenced Collections API to access first and last elements
      log.fine(STR."Using \{repositoryIds.size()} repository IDs from constraints, first: \{repositoryIds.getFirst()}, last: \{repositoryIds.getLast()}");
    }

    return repositoryIds;
  }
  
  /**
   * Optimizes repository ID flattening operations using Java 21 Sequenced Collections API.
   * This method demonstrates the use of enhanced collection APIs for handling repository IDs.
   *
   * @param repositoryIdCollections a collection of repository ID collections
   * @return a flattened set of repository IDs
   */
  static SequencedSet<Integer> flattenRepositoryIds(Collection<Collection<Integer>> repositoryIdCollections) {
    // Using Java 21 Sequenced Collections API for optimized flattening
    SequencedSet<Integer> flattenedIds = new java.util.LinkedHashSet<>();
    
    if (repositoryIdCollections.isEmpty()) {
      log.fine(STR."No repository ID collections to flatten");
      return flattenedIds;
    }
    
    // Process each collection of repository IDs
    for (Collection<Integer> idCollection : repositoryIdCollections) {
      // Using pattern matching to handle different collection types
      if (idCollection instanceof SequencedSet<Integer> sequencedIds) {
        // For sequenced collections, we can use the enhanced API
        if (!sequencedIds.isEmpty()) {
          log.fine(STR."Adding sequenced IDs from \{sequencedIds.getFirst()} to \{sequencedIds.getLast()}");
          flattenedIds.addAll(sequencedIds);
        }
      } else {
        // For regular collections, we add all elements
        flattenedIds.addAll(idCollection);
      }
    }
    
    // Using String Templates for more readable logging of the result
    if (!flattenedIds.isEmpty()) {
      log.fine(STR."Flattened \{repositoryIdCollections.size()} collections into \{flattenedIds.size()} unique repository IDs");
      log.fine(STR."First ID: \{flattenedIds.getFirst()}, Last ID: \{flattenedIds.getLast()}");
    } else {
      log.fine("No repository IDs found after flattening");
    }
    
    return flattenedIds;
  }
}