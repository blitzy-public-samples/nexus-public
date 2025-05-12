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
package org.sonatype.nexus.repository.apt.internal.gpg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import javax.inject.Named;
import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;

import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.apt.internal.AptMimeTypes;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationFacet;
import org.sonatype.nexus.repository.security.GpgUtils;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.io.IOUtils;
// Using BouncyCastle 1.78.1+ for Java 21 compatibility
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGOutputStream;
import org.bouncycastle.openpgp.PGPPublicKey;

/**
 * Signs an Apt metadata by using PGP signing key pair.
 * Updated for Java 21 compatibility with BouncyCastle 1.78.1+ and Virtual Threads support.
 *
 * @since 3.17
 */
@Named
@Facet.Exposed
public class AptSigningFacet
    extends FacetSupport
{
  @VisibleForTesting
  static final String CONFIG_KEY = "aptSigning";

  /**
   * Configuration class for APT signing.
   * Contains the keypair and optional passphrase for GPG signing operations.
   */
  @VisibleForTesting
  static class Config
  {
    @NotNull(groups = {HostedType.ValidationGroup.class, GroupType.ValidationGroup.class})
    public String keypair;

    public String passphrase = "";
  }

  private Config config;

  @Override
  protected void doValidate(final Configuration configuration) throws Exception {
    facet(ConfigurationFacet.class).validateSection(
        configuration,
        CONFIG_KEY,
        Config.class,
        Default.class,
        getRepository().getType().getValidationGroup());
  }

  @Override
  protected void doConfigure(final Configuration configuration) throws Exception {
    config = facet(ConfigurationFacet.class).readSection(configuration, CONFIG_KEY, Config.class);
  }

  @Override
  protected void doDestroy() throws Exception {
    config = null;
  }

  /**
   * Retrieves the public key for APT repository signing.
   * 
   * @return Content containing the public key
   * @throws IOException if an error occurs during key retrieval or encoding
   */
  public Content getPublicKey() throws IOException {
    // Using try-with-resources for better resource management
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    PGPPublicKey publicKey = GpgUtils.getPublicKey(config.keypair);
    try (var armoredOutput = new ArmoredOutputStream(buffer);
         var os = new BCPGOutputStream(armoredOutput)) {
      publicKey.encode(os);
    }

    return new Content(new BytesPayload(buffer.toByteArray(), AptMimeTypes.PUBLICKEY));
  }

  /**
   * Signs the input string inline using PGP.
   * 
   * @param input the string to sign
   * @return the signed data as a byte array
   * @throws IOException if an error occurs during signing
   */
  public byte[] signInline(final String input) throws IOException {
    return GpgUtils.signInline(input, config.keypair, config.passphrase);
  }
  
  /**
   * Asynchronously signs the input string inline using PGP with Java 21 Virtual Threads.
   * This method is useful for non-blocking signing operations in high-throughput scenarios.
   * 
   * @param input the string to sign
   * @return the signed data as a byte array
   * @throws IOException if an error occurs during signing
   * @throws InterruptedException if the signing operation is interrupted
   * @since Java 21
   */
  public byte[] signInlineAsync(final String input) throws IOException, InterruptedException {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executor.submit(() -> GpgUtils.signInline(input, config.keypair, config.passphrase)).get();
    } catch (Exception e) {
      if (e.getCause() instanceof IOException ioe) {
        throw ioe;
      }
      if (e instanceof InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw ie;
      }
      throw new IOException("Error during async signing", e);
    }
  }

  /**
   * Signs the input string externally using PGP.
   * 
   * @param input the string to sign
   * @return the signature as a byte array
   * @throws IOException if an error occurs during signing
   */
  public byte[] signExternal(final String input) throws IOException {
    try (InputStream is = IOUtils.toInputStream(input, StandardCharsets.UTF_8)) {
      return GpgUtils.signExternal(is, config.keypair, config.passphrase);
    }
  }
  
  /**
   * Asynchronously signs the input string externally using PGP with Java 21 Virtual Threads.
   * This method is useful for non-blocking signing operations in high-throughput scenarios.
   * 
   * @param input the string to sign
   * @return the signature as a byte array
   * @throws IOException if an error occurs during signing
   * @throws InterruptedException if the signing operation is interrupted
   * @since Java 21
   */
  public byte[] signExternalAsync(final String input) throws IOException, InterruptedException {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executor.submit(() -> {
        try (InputStream is = IOUtils.toInputStream(input, StandardCharsets.UTF_8)) {
          return GpgUtils.signExternal(is, config.keypair, config.passphrase);
        }
      }).get();
    } catch (Exception e) {
      if (e.getCause() instanceof IOException ioe) {
        throw ioe;
      }
      if (e instanceof InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw ie;
      }
      throw new IOException("Error during async signing", e);
    }
  }
}