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
package org.sonatype.nexus.content.raw;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.importtask.ImportFileConfiguration;
import org.sonatype.nexus.repository.raw.RawUploadHandlerSupport;
import org.sonatype.nexus.repository.raw.internal.RawFormat;
import org.sonatype.nexus.repository.rest.UploadDefinitionExtension;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapter;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.PartPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.repository.view.payloads.TempBlobPayload;

import com.google.common.collect.Lists;

/**
 * Support for uploading raw components via UI & API
 *
 * @since 3.24
 */
@Named(RawFormat.NAME)
@Singleton
public class RawUploadHandler
    extends RawUploadHandlerSupport
{
  @Inject
  public RawUploadHandler(final ContentPermissionChecker contentPermissionChecker,
                          @Named("simple") final VariableResolverAdapter variableResolverAdapter,
                          final Set<UploadDefinitionExtension> uploadDefinitionExtensions)
  {
    super(contentPermissionChecker, variableResolverAdapter, uploadDefinitionExtensions, true);
  }

  @Override
  protected List<Content> getResponseContents(final Repository repository, final Map<String, PartPayload> pathToPayload)
      throws IOException
  {
    RawContentFacet facet = repository.facet(RawContentFacet.class);
    
    // Use a list to store the Future objects for each upload task
    List<Future<Content>> futures = new ArrayList<>(pathToPayload.size());
    List<Content> responseContents = new ArrayList<>(pathToPayload.size());
    
    // Create a virtual thread executor for parallel processing
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit each upload task to the executor
      for (Entry<String, PartPayload> entry : pathToPayload.entrySet()) {
        String path = entry.getKey();
        PartPayload payload = entry.getValue();
        
        // Submit the task and store the Future
        futures.add(executor.submit(() -> facet.put(path, payload)));
      }
      
      // Collect results from all futures
      for (Future<Content> future : futures) {
        try {
          Content content = future.get();
          responseContents.add(content);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Upload processing was interrupted", e);
        } catch (ExecutionException e) {
          Throwable cause = e.getCause();
          if (cause instanceof IOException) {
            throw (IOException) cause;
          } else {
            throw new IOException("Error during parallel upload processing", cause);
          }
        }
      }
    }
    
    return responseContents;
  }

  @Override
  protected Content doPut(final ImportFileConfiguration configuration) throws IOException {
    Repository repository = configuration.getRepository();
    String path = configuration.getAssetName();
    Path contentPath = configuration.getFile().toPath();

    RawContentFacet contentFacet = repository.facet(RawContentFacet.class);
    String contentType = Files.probeContentType(contentPath);
    try (TempBlob blob = contentFacet.blobs().ingest(contentPath, contentType, RawContentFacet.HASHING,
        configuration.isHardLinkingEnabled())) {
      return contentFacet.put(path, new TempBlobPayload(blob, contentType));
    }
  }

  @Override
  protected String normalizePath(final String path) {
    return "/" + super.normalizePath(path);
  }
}
