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

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import org.sonatype.nexus.validation.constraint.Hostname;
import org.sonatype.nexus.validation.constraint.PortNumber;

/**
 * Data Transfer Object (DTO) for email configuration.
 * Implemented as a Java 21 record for improved maintainability and conciseness.
 */
public record EmailConfigurationXO(
    boolean enabled,
    
    @Hostname
    @NotBlank
    String host,
    
    @PortNumber
    @NotNull
    int port,
    
    String username,
    
    String password,
    
    @Email
    @NotBlank
    String fromAddress,
    
    String subjectPrefix,
    
    boolean startTlsEnabled,
    
    boolean startTlsRequired,
    
    boolean sslOnConnectEnabled,
    
    boolean sslCheckServerIdentityEnabled,
    
    boolean nexusTrustStoreEnabled
) {
    // Record automatically provides:
    // - Constructor with all fields
    // - Accessor methods for all fields (named the same as the fields)
    // - equals(), hashCode(), and toString() methods
}