package org.sonatype.nexus.rest;

import jakarta.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Fault exchange object.
 *
 * @since 3.0
 */
@XmlRootElement(name = "fault")
public record FaultXO(
        @JsonProperty
        String id,

        @JsonProperty
        String message
) {
  /**
   * Constructor with a cause.
   */
  public FaultXO(final String id, final Throwable cause) {
    this(id, cause == null ? null : cause.getMessage());
  }
}