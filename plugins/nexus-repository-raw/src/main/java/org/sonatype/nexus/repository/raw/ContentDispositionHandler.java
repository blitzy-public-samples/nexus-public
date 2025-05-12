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
package org.sonatype.nexus.repository.raw;

import javax.annotation.Nonnull;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;

import static org.sonatype.nexus.repository.http.HttpMethods.GET;

/**
 * Handler to set Content-Disposition HTTP header
 *
 * @since 3.25
 */
@Named
@Singleton
public class ContentDispositionHandler
    implements Handler
{
  public static final String CONTENT_DISPOSITION_CONFIG_KEY = "contentDisposition";

  @Nonnull
  @Override
  public Response handle(@Nonnull final Context context) throws Exception {
    Response response = context.proceed();
    
    // Get the request action
    String action = context.getRequest().getAction();
    
    // Use Java 21 pattern matching for switch to check if action is GET
    // This leverages the enhanced switch expression with arrow syntax and null handling
    switch (action) {
      case null -> {
        // Handle null action (shouldn't normally happen, but Java 21 switch can handle null cases)
      }
      case GET -> {
        // Retrieve content disposition configuration from repository attributes
        String contentDisposition = context.getRepository().getConfiguration().attributes("raw")
            .get(CONTENT_DISPOSITION_CONFIG_KEY, String.class, ContentDisposition.INLINE.name());
        
        // Replace Content-Disposition header with configured value
        response.getHeaders().replace("Content-Disposition", 
            ContentDisposition.valueOf(contentDisposition).getValue());
      }
      // No action needed for other HTTP methods
      default -> {}
    }
    
    return response;
  }
}