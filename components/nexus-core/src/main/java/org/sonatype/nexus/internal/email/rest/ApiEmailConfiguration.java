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
package org.sonatype.nexus.internal.email.rest;

import jakarta.validation.constraints.NotNull;

import org.sonatype.nexus.validation.constraint.Hostname;
import org.sonatype.nexus.validation.constraint.PortNumber;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Email configuration API record.
 * Converted to a Java 21 record for improved immutability and reduced boilerplate.
 */
public record ApiEmailConfiguration(
  boolean enabled,

  @Hostname
  @NotBlank
  String host,

  @PortNumber
  @NotNull
  Integer port,

  String username,

  String password,

  @Email
  @NotBlank
  @Schema(example = "nexus@example.org")
  String fromAddress,

  @Schema(description = "A prefix to add to all email subjects to aid in identifying automated emails")
  String subjectPrefix,

  @Schema(description = "Enable STARTTLS Support for Insecure Connections")
  boolean startTlsEnabled,

  @Schema(description = "Require STARTTLS Support")
  boolean startTlsRequired,

  @Schema(description = "Enable SSL/TLS Encryption upon Connection")
  boolean sslOnConnectEnabled,

  @Schema(description = "Verify the server certificate when using TLS or SSL")
  boolean sslServerIdentityCheckEnabled,

  @Schema(description = "Use the Nexus Repository Manager's certificate truststore")
  boolean nexusTrustStoreEnabled
) {}