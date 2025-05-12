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
package org.sonatype.nexus.repository.apt.rest;

import javax.inject.Named;

import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.rest.api.HostedRepositoryApiRequestToConfigurationConverter;

/**
 * Converter for APT hosted repository API requests to repository configurations.
 * 
 * @since 3.20
 * @apiNote Updated for Java 21 compatibility with enhanced code patterns and documentation.
 */
@Named
public class AptHostedRepositoryApiRequestToConfigurationConverter
    extends HostedRepositoryApiRequestToConfigurationConverter<AptHostedRepositoryApiRequest>
{
  @Override
  public Configuration convert(final AptHostedRepositoryApiRequest request) {
    // First apply the standard hosted repository configuration
    Configuration configuration = super.convert(request);
    
    // Then add APT-specific attributes
    configuration.attributes("apt").set("distribution", request.getApt().getDistribution());
    
    // Add APT signing attributes
    var aptSigning = request.getAptSigning();
    configuration.attributes("aptSigning")
        .set("keypair", aptSigning.keypair())
        .set("passphrase", aptSigning.passphrase());
    
    return configuration;
  }
}