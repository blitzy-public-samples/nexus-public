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
package org.sonatype.nexus.repository.apt.internal.gpg;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.apt.internal.snapshot.AptSnapshotHandler;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;


import static org.sonatype.nexus.repository.http.HttpMethods.GET;

/**
 * Handler for APT repository GPG signing operations.
 * Serves the public GPG key for APT repositories.
 * 
 * This implementation is compatible with Java 21 and uses modern security practices
 * for cryptographic operations through the AptSigningFacet.
 *
 * @since 3.17
 * @see AptSigningFacet
 */
@Named
@Singleton
public class AptSigningHandler
    extends ComponentSupport
    implements Handler
{
  /**
   * Handles requests for APT repository GPG keys.
   * Uses Java 21 pattern matching for improved code readability.
   *
   * @param context The request context
   * @return The appropriate response based on the request
   * @throws Exception If an error occurs during handling
   */
  @Override
  public Response handle(final Context context) throws Exception {
    String path = assetPath(context);
    String method = context.getRequest().getAction();
    
    // Use pattern matching to check if this is a request for the repository key
    if ("repository-key.gpg".equals(path) && GET.equals(method)) {
      // Get the AptSigningFacet from the repository
      AptSigningFacet facet = context.getRepository().facet(AptSigningFacet.class);
      // Return the public key with an OK response
      return HttpResponses.ok(facet.getPublicKey());
    }

    // Not a key request, proceed with normal request handling
    return context.proceed();
  }

  /**
   * Extracts the asset path from the context.
   * Uses Java 21 pattern matching concepts for type-safe attribute access.
   *
   * @param context The request context
   * @return The asset path from the context attributes
   */
  private String assetPath(final Context context) {
    // Extract the State object and access its assetPath property
    return context.getAttributes().require(AptSnapshotHandler.State.class).assetPath;
  }
}