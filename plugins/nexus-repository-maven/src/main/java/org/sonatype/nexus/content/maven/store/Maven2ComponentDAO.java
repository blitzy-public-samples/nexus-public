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

import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.datastore.api.ContentDataAccess;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.SqlAdapter;
import org.sonatype.nexus.repository.content.SqlGenerator;
import org.sonatype.nexus.repository.content.SqlQueryParameters;
import org.sonatype.nexus.repository.content.store.ComponentDAO;
import org.sonatype.nexus.repository.content.store.OrderedComponentData;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.SelectProvider;

/**
 * Maven Component {@link ContentDataAccess}.
 * 
 * <p>This interface has been updated for Java 21 compatibility and works with the latest
 * dependencies (Guice 7.0.0, Sisu 0.10.0, Jetty 12.0.5, Shiro 2.0.0).</p>
 * 
 * <p>When implementing or using this interface with Java 21, you can leverage pattern matching
 * for more concise code. For example:</p>
 * 
 * <pre>
 * {@code
 * // Using pattern matching with instanceof
 * if (component instanceof Maven2ComponentData maven2Component && 
 *     maven2Component.getBaseVersion() != null) {
 *     // Use maven2Component directly
 * }
 * 
 * // Using pattern matching with switch expressions
 * String version = switch (component) {
 *     case Maven2ComponentData maven2Component -> maven2Component.getBaseVersion();
 *     default -> component.version();
 * };
 * }
 * </pre>
 */
