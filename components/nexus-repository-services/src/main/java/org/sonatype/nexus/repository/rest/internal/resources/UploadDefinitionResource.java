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
package org.sonatype.nexus.repository.rest.internal.resources;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.rest.api.UploadDefinitionXO;
import org.sonatype.nexus.repository.rest.api.UploadFieldDefinitionXO;
import org.sonatype.nexus.repository.rest.internal.resources.doc.UploadDefinitionResourceDoc;
import org.sonatype.nexus.repository.upload.UploadConfiguration;
import org.sonatype.nexus.repository.upload.UploadDefinition;
import org.sonatype.nexus.repository.upload.UploadManager;
import org.sonatype.nexus.rest.Resource;

import static java.lang.StringTemplate.STR;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.stream.Collectors.toList;
import static com.google.common.base.Preconditions.checkNotNull;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.sonatype.nexus.rest.APIConstants.V1_API_PREFIX;

/**
 * @since 3.10
 */
@Named
@Singleton
@Path(UploadDefinitionResource.BASE_PATH)
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class UploadDefinitionResource
    extends ComponentSupport
    implements Resource, UploadDefinitionResourceDoc
{
  public static final String BASE_PATH = V1_API_PREFIX + "/formats";

  private final UploadManager uploadManager;

  private final UploadConfiguration uploadConfiguration;

  @Inject
  public UploadDefinitionResource(final UploadManager uploadManager, final UploadConfiguration uploadConfiguration) {
    this.uploadManager = checkNotNull(uploadManager);
    this.uploadConfiguration = checkNotNull(uploadConfiguration);
  }

  @Path("upload-specs")
  @GET
  public List<UploadDefinitionXO> get() {
    log.debug("Get upload definitions for all formats.");

    if (!uploadConfiguration.isEnabled()) {
      log.debug("Upload is disabled.");
      throw new NotFoundException();
    }

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return supplyAsync(() -> uploadManager.getAvailableDefinitions().stream()
          .map(this::from)
          .collect(toList()), executor)
          .join();
    }
  }

  @Path("{format}/upload-specs")
  @GET
  public UploadDefinitionXO get(@PathParam("format") final String format) {
    log.debug("Get upload definition for format '{}'.", format);

    if (!uploadConfiguration.isEnabled()) {
      log.debug("Upload is disabled.");
      throw new NotFoundException();
    }

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return supplyAsync(() -> {
        UploadDefinition uploadDefinition = uploadManager.getByFormat(format);

        if (uploadDefinition == null || !uploadDefinition.isApiUpload()) {
          log.debug("Could not find upload definition for format '{}'.", format);
          throw new NotFoundException(STR."Unable to locate upload definition for format '\{format}'");
        }

        return from(uploadDefinition);
      }, executor).join();
    }
  }

  private UploadDefinitionXO from(UploadDefinition def) {
    UploadDefinitionXO xo = UploadDefinitionXO.from(def);
    UploadFieldDefinitionXO fieldXo = new UploadFieldDefinitionXO();
    fieldXo.setName("asset");
    fieldXo.setType("FILE");
    fieldXo.setOptional(false);
    xo.getAssetFields().add(fieldXo);
    return xo;
  }
}