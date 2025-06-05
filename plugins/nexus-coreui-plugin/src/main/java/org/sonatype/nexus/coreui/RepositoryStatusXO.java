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

import javax.validation.constraints.NotEmpty;

/**
 * Repository status exchange object.
 *
 * @since 3.0
 */
public record RepositoryStatusXO(
  /**
   * Name of associated Repository.
   */
  @NotEmpty
  String repositoryName,

  /**
   * Whether or not the repository is online.
   */
  boolean online,

  /**
   * A description of the status.
   */
  String description,

  /**
   * A reason for the status.
   */
  String reason
) {
	/**
     * Provides a static method to create a new Builder instance for RepositoryStatusXO.
     *
     * @return A new Builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    // --- Builder Class ---
    public static class Builder {
        private String repositoryName;
        private boolean online;
        private String description;
        private String reason;

        // Private constructor to enforce usage of RepositoryStatusXO.builder()
        private Builder() {
            // Initialize fields with default values if necessary
            this.online = false; // Example: default to offline
        }

        public Builder repositoryName(String repositoryName) {
            this.repositoryName = repositoryName;
            return this; // Return this for method chaining
        }

        public Builder online(boolean online) {
            this.online = online;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        /**
         * Builds the final RepositoryStatusXO instance.
         * The validation annotations on the record's fields (like @NotEmpty)
         * will be checked when the record's canonical constructor is called.
         *
         * @return A new RepositoryStatusXO instance.
         */
        public RepositoryStatusXO build() {
            // You can add any pre-build validation or default assignments here if needed
            return new RepositoryStatusXO(
                repositoryName,
                online,
                description,
                reason
            );
        }
    }
	
}