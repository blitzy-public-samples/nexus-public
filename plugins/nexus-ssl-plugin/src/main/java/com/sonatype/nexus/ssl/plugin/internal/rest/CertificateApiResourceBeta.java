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

import org.sonatype.nexus.rest.APIConstants;
import org.sonatype.nexus.security.internal.rest.SecurityApiResourceBeta;
import org.sonatype.nexus.ssl.TrustStore;
import org.sonatype.nexus.ssl.CertificateRetriever;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * @since 3.26
 * @deprecated beta prefix is being phased out, prefer starting new APIs with {@link APIConstants#V1_API_PREFIX} instead
 */
@Hidden
@Named
@Singleton
@Path(CertificateApiResourceBeta.RESOURCE_URI)
@Deprecated
public class CertificateApiResourceBeta
    extends CertificateApiResource
{
  static final String RESOURCE_URI = SecurityApiResourceBeta.BETA_RESOURCE_URI + "ssl/";

  @Inject
  public CertificateApiResourceBeta(
      final TrustStore trustStore,
      final CertificateRetriever certificateRetriever)
  {
    super(trustStore, certificateRetriever);
  }
}