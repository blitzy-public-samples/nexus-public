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
package org.sonatype.nexus.security.jwt;

import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.security.JwtHelper;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.InvalidClaimException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.Claim;

/**
 * Perform the verification against the given JWT.
 * Updated for Java 21 compatibility with enhanced security and pattern matching.
 *
 * @since 3.38
 */
public class JwtVerifier
    extends ComponentSupport
{
  private final Algorithm algorithm;

  private final JWTVerifier verifier;

  private final String secret;

  /**
   * Constructs a JWT verifier with the specified secret key.
   * Uses HMAC256 algorithm with Java 21 security enhancements.
   *
   * @param secret the secret key used for JWT verification
   */
  public JwtVerifier(final String secret) {
    this.secret = secret;
    // Using enhanced security implementation for HMAC256 in Java 21
    this.algorithm = Algorithm.HMAC256(secret);
    this.verifier = JWT.require(algorithm)
        .withIssuer(JwtHelper.ISSUER)
        .build();
  }

  /**
   * Perform the verification against the given Token.
   * Uses Java 21 pattern matching for switch to handle different verification exceptions.
   *
   * @param jwt the JWT to verify.
   * @return the {@link DecodedJWT} object.
   * @throws JwtVerificationException if verification fails
   */
  public DecodedJWT verify(final String jwt) throws JwtVerificationException {
    if (jwt == null) {
      throw new JwtVerificationException("JWT token cannot be null");
    }
    
    try {
      return verifier.verify(jwt);
    }
    catch (JWTVerificationException e) {
      // Using Java 21 pattern matching for switch to handle different exception types
      String errorMsg = switch (e) {
        case TokenExpiredException expired -> "Token has expired";
        case SignatureVerificationException signature -> "Invalid token signature";
        case InvalidClaimException claim -> "Invalid token claim: " + claim.getMessage();
        case JWTVerificationException other -> "Can't verify the token: " + other.getMessage();
      };
      
      log.debug(errorMsg, e);
      throw new JwtVerificationException(errorMsg, e);
    }
  }

  /**
   * Gets the algorithm used for JWT verification.
   *
   * @return the algorithm instance
   */
  public Algorithm getAlgorithm() {
    return algorithm;
  }

  /**
   * Gets the secret key used for JWT verification.
   *
   * @return the secret key
   */
  public String getSecret() {
    return secret;
  }
  
  /**
   * Validates specific claims in the JWT token using pattern matching.
   * 
   * @param jwt the decoded JWT to validate
   * @throws JwtVerificationException if claim validation fails
   */
  public void validateClaims(final DecodedJWT jwt) throws JwtVerificationException {
    // Example of using pattern matching for switch to validate claims
    try {
      Claim issuerClaim = jwt.getClaim("iss");
      if (issuerClaim != null) {
        Object issuerValue = issuerClaim.asString();
        
        // Using Java 21 pattern matching for switch to validate issuer
        switch (issuerValue) {
          case String s when s.equals(JwtHelper.ISSUER) -> {
            // Valid issuer, do nothing
          }
          case String s -> {
            throw new JwtVerificationException("Invalid issuer: " + s);
          }
          case null -> {
            throw new JwtVerificationException("Missing issuer claim");
          }
          default -> {
            throw new JwtVerificationException("Unexpected issuer claim type");
          }
        }
      }
    }
    catch (Exception e) {
      if (e instanceof JwtVerificationException) {
        throw (JwtVerificationException) e;
      }
      throw new JwtVerificationException("Error validating claims", e);
    }
  }
}