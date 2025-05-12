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
package org.sonatype.nexus.testsuite.helpers;

import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.CacheInfo;

import org.joda.time.DateTime;

/**
 * Interface for component and asset testing operations, optimized for Java 21 compatibility.
 * <p>
 * This interface provides methods for testing repository components and assets, with support for
 * Java 21 features including virtual threads for I/O operations and enhanced pattern matching.
 * Implementations should ensure thread safety and compatibility with concurrent operations.
 */
public interface ComponentAssetTestHelper
{
  /**
   * Get the created time of the the asset at the path in the given repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return the creation timestamp
   * @throws AssetNotFoundException if the asset cannot be found
   */
  DateTime getAssetCreatedTime(Repository repository, String path);

  /**
   * Get the updated time of the blob associated with the asset at the path in the given repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return the blob update timestamp
   * @throws AssetNotFoundException if the asset cannot be found
   * @throws BlobNotFoundException if the blob cannot be found
   */
  DateTime getBlobUpdatedTime(Repository repository, String path);

  /**
   * Get the last downloaded time for a path in the given repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return the last downloaded timestamp
   * @throws AssetNotFoundException if the asset cannot be found
   */
  DateTime getLastDownloadedTime(Repository repository, String path);

  /**
   * Get the 'created by'(user who created the asset) for a path in the given repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return the creator identifier
   * @throws AssetNotFoundException if the asset cannot be found
   */
  String getCreatedBy(Repository repository, String path);

  /**
   * Get the 'created by ip'(IP address of a user who created the asset) for a path in the given repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return the creator's IP address
   * @throws AssetNotFoundException if the asset cannot be found
   */
  String getCreatedByIP(Repository repository, String path);

  /**
   * Retrieves the CacheInfo for the asset if it exists.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return the cache information or null if not available
   * @throws AssetNotFoundException if the asset cannot be found
   */
  @Nullable
  CacheInfo getCacheInfo(Repository repository, String path);

  /**
   * Delete a component in a repository from the database.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param namespace the component namespace
   * @param name the component name
   * @param version the component version
   * @throws ComponentNotFoundException if the component cannot be found
   */
  void deleteComponent(Repository repository, String namespace, String name, String version);

  /**
   * Delete a component in a repository from the database.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param name the component name
   * @param version the component version
   * @throws ComponentNotFoundException if the component cannot be found
   */
  void deleteComponent(Repository repository, String name, String version);

  /**
   * Remove an component from a repository using ComponentMaintenance or similar.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param namespace the component namespace
   * @param name the component name
   * @param version the component version
   * @throws ComponentNotFoundException if the component cannot be found
   */
  void removeComponent(final Repository repository, String namespace, String name, String version);

  /**
   * Remove an asset from a repository using ComponentMaintenance or similar.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @throws AssetNotFoundException if the asset cannot be found
   */
  void removeAsset(final Repository repository, final String path);

  /**
   * Retrieve the paths for all assets in a repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   * The returned list may be processed using Java 21's sequenced collection features.
   *
   * @param repositoryName the name of the repository
   * @return a list of asset paths
   */
  List<String> findAssetPaths(final String repositoryName);

  /**
   * Retrieve the AssetKind from asset at the given path.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return the asset kind identifier
   * @throws AssetNotFoundException if the asset cannot be found
   */
  String assetKind(Repository repository, String path);

  /**
   * Retrieve the asset checksums.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return a map of checksum types to checksum values
   * @throws AssetNotFoundException if the asset cannot be found
   */
  Map<String, String> checksums(Repository repository, String path);

  /**
   * Verify that an asset at the given path exists for the specified repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return true if the asset exists, false otherwise
   */
  boolean assetExists(Repository repository, String path);

  /**
   * Verify that an asset at the given component name and format extension exists for the specified repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param componentName the component name
   * @param formatExtension the format extension
   * @return true if the asset exists, false otherwise
   */
  boolean assetExists(Repository repository, String componentName, String formatExtension);

  /**
   * Verify that an asset at the given path exists for the specified repository, and associated with a component.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @param group the component group
   * @param name the component name
   * @return true if the asset exists and is associated with the component, false otherwise
   */
  boolean assetWithComponentExists(Repository repository, String path, String group, String name);

  /**
   * Verify that an asset at the given path exists for the specified repository, and associated with a component.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @param group the component group
   * @param name the component name
   * @param version the component version
   * @return true if the asset exists and is associated with the component, false otherwise
   */
  boolean assetWithComponentExists(Repository repository, String path, String group, String name, String version);

  /**
   * Verify that an asset at the given path exists for the specified repository, and is not associated with a component.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return true if the asset exists and is not associated with a component, false otherwise
   */
  boolean assetWithoutComponentExists(Repository repository, String path);

  /**
   * Verify that a component with the given name exists for the specified repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param name the component name
   * @return true if the component exists, false otherwise
   */
  boolean componentExists(Repository repository, String name);

