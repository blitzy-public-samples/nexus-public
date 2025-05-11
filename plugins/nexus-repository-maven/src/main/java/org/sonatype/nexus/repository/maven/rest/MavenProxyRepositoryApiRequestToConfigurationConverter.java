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
package org.sonatype.nexus.repository.maven.rest;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.rest.api.ProxyRepositoryApiRequestToConfigurationConverter;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;

/**
 * Converter for Maven proxy repository API requests to repository configuration.
 *
 * @since 3.20
 */
@Named
public class MavenProxyRepositoryApiRequestToConfigurationConverter
    extends ProxyRepositoryApiRequestToConfigurationConverter<MavenProxyRepositoryApiRequest>
{
  private static final String MAVEN = "maven";

  @Inject
  public MavenProxyRepositoryApiRequestToConfigurationConverter(final RoutingRuleStore routingRuleStore) {
    super(routingRuleStore);
  }

  @Override
  public Configuration convert(final MavenProxyRepositoryApiRequest request) {
    Configuration configuration = super.convert(request);
    
    // Set Maven-specific attributes
    NestedAttributesMap mavenAttributes = configuration.attributes(MAVEN);
    mavenAttributes.set("versionPolicy", request.getMaven().getVersionPolicy());
    mavenAttributes.set("layoutPolicy", request.getMaven().getLayoutPolicy());
    mavenAttributes.set("contentDisposition", request.getMaven().getContentDisposition());
    
    // Configure HTTP client authentication if present
    NestedAttributesMap httpclient = configuration.attributes("httpclient");
    var authentication = httpclient.get("authentication");
    if (authentication != null) {
      httpclient.child("authentication").set("preemptive", request.getHttpClient().getAuthentication().isPreemptive());
    }
    
    return configuration;
  }
}