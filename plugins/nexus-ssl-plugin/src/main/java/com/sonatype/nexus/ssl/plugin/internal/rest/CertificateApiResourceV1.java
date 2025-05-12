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
package com.sonatype.nexus.ssl.plugin.internal.rest;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;

import org.sonatype.nexus.security.internal.rest.SecurityApiResourceV1;
import org.sonatype.nexus.ssl.TrustStore;
import org.sonatype.nexus.ssl.CertificateRetriever;

/**
 * REST API resource for SSL certificate management (v1).
 * @since 3.26
 */
@Named
@Singleton
@Path(CertificateApiResourceV1.RESOURCE_URI)
public class CertificateApiResourceV1
    extends CertificateApiResource
{
  static final String RESOURCE_URI = SecurityApiResourceV1.V1_RESOURCE_URI + "ssl/";

  @Inject
  public CertificateApiResourceV1(
      final TrustStore trustStore,
      final CertificateRetriever certificateRetriever)
  {
    super(trustStore, certificateRetriever);
  }
}
