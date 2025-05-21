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
package org.sonatype.nexus.internal.security.secrets.rest;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;

import com.google.common.annotations.VisibleForTesting;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * API model for re-encryption requests.
 */
public record ReEncryptionRequestApiXO(
    @Schema(description = "Key identifier that will be used to re-encrypt secrets", required = true)
    @NotBlank
    String secretKeyId,
    
    @Schema(description = "Optional - Email to notify when task finishes")
    @Nullable
    String notifyEmail
) {
  /**
   * Default constructor for serialization.
   */
  public ReEncryptionRequestApiXO {
    // validation happens via annotations
  }
  
  /**
   * Constructor for testing.
   */
  @VisibleForTesting
  public ReEncryptionRequestApiXO(final String secretKeyId, final String notifyEmail) {
    this.secretKeyId = secretKeyId;
    this.notifyEmail = notifyEmail;
  }
}