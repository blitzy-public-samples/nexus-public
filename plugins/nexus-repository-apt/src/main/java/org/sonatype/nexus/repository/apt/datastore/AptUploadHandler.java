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
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.apt.AptUploadHandlerSupport;
import org.sonatype.nexus.repository.apt.datastore.internal.hosted.AptHostedFacet;
import org.sonatype.nexus.repository.apt.internal.AptFacetHelper;
import org.sonatype.nexus.repository.apt.AptFormat;
import org.sonatype.nexus.repository.apt.internal.AptPackageParser;
import org.sonatype.nexus.repository.apt.internal.debian.ControlFile;
import org.sonatype.nexus.repository.apt.internal.debian.PackageInfo;
import org.sonatype.nexus.repository.rest.UploadDefinitionExtension;
import org.sonatype.nexus.repository.security.ContentPermissionChecker;
import org.sonatype.nexus.repository.security.VariableResolverAdapter;
import org.sonatype.nexus.repository.upload.ComponentUpload;
import org.sonatype.nexus.repository.upload.UploadResponse;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.PartPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.thread.NexusExecutorService;

import static org.apache.commons.lang3.StringUtils.prependIfMissing;
import static org.sonatype.nexus.security.subject.FakeAlmightySubject.TASK_SUBJECT;

/**
 * Support for uploading an Apt components via UI.
 * <p>
 * This implementation leverages Java 21 Virtual Threads for efficient handling of file uploads,
 * which are I/O-bound operations. Virtual threads provide higher throughput with minimal resource
 * overhead compared to traditional platform threads, allowing for better scalability when handling
 * multiple concurrent uploads.
 *
 * @since 3.31
 * @see org.sonatype.nexus.thread.NexusExecutorService#forVirtualThreads(org.apache.shiro.subject.Subject)
 */
@Singleton
@Named(AptFormat.NAME)
public class AptUploadHandler
    extends AptUploadHandlerSupport
{
  /**
   * Executor service for handling file uploads using Java 21 Virtual Threads.
   * Virtual threads are lightweight and efficient for I/O-bound operations like file uploads,
   * allowing for higher throughput with minimal resource overhead.
   */
  private final ExecutorService virtualThreadExecutor;
  
  @Inject
  public AptUploadHandler(@Named("simple") final VariableResolverAdapter variableResolverAdapter,
                          final ContentPermissionChecker contentPermissionChecker,
                          final Set<UploadDefinitionExtension> uploadDefinitionExtensions)
  {
    super(variableResolverAdapter, contentPermissionChecker, uploadDefinitionExtensions);
    // Create a virtual thread executor for I/O-bound operations
    this.virtualThreadExecutor = NexusExecutorService.forVirtualThreads(TASK_SUBJECT);
  }

  /**
   * Handles the upload of an APT component.
   * <p>
   * This implementation leverages Java 21 Virtual Threads for efficient I/O operations,
   * allowing for higher throughput when handling multiple concurrent uploads.
   *
   * @param repository the repository to upload to
   * @param upload the component upload
   * @return the upload response
   * @throws IOException if an I/O error occurs
   */
  @Override
  public UploadResponse handle(final Repository repository, final ComponentUpload upload) throws IOException {
    try {
      // Submit the upload task to be executed on a virtual thread for optimal I/O performance
      return virtualThreadExecutor.submit(() -> handleUpload(repository, upload)).get();
    } catch (Exception e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException("Failed to process upload", e);
    }
  }
  
  /**
   * Internal method to handle the actual upload processing.
   * This method is executed on a virtual thread for improved I/O performance.
   *
   * @param repository the repository to upload to
   * @param upload the component upload
   * @return the upload response
   * @throws IOException if an I/O error occurs
   */
  private UploadResponse handleUpload(final Repository repository, final ComponentUpload upload) throws IOException {
    AptContentFacet aptContentFacet = repository.facet(AptContentFacet.class);
    AptHostedFacet hostedFacet = repository.facet(AptHostedFacet.class);
    PartPayload payload = upload.getAssetUploads().get(0).getPayload();
    
    try (TempBlob tempBlob = aptContentFacet.getTempBlob(payload)) {
      ControlFile controlFile = AptPackageParser
          .parsePackageInfo(tempBlob)
          .getControlFile();
      String assetPath = AptFacetHelper.buildAssetPath(controlFile);
      doValidation(repository, prependIfMissing(assetPath, "/"));
      
      Content content = hostedFacet
          .put(assetPath, payload, new PackageInfo(controlFile))
          .markAsCached(payload)
          .download();
          
      return new UploadResponse(Collections.singletonList(content), Collections.singletonList(assetPath));
    }
  }
}