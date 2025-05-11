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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.HashType;
import org.sonatype.nexus.repository.maven.internal.Constants;
import org.sonatype.nexus.repository.maven.internal.MavenMimeRulesSource;
import org.sonatype.nexus.repository.maven.internal.MavenModels;
import org.sonatype.nexus.repository.maven.internal.hosted.metadata.AbstractMetadataUpdater;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.sonatype.nexus.repository.view.payloads.StringPayload;

import org.apache.maven.artifact.repository.metadata.Metadata;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.StringTemplate.STR;

/**
 * Maven metadata updater implementation for datastore repositories.
 * 
 * @since 3.26
 */
public class DatastoreMetadataUpdater
    extends AbstractMetadataUpdater
{
  public DatastoreMetadataUpdater(final boolean update, final Repository repository) {
    super(update, repository);
  }

  @Override
  protected void write(final MavenPath mavenPath, final Metadata metadata) throws IOException {
    // Use a virtual thread for I/O operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Void> writeTask = executor.submit(() -> {
        MavenContentFacet mavenContentFacet = repository.facet(MavenContentFacet.class);
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        MavenModels.writeMetadata(buffer, metadata);
        mavenContentFacet.put(mavenPath, new BytesPayload(buffer.toByteArray(), MavenMimeRulesSource.METADATA_TYPE));

        final Optional<Map<String, String>> hashCodes = mavenContentFacet
            .assets()
            .path("/" + mavenPath.getPath())
            .find()
            .flatMap(Asset::blob)
            .map(AssetBlob::checksums);
        checkState(hashCodes.isPresent(), "hashCodes");

        for (HashType hashType : HashType.values()) {
          MavenPath checksumPath = mavenPath.hash(hashType);
          String hashCode = hashCodes.get().get(hashType.getHashAlgorithm().name());
          checkState(hashCode != null, STR."hashCode: type=\{hashType}");
          mavenContentFacet.put(checksumPath, new StringPayload(hashCode, Constants.CHECKSUM_CONTENT_TYPE));
        }
        return null;
      });
      
      try {
        writeTask.get(); // Wait for the task to complete
      } 
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while writing metadata", e);
      } 
      catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException) {
          throw (IOException) cause;
        }
        throw new IOException("Error writing metadata", cause);
      }
    }
  }

  @Override
  protected Optional<Metadata> read(final MavenPath mavenPath) throws IOException {
    // Use a virtual thread for I/O operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Optional<Metadata>> readTask = executor.submit(() -> {
        return repository.facet(MavenContentFacet.class)
            .get(mavenPath)
            .map(content -> {
              try {
                return MavenModels.readMetadata(content.openInputStream());
              }
              catch (IOException e) {
                log.warn(STR."Corrupted metadata \{repository.getName()} @ \{mavenPath.getPath()}");
                return null;
              }
            });
      });
      
      try {
        return readTask.get(); // Wait for the task to complete
      } 
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while reading metadata", e);
      } 
      catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException) {
          throw (IOException) cause;
        }
        throw new IOException("Error reading metadata", cause);
      }
    }
  }

  @Override
  protected void delete(final MavenPath mavenPath) {
    checkNotNull(repository);
    checkNotNull(mavenPath);
    try {
      // Use a virtual thread for I/O operations
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<Void> deleteTask = executor.submit(() -> {
          repository.facet(MavenContentFacet.class).deleteWithHashes(mavenPath);
          return null;
        });
        
        try {
          deleteTask.get(); // Wait for the task to complete
        } 
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while deleting metadata", e);
        } 
        catch (ExecutionException e) {
          Throwable cause = e.getCause();
          if (cause instanceof IOException) {
            throw (IOException) cause;
          }
          throw new IOException("Error deleting metadata", cause);
        }
      }
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}