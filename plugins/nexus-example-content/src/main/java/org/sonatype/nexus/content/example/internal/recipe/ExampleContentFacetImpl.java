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
package org.sonatype.nexus.content.example.internal.recipe;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.content.example.ExampleContentFacet;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;

import com.google.common.collect.ImmutableList;

import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA256;

/**
 * Provides persistent content for an 'example' format.
 *
 * @since 3.24
 */
@Named(ExampleFormat.NAME)
public class ExampleContentFacetImpl
    extends ContentFacetSupport
    implements ExampleContentFacet
{
  private static final Iterable<HashAlgorithm> HASHING = ImmutableList.of(SHA256);

  @Inject
  public ExampleContentFacetImpl(
      @Named(ExampleFormat.NAME) final FormatStoreManager formatStoreManager)
  {
    super(formatStoreManager);
  }

  @Override
  public Optional<Content> get(final String path) {
    // Create a virtual thread executor for I/O-bound operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit the asset retrieval operation to the virtual thread executor
      return CompletableFuture.supplyAsync(() -> assets().path(path).find().map(FluentAsset::download), executor)
          .join();
    }
  }

  @Override
  public Content put(final String path, final Payload content) throws IOException {
    // Create a virtual thread executor for I/O-bound operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit the content storage operation to the virtual thread executor
      return CompletableFuture.supplyAsync(() -> {
        try (TempBlob blob = blobs().ingest(content, HASHING)) {
          return assets().path(path).blob(blob).save().markAsCached(content).download();
        } catch (IOException e) {
          throw new RuntimeException("Failed to store content at path: " + path, e);
        }
      }, executor).join();
    }
  }

  @Override
  public boolean delete(final String path) throws IOException {
    // Create a virtual thread executor for I/O-bound operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit the asset deletion operation to the virtual thread executor
      return CompletableFuture.supplyAsync(
          () -> assets().path(path).find().map(FluentAsset::delete).orElse(false),
          executor).join();
    }
  }
}