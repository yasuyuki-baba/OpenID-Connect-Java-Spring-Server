/*******************************************************************************
 * Copyright 2013 The MITRE Corporation 
 *   and the MIT Kerberos and Internet Trust Consortium
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.mitre.openid.connect.token;

import java.util.Date;
import java.util.Set;
import java.util.UUID;

import javax.servlet.http.HttpSession;

import org.mitre.jwt.signer.service.JwtSigningAndValidationService;
import org.mitre.oauth2.model.ClientDetailsEntity;
import org.mitre.oauth2.model.OAuth2AccessTokenEntity;
import org.mitre.oauth2.service.ClientDetailsEntityService;
import org.mitre.openid.connect.config.ConfigurationPropertiesBean;
import org.mitre.openid.connect.util.JWSUtils;
import org.mitre.openid.connect.web.AuthenticationTimeStamper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.util.OAuth2Utils;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.token.TokenEnhancer;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@Service
public class ConnectTokenEnhancer implements TokenEnhancer {

	Logger logger = LoggerFactory.getLogger(ConnectTokenEnhancer.class);

	@Autowired
	private ConfigurationPropertiesBean configBean;

	@Autowired
	private JwtSigningAndValidationService jwtService;

	@Autowired
	private ClientDetailsEntityService clientService;

	@Override
	public OAuth2AccessToken enhance(OAuth2AccessToken accessToken,	OAuth2Authentication authentication) {

		OAuth2AccessTokenEntity token = (OAuth2AccessTokenEntity) accessToken;

		String clientId = authentication.getAuthorizationRequest().getClientId();
		ClientDetailsEntity client = clientService.loadClientByClientId(clientId);

		JWTClaimsSet claims = new JWTClaimsSet();

		claims.setAudience(Lists.newArrayList(clientId));

		claims.setIssuer(configBean.getIssuer());

		claims.setIssueTime(new Date());

		claims.setExpirationTime(token.getExpiration());

		claims.setJWTID(UUID.randomUUID().toString()); // set a random NONCE in the middle of it

		// TODO: use client's default signing algorithm

		JWSAlgorithm signingAlg = jwtService.getDefaultSigningAlgorithm();
		SignedJWT signed = new SignedJWT(new JWSHeader(signingAlg), claims);

		jwtService.signJwt(signed);

		token.setJwt(signed);

		/**
		 * Authorization request scope MUST include "openid" in OIDC, but access token request
		 * may or may not include the scope parameter. As long as the AuthorizationRequest
		 * has the proper scope, we can consider this a valid OpenID Connect request. Otherwise,
		 * we consider it to be a vanilla OAuth2 request.
		 */
		if (authentication.getAuthorizationRequest().getScope().contains("openid")) {

			// TODO: maybe id tokens need a service layer

			String userId = authentication.getName();

			OAuth2AccessTokenEntity idTokenEntity = new OAuth2AccessTokenEntity();

			// FIXME: extend the "claims" section for id tokens
			JWTClaimsSet idClaims = new JWTClaimsSet();


			//
			// FIXME: storing the auth time in the session doesn't actually work, because we need access to it from the token endpoint when the user isn't present
			//

			// get the auth time from the session
			ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
			if (attr != null) {
				HttpSession session = attr.getRequest().getSession();
				if (session != null) {
					Date authTime = (Date) session.getAttribute(AuthenticationTimeStamper.AUTH_TIMESTAMP);
					if (authTime != null) {
						idClaims.setClaim("auth_time", authTime.getTime() / 1000);
					}
				}
			}

			idClaims.setIssueTime(claims.getIssueTime());

			if (client.getIdTokenValiditySeconds() != null) {
				Date expiration = new Date(System.currentTimeMillis() + (client.getIdTokenValiditySeconds() * 1000L));
				idClaims.setExpirationTime(expiration);
				idTokenEntity.setExpiration(expiration);
			}

			idClaims.setIssuer(configBean.getIssuer());
			idClaims.setSubject(userId);
			idClaims.setAudience(Lists.newArrayList(clientId));


			String nonce = authentication.getAuthorizationRequest().getAuthorizationParameters().get("nonce");
			if (!Strings.isNullOrEmpty(nonce)) {
				idClaims.setCustomClaim("nonce", nonce);
			}

			//Calculate token hash (at_hash)
			String responseType = authentication.getAuthorizationRequest().getAuthorizationParameters().get("response_type");
			Set<String> responseTypes = OAuth2Utils.parseParameterList(responseType);
			if (responseTypes.contains("token")) {
				Base64URL at_hash = JWSUtils.getAccessTokenHash(signingAlg, token);
				//TODO: What should happen if the hash cannot be calculated?
				idClaims.setClaim("at_hash", at_hash);
			}
			
			//Calculate code hash (c_hash)
			if (responseTypes.contains("code")) {
				String code = authentication.getAuthorizationRequest().getAuthorizationParameters().get("code");
				Base64URL c_hash = JWSUtils.getCodeHash(signingAlg, code);
				//TODO: What should happen if the hash cannot be calculated?
				idClaims.setClaim("c_hash", c_hash);
			}

			SignedJWT idToken = new SignedJWT(new JWSHeader(signingAlg), idClaims);

			//TODO: check for client's preferred signer alg and use that

			jwtService.signJwt(idToken);

			idTokenEntity.setJwt(idToken);

			idTokenEntity.setAuthenticationHolder(token.getAuthenticationHolder());

			// create a scope set with just the special "id-token" scope
			//Set<String> idScopes = new HashSet<String>(token.getScope()); // this would copy the original token's scopes in, we don't really want that
			Set<String> idScopes = Sets.newHashSet(OAuth2AccessTokenEntity.ID_TOKEN_SCOPE);
			idTokenEntity.setScope(idScopes);

			idTokenEntity.setClient(token.getClient());

			// attach the id token to the parent access token
			token.setIdToken(idTokenEntity);
		}

		return token;
	}

	public ConfigurationPropertiesBean getConfigBean() {
		return configBean;
	}

	public void setConfigBean(ConfigurationPropertiesBean configBean) {
		this.configBean = configBean;
	}

	public JwtSigningAndValidationService getJwtService() {
		return jwtService;
	}

	public void setJwtService(JwtSigningAndValidationService jwtService) {
		this.jwtService = jwtService;
	}

	public ClientDetailsEntityService getClientService() {
		return clientService;
	}

	public void setClientService(ClientDetailsEntityService clientService) {
		this.clientService = clientService;
	}

}