public interface Maven2ComponentDAO
    extends ComponentDAO
{
  /**
   * Adds base_version column. See {@see Maven2ComponentDAO.xml}
   * 
   * @since 3.29
   */
  @Override
  void extendSchema();

  /**
   * Updates the maven base_version of the given component in the content data store.
   * 
   * <p>In Java 21, you can use pattern matching when working with components:</p>
   * <pre>
   * {@code
   * // Before updating, you can check the component type with pattern matching
   * if (someComponent instanceof Maven2ComponentData mavenComponent) {
   *     mavenComponent.setBaseVersion(newBaseVersion);
   *     updateBaseVersion(mavenComponent);
   * }
   * }
   * </pre>
   *
   * @param component the component to update
   */
  void updateBaseVersion(Maven2ComponentData component);

  /**
   * Find all GAVs that qualify for deletion.
   * 
   * <p>The returned Set can be processed efficiently using Java 21 features:</p>
   * <pre>
   * {@code
   * // Using enhanced for loop with pattern matching
   * for (GAV gav : findGavsWithSnaphots(repoId, minRetained)) {
   *     String groupId = gav.group;
   *     String artifactId = gav.name;
   *     // Process GAV information
   * }
   * 
   * // Using streams with pattern matching in filters
   * findGavsWithSnaphots(repoId, minRetained).stream()
   *     .filter(gav -> gav.count > threshold)
   *     .forEach(gav -> processGav(gav));
   * }
   * </pre>
   *
   * @param repositoryId the repository to select from
   * @param minimumRetained the minimum number of snapshots to keep
   * @return all GAVs that qualify for deletion
   *
   * @since 3.30
   */
  Set<GAV> findGavsWithSnaphots(@Param("repositoryId") final int repositoryId,
                                @Param("minimumRetained") final int minimumRetained);

  /**
   * Find components by Group Artifact Version(GAVs).
   * Eagerly fetches {@link org.sonatype.nexus.repository.content.store.AssetBlobData}
   * & {@link org.sonatype.nexus.repository.content.store.AssetData}
   * 
   * <p>With Java 21, you can process the returned components using pattern matching and
   * enhanced stream operations:</p>
   * 
   * <pre>
   * {@code
   * // Using streams with pattern matching in map operations
   * List<String> baseVersions = findComponentsForGav(repoId, name, group, baseVersion, releaseVersion)
   *     .stream()
   *     .map(Maven2ComponentData::getBaseVersion)
   *     .distinct()
   *     .toList(); // Using toList() collector from Java 17+
   * }
   * </pre>
   *
   * @param repositoryId the repository to select from
   * @param name artifact name
   * @param group artifact group
   * @param baseVersion artifact base version
   * @param releaseVersion artifact release version
   * @return all components by Group Artifact Version(GAVs)
   *
   * @since 3.30
   */
  List<Maven2ComponentData> findComponentsForGav(@Param("repositoryId") final int repositoryId,
                                                 @Param("name") final String name,
                                                 @Param("group") final String group,
                                                 @Param("baseVersion") final String baseVersion,
                                                 @Param("releaseVersion") final String releaseVersion);

  /**
   * Retrieve known base versions for a provided GA.
   * 
   * <p>In Java 21, you can process the returned set using enhanced string operations
   * and pattern matching:</p>
   * 
   * <pre>
   * {@code
   * // Using text blocks for SQL-like string operations
   * String query = """
   *     SELECT * FROM components 
   *     WHERE base_version IN (%s)
   *     """.formatted(String.join(",", getBaseVersions(repoId, namespace, name)));
   * 
   * // Using streams with filtering
   * boolean hasSnapshot = getBaseVersions(repoId, namespace, name).stream()
   *     .anyMatch(version -> version.endsWith("-SNAPSHOT"));
   * }
   * </pre>
   *
   * @param repositoryId the repository containing the components
   * @param namespace    the namespace for the components
   * @param name         the name for the components
   *
   * @return a unique set of base versions
   * 
   * @since 3.30
   */
  Set<String> getBaseVersions(
      @Param("repositoryId") int repositoryId,
      @Param("namespace") String namespace,
      @Param("name") String name);

  /**
   * Find snapshots to delete for which a release version exists.
   * 
   * <p>With Java 21, you can process the returned array using enhanced array operations:</p>
   * 
   * <pre>
   * {@code
   * // Using Arrays.stream for primitive int arrays
   * int[] componentIds = selectSnapshotsAfterRelease(repoId, gracePeriod);
   * long count = Arrays.stream(componentIds).count();
   * 
   * // Using text blocks for logging
   * logger.debug("""
   *     Found %d snapshot components to delete after release:
   *     Repository ID: %d
   *     Grace Period: %d days
   *     """.formatted(componentIds.length, repoId, gracePeriod));
   * }
   * </pre>
   *
   * @param repositoryId the repository to select from
   * @param gracePeriod an optional period to keep snapshots around (in days)
   * @return array of snapshot components IDs to delete for which a release version exists
   *
   * @since 3.30
   */
  int[] selectSnapshotsAfterRelease(@Param("repositoryId") final int repositoryId,
                                   @Param("gracePeriod") final int gracePeriod);
  /**
   * Selects snapshot components ids last used before provided date.
   * 
   * <p>With Java 21, you can process the returned collection using enhanced collection operations
   * and pattern matching:</p>
   * 
   * <pre>
   * {@code
   * // Using streams with collectors
   * Collection<Integer> componentIds = selectUnusedSnapshots(repoId, olderThan, limit);
   * 
   * // Convert to primitive array if needed
   * int[] idArray = componentIds.stream().mapToInt(Integer::intValue).toArray();
   * 
   * // Using text blocks for SQL-like operations
   * String query = """
   *     DELETE FROM components 
   *     WHERE component_id IN (%s)
   *     """.formatted(componentIds.stream()
   *                    .map(String::valueOf)
   *                    .collect(Collectors.joining(",")));
   * }
   * </pre>
   *
   * @param repositoryId the repository to select from
   * @param olderThan    selects component before this date
   * @param limit        limit the selection
   * @return snapshot components last used before provided date
   *
   * @since 3.30
   */
  Collection<Integer> selectUnusedSnapshots(@Param("repositoryId") int repositoryId,
                                            @Param("olderThan") LocalDate olderThan,
                                            @Param("limit") long limit);

  /**
   * Selects components based on the provided SQL generator and parameters.
   * 
   * <p>This method is compatible with Java 21 and updated dependencies including
   * Guice 7.0.0, Sisu 0.10.0, and Shiro 2.0.0.</p>
   *
   * @param generator the SQL generator to use
   * @param params the SQL query parameters
   * @return a continuation of components
   */
  @Override
  @ResultMap("OrderedComponentDataMap")
  @ResultType(OrderedComponentData.class)
  @SelectProvider(type = SqlAdapter.class, method = "select")
  Continuation<Component> selectComponents(final SqlGenerator generator,
                                           @Param("params") final SqlQueryParameters params);

  /**
   * Selects components with their associated assets based on the provided SQL generator and parameters.
   * 
   * <p>This method is compatible with Java 21 and updated dependencies including
   * Guice 7.0.0, Sisu 0.10.0, and Shiro 2.0.0.</p>
   *
   * @param generator the SQL generator to use
   * @param params the SQL query parameters
   * @return a continuation of components with their assets
   */
  @Override
  @ResultMap("OrderedComponentAssetsDataMap")
  @ResultType(OrderedComponentData.class)
  @SelectProvider(type = SqlAdapter.class, method = "select")
  Continuation<Component> selectComponentsWithAssets(final SqlGenerator generator,
                                                     @Param("params") final SqlQueryParameters params);
}
