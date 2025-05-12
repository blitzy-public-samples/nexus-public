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
package com.sonatype.nexus.ssl.plugin.internal.ui;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;

import com.sonatype.nexus.ssl.plugin.validator.PemCertificate;

import org.sonatype.nexus.extdirect.DirectComponent;
import org.sonatype.nexus.extdirect.DirectComponentSupport;
import org.sonatype.nexus.ssl.CertificateUtil;
import org.sonatype.nexus.ssl.KeystoreException;
import org.sonatype.nexus.ssl.TrustStore;
import org.sonatype.nexus.validation.Validate;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.sonatype.nexus.ssl.CertificateUtil.calculateFingerprint;

/**
 * SSL TrustStore {@link DirectComponent}.
 *
 * @since 3.0
 */
@Named
@Singleton
@DirectAction(action = "ssl_TrustStore")
class TrustStoreComponent
    extends DirectComponentSupport
{
  private static final Logger log = LoggerFactory.getLogger(TrustStoreComponent.class);

  @Inject
  TrustStore trustStore;

  /**
   * Retrieves certificates.
   *
   * @return a list of certificates
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:ssl-truststore:read")
  List<CertificateXO> read() throws Exception {
    log.debug("Retrieving certificates from trust store");
    List<CertificateXO> list = new ArrayList<>();
    for (Certificate certificate : trustStore.getTrustedCertificates()) {
      list.add(asCertificateXO(certificate, true));
    }
    log.debug("Retrieved {} certificates from trust store", list.size());
    return list;
  }

  /**
   * Creates a certificate.
   *
   * @param pem certificate in PEM format
   * @return created certificate
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:ssl-truststore:create")
  @Validate
  CertificateXO create(final @NotBlank @PemCertificate String pem) throws Exception {
    log.debug("Creating certificate from PEM format");
    Certificate certificate = CertificateUtil.decodePEMFormattedCertificate(pem);
    String fingerprint = calculateFingerprint(certificate);
    log.debug("Importing certificate with fingerprint: {}", fingerprint);
    trustStore.importTrustCertificate(certificate, fingerprint);
    return asCertificateXO(certificate, true);
  }

  /**
   * Deletes a certificate.
   *
   * @param id of certificate to be deleted
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:ssl-truststore:delete")
  @Validate
  void remove(final @NotEmpty String id) throws KeystoreException {
    log.debug("Removing certificate with id: {}", id);
    trustStore.removeTrustCertificate(id);
  }

  /**
   * Converts a Certificate to a CertificateXO for UI display.
   *
   * @param certificate the certificate to convert
   * @param inTrustStore whether the certificate is in the trust store
   * @return the certificate exchange object
   */
  static CertificateXO asCertificateXO(final Certificate certificate, final boolean inTrustStore) throws Exception {
    String fingerprint = calculateFingerprint(certificate);
    String pemCertificate = CertificateUtil.serializeCertificateInPEM(certificate);

    if (certificate instanceof X509Certificate x509Certificate) {
      Map<String, String> subjectRdns = CertificateUtil.getSubjectRdns(x509Certificate);
      Map<String, String> issuerRdns = CertificateUtil.getIssuerRdns(x509Certificate);

      return new CertificateXO(
          fingerprint,
          fingerprint,
          pemCertificate,
          x509Certificate.getSerialNumber().toString(),
          subjectRdns.get("CN"),
          subjectRdns.get("O"),
          subjectRdns.get("OU"),
          issuerRdns.get("CN"),
          issuerRdns.get("O"),
          issuerRdns.get("OU"),
          x509Certificate.getNotBefore().getTime(),
          x509Certificate.getNotAfter().getTime(),
          inTrustStore
      );
    }
    else {
      return new CertificateXO(fingerprint, fingerprint, pemCertificate);
    }
  }
}