  /**
   * Verify that a component with the given name matcher exists for the specified repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   * The predicate can leverage Java 21's pattern matching for enhanced type checking.
   *
   * @param repository the containing repository
   * @param nameMatcher a predicate to match component names
   * @return true if a matching component exists, false otherwise
   */
  boolean checkComponentExist(Repository repository, Predicate<String> nameMatcher);

  /**
   * Verify that a component with the given name and version exists for the specified repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param name the component name
   * @param version the component version
   * @return true if the component exists, false otherwise
   */
  boolean componentExists(Repository repository, String name, String version);

  /**
   * Verify that a component at the given namespace, name, and version exists for the specified repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param namespace the component namespace
   * @param name the component name
   * @param version the component version
   * @return true if the component exists, false otherwise
   */
  boolean componentExists(Repository repository, String namespace, String name, String version);

  /**
   * Verify that a component with an asset that matches the path exists.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   * The predicate can leverage Java 21's pattern matching for enhanced type checking.
   *
   * @param repository the containing repository
   * @param pathMatcher a predicate to match asset paths
   * @return true if a component with a matching asset exists, false otherwise
   */
  boolean componentExistsWithAssetPathMatching(Repository repository, Predicate<String> pathMatcher);

  /**
   * Retrieve content type for a path within the repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return the content type string
   * @throws AssetNotFoundException if the asset cannot be found
   */
  String contentTypeFor(final Repository repository, final String path);

  /**
   * Count the number of assets in the given repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @return the asset count
   */
  int countAssets(Repository repository);

  /**
   * Count the number of components in the given repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @return the component count
   */
  int countComponents(final Repository repository);

  /**
   * Retrieve the attributes for the asset at the given path.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return the nested attributes map
   * @throws AssetNotFoundException if the asset cannot be found
   */
  NestedAttributesMap attributes(Repository repository, String path);

  /**
   * Retrieve the format specific attributes for the asset at the given path.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return the format-specific nested attributes map
   * @throws AssetNotFoundException if the asset cannot be found
   */
  default NestedAttributesMap formatAttributes(final Repository repository, final String path) {
    return attributes(repository, path).child(repository.getFormat().getValue());
  }

  /**
   * Retrieve the attributes for the component.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param namespace the component namespace
   * @param name the component name
   * @param version the component version
   * @return the nested attributes map
   * @throws ComponentNotFoundException if the component cannot be found
   */
  NestedAttributesMap componentAttributes(Repository repository, String namespace, String name, String version);

  /**
   * Retrieve the attributes for the component.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param namespace the component namespace
   * @param name the component name
   * @return the nested attributes map
   * @throws ComponentNotFoundException if the component cannot be found
   * 
   * NOTE: this is intended for formats which do not have versions (e.g. raw)
   */
  NestedAttributesMap componentAttributes(Repository repository, String namespace, String name);

  /**
   * Retrieve the attributes for a snapshot component.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param name the component name
   * @param version the component version
   * @return the nested attributes map
   * @throws ComponentNotFoundException if the component cannot be found
   */
  NestedAttributesMap snapshotComponentAttributes(Repository repository, String name, String version);

  /**
   * Set the last downloaded time for all assets in a repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param minusSeconds seconds to subtract from current time
   */
  void setLastDownloadedTime(Repository repository, int minusSeconds);

  /**
   * Set the last downloaded time for assets where path matches regex in a repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param minusSeconds seconds to subtract from current time
   * @param regex regular expression to match asset paths
   */
  void setLastDownloadedTime(Repository repository, int minusSeconds, String regex);

  /**
   * Set the last downloaded time for the asset.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @param date the date to set
   * @throws AssetNotFoundException if the asset cannot be found
   */
  void setLastDownloadedTime(Repository repository, String path, Date date);

  /**
   * Set the last updated time for all components in a repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param date the date to set
   */
  void setComponentLastUpdatedTime(Repository repository, final Date date);

  /**
   * Semantically set the date the asset was originally created. For Orient this will be the BlobCreated time, for SQL
   * this will be Asset.created.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @param date the date to set
   * @throws AssetNotFoundException if the asset cannot be found
   */
  void setAssetCreatedTime(Repository repository, String path, Date date);

  /**
   * Set the last updated time for all assets in a repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param date the date to set
   */
  void setAssetLastUpdatedTime(final Repository repository, final Date date);

  /**
   * Set the last updated time for a single asset.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @param date the date to set
   * @throws AssetNotFoundException if the asset cannot be found
   */
  void setAssetLastUpdatedTime(final Repository repository, final String path, final Date date);

  /**
   * Semantically sets the date when the blob was changed. For Orient this is the blobLastUpdated, for SQL this is the
   * AssetBlob.created time.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param pathRegex regular expression to match asset paths
   * @param date the date to set
   */
  void setBlobUpdatedTime(final Repository repository, final String pathRegex, final Date date);

  /**
   * Set the last modified date associated with remote content.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @param date the date to set
   * @throws AssetNotFoundException if the asset cannot be found
   * 
   * Note: For SQL this is only applicable to proxy repositories, while for Orient this is set for both
   */
  void setAssetContentLastModified(Repository repository, String path, Date date);

