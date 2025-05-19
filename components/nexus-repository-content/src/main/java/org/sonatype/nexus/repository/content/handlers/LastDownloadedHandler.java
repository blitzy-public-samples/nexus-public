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
package org.sonatype.nexus.repository.content.handlers;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.time.UTC;
import org.sonatype.nexus.repository.capability.GlobalRepositorySettings;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.Status;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;
import static org.sonatype.nexus.repository.http.HttpMethods.HEAD;

/**
 * Updates the asset last downloaded time.
 *
 * @since 3.24
 */
@Named
@Singleton
public class LastDownloadedHandler
    extends ComponentSupport
    implements org.sonatype.nexus.repository.view.handlers.LastDownloadedHandler
{
  private final GlobalRepositorySettings globalSettings;

  private LastDownloadedAttributeHandler lastDownloadedAttributeHandler;
  
  private final ExecutorService executor;

  @Inject
  public LastDownloadedHandler(final GlobalRepositorySettings globalSettings) {
    this.globalSettings = checkNotNull(globalSettings);
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Inject
  public void injectExtraDependencies(final LastDownloadedAttributeHandler lastDownloadedPropertyHandler) {
    this.lastDownloadedAttributeHandler = checkNotNull(lastDownloadedPropertyHandler);
  }

  @Override
  public Response handle(final Context context) throws Exception {
    Response response = context.proceed();

    try {
      if (isSuccessfulRequestWithContent(context, response)) {
        Content content = (Content) response.getPayload();
        // Use virtual threads for asynchronous asset download tracking
        executor.submit(() -> maybeUpdateLastDownloaded(content.getAttributes()));
      }
    }
    catch (Exception e) {
      log.error(STR."Failed to update last downloaded time for request \{context.getRequest().getPath()}", e);
    }

    return response;
  }

  protected void maybeUpdateLastDownloaded(final AttributesMap attributes) {
    maybeUpdateLastDownloaded(attributes.get(Asset.class));
  }

  protected void maybeUpdateLastDownloaded(@Nullable final Asset asset) {
    switch (asset) {
      case FluentAsset fluentAsset when !isNextUpdateInFuture(fluentAsset.lastDownloaded()) -> {
        fluentAsset.markAsDownloaded();
        lastDownloadedAttributeHandler.writeLastDownloadedAttribute(fluentAsset);
      }
      case null -> {}
      default -> log.debug(STR."Cannot mark read-only asset \{asset.path()} as downloaded");
    }
  }

  private boolean isNextUpdateInFuture(Optional<OffsetDateTime> lastTime) {
    return lastTime.isPresent() && lastTime.get().plus(globalSettings.getLastDownloadedInterval()).isAfter(UTC.now());
  }

  private boolean isSuccessfulRequestWithContent(final Context context, final Response response) {
    return switch (context.getRequest().getAction()) {
      case GET, HEAD when isSuccessfulOrNotModified(response.getStatus()) && response.getPayload() instanceof Content -> true;
      default -> false;
    };
  }

  private boolean isGetOrHeadRequest(final Context context) {
    String action = context.getRequest().getAction();
    return GET.equals(action) || HEAD.equals(action);
  }

  private boolean isSuccessfulOrNotModified(final Status status) {
    return status.isSuccessful() || status.getCode() == 304;
  }
}