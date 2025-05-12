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
package org.sonatype.nexus.repository.apt.datastore;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import javax.annotation.Nullable;

import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.apt.internal.debian.PackageInfo;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;

/**
 * Apt content facet
 *
 * This interface defines operations for managing APT repository content. With Java 21,
 * implementations can leverage Virtual Threads for improved I/O performance, particularly
 * for operations like content retrieval, storage, and streaming.
 *
 * @since 3.31
 */
@Facet.Exposed
public interface AptContentFacet
    extends ContentFacet
{
  /**
   * Get the distribution name for this repository.
   *
   * @return the distribution name
   */
  String getDistribution();

  /**
   * Check if this repository has a flat structure.
   *
   * @return true if the repository has a flat structure, false otherwise
   */
  boolean isFlat();

  /**
   * Get an asset by its path.
   *
   * @param path the asset path
   * @return the asset if found, empty otherwise
   */
  Optional<FluentAsset> getAsset(String path);

  /**
   * Get content by its asset path.
   * 
   * With Java 21, this I/O operation can benefit from Virtual Threads for improved throughput
   * when handling multiple concurrent requests.
   *
   * @param assetPath the asset path
   * @return the content if found, empty otherwise
   */
  Optional<Content> get(String assetPath);

  /**
   * Put content at the specified path.
   * 
   * With Java 21, this I/O operation can benefit from Virtual Threads for improved throughput
   * when handling multiple concurrent uploads.
   *
   * @param path the path to store the content at
   * @param content the content to store
   * @return the created asset
   * @throws IOException if an I/O error occurs
   */
  FluentAsset put(String path, Payload content) throws IOException;

  /**
   * Put content at the specified path with package info.
   * 
   * With Java 21, this I/O operation can benefit from Virtual Threads for improved throughput
   * when handling multiple concurrent uploads.
   *
   * @param path the path to store the content at
   * @param payload the content to store
   * @param packageInfo the package info, may be null
   * @return the created asset
   * @throws IOException if an I/O error occurs
   */
  FluentAsset put(String path, Payload payload, @Nullable PackageInfo packageInfo) throws IOException;

  /**
   * Find or create a metadata asset.
   * 
   * With Java 21, this operation can benefit from Virtual Threads when performing I/O operations.
   *
   * @param tempBlob the temporary blob containing the metadata
   * @param path the path to store the metadata at
   * @return the found or created asset
   */
  FluentAsset findOrCreateMetadataAsset(TempBlob tempBlob, String path);

  /**
   * Get a temporary blob from a payload.
   * 
   * With Java 21, this I/O operation can benefit from Virtual Threads for improved throughput
   * when handling multiple concurrent blob operations.
   *
   * @param payload the payload to create a temporary blob from
   * @return the temporary blob
   */
  TempBlob getTempBlob(Payload payload);

  /**
   * Get a temporary blob from an input stream.
   * 
   * With Java 21, this I/O operation can benefit from Virtual Threads for improved throughput
   * when handling multiple concurrent blob operations.
   *
   * @param in the input stream to create a temporary blob from
   * @param contentType the content type, may be null
   * @return the temporary blob
   */
  TempBlob getTempBlob(InputStream in, @Nullable String contentType);

  /**
   * Delete assets by prefix.
   * 
   * With Java 21, this operation can benefit from Virtual Threads when performing I/O operations.
   *
   * @param pathPrefix the path prefix to match assets to delete
   */
  void deleteAssetsByPrefix(String pathPrefix);

  /**
   * Get all APT package assets.
   * 
   * With Java 21, this operation can benefit from Virtual Threads when performing I/O operations
   * to retrieve and process multiple assets concurrently.
   *
   * @return an iterable of APT package assets
   */
  Iterable<FluentAsset> getAptPackageAssets();
}