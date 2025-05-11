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
package org.sonatype.nexus.repository.maven;

import javax.annotation.Nonnull;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;

import static org.sonatype.nexus.repository.http.HttpMethods.GET;

/**
 * Handler to set Content-Disposition HTTP header for Maven repository responses.
 * This handler intercepts GET requests and applies the configured Content-Disposition
 * header value based on repository configuration.
 *
 * <p>Java 21 compatible implementation using modern language features and best practices.</p>
 *
 * @since 3.33
 */
@Named
@Singleton
public class ContentDispositionHandler
    implements Handler
{
  public static final String CONTENT_DISPOSITION_HEADER = "Content-Disposition";

  public static final String CONTENT_DISPOSITION_CONFIG_KEY = "contentDisposition";

  /**
   * Handles the request by proceeding with the handler chain and then potentially
   * setting the Content-Disposition header on the response for GET requests.
   *
   * @param context The request context
   * @return The response, potentially with Content-Disposition header added
   * @throws Exception if an error occurs during handling
   */
  @Nonnull
  @Override
  public Response handle(@Nonnull final Context context) throws Exception {
    // First proceed with the handler chain to get the response
    Response response = context.proceed();
    
    // Only process GET requests
    String action = context.getRequest().getAction();
    if (GET.equals(action)) {
      // Retrieve the configured content disposition setting from repository configuration
      // Default to INLINE if not specified
      String contentDisposition = context.getRepository().getConfiguration().attributes("maven")
          .get(CONTENT_DISPOSITION_CONFIG_KEY, String.class, ContentDisposition.INLINE.name());
      
      // Apply the Content-Disposition header to the response
      response.getHeaders()
          .replace(CONTENT_DISPOSITION_HEADER, ContentDisposition.valueOf(contentDisposition).getValue());
    }
    
    return response;
  }
}