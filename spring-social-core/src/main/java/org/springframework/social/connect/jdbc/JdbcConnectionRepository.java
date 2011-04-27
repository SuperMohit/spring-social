/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.social.connect.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.social.connect.DuplicateConnectionException;
import org.springframework.social.connect.NoSuchConnectionException;
import org.springframework.social.connect.Connection;
import org.springframework.social.connect.ConnectionData;
import org.springframework.social.connect.ConnectionFactory;
import org.springframework.social.connect.ConnectionFactoryLocator;
import org.springframework.social.connect.ConnectionKey;
import org.springframework.social.connect.ConnectionRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class JdbcConnectionRepository implements ConnectionRepository {

	private final String userId;
	
	private final JdbcTemplate jdbcTemplate;
	
	private final ConnectionFactoryLocator connectionFactoryLocator;

	private final TextEncryptor textEncryptor;

	public JdbcConnectionRepository(String userId, JdbcTemplate jdbcTemplate, ConnectionFactoryLocator connectionFactoryLocator, TextEncryptor textEncryptor) {
		this.userId = userId;
		this.jdbcTemplate = jdbcTemplate;
		this.connectionFactoryLocator = connectionFactoryLocator;
		this.textEncryptor = textEncryptor;
	}
	
	public MultiValueMap<String, Connection<?>> findConnections() {
		List<Connection<?>> resultList = jdbcTemplate.query(SELECT_FROM_SERVICE_PROVIDER_CONNECTION + " where userId = ? order by providerId, rank", connectionMapper, userId);
		MultiValueMap<String, Connection<?>> connections = new LinkedMultiValueMap<String, Connection<?>>();
		Set<String> registeredProviderIds = connectionFactoryLocator.registeredProviderIds();
		for (String registeredProviderId : registeredProviderIds) {
			connections.put(registeredProviderId, Collections.<Connection<?>>emptyList());
		}
		for (Connection<?> connection : resultList) {
			String providerId = connection.getKey().getProviderId();
			if (connections.get(providerId).size() == 0) {
				connections.put(providerId, new LinkedList<Connection<?>>());
			}
			connections.add(providerId, connection);
		}
		return connections;
	}

	public List<Connection<?>> findConnectionsToProvider(String providerId) {
		return jdbcTemplate.query(SELECT_FROM_SERVICE_PROVIDER_CONNECTION + " where userId = ? and providerId = ? order by rank", connectionMapper, userId, providerId);
	}

	public MultiValueMap<String, Connection<?>> findConnectionsForUsers(MultiValueMap<String, String> providerUsers) {
		if (providerUsers.isEmpty()) {
			throw new IllegalArgumentException("Unable to execute find: no providerUsers provided");
		}
		StringBuilder providerUsersCriteriaSql = new StringBuilder();
		MapSqlParameterSource parameters = new MapSqlParameterSource();
		parameters.addValue("userId", userId);
		for (Iterator<Entry<String, List<String>>> it = providerUsers.entrySet().iterator(); it.hasNext();) {
			Entry<String, List<String>> entry = it.next();
			String providerId = entry.getKey();
			providerUsersCriteriaSql.append("providerId = :providerId_").append(providerId).append(" and providerUserId in (:providerUserIds_").append(providerId).append(")");
			parameters.addValue("providerId_" + providerId, providerId);
			parameters.addValue("providerUserIds_" + providerId, entry.getValue());
			if (it.hasNext()) {
				providerUsersCriteriaSql.append(" or " );
			}
		}
		List<Connection<?>> resultList = new NamedParameterJdbcTemplate(jdbcTemplate).query(SELECT_FROM_SERVICE_PROVIDER_CONNECTION + " where userId = :userId and " + providerUsersCriteriaSql + " order by providerId, rank", parameters, connectionMapper);
		MultiValueMap<String, Connection<?>> connectionsForUsers = new LinkedMultiValueMap<String, Connection<?>>();
		for (Connection<?> connection : resultList) {
			String providerId = connection.getKey().getProviderId();
			List<String> userIds = providerUsers.get(providerId);
			List<Connection<?>> connections = connectionsForUsers.get(providerId);
			if (connections == null) {
				connections = new ArrayList<Connection<?>>(userIds.size());
				for (int i = 0; i < userIds.size(); i++) {
					connections.add(null);
				}
				connectionsForUsers.put(providerId, connections);
			}
			String providerUserId = connection.getKey().getProviderUserId();
			int connectionIndex = userIds.indexOf(providerUserId);
			connections.set(connectionIndex, connection);
		}
		return connectionsForUsers;
	}

	public Connection<?> findConnection(ConnectionKey connectionKey) {
		try {
			return jdbcTemplate.queryForObject(SELECT_FROM_SERVICE_PROVIDER_CONNECTION + " where userId = ? and providerId = ? and providerUserId = ?", connectionMapper, userId, connectionKey.getProviderId(), connectionKey.getProviderUserId());
		} catch (EmptyResultDataAccessException e) {
			throw new NoSuchConnectionException(connectionKey);
		}
	}

	@SuppressWarnings("unchecked")
	public <S> Connection<S> findPrimaryConnectionToApi(Class<S> serviceApiType) {
		try {
			String providerId = getProviderId(serviceApiType);
			return (Connection<S>) jdbcTemplate.queryForObject(SELECT_FROM_SERVICE_PROVIDER_CONNECTION + " where userId = ? and providerId = ? and rank = 1", connectionMapper, userId, providerId);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	public <S> List<Connection<S>> findConnectionsToServiceApi(Class<S> serviceApiType) {
		List<?> connections = findConnectionsToProvider(getProviderId(serviceApiType));
		return (List<Connection<S>>) connections;
	}
	
	@SuppressWarnings("unchecked")
	public <S> Connection<S> findConnectionToApiForUser(Class<S> serviceApiType, String providerUserId) {
		String providerId = getProviderId(serviceApiType);
		return (Connection<S>) findConnection(new ConnectionKey(providerId, providerUserId));
	}

	@Transactional
	public void addConnection(Connection<?> connection) {
		try {
			ConnectionData data = connection.createData();
			int rank = jdbcTemplate.queryForInt("(select ifnull(max(rank) + 1, 1) as rank from ServiceProviderConnection where userId = ? and providerId = ?)", userId, data.getProviderId());
			jdbcTemplate.update("insert into ServiceProviderConnection (userId, providerId, providerUserId, rank, displayName, profileUrl, imageUrl, accessToken, secret, refreshToken, expireTime) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
					userId, data.getProviderId(), data.getProviderUserId(), rank, data.getDisplayName(), data.getProfileUrl(), data.getImageUrl(), encrypt(data.getAccessToken()), encrypt(data.getSecret()), encrypt(data.getRefreshToken()), data.getExpireTime());
		} catch (DuplicateKeyException e) {
			throw new DuplicateConnectionException(connection.getKey());
		}
	}
	
	public void updateConnection(Connection<?> connection) {
		ConnectionData data = connection.createData();
		jdbcTemplate.update("update ServiceProviderConnection set displayName = ?, profileUrl = ?, imageUrl = ?, accessToken = ?, secret = ?, refreshToken = ?, expireTime = ? where userId = ? and providerId = ? and providerUserId = ?",
				data.getDisplayName(), data.getProfileUrl(), data.getImageUrl(), encrypt(data.getAccessToken()), encrypt(data.getSecret()), encrypt(data.getRefreshToken()), data.getExpireTime(), userId, data.getProviderId(), data.getProviderUserId());
	}

	public void removeConnectionsToProvider(String providerId) {
		jdbcTemplate.update("delete from ServiceProviderConnection where userId = ? and providerId = ?", userId, providerId);
	}

	public void removeConnection(ConnectionKey connectionKey) {
		jdbcTemplate.update("delete from ServiceProviderConnection where userId = ? and providerId = ? and providerUserId = ?", userId, connectionKey.getProviderId(), connectionKey.getProviderUserId());		
	}

	// internal helpers
	
	private final static String SELECT_FROM_SERVICE_PROVIDER_CONNECTION = "select userId, providerId, providerUserId, displayName, profileUrl, imageUrl, accessToken, secret, refreshToken, expireTime from ServiceProviderConnection";
	
	
	private final ServiceProviderConnectionMapper connectionMapper = new ServiceProviderConnectionMapper();
	
	private final class ServiceProviderConnectionMapper implements RowMapper<Connection<?>> {
		
		public Connection<?> mapRow(ResultSet rs, int rowNum) throws SQLException {
			ConnectionData connectionData = mapConnectionData(rs);
			ConnectionFactory<?> connectionFactory = connectionFactoryLocator.getConnectionFactory(connectionData.getProviderId());
			return connectionFactory.createConnection(connectionData);
		}
		
		private ConnectionData mapConnectionData(ResultSet rs) throws SQLException {
			return new ConnectionData(rs.getString("providerId"), rs.getString("providerUserId"), rs.getString("displayName"), rs.getString("profileUrl"), rs.getString("imageUrl"),
					decrypt(rs.getString("accessToken")), decrypt(rs.getString("secret")), decrypt(rs.getString("refreshToken")), expireTime(rs.getLong("expireTime")));
		}
		
		private String decrypt(String encryptedText) {
			return encryptedText != null ? textEncryptor.decrypt(encryptedText) : encryptedText;
		}
		
		private Long expireTime(long expireTime) {
			return expireTime == 0 ? null : expireTime;
		}
		
	}

	private <S> String getProviderId(Class<S> serviceApiType) {
		return connectionFactoryLocator.getConnectionFactory(serviceApiType).getProviderId();
	}
	
	private String encrypt(String text) {
		return text != null ? textEncryptor.encrypt(text) : text;
	}

}