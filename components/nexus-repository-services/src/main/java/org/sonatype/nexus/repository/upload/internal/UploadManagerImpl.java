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
package org.sonatype.nexus.repository.upload.internal;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.importtask.ImportFileConfiguration;
import org.sonatype.nexus.repository.importtask.ImportResult;
import org.sonatype.nexus.repository.rest.ComponentUploadExtension;
import org.sonatype.nexus.repository.rest.internal.resources.ComponentUploadUtils;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.upload.AssetUpload;
import org.sonatype.nexus.repository.upload.ComponentUpload;
import org.sonatype.nexus.repository.upload.UploadDefinition;
import org.sonatype.nexus.repository.upload.UploadHandler;
import org.sonatype.nexus.repository.upload.UploadManager;
import org.sonatype.nexus.repository.upload.UploadProcessor;
import org.sonatype.nexus.repository.upload.UploadResponse;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.rest.ValidationErrorsException;

import org.apache.commons.fileupload.FileUploadException;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.prependIfMissing;
import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_ENABLED;

/**
 * {@link UploadManager} implementation.
 *
 * @since 3.24
 */
@FeatureFlag(name = DATASTORE_ENABLED)
@Named
@Singleton
public class UploadManagerImpl
    extends ComponentSupport
    implements UploadManager
{
  private final List<UploadDefinition> uploadDefinitions;

  private final Map<String, UploadHandler> uploadHandlers;

  private final UploadComponentMultipartHelper multipartHelper;

  private final UploadProcessor uploadComponentProcessor;

  private final Set<ComponentUploadExtension> componentUploadExtensions;

  private final EventManager eventManager;

  @Inject
  public UploadManagerImpl(
      final Map<String, UploadHandler> uploadHandlers,
      final UploadComponentMultipartHelper multipartHelper,
      final UploadProcessor uploadComponentProcessor,
      final EventManager eventManager,
      final Set<ComponentUploadExtension> componentsUploadExtensions)
  {
    this.uploadHandlers = checkNotNull(uploadHandlers);
    this.uploadDefinitions = Collections
        .unmodifiableList(uploadHandlers.values().stream()
            .filter(UploadHandler::supportsApiUpload)
            .map(UploadHandler::getDefinition)
            .collect(toList()));
    this.multipartHelper = checkNotNull(multipartHelper);
    this.uploadComponentProcessor = checkNotNull(uploadComponentProcessor);
    this.eventManager = checkNotNull(eventManager);
    this.componentUploadExtensions = checkNotNull(componentsUploadExtensions);
  }

  @Override
  public Collection<UploadDefinition> getAvailableDefinitions() {
    return uploadDefinitions;
  }

  @Override
  public UploadResponse handle(final Repository repository, final HttpServletRequest request) throws IOException {
    checkNotNull(repository);
    checkNotNull(request);

    if (!repository.getConfiguration().isOnline()) {
      throw new ValidationErrorsException(STR."Repository \{repository.getName()} is offline");
    }

    UploadHandler uploadHandler = getUploadHandler(repository);
    ComponentUpload upload = create(repository, request);
    logUploadDetails(upload, repository);

    try {
      // Validate component upload extensions using Virtual Threads for concurrent processing
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit all validation tasks and wait for completion
        List<Future<?>> validationTasks = componentUploadExtensions.stream()
            .map(extension -> executor.submit(() -> extension.validate(upload)))
            .toList();
        
        // Wait for all validations to complete
        for (Future<?> task : validationTasks) {
          task.get(); // This will throw if any validation fails
        }
      } catch (Exception e) {
        throw new IOException(STR."Validation failed: \{e.getMessage()}", e);
      }

      UploadResponse uploadResponse =
          uploadHandler.handle(repository, uploadHandler.getValidatingComponentUpload(upload).getComponentUpload());

      // Process component upload extensions using Virtual Threads for concurrent processing
      List<EntityId> componentIds = uploadResponse.getContents().stream()
          .map(uploadComponentProcessor::extractId)
          .filter(Optional::isPresent)
          .map(Optional::get)
          .collect(toList());
      
      // Use structured concurrency for applying extensions
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<?>> extensionTasks = componentUploadExtensions.stream()
            .map(extension -> executor.submit(() -> extension.apply(repository, upload, componentIds)))
            .toList();
            
        // Wait for all extensions to complete
        for (Future<?> task : extensionTasks) {
          task.get(); // This ensures all extensions are applied before continuing
        }
      } catch (Exception e) {
        log.warn(STR."Error applying component upload extensions: \{e.getMessage()}", e);
        // Continue processing as this is not critical
      }

      // Post event asynchronously using Virtual Threads
      Thread.startVirtualThread(() -> {
        try {
          List<String> assetPaths = uploadResponse.getAssetPaths().stream()
              .map(assetPath -> prependIfMissing(assetPath, "/"))
              .collect(toList());
          eventManager.post(new UIUploadEvent(repository, assetPaths));
          log.debug(STR."Posted upload event for repository \{repository.getName()} with \{assetPaths.size()} assets");
        } catch (Exception e) {
          log.error(STR."Failed to post upload event: \{e.getMessage()}", e);
        }
      });

      return uploadResponse;
    }
    finally {
      // Enhanced try-with-resources for better error handling
      for (AssetUpload assetUpload : upload.getAssetUploads()) {
        try (var payload = assetUpload.getPayload()) {
          // Resource will be automatically closed
        } catch (Exception e) {
          log.warn(STR."Error closing asset upload payload: \{e.getMessage()}");
        }
      }
    }
  }

  @Override
  public UploadDefinition getByFormat(final String format) {
    checkNotNull(format);

    UploadHandler handler = uploadHandlers.get(format);
    return handler != null ? handler.getDefinition() : null;
  }

  @Override
  public Content handle(final ImportFileConfiguration importFileConfiguration)
      throws IOException
  {
    UploadHandler uploadHandler = getUploadHandler(importFileConfiguration.getRepository());

    return switch (importFileConfiguration.isHardLinkingEnabled()) {
      case true -> uploadHandler.handle(importFileConfiguration);
      case false -> uploadHandler.handle(
          importFileConfiguration.getRepository(),
          importFileConfiguration.getFile(),
          importFileConfiguration.getAssetName()
      );
    };
  }

  @Override
  public void handleAfterImport(final ImportResult importResult) throws IOException {
    Repository repository = importResult.getRepository();
    UploadHandler uploadHandler = getUploadHandler(repository);
    uploadHandler.handleAfterImport(importResult);
  }

  private ComponentUpload create(final Repository repository, final HttpServletRequest request)
      throws IOException
  {
    try {
      // Use Virtual Thread for multipart file upload handling to improve throughput for large artifacts
      return Thread.ofVirtual()
          .name(STR."upload-\{repository.getName()}-\{System.currentTimeMillis()}")
          .call(() -> {
            try {
              BlobStoreMultipartForm multipartForm = multipartHelper.parse(repository, request);
              return ComponentUploadUtils.createComponentUpload(repository.getFormat().getValue(), multipartForm);
            } catch (FileUploadException e) {
              throw new IOException(STR."File upload failed: \{e.getMessage()}", e);
            }
          });
    }
    catch (Exception e) {
      // Pattern matching for exception handling
      switch (e) {
        case IOException ioe -> throw ioe;
        case RuntimeException re -> throw re;
        default -> throw new IOException(STR."Error processing upload: \{e.getMessage()}", e);
      }
    }
  }

  private UploadHandler getUploadHandler(final Repository repository)
  {
    // Use Pattern Matching for switch to handle repository type checking
    return switch (repository.getType()) {
      case HostedType _ -> {
        // Use Pattern Matching for switch to handle repository format checking
        String repositoryFormat = repository.getFormat().toString();
        UploadHandler uploadHandler = uploadHandlers.get(repositoryFormat);
        
        yield switch (uploadHandler) {
          case null -> throw new ValidationErrorsException(
              STR."Uploading components to '\{repositoryFormat}' repositories is unsupported");
          case UploadHandler handler -> handler;
        };
      }
      case Object _ -> throw new ValidationErrorsException(
          STR."Uploading components to a '\{repository.getType().getValue()}' type repository is unsupported, must be '\{HostedType.NAME}'");
    };
  }

  private void logUploadDetails(final ComponentUpload componentUpload, final Repository repository) {
    if (log.isInfoEnabled()) {
      Map<String, String> componentFields = componentUpload.getFields();
      List<AssetUpload> assetUploads = componentUpload.getAssetUploads();

      // Use String Templates for improved logging
      StringBuilder fieldsStr = new StringBuilder();
      for (Entry<String, String> entry : componentFields.entrySet()) {
        fieldsStr.append(STR."\{entry.getKey()}=\"\{entry.getValue()}\" ");
      }
      log.info(STR."Uploading component with parameters: repository=\"\{repository.getName()}\" format=\"\{repository.getFormat().getValue()}\" \{fieldsStr}");

      for (AssetUpload assetUpload : assetUploads) {
        StringBuilder assetFieldsStr = new StringBuilder();
        for (Entry<String, String> entry : assetUpload.getFields().entrySet()) {
          assetFieldsStr.append(STR."\{entry.getKey()}=\"\{entry.getValue()}\" ");
        }
        log.info(STR."Asset with parameters: file=\"\{assetUpload.getPayload().getName()}\" \{assetFieldsStr}");
      }
    }
  }
}