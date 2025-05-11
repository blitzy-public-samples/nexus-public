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
package org.sonatype.nexus.coreui;

import java.util.Set;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

import org.sonatype.nexus.httpclient.config.NonProxyHosts;
import org.sonatype.nexus.validation.constraint.Hostname;
import org.sonatype.nexus.validation.constraint.PortNumber;

/**
 * HTTP System Settings exchange object.
 *
 * @since 3.0
 */
public record HttpSettingsXO(
    String userAgentSuffix,
    
    /**
     * Timeout seconds.
     */
    @Min(1L)
    @Max(3600L)
    Integer timeout,
    
    @Min(0L)
    @Max(10L)
    Integer retries,
    
    Boolean httpEnabled,
    
    @Hostname
    String httpHost,
    
    @PortNumber
    Integer httpPort,
    
    Boolean httpAuthEnabled,
    
    String httpAuthUsername,
    
    String httpAuthPassword,
    
    String httpAuthNtlmHost,
    
    String httpAuthNtlmDomain,
    
    Boolean httpsEnabled,
    
    @Hostname
    String httpsHost,
    
    @PortNumber
    Integer httpsPort,
    
    Boolean httpsAuthEnabled,
    
    String httpsAuthUsername,
    
    String httpsAuthPassword,
    
    String httpsAuthNtlmHost,
    
    String httpsAuthNtlmDomain,
    
    @NonProxyHosts
    Set<String> nonProxyHosts
) {
  // Custom implementation of toString() to match the original implementation
  @Override
  public String toString() {
    return "HttpSettingsXO{" +
        "userAgentSuffix='" + userAgentSuffix + '\'' +
        ", timeout=" + timeout +
        ", retries=" + retries +
        ", httpEnabled=" + httpEnabled +
        ", httpHost='" + httpHost + '\'' +
        ", httpPort=" + httpPort +
        ", httpAuthEnabled=" + httpAuthEnabled +
        ", httpAuthUsername='" + httpAuthUsername + '\'' +
        ", httpAuthPassword='" + httpAuthPassword + '\'' +
        ", httpAuthNtlmHost='" + httpAuthNtlmHost + '\'' +
        ", httpAuthNtlmDomain='" + httpAuthNtlmDomain + '\'' +
        ", httpsEnabled=" + httpsEnabled +
        ", httpsHost='" + httpsHost + '\'' +
        ", httpsPort=" + httpsPort +
        ", httpsAuthEnabled=" + httpsAuthEnabled +
        ", httpsAuthUsername='" + httpsAuthUsername + '\'' +
        ", httpsAuthPassword='" + httpsAuthPassword + '\'' +
        ", httpsAuthNtlmHost='" + httpsAuthNtlmHost + '\'' +
        ", httpsAuthNtlmDomain='" + httpsAuthNtlmDomain + '\'' +
        ", nonProxyHosts=" + nonProxyHosts +
        '}';
  }
}