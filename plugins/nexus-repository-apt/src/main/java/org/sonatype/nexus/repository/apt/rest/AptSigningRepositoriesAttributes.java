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

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import javax.validation.constraints.NotEmpty;

/**
 * Data Transfer Object for APT repository signing attributes.
 *
 * @since 3.20
 * @apiNote Updated for Java 21 compatibility using Record for immutable data structure.
 */
public record AptSigningRepositoriesAttributes(
    @ApiModelProperty(value = "PGP signing key pair (armored private key e.g. gpg --export-secret-key --armor)",
        example = "")
    @NotEmpty
    @JsonProperty("keypair")
    String keypair,

    @ApiModelProperty(value = "Passphrase to access PGP signing key", example = "")
    @JsonProperty("passphrase")
    String passphrase
) {
  // Record automatically provides constructor, accessors, equals, hashCode, and toString methods
  // This leverages Java 21's Record feature for more concise and maintainable code
}