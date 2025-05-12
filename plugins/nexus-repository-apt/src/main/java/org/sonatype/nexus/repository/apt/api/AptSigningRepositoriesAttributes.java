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
package org.sonatype.nexus.repository.apt.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

/**
 * REST API model for apt repository signing.
 * 
 * This class is implemented as a Java record for immutability and concise data representation,
 * leveraging Java 21 features for improved type safety and pattern matching capabilities.
 *
 * @since 3.20
 */
public record AptSigningRepositoriesAttributes(
    @Schema(description = "PGP signing key pair (armored private key e.g. gpg --export-secret-key --armor)",
            example = "")
    @NotEmpty
    @JsonProperty("keypair")
    String keypair,

    @Schema(description = "Passphrase to access PGP signing key", 
            example = "")
    @JsonProperty("passphrase")
    String passphrase
) {
    /**
     * Creates an instance of AptSigningRepositoriesAttributes.
     * 
     * @param keypair    PGP signing key pair (armored private key)
     * @param passphrase Passphrase to access PGP signing key
     * @return a new AptSigningRepositoriesAttributes instance
     */
    @JsonCreator
    public static AptSigningRepositoriesAttributes of(
        @JsonProperty("keypair") final String keypair,
        @JsonProperty("passphrase") final String passphrase) {
        return new AptSigningRepositoriesAttributes(keypair, passphrase);
    }
}