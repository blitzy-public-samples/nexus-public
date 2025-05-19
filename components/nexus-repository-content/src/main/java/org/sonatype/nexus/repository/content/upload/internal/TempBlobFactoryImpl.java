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
package org.sonatype.nexus.repository.content.upload.internal;

import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.ShutdownOnFailure;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.upload.TempBlobFactory;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Content implementation of {@code TempBlobFactory}
 *
 * @since 3.24
 */
@Named
@Singleton
public class TempBlobFactoryImpl
    implements TempBlobFactory
{
  private static final Logger log = LoggerFactory.getLogger(TempBlobFactoryImpl.class);

  @Override
  public TempBlob create(final Repository repository,
                         final InputStream inputStream,
                         final Iterable<HashAlgorithm> hashAlgorithms)
  {
    log.debug(STR."Creating temp blob from InputStream for repository \{repository.getName()} with hash algorithms \{hashAlgorithms}");
    
    try (var scope = new ShutdownOnFailure()) {
      var future = scope.fork(() -> repository.facet(ContentFacet.class).blobs().ingest(inputStream, null, hashAlgorithms));
      scope.join();
      scope.throwIfFailed();
      return future.resultNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(STR."Thread interrupted while creating temp blob for repository \{repository.getName()}", e);
    } catch (ExecutionException e) {
      throw new RuntimeException(STR."Failed to create temp blob for repository \{repository.getName()}", e.getCause());
    }
  }

  @Override
  public TempBlob create(final Repository repository,
                         final Payload payload,
                         final Iterable<HashAlgorithm> hashAlgorithms)
  {
    log.debug(STR."Creating temp blob from Payload \{payload.getName()} for repository \{repository.getName()} with hash algorithms \{hashAlgorithms}");
    
    try (var scope = new ShutdownOnFailure()) {
      var future = scope.fork(() -> repository.facet(ContentFacet.class).blobs().ingest(payload, hashAlgorithms));
      scope.join();
      scope.throwIfFailed();
      return future.resultNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(STR."Thread interrupted while creating temp blob from payload \{payload.getName()} for repository \{repository.getName()}", e);
    } catch (ExecutionException e) {
      throw new RuntimeException(STR."Failed to create temp blob from payload \{payload.getName()} for repository \{repository.getName()}", e.getCause());
    }
  }
}