  /**
   * Set null to the last downloaded time column for all assets in a repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   */
  void setLastDownloadedTimeNull(Repository repository);

  /**
   * Set the last downloaded time for any asset matching the pathMatcher in the given repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   * The predicate can leverage Java 21's pattern matching for enhanced type checking.
   *
   * @param repository the containing repository
   * @param minusSeconds seconds to subtract from current time
   * @param pathMatcher a predicate to match asset paths
   */
  void setLastDownloadedTime(Repository repository, int minusSeconds, Predicate<String> pathMatcher);

  /**
   * Adjust {@code path} for differences between Orient and Datastore.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param path the path to adjust
   * @return the adjusted path
   */
  String adjustedPath(final String path);

  /**
   * Read an asset from the specified repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   * The returned Optional can be processed using Java 21's pattern matching for enhanced type checking.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return an optional input stream containing the asset content
   */
  Optional<InputStream> read(Repository repository, String path);

  /**
   * Delete the blob associated with the asset with the specified path.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param assetPath the path of the asset
   * @throws AssetNotFoundException if the asset cannot be found
   * @throws BlobNotFoundException if the blob cannot be found
   */
  void deleteAssetBlob(Repository repository, String assetPath);

  /**
   * Get the blob associated with the asset with the specified path.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   * The returned Optional can be processed using Java 21's pattern matching for enhanced type checking.
   *
   * @param repository the containing repository
   * @param assetPath the path of the asset
   * @return an optional blob
   */
  Optional<Blob> getBlob(Repository repository, String assetPath);

  /**
   * Obtains a blob ref of an asset in the given repo with the specified path.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param path the path of the asset
   * @return the blob reference
   * @throws AssetNotFoundException if the asset cannot be found
   * @throws BlobNotFoundException if the blob cannot be found
   */
  BlobRef getBlobRefOfAsset(Repository repository, String path);

  /**
   * Gets the id of the component associated with the specified asset and repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the containing repository
   * @param assetPath the path of the asset
   * @return the component id or null if the asset is not associated with a component
   * @throws AssetNotFoundException if the asset cannot be found
   */
  @Nullable
  EntityId getComponentId(Repository repository, String assetPath);

  /**
   * Get the attributes for a repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the repository
   * @return the nested attributes map
   */
  NestedAttributesMap getAttributes(Repository repository);

  /**
   * Modify attributes for a repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the repository
   * @param child1 the first child key
   * @param child2 the second child key
   * @param value the value to set
   */
  void modifyAttributes(final Repository repository, String child1, final String child2, final int value);

  /**
   * Deletes all components from the given repository.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param repository the repository from which all components are going to be deleted
   */
  void deleteAllComponents(final Repository repository);

  /**
   * Update aggregate metrics.
   * <p>
   * This operation is suitable for execution in virtual threads when implemented for I/O-bound operations.
   *
   * @param metricName the metric name
   * @param metricValue the metric value
   */
  void updateAggregateMetrics(String metricName, Long metricValue);

  /**
   * Do not implement this method, it is not correct to do so. Orient & SQL have different semantics for the lifecycle
   * of blobs:<br/>
   *
   * {@code orient.blobCreated == sql.assetCreated}<br/>
   * {@code orient.blobUpdated == sql.blobCreated}
   *
   * @see #getAssetCreatedTime(Repository, String)
   * @see #getBlobUpdatedTime(Repository, String)
   *
   * @deprecated Do not implement this method
   */
  @Deprecated
  default DateTime getBlobCreatedTime(final Repository repository, final String path) {
    throw new UnsupportedOperationException("Do not implement me");
  }

  /**
   * Exception thrown when an asset cannot be found.
   */
  class AssetNotFoundException
      extends RuntimeException
  {
    /**
     * Constructs a new asset not found exception.
     *
     * @param repository the repository where the asset was expected
     * @param path the path of the missing asset
     */
    AssetNotFoundException(final Repository repository, final String path) {
      super("Missing asset: " + path + " from repository: " + repository.getName());
    }
  }

  /**
   * Exception thrown when a blob cannot be found.
   */
  class BlobNotFoundException
      extends RuntimeException
  {
    /**
     * Constructs a new blob not found exception.
     *
     * @param repository the repository where the blob was expected
     * @param path the path of the asset with the missing blob
     */
    BlobNotFoundException(final Repository repository, final String path) {
      super("Missing blob for the asset: " + path + " from repository: " + repository.getName());
    }
  }

  /**
   * Exception thrown when a component cannot be found.
   */
  class ComponentNotFoundException
      extends RuntimeException
  {
    /**
     * Constructs a new component not found exception.
     *
     * @param repository the repository where the component was expected
     * @param namespace the namespace of the missing component
     * @param name the name of the missing component
     * @param version the version of the missing component
     */
    ComponentNotFoundException(
        final Repository repository,
        final String namespace,
        final String name,
        final String version)
    {
      super(String.format("Repository:%s namespace:%s name:%s version:%s", repository.getName(), namespace, name,
          version));
    }
  }